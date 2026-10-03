# Extração Pokémon — implementação e validação

Data: 2026-10-03. Alterações locais preparadas para revisão e homologação.
Nenhum deploy, login/logout WhatsApp, mensagem real ou alteração em jogadores de
produção foi realizado nesta migração. O diretório `pokemon-service/` pode ser
extraído para um repositório próprio.

## Arquitetura antes

WhatsApp → ZapBot com Chromium, jogos, Pokémon, imagens e timers → Cassandra.
O processo do bot carregava todos os módulos persistidos e renderizava Pokémon.

## Arquitetura depois

```text
WhatsApp → ZapBot/Chromium e outros comandos
                      │ HTTP privado :8090
                      ▼
              pokemon-service
            regras, sprites, PNG,
          PokeAPI, timers e eventos
                      │ TCP :9042
                      ▼
             Cassandra existente
```

O ZapBot mantém a propriedade do placar compartilhado. O serviço envia efeitos
idempotentes de rank por HTTP. Nenhum broker, fila externa, Chromium ou biblioteca
WhatsApp foi adicionado ao serviço. Há uma única réplica proprietária do jogo.

## Inventário e decisões

O levantamento precedeu a extração. Referências:

- [Comandos e testes existentes](pokemon-command-inventory.md).
- [Estado, atoms, caches e Cassandra](pokemon-state-inventory.md).
- [Infraestrutura, dependências, imagens e timers](pokemon-infra-inventory.md).
- [Índice de namespaces e funções](pokemon-project-index.json).

Os inventários registram o código anterior; as decisões finais estão neste
relatório e no README do serviço. Em especial, relatórios `bugs` passaram ao
serviço; o adaptador obtém contexto/citação e autorização no WhatsApp.

## Arquivos criados

- `pokemon-service/`: fontes do domínio, runtime HTTP, assets, manifests, lock,
  Dockerfile, `.dockerignore`, `.env.example`, README e testes independentes.
- `src/zapbot/pokemon_http.cljs`: contexto, entrega WhatsApp, eventos e rank.
- `scripts/lib/pokemon-http-client.cjs`: transporte HTTP com keep-alive e limites.
- `scripts/test-pokemon-contract.cjs`, `scripts/test-pokemon-domain-contract.cjs`:
  testes do cliente contra a API e contra o domínio compilado.
- `test/pokemon-http-client.test.cjs`, `test/zapbot/pokemon_http_test.cljs`,
  `test/zapbot/rank_http_test.cljs`: transporte, adaptação e deduplicação.
- `scripts/benchmark-pokemon.cjs` e fixtures `test/pokemon_benchmark.cljs` nas
  duas árvores: comparação local reproduzível.
- Inventários, este relatório e `pokemon-benchmark-local.json` em `docs/`.

## Arquivos modificados

- `src/zapbot/core.cljs`, `router.cljs`: comandos e inicialização via HTTP.
- `src/zapbot/config.cljs`, `.env.example`: configuração do serviço.
- `src/zapbot/armazenamento.cljs`: leitura apenas dos módulos registrados e
  confirmação obrigatória da persistência para recibos de entrega.
- `src/zapbot/rank.cljs`: efeitos HTTP e marcadores no mesmo registro do placar.
- `Dockerfile`: copia cliente HTTP e somente o asset geral `abujamra.png`.
- `package.json`, `shadow-cljs.edn`: testes HTTP e build opcional de benchmark.
- `.github/workflows/deploy.yml`: valida o serviço independente antes do job
  existente de build/deploy; adiciona testes de transporte e contrato.
- `README.md` e testes de armazenamento/router: documentação e nova fronteira.

## Arquivos movidos/extraídos

As fontes originais foram preservadas. Foram extraídos os 12 namespaces de jogo
em `src/zapbot/pokemon/`, exceto a ferramenta destrutiva `resetar.cljs`, e os seis
assets Pokémon. O serviço ganhou uma porta neutra `boundary.cljs` e uma fachada
`pokemon_service/entry.cljs`.

