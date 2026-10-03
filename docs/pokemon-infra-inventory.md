# Inventário de infraestrutura e fronteiras Pokémon

Levantamento anterior à alteração de código, em 2026-10-03. As referências abaixo
descrevem a implementação original; a numeração de linhas pode mudar durante a
extração. O inventário de comandos e persistência complementa este documento.

## Dependências reais

| Dependência | Classificação | Uso atual / destino |
| --- | --- | --- |
| `zapbot.pokemon.aventuras`, `.golpes`, `.shiny` | DOMAIN | Catálogos e regras puras; extrair sem redesenhar. |
| `.treinador`, `.loja`, `.ginasios`, `.raids`, `.missoes`, `.mundo` | DOMAIN + INFRASTRUCTURE | Regras e acesso ao armazenamento/configuração; ficam no serviço. |
| `.core` | DOMAIN + NEEDS_REFACTOR | Combina regras, PokeAPI, renderização, mensagens e timers. Separar apenas os pontos de transporte. |
| `.pokedex` | INFRASTRUCTURE + NEEDS_REFACTOR | PokeAPI, tradução, cache Cassandra e envio direto de mídia. |
| `.ajuda` | DOMAIN + SHARED | Texto e comandos dependem de `PREFIX`; preservar respostas. |
| `zapbot.armazenamento` | SHARED + INFRASTRUCTURE | Adaptador Cassandra genérico; serviço carrega somente módulos de que é proprietário. |
| `zapbot.rank` | SHARED + NEEDS_REFACTOR | PvP premia e penaliza placar global compartilhado com velha/naval/quiz/adedonha. Não duplicar snapshots de um mesmo registro entre processos. |
| `zapbot.bugs` | SHARED + WHATSAPP_SPECIFIC + NEEDS_REFACTOR | `pk bug/bugs` usa histórico/citação/autorização do bot; dados neutros necessários na entrada. |
| `zapbot.traducao` → `zapbot.gemini` | SHARED + INFRASTRUCTURE | Pokédex traduz descrição/habilidades. Google principal, Gemini opcional em contingência. Preservar ambos. |
| `zapbot.desempenho` | SHARED + NEEDS_REFACTOR | Medições usam WeakMap indexado por mensagem; aceitar contexto neutro no serviço. |
| `zapbot.config` | SHARED | Serviço precisa somente de parte das variáveis. |
| `whatsapp-web.js` | WHATSAPP_SPECIFIC | Importado diretamente por core, loja e pokedex; remover do serviço. |
| `sharp` | INFRASTRUCTURE | Resize, composição e PNG de Pokémon; dependência nativa necessária no serviço. |
| `fs` | INFRASTRUCTURE | Core verifica presença do asset Ash. Demais assets são abertos pelo Sharp. |
| `promesa.core`, `clojure.string`, `cljs.reader` | SHARED | Controle assíncrono, strings e snapshots EDN. |

O grafo transitivo atual inclui `bugs → bloqueio → admins/config` e
`bugs → historico`. Copiar esses módulos integralmente traria contexto de
WhatsApp/estado genérico desnecessário. O adaptador ZapBot deve fornecer a
autorização e a mensagem alvo já extraídas.

O script `pokemon/resetar.cljs` é destrutivo, manual, não integra o serviço e
não deve ser executado nesta migração.

### Pacotes npm e versões efetivas no lockfile

| Pacote | Versão instalada no lock | Necessidade do serviço |
| --- | --- | --- |
| `cassandra-driver` | 4.9.0 | Sim; requer Node >=20. |
| `dotenv` | 16.6.1 | Sim; configuração local. |
| `sharp` | 0.35.3 | Sim; requer Node >=20.9. |
| `shadow-cljs` | build de desenvolvimento | Sim, somente compilação/testes. |
| `whatsapp-web.js` | 1.34.6, commit fixo por tarball | Não. |
| `puppeteer` / `puppeteer-core` | 24.38.0 | Não; transitivos de WhatsApp. |
| `qrcode-terminal`, `rss-parser` | pacotes do bot | Não. |

`whatsapp-web.js` também traz `fluent-ffmpeg`, `node-webpmux`, `mime`,
`node-fetch` e dependências opcionais de arquivos. Nenhum é necessário para
executar Pokémon. `postinstall` e `verify:media-patch` do pacote raiz existem
para WhatsApp e não devem aparecer no pacote independente.

## Contexto neutro: entradas e respostas

### Pontos de entrada acoplados