Persistência, HTTP externo, tradução, Gemini, desempenho, configuração, bugs e
rank foram reduzidos/adaptados às necessidades do serviço. Outros comandos e
jogos do ZapBot não foram copiados. Os 133 testes Pokémon existentes foram
adaptados para contexto neutro e persistência fake.

## Comandos migrados

Todas as famílias identificadas continuam roteadas:

- `pokemon` / `pk`, com os subcomandos e aliases do inventário.
- `pokedex` / `dex` / `pdx`.
- `presente` / `presentes`, `missoes` / `missões`.
- `mochila`, `loja`, `loja comprar`, `loja detalhes` / `detalhe`.

Inclui treinador, coleção, captura, batalhas, evolução, golpes, ginásios, ligas,
raids, Joy, professor, presentes, trocas, inventário, missões e bugs. A fachada
reutiliza os handlers extraídos. O inventário detalha os subcomandos, efeitos,
respostas e testes; não houve redesenho das regras.

## Dependências removidas do ZapBot

O entrypoint não importa mais core, loja, Pokédex, treinador ou persistência
Pokémon. A imagem do bot não copia os assets Pokémon. **Sharp permanece** como
dependência de produção porque `bola8` também o usa; retirá-lo quebraria outro
comando. Isso não mantém processamento Pokémon no bot.

## Dependências do pokemon-service

Runtime: Node 22, `cassandra-driver`, `dotenv`, `sharp`, módulos nativos HTTP,
filesystem e AsyncLocalStorage. Build: `shadow-cljs`, Promesa e Java. Java fica
fora da imagem final. Não há WhatsApp/Puppeteer no manifest/lock do serviço.

## Contrato HTTP

Contrato completo e exemplos: [README do serviço](../pokemon-service/README.md).

| Rota | Responsabilidade |
|---|---|
| `POST /commands` | requestId, chatId, playerId, playerName, comando e contexto neutro |
| `GET /media/{id}` | Bytes de imagem/documento com autenticação |
| `GET /events/pending` | Até 20 notificações persistidas, ordenadas |
| `POST /events/{id}/ack` | Confirmação idempotente de entrega |
| `GET /health` | Processo HTTP respondendo |
| `GET /ready` | Persistência hidratada e Cassandra conectado |

A resposta contém `messages`, `effects` e tempos separados. O ID atual do jogador
é preservado integralmente. Conexão: 5s; chamada: 45s; resposta do servidor: 60s.
Não há retry automático de comandos. Chamadas externas têm prazo finito de 8s.
Reserva Cassandra LWT antes da execução impede reaplicar o mesmo requestId;
resultados completos são reutilizados, resultados incertos retornam conflito.

## Cassandra

Mesmos keyspace, IDs, formatos JSON/EDN e tabelas `estado` / `estado_particionado`.
O serviço não executa DDL nem migração automática. Bloqueia startup se encontrar
legado não migrado, evitando sobrescrever progresso com um snapshot antigo.
Leitura paginada apenas dos módulos do serviço; falha de escrita não é sucesso.

Novos módulos na tabela existente: `pokemon-http-requests`, `pokemon-http-events`,
`pokemon-remocoes-pendentes`, `pokemon-propostas-troca`. O bot mantém
`pokemon-http-deliveries` e marcadores de efeito em `rank`.

## Imagens

Downloads, resize, composição e PNG Pokémon rodam no serviço. A API transmite
referências e bytes, sem Base64 no JSON. O gateway cria `MessageMedia` na entrega.
Cache local de mídia: limite de 10 MiB por arquivo e limpeza após 24h; eventos
pendentes protegem seus arquivos. O volume é próprio da VM Pokémon.

## Eventos / Nurse Joy / raids

Joy conserva o prazo `pronto-em` e o recolhimento na próxima interação, como no
jogo original. O teste reidrata o JSON, avança 30 minutos e verifica retorno único.
Raids, expirações de combate e remoções programadas rodam no serviço. Notificações
são persistidas e consultadas pelo ZapBot a cada 15s, configurável. Propostas de
troca e remoções pendentes sobrevivem ao restart sem salvar handles de timers.