| Função atual | Informação necessária | Fronteira proposta |
| --- | --- | --- |
| `core/chat-id` e usos em `loja` | `fromMe`, `to`, `from` | `chat-id`/`context.chatId`, resolvido pelo bot. |
| `core/jogador-id` e usos em `loja` | `author` ou `from` | `player-id`/`playerId`, string idêntica à existente. |
| `core/alvo-mencionado`, `serializar-id` | `mentionedIds`, `_serialized` | `mentioned-ids`: vetor de strings normalizadas no bot, sem reescrever `@lid`/`@c.us`. |
| `core/alvo-citado`, `resolver-alvo-doacao` | `hasQuotedMsg`, `getQuotedMessage` | `quoted-player-id`, opcional. |
| `core/nome-de` | `getContact`, `pushname/name/number` | `player-name`, fallback `Alguém`. |
| `bugs/mensagem-alvo` | mensagem citada ou histórico anterior | `bug-target`: autor, corpo, instante e origem. |
| `bugs/somente-admin` | `bloqueio/autorizado?` | `admin?`, calculado pelo adaptador autorizado. |
| `desempenho/contexto-de` | objeto `Message` | Contexto de medição atrelado ao request/contexto neutro. |

Não é necessário transportar `Client`, `Message`, `Chat` ou `Contact`, nem
criar versões falsas desses objetos no serviço. Os IDs antigos continuam
sendo chaves dos jogadores, preservando inventários e dados.

### Emissões que precisam sair do domínio

`core/enviar-imagem`, `enviar-imagem-ginasio`, `enviar-cartao-evolucao!`,
`enviar-cartao-evento!`, `enviar-imagem-vs`, `enviar-anuncio-batalha`,
`aplicar-remocao-golpe!`, `enviar-aviso-temporizado`, `verificar-raides!` e
`pokedex/enviar-cartao` enviam mensagens diretamente. Algumas são mensagens
intermediárias, seguidas de uma resposta final. É necessário preservar sua
ordem em uma lista `messages`, não somente capturar o retorno final.

`core/resposta-time-ginasio`, `resposta-imagem-ginasio`,
`resposta-imagem-cacada`, `resposta-imagem-captura`, `resposta-cartao-evento`,
`resposta-imagem-pvp`, `ver-treinador`, `criar-cartao-time`,
`resposta-time-csv` e `loja/ver-loja-com-imagem` retornam `MessageMedia`.
Substituir por mídia neutra `{mime, buffer, filename}` dentro do processo.
O endpoint retorna somente metadados, texto, legenda, menções e `mediaId`;
`GET /media/{id}` entrega os bytes. CSV também deve ser suportado.

O ZapBot converte os bytes prontos em `MessageMedia` na última etapa. A
codificação Base64 que o SDK do WhatsApp exige fica apenas nessa borda. O
JSON HTTP não transporta imagem codificada. Se mídia falhar, preservar o
fallback textual atual e a separação entre legenda curta/texto longo.

### Timers que emitem mensagens

`core/iniciar!` armazena o cliente no atom `cliente-whatsapp`, rearma turnos e
inicia raids. `enviar-aviso-temporizado` e `verificar-raides!` dependem dele.
Substituir esse vínculo por uma função que cria evento neutro persistido.
Dados de destino (`chatId`, texto, menções, metadados de mídia) devem bastar.
Ack HTTP só ocorre depois do envio pelo ZapBot; falha entre envio e ack pode
produzir entrega repetida, devendo ser documentada/testada.

## PokeAPI, tradução e downloads

| Função | Origem | Comportamento atual |
| --- | --- | --- |
| `core/buscar-golpe` | `/api/v2/move/{nome}` | Fetch direto, sem timeout explícito. |
| `core/buscar-info-especie` | `/api/v2/pokemon-species/{slug}` | AbortSignal timeout já presente. |
| `core/sortear-pokemon` | `/api/v2/pokemon/{id}` | Fetch direto, sem timeout explícito. |
| `core/buscar-pokemon-por-nome` | `/api/v2/pokemon/{nome}` | Cache local e timeout já presente. |
| `core/buscar-cadeia-evolucao` | espécie e URL da cadeia | Fetch direto, sem timeout explícito. |
| `core/tentar-evoluir!` | `/api/v2/pokemon/{slug}` | Fetch direto, sem timeout explícito. |
| `pokedex/buscar-json`, `dados-especie` | Pokémon, espécie, habilidades, cadeia | Wrapper fetch sem timeout explícito. |
| `traducao/traduzir-google` | `translate.googleapis.com` | Sem timeout; cooldown 429 em atom. |
| `gemini/chamar-uma-vez` | `generativelanguage.googleapis.com` | Sem timeout; até 2 retries de 429/503, espera de 2 s. |
| `core/baixar-buffer-url`, `baixar-buffer` | URL do sprite | Timeout 6 s por tentativa; tenta jsDelivr antes de GitHub raw. |
| `pokedex/enviar-cartao` | URL da imagem | Usa `MessageMedia.fromUrl`; mover download para infraestrutura do serviço. |