## Variáveis de ambiente

Serviço: `HOST`, `PORT`, `API_TOKEN`, `DATA_DIR`, `CASSANDRA_CONTACT_POINTS`,
`CASSANDRA_DATACENTER`, `CASSANDRA_KEYSPACE`, `PREFIX`, `BOT_NAME`,
`MISSOES_TIMEZONE`; tradução opcional: `GEMINI_API_KEY`, `GEMINI_MODEL`.

Bot: `POKEMON_SERVICE_URL`, `POKEMON_SERVICE_TOKEN`, `POKEMON_CONNECT_TIMEOUT_MS`,
`POKEMON_TIMEOUT_MS`, `POKEMON_EVENT_POLL_MS`, `POKEMON_MAX_MEDIA_BYTES`.
Sem configuração válida, somente Pokémon fica indisponível.

## Docker

Imagem do serviço construída localmente com sucesso. Smoke test sem rede externa:
Sharp gerou PNG; `/health`=200; `/ready`=503 e `/commands`=503 sem Cassandra.
O teste verificou que WhatsApp/Puppeteer não são resolvidos na imagem.

A imagem do bot também foi construída em ARM64. A inspeção executável confirmou
cliente HTTP, dependências de WhatsApp e Sharp para `bola8`, sem importar o domínio
Pokémon e sem iniciar WhatsApp. O workflow mantém os testes
das rotinas de deploy anteriores; nenhum workflow remoto foi acionado nesta tarefa.

## Compatibilidade ARM64

Build e execução nativos Linux ARM64 comprovados no Docker local, incluindo
Sharp. Dockerfiles não fixam plataforma; o CI existente constrói o bot em amd64.
O serviço usa base e dependências com variantes amd64/arm64. **Não foi executada
uma imagem amd64 nesta validação local**, nem publicado manifest multiarch.

## Testes executados e resultados

| Verificação | Resultado |
|---|---|
| ZapBot `npm test` | 174 testes, 1127 assertions; zero falhas/erros |
| Serviço `test:domain` | 147 testes, 1002 assertions; zero falhas/erros |
| API, cliente, contratos e serialização JS | 19 testes; zero falhas |
| Contrato com domínio compilado real | Consulta `loja detalhes atadura`, HTTP e replay; passou |
| Release ZapBot e domínio | Compilaram; há warnings do código e depreciação de Promesa |
| Renderer antes/depois | PNG byte a byte idêntico, 174.218 bytes |
| Docker serviço ARM64 | Build, execução, PNG, isolamento e health/readiness passaram |

Persistência e falhas de Cassandra usam fakes. Testes cobrem LWT, paginação,
recusa de legado não migrado, falhas de gravação, serialização de snapshots,
Joy, eventos após restart, ack repetido, entrega/rank sem duplicação, timeouts,
limites de payload e progresso de chats independentes. Isso não substitui
homologação com Cassandra real e rede entre VMs.

## Performance observada

[Resultados completos e ambiente](pokemon-benchmark-local.json). macOS ARM64,
Node 22.22.0, sprite SVG local; três aquecimentos e 20 amostras por etapa.

| Etapa | Mediana | p95 |
|---|---:|---:|
| Renderer original do treinador | 14,77 ms | 15,27 ms |
| Renderer extraído | 14,72 ms | 15,90 ms |
| Comando HTTP com renderer real | 15,71 ms | 16,05 ms |
| Transferência binária de 174.218 bytes | 0,29 ms | 0,35 ms |

O renderer produziu o mesmo SHA-256. Os números indicam comportamento preservado
neste fixture; não demonstram ganho de latência em produção. Banco em memória e
loopback não medem Cassandra, PokeAPI, Internet, raids completas ou envio WhatsApp.
Os logs permitem separar esses custos na homologação. Tempos de etapas paralelas
podem se sobrepor e não devem ser somados como tempo total.

## Como rodar localmente

Em `pokemon-service`: `npm ci`, configurar `.env`, `npm run build`, `npm start`.
Usar banco de homologação com as tabelas existentes. `npm test` e
`npm run test:domain` não dependem de banco real. Na raiz, `npm run test:http`.
Após compilar o serviço: `node --test scripts/test-pokemon-domain-contract.cjs`.
Os comandos de benchmark e Docker estão no README do serviço.

## Como subir na VM Pokémon

Construir/taguear a imagem; configurar arquivo privado de ambiente; montar volume
em `/app/data`; iniciar uma única instância. Usar `HOST=0.0.0.0` no container e
publicar TCP 8090 somente na interface privada. Liberar acesso apenas da VM do
ZapBot. Cassandra TCP 9042 continua privado. Não executar o serviço sobre dados
reais enquanto o bot antigo ainda estiver ativo.

## Como apontar o ZapBot para ela

Parar a imagem antiga, iniciar o serviço, esperar `/ready`=200, configurar
`POKEMON_SERVICE_URL=http://IP_PRIVADO:8090` e token correspondente no `.env` do
bot, e iniciar a imagem HTTP mantendo a sessão e os volumes existentes.

## Como validar em produção

Primeiro homologar leitura de snapshots, recuperação de timers e reinício com
Cassandra real. Guardar backup e imagem anterior antes do corte. Após ativar,
verificar readiness, consulta de treinador, demais comandos, mídia, eventos/ack e
logs. Exercitar compras/trocas/batalhas apenas com contas e grupo de homologação.
Medir processamento separado de envio WhatsApp; confirmar que o bot responde aos
outros jogos mesmo com o serviço indisponível. Estes passos não foram executados
contra a produção durante a migração.

## Como fazer rollback

Parar o bot HTTP e o serviço, reconciliar eventos/pendências novos, iniciar a
imagem anterior do bot com os mesmos volumes. Nunca manter dois proprietários do
estado Pokémon. Não foi criado modo local/http no mesmo binário: o rollback é
pela imagem preservada, e o build novo usa exclusivamente HTTP.

## Código antigo que pode ser removido

Depois de homologar o corte e encerrar o período de rollback:

- `src/zapbot/pokemon/` antigo e seus testes locais duplicados.
- Assets Pokémon da raiz; já ausentes da imagem nova do bot.
- `src/zapbot/bugs.cljs` antigo, após confirmar que não há outro consumidor.
- Target e script `resetar-pokemon`, se manutenção passar ao repositório do serviço.
- Fixture/build do benchmark antigo e dependências exclusivas que a busca final
  comprovar sem consumidores. **Não remover Sharp**, usado por `bola8`.

## Pendências/riscos

- Produção, Cassandra real, deploy entre VMs e login/envio WhatsApp não validados.
- Snapshot por chat exige uma réplica; LWT de requestId não protege duas réplicas
  alterando inventários. Não iniciar escritor antigo e novo simultaneamente.
- Operações que alteram vários módulos não são uma transação global. Queda entre
  mutação e persistência da notificação pode deixar efeito parcial/aviso ausente;
  o journal bloqueia replay automático e exige reconciliação nesses casos.
- WhatsApp pode repetir visualmente uma entrega se cair entre envio e gravação
  do recibo; os efeitos de rank têm dedupe persistido.
- Hidratação com `ALLOW FILTERING` preserva o schema, mas exige medir custo com
  dados reais. Recibos e marcadores precisam de política futura de retenção.
- Mídia de resposta antiga pode expirar. O comando não deve ser reexecutado para
  regenerá-la. Eventos sem ack dependem do volume de mídia preservado.
- Startup malsucedido mantém readiness negativa; após corrigir configuração,
  reiniciar o serviço. Não há fallback silencioso à implementação local.
- Push em `master` mantém o deploy automático já existente. As alterações ainda
  são locais; configurar a VM Pokémon e planejar o corte antes de publicar.