Toda chamada HTTP no serviço extraído deve ganhar limites explícitos, inclusive
as dependências externas legadas. O timeout do cliente ZapBot não cancela com
segurança um comando que já alterou o jogo. Não repetir comandos automaticamente.

## Imagens, sprites e filesystem

Os seguintes assets são específicos do jogo e acompanham o serviço:

- `assets/loja-pokemon.png`;
- `assets/enfermeira-joy-tratando.png`;
- `assets/enfermeira-joy.png`;
- `assets/ash.png`;
- `assets/centro-pokemon.png`;
- `assets/professor-carvalho.png`.

`assets/abujamra.png` pertence a outra funcionalidade e permanece no bot.
Os caminhos atuais são relativos a `__dirname/../assets`. Não há gravação
de sprites/PNG Pokémon em filesystem compartilhado: os buffers são produzidos
em memória. O serviço pode manter um cache temporário limitado de mídia; é
necessário um mecanismo de regeneração/fallback para eventos que sobrevivam
ao cache/processo.

| Funções de imagem em `pokemon/core.cljs` | Operação |
| --- | --- |
| `baixar-buffer-url`, `baixar-buffer`, `candidatos-url-sprite`, `url-jsdelivr-sprite` | Download, limite e fallback de host. |
| `sprite-redimensionado`, `sprite-proporcional`, `tamanho-visual-pokemon`, `pokemon-com-medidas` | Resize de sprite, medidas e proporção. |
| `criar-imagem-vs` | Arena PvP, dois sprites e separador. |
| `criar-imagem-ginasio`, `criar-imagem-time-ginasio`, `svg-marcador-motivacao` | Arenas, time defensor e motivação. |
| `criar-imagem-cacada`, `resposta-imagem-captura` | Caçada, fuga e Pokébola. |
| `aplicar-sobreposicao-batalha` | Overlay de golpe/efeito. |
| `criar-cartao-evento` | Joy, hospital, professor e eventos, 760×400. |
| `criar-cartao-evolucao` | Sprites antigo/novo e moldura de evolução. |
| `sprite-ash-treinador`, `sprite-pokemon-treinador`, `criar-cartao-treinador` | Asset Ash, sprite ativo e perfil. |
| `baixar-sprite-time`, `criar-cartao-time` | Grade de Pokémon, sprites 145×145. |
| `resposta-time-csv` | Exportação CSV; preservar nome/MIME/anexo. |
| `loja/ver-loja-com-imagem` | Resize do asset da loja para 760×400. |

Funções `svg-*` produzem as mesmas molduras e marcadores de fallback; devem
ser extraídas sem alteração visual. Nenhuma delas usa Chromium para renderizar.

## Configuração mínima derivada do código

| Variável atual | Uso no serviço |
| --- | --- |
| `CASSANDRA_CONTACT_POINTS` | Hosts existentes, separados por vírgula. |
| `CASSANDRA_DATACENTER` | Datacenter do driver. |
| `CASSANDRA_KEYSPACE` | Keyspace existente; nunca recriar dados na extração. |
| `MISSOES_TIMEZONE` | Missões, clima, ginásios e rotação diária. |
| `BOT_NAME` | Texto de loja/Pokédex/cabeçalhos. |
| `PREFIX` | Texto das respostas e instruções de comandos. |
| `GEMINI_API_KEY`, `GEMINI_MODEL` | Opcionais, contingência de tradução da Pokédex. |

O serviço novo precisa acrescentar `HOST` e `PORT` para HTTP. Demais parâmetros
devem corresponder a recursos implementados: token se houver autenticação,
timeouts e limites quando configuráveis. As variáveis `PUPPETEER_*`,
`CHROMIUM_DISABLE_GPU`, `ADMIN_NUMBERS`, `APP_ENV`, `DEV_GROUP_ID`, clima,
notícias, Spotify e TMDB não pertencem ao processo Pokémon.

O adaptador ZapBot precisa de `POKEMON_SERVICE_MODE=local|http`, URL do serviço,
timeout de conexão, timeout da operação e intervalo de polling. Filtros de
grupo/admin continuam no ZapBot. O rollback para local exige parar primeiro o
serviço, para não manter dois escritores/timers para os mesmos jogadores.

## Docker e ARM64

A base existente é `node:22-bookworm-slim`; o serviço pode preservá-la sem
Chromium, Puppeteer ou scripts de patch de mídia. Java é necessário somente
no estágio de compilação ClojureScript. O runtime contém Node, certificados,
fontes para SVG, os pacotes de produção, o bundle e os seis assets do jogo.

O catálogo oficial de imagens Node inclui `amd64` e `arm64v8` para Node 22
Bookworm Slim. [Fonte: docker-node/versions.json](https://github.com/nodejs/docker-node/blob/main/versions.json).

O lock atual contém `@img/sharp-linux-arm64`, `@img/sharp-linux-x64` e seus
pacotes libvips 1.3.2. A documentação do Sharp confirma binários Linux ARM64 e
x64 e exige instalar dependências opcionais. Não copiar `node_modules` do
macOS para a imagem Linux. [Fonte: instalação do Sharp](https://sharp.pixelplumbing.com/install/).

Essa inspeção confirma suporte declarado, não execução real das duas imagens.
Validar build e PNG por arquitetura quando houver Docker/buildx disponível.
Não usar `npm ci --omit=optional`, pois removeria os binários nativos.

## Testes existentes que protegem a fronteira

`test/zapbot/pokemon/` contém suites `core`, `raids`, `pc`, `professor`, `loja`,
`golpes`, `aventuras`, `missoes`, `ajuda` e `evento_recomeco`. São relevantes:

- regras puras: dano, habilidades, golpes, captura, XP, liga, missões e professor;
- persistência: coleção/PC, enfermaria, partidas restauradas, marcadores terminais,
  raids/agenda e recompensas uma única vez;
- mídia: PNG, molduras PvP/ginásio, imagens Joy/professor/loja, perfil com sprite,
  fallback sem sprite, time paginado;
- concorrência: fila somente por chat, erro liberando fila e raids aguardando turno;
- aliases e comandos que não devem executar rodada (`bug/bugs`).

`core_test.cljs` fora de Pokémon verifica legenda longa, ordem de envio,
fallback textual e opções seguras do WhatsApp; `router_test.cljs` protege
despacho de `pk` e bloqueio usando a chave `pokemon`; `bugs_test.cljs` protege
autorização e prioridade da mensagem citada. `desempenho_test.cljs` verifica
isolamento dos contextos e captura de falhas.

Os testes de serviço devem usar contexto neutro e mídia Buffer. Os testes de
adaptador continuam usando mocks do WhatsApp. Além da regressão existente,
precisam existir testes HTTP reais em loopback: erros/timeouts, 4xx/5xx,
resposta inválida, requestId repetido/conflitante, transferência binária,
pending/ack e restart com dados fakes persistidos. Nenhum teste deve iniciar
WhatsApp ou conectar ao Cassandra real.

## Riscos que a implementação precisa resolver

1. `rank/pontuar!` e `rank/penalizar!` são usados pelo jogo e por outros jogos;
   separar ownership ou projetar atualização idempotente, sem snapshots
   concorrentes sobrescrevendo placares.
2. Emissões intermediárias e notificações precisam sobreviver à separação
   de processos e manter a ordem.
3. `:message` aparece no estado de batalha somente como contexto runtime;
   snapshots já o removem. Trocar por dados neutros sem invalidar snapshots.
4. Remoção de golpe atrasada (30 s), turnos e raids precisam rearmar a partir
   do estado persistido. Cache de renderização pode ser efêmero.
5. Request timeout não prova falha da ação: idempotência durável precisa ser
   definida antes de qualquer retry. Cassandra legado não oferece transação
   atômica entre todos os módulos; não prometer execução exatamente uma vez.
6. O pacote raiz declara Node >=18, mas o lock de Sharp já exige >=20.9 e
   Cassandra >=20. O pacote novo deve declarar um Node compatível, como 22.

## Verificação da fronteira após o build

`zapbot.bola8` também depende de Sharp (dependência **SHARED**). O pacote deve
permanecer em `dependencies` do ZapBot para preservar esse comando. A extração
remove o processamento e os assets específicos do Pokémon, não o renderizador
usado por outros jogos. O build final do bot não importa `zapbot.pokemon.core`,
`loja`, `pokedex` ou os módulos de persistência do jogo.
