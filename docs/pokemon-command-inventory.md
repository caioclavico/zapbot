# Inventário de comandos Pokémon para extração HTTP

Análise do código existente em 2026-10-03. Este documento descreve comportamento atual e pontos de separação; não implementa a extração nem altera regras. O prefixo configurável é representado como `!` nos exemplos. `!pk` e `!pokemon` são equivalentes.

## Entrada e sequência de processamento

- `src/zapbot/core.cljs`: registra o histórico antes de chamar `router/processar`, envia a resposta final e, no evento de conexão, chama `pokemon/iniciar!`.
- `src/zapbot/router.cljs:144`: encaminha `pokemon`/`pk`, mas também chama módulos Pokémon diretamente para `pokedex`, `mochila` e `loja`. Esses caminhos precisam entrar no escopo da extração.
- `pokemon/core.cljs:5735`, `jogar`: desvia `bug`/`bugs` antes de entrar na fila; os demais comandos usam `enfileirar-jogada` por chat. `gin/ginasio/ginásio atacar/atk` é convertido em ataque normal quando já existe batalha de ginásio.
- `jogar-rodada`: intercepta ajuda; hidrata referências transitórias das batalhas restauradas; recolhe curados; resgata XP pendente de raides; processa evolução e aprendizado; executa o comando; executa o turno automático do líder quando autorizado; produz imagens; espera a persistência iniciada pelo combate e demais módulos.
- `jogar-comando`: migra coleção, recolhe curados novamente, corrige golpes iniciais fora de combate, aplica bloqueios transitórios e despacha comandos. Consultas não são necessariamente livres de gravações por causa dessas migrações e resgates.
- Durante preparação de ginásio, finalização de recompensas ou evolução, retorna a mensagem de espera existente. `espaco` e `pc` são verificados antes desses bloqueios. Com captura pendente, ações de batalha específicas retornam o menu de captura em vez de agir.

O roteador normaliza acentos no comando externo. O dispatcher interno converte para minúsculas, mas aceita acentos somente nos aliases que enumera. A ajuda possui normalização própria. Preservar essa diferença evita mudar entradas aceitas durante a extração.

## Rotas externas ao prefixo Pokémon

| Comando | Destino atual | Dependências e efeitos | Resposta e testes relacionados |
| --- | --- | --- | --- |
| `!pokemon ...`, `!pk ...` | `pokemon/jogar` | Todos os fluxos abaixo; fila por chat; armazenamento | Texto, menções, PNG, CSV, ações enviadas durante o processamento; `router_test`, `pokemon/core_test` |
| `!pokedex [espécie ou número]`, `!dex`, `!pdx` | `pokedex/buscar` | PokeAPI, tradução, cache persistido `pokedex-cache`; sorteio sem argumento | Cartão da espécie via envio direto; texto se imagem indisponível. Diferente da Pokédex pessoal de `!pk dex` |
| `!presente @pessoa`, `!presentes` | `pokemon/jogar` com `presente ...` | Inventário, destinatário mencionado, missões | Cartão/texto e menção |
| `!missoes ...`, `!missões ...` | `pokemon/jogar` com `missoes ...` | Missões diárias/semanais e recompensas | Cartão/texto; `missoes_test` |
| `!mochila [kit/diario/resgatar]` | `loja/mochila` diretamente | Inventário, pendências, bônus diário; hoje não passa por `jogar-rodada` | Texto; `loja_test`, `evento_recomeco_test` |
| `!loja` ou subcomando não reconhecido | `loja/ver-loja-com-imagem` | Catálogo, saldo, `sharp`, asset loja | PNG + texto; fallback texto; `catalogo-da-loja-usa-imagem-propria` |
| `!loja comprar <item>` | `loja/comprar` | Débito, inventário, capacidade; rejeita bolas vendidas e itens exclusivos | Texto; `loja_test`, `pc_test` |
| `!loja detalhes <item>`, `detalhe` | `loja/detalhes` | Normalização do identificador e catálogo | Texto; `nomes-de-itens-sao-normalizados`, catálogo |

O primeiro token de `!loja` aceita `:` removidos e minúsculas; a normalização de item também reconhece nomes/acentos/aliases. Não reduzir a API ao prefixo `pk` deixando as rotas diretas no bot.

## Catálogo completo por família de comando

Nas referências de testes: **C** = `test/zapbot/pokemon/core_test.cljs`; **PC** = `pc_test.cljs`; **P** = `professor_test.cljs`; **R** = `raids_test.cljs`; **L** = `loja_test.cljs`; **M** = `missoes_test.cljs`; **A** = `aventuras_test.cljs`; **E** = `evento_recomeco_test.cljs`; **H** = `ajuda_test.cljs`; **G** = `golpes_test.cljs`. Os nomes completos de todos os testes Pokémon estão no apêndice. As referências abaixo indicam cobertura relacionada, não cobertura exaustiva de cada comando ou de cada erro.

| Comando após `!pk` e aliases completos | Handler e principais funções | Dependências, efeito e resposta | Testes relacionados |
| --- | --- | --- | --- |
| Sem argumento | `iniciar-ou-entrar` → `iniciar-ou-entrar-atualizado`; ou `mensagem-estado`/`menu-captura` | Liga, escalação, treinador, contato; abre/entra em PvP 3 × 3, ou consulta combate/captura já existentes. Envia anúncio e estado com menções | C: filas, persistência, arenas PvP; H: batalha/ligas |
| `sair`, `sai` | `sair`, `tentar-encerrar-por-desistencia!` | Remove batalha/caçada; encerra sequência; penaliza rank na desistência PvP iniciada; sem prêmio de vitória por desistência | C: persistência/encerramento; testes de regras relacionados |
| `liga`, `ligas`, `lig` `[nome/time n1,n2,n3]` | `configurar-liga` | `treinador`: seleciona faixa de nível, valida escalação de três diferentes e disponíveis; consulta liga e time | C: escalações nomeadas, progressão; H: ligas |
| `ginasio`, `ginásio`, `ginasios`, `ginásios`, `gin` | `configurar-ginasio` | `aventuras`, `ginasios`, `treinador`, `loja`, `raids`; liderança, recompensas, time, motivação e batalha; detalhes na tabela de subcomandos | C: ginásios/turno líder/imagens; R; A |
| `evento`, `eventos`, `evt` | `ver-evento` | `aventuras/evento-atual`, evento recomeço no treinador; consulta espécies em destaque, prazo, missão e recompensas | A: períodos; E: todos |
| `professor` | `comando-professor`, `familia-da-cadeia`, `buscar-cadeia-evolucao` | Transferência definitiva confirmada; cartões de família; XP com evolução e aprendizado; detalhes abaixo | P: todos |
| `evoluir`, `evo` `<n> <pedra>` ou `<n> especial [destino]` | `evoluir-com-item`, `evoluir-especial`, `evolucoes-especiais` | PokeAPI, pedras/catalisador, treinador; revalida ocupação/identidade antes do consumo; preserva nível/XP/item; cartão de evento | A: pedra solar; C: progressão e evolução visual; P: XP/nível |
| `negociar`, `neg` `<seu n> <n do outro> @pessoa` | `negociar-pokemon`, `concluir-troca!`, `preparar-evolucao-troca` | Alvo por menção ou citação; proposta de 5 min em memória, aceite do destinatário e confirmação do autor; troca/evolução e consumo de item compatível | A: validação de troca; C: dados de evolução |
| `raid`, `raide`, `raides` | `comando-raid`, `capturar-raide`, `raids/comando!` | Agenda, inscrição, combate cooperativo, recompensas, captura e evolução; detalhes abaixo | R: todos; C: fila de raide/contexto |
| `shiny` | `ver-colecao-shiny` | Treinador, Joy, ginásios, registro shiny histórico; consulta inclui recuperação de registros antigos | C: descobertas/shiny |
| `treinador`, `tre` | `ver-treinador`, `texto-treinador`, `criar-cartao-treinador` | Perfil, conquistas, PE, contato, Pokémon ativo, sprites, `sharp`; PNG e texto com legenda curta alternativa | C: perfil/carta/sprite indisponível; router |
| `favorito`, `fav` `[n/remover/tirar/nenhum]` | `configurar-favorito` | Identidade do Pokémon, seleção favorita e ativação automática ao retornar saudável | C: favorito volta da Joy; P: favorito não transferível |
| `titulo [n]` | `configurar-titulo` | Consulta/seleção de título conquistado no treinador | C: amizade/títulos/descobertas |
| `amizade [n]` | `ver-amizade` | Consulta amizade 0–255 do ativo ou número informado | C: amizade/títulos/descobertas |
| `clima`, `areas`, `áreas`, `mapa` | `mundo/resumo` | Áreas/clima por data/fuso; texto | C: rotação diária estável |
| `aprender`, `apr` `[n/aceitar/recusar]` | `aprender-oferta`, `mostrar-oferta`, `aprender-golpe-por-nivel!` | Ofertas persistidas; consulta pode gerar oferta pendente por nível; revalida limite e ataque do próprio tipo | G; C: golpes e XP |
| `reaprender`, `reap` `[n [slot]]` | `reaprender-golpe` | Lista esquecidos/recusados; memória de golpes ou PokeAPI; cobra 50 moedas apenas depois de revalidar; devolve moeda se aplicação falhar | G; C: golpes/nível; L: catálogo |
| `mt [slot]` | `usar-mt`, `sortear-ataque-mt` | Consulta movimentos da espécie; sorteia golpe elegível, protege ataque do próprio tipo, consome MT após sucesso | G; L: catálogo; sem teste isolado de despacho identificado |
| `reviver`, `rev` `[n]` | `reviver-pokemon`, `treinador/reviver!` | Item da mochila, HP/status; bloqueia combate/alteração pendente | L/M; sem teste isolado de despacho identificado |
| `mochila`, `mch` `[kit/diario/resgatar]` | `loja/mochila` | Kit, diário, inventário, capacidade, recompensas pendentes | L; E |
| `missoes`, `missões`, `mis` `[diarias [resgatar]/todas/resgatar]` | `loja/ver-missoes` | Missões por nível, PE/itens e resgate; vazio/todas também mostra semanais; cartão professor | M; C: cartão de missões; E |
| `missoes semanais [resgatar]` e aliases da família | `loja/ver-semanais` | Resgate semanal, moedas, bolas, bônus; marcação persistida com recompensa | M: semana e resgate |
| `presente`, `presentes`, `pre` `@pessoa` | `loja/enviar-presente!`, `alvo-mencionado` | Exige menção; não usa alvo citado. Consome cartão, sorteia bolas, atualiza diárias/semanais; excedente vai para pendências | M/L relacionados; resposta estruturada com menção |
| `capturar`, `cap` `<bola>` | `capturar-selvagem` | Chance por raridade/status/bola, capacidade, consumo, limite de tentativas/fuga; XP e coleção; cartão da bola/captura | C: capturas, bônus, fuga, PNG; PC: capacidade; E |
| `inicial`, `iniciais`, `ini` `[1–3]` | `escolher-inicial`, `texto-iniciais` | Consulta opções ou atribui inicial uma vez; PokeAPI/golpes/treinador; imagem e texto | PC: reinício não libera outro inicial; G |
| `cacar`, `caçar`, `cac` `[área]` | `cacar`, `sortear-selvagem`, `criar-imagem-cacada` | Mundo/bioma/clima, progressão, cooldown, evento, raridade, shiny, descoberta, combate PvE por chat | C: biomas/captura; PC: estoque cheio/cooldown; E |
| `pokedex`, `dex`, `pdx`, `colecao`, `coleção` `[filtros]` | `ver-pokedex-pessoal`, `renderizar-pokedex-pessoal` | Coleção pessoal e estatísticas; não é busca de espécie de `!pokedex` | C/PC: coleção e filtros |
| Família `pokedex` + `<n>` | `ver-pokemon-do-time`, `legenda-ficha-time`, `pokedex/dados-especie` | Ficha do índice da coleção, HP/XP/golpes/item e dados traduzidos da espécie; envio de imagem | C: tamanho/espécie/sprites |
| Família `pokedex` + `descobertas` | `ver-descobertas` | Resumo pessoal e primeiras descobertas shiny globais persistidas | C: descobertas |
| Família `pokedex` + `shiny` | `ver-colecao-shiny` | Mesmo histórico de `shiny` | C: descobertas/shiny |
| `time`, `equipe`, `tm` `[página] [filtros]` | `resposta-time-visual` → `resposta-colecao-visual`; `pagina-colecao`, `criar-cartao-time` | Coleção unificada, 12 por página, numeração original, imagens/fallback texto | PC: paginação/ordenação/imagem; C: PNG/base64 |
| Família `time` com `txt` ou `texto` em qualquer posição | `ver-time` | Lista textual filtrada, tratamento Joy, contexto dos defensores | PC: filtros; C: coleção |
| Família `time` + `csv`/`planilha` | `resposta-time-csv`, `csv-time` | Documento CSV com equipe e Joy, BOM, separador `;`, escape de fórmula; texto antes do documento | Sem teste dedicado ao CSV identificado |
| Família `time` + `ativo` | `ver-pokemon-ativo-do-time` → `ver-pokemon-do-time` | Ficha do ativo | C: perfil e espécie relacionados |
| Família `time` + `liga` | `configurar-liga` com `time` | Consulta escalação selecionada | C/H: ligas |
| Família `time` + `salvar/usar/ver/excluir/apagar/remover` | `configurar-time-pronto`, `nome-time-pronto`, `descrever-time-pronto` | Escalações nomeadas por identidade; não reserva Pokémon; usa em liga/ginásio | C: `escalacao-nomeada-usa-identidades-sem-reservar-pokemons` |
| `times` | `configurar-time-pronto` sem args | Lista escalações nomeadas | C: escalações |
| `removergolpe`, `removergolpes`, `esquecer`, `esquecergolpe`, `rmg` `<n>` | `remover-golpe` → timer `aplicar-remocao-golpe!` | Janela de 30 s; protege último ataque do tipo; revalida ativo/golpe ao vencer prazo; notificação posterior | G/C: proteção de golpes; timer sem teste isolado identificado |
| `cancelar`, `cancela`, `can` | `cancelar-remocao` | Cancela exclusivamente remoção de golpe pendente; troca/professor/raid têm cancelamentos próprios | C relacionados |
| `escolher`, `trocar`, `troca`, `esc` `<n>` | `escolher-ativo` | Define ativo; uma troca na caçada consome turno; respeita bloqueios PvP/ginásio | C/PC: índices e combate |
| `equipar`, `item`, `eqp` `<n> <item>` | `equipar-item` | Item equipável, consumo/devolução ao inventário, restrições de batalha | L: itens equipáveis/evolução |
| `doar`, `doa` `<n> @pessoa` ou resposta | `doar`, `resolver-alvo-doacao` | Revalida remetente/destinatário/ocupação/capacidade depois de resolver alvo; transfere registro; marca doação | PC: doação, capacidade, retorno |
| `joy`, `enfermeira`, `enfermaria`, `hospital` `[números]` | `enfermeira-joy` | Consulta ou envia feridos para tratamento por 30 min; retorno posterior preserva identidade/favorito; cartões distintos | C: Joy, retorno/favorito e imagens; PC: estoque |
| `atacar`, `ataque`, `atirar`, `usar`, `atk` `<slot>` | `atacar`, `atacar-selvagem`, `resolver-ataque`, `turno-selvagem`, `turno-lider` | Núcleo PvP/PvE/ginásio: dano, precisão, tipo, habilidades, estágio, status, turno, nocaute/substituição, XP, moedas, captura, rank e evolução; imagens | C: combate/rodadas/imagens/persistência; G |
| `defender`, `defesa`, `esquivar`, `evasiva`, `def` | `defender-turno` | Defesa/evasão e turno; oponente automático em caçada/ginásio | C: combate/rodada líder |
| `curar`, `cura`, `cur` | `curar-turno`, `curar-fora-de-batalha`, `curar-na-cacada` | Cura de status com inventário; amizade; consome turno quando aceita em batalha | C: status/fim de turno |
| `pocao`, `poção`, `vida`, `pot` `[n]` | `pocao-turno`, `pocao-fora-de-batalha`, `pocao-na-cacada` | Cura 40% HP no alvo ou ativo; amizade; inventário e turno | C: alvo da poção; L: preço/cura |
| `pocao-maxima`, `maxima`, `pmax` `[n]`; família poção + `maxima/máxima/max [n]` | Mesmos handlers com item `pocao-maxima` | Cura total; restante da semântica da poção | C: poção; L: cura |
| `espaco`, `espaço` `[comprar]` | `comando-espaco`, `loja/comprar-espaco-pc!` | Consulta ocupação total com Joy/ginásios; +50 vagas por 200 moedas; migra coleção | PC: migração, preço/capacidade; E |
| `pc`, `computador`, `centro` `[pc] [comprar]` | `comando-pc` → `comando-espaco` se comprar | Compatibilidade do PC desativado: orientação sobre coleção; compra antiga continua válida | PC: migração e compras |
| `bug`, `bugs` | `bugs/comando!` fora da fila Pokémon | Histórico/citação, versão e admin; relatórios genéricos (detalhes abaixo) | C: bugs não executam rodada; `bugs_test` |
| `ajuda`, `help`, `?` `[tema]`; `<tema> ajuda/help/?`; `ajd` | `pokemon-ajuda/resposta` | Guias sem ação de jogo; `ajd` mostra aliases; não migra/recolhe nem executa líder | H: ambos; C: ação autorizada do líder |

### Subcomandos e aliases contextuais

| Família | Entradas e destinos |
| --- | --- |
| Liga | `time`, `tm` → `time`; nomes definidos em `treinador/ligas` |
| Ginásio | Consulta vazia/nome; `desafiar`, `des`, `dsf`; `time`, `tm` com `auto`, números, ou `pagina N`; `ranking`, `ran`; `historico`, `histórico`, `hist`; `pocao`, `poção`, `pot`; `fruta`, `frambo`, `fru`, `fruta-dourada`, `dourada` + nome + defensor 1–3 |
| Ginásio → raide | `entrar/ent`, `iniciar/ini`, `atacar/atk`, `capturar/cap`, `sair/sai`, `cancelar`. Entrada sem número escolhe `auto`. `desafiar <ginásio>` com raide ativa inscreve automaticamente. Ataque em batalha de ginásio continua no combate do ginásio |
| Raide | `abrir/abr` apenas informa que a aparição é automática; `entrar/ent [n/auto]`; `iniciar/ini`; `atacar/atk <slot>`; `sair/sai`; `cancelar/can`; `time [pagina N ou N]`; `capturar/cap <bola>` |
| Time nomeado | `salvar/sal <nome> n1,n2,n3`; `usar/usa <nome> [liga/lig/ginasio/gin/ginásio]`; `ver <nome>`; `excluir/exc/apagar/apa/remover/rm <nome>`. O mapa de normalização inclui `raide/raides → raid`, mas o handler de aplicação só implementa destinos liga/ginásio |
| Professor | `enviar <n>`, `confirmar <código>`, `cancelar`; `cartoes/cartões/saldo`; `usar <n>`. Confirmação de transferência vale 5 min; cartão concede 3 XP, sem consumo no nível máximo |
| Negociar | Propor com dois índices e alvo; `aceitar <id>` pelo destinatário; `confirmar <id>` pelo autor; `cancelar/recusar <id>` por participante; validade de 5 min |
| Bug | `bug` registra; `bugs [versão]` lista até 20; `bug ver <id>` detalha; `bug resolver/res <id>` resolve. Consultar/resolver exige admin; registro simples não exige. `bugs` é comando plural, não alias de cadastro |
| Ajuda | Temas: batalha/batalhas/pvp; atacar/ataque/atk; ginasio/ginasios/gin; cacar/cacada/cacadas/cac; capturar/captura/cap/pokebola; liga/ligas/lig; pc/computador/centro/espaco; professor; time/equipe/tm; atalho/atalhos/abreviacoes/abreviacao; raid/raide/raides/raids; shiny; missoes/semanais; evoluir/evolucao/evo. A normalização da ajuda remove acentos |

Filtros de coleção: um ou mais tipos/raridades, liga (direta ou `liga <nome>`), nível (`nivel N`, `nv N`, `nv.N`, número conforme contexto), nome parcial, `shiny`, `>`/`<` para força. Em `time` o número inicial é página; em `pokedex <n>` é índice individual. Não unificar esses parses acidentalmente.

### Diferenças existentes entre código e textos de ajuda

- O texto de comando desconhecido anuncia `pokemon abrir` e `pokemon entrar`, mas `jogar-comando` não tem essas entradas: abrir/entrar PvP ocorre sem argumento.
- `raid abrir`/`abr` existe apenas como orientação, pois raides agora aparecem automaticamente.
- Há textos antigos de ajuda que mencionam PC separado ou bloqueio de caçada com coleção cheia, enquanto os handlers/testes atuais preservam coleção unificada e permitem lutar sem capturar.
- O inventário toma o dispatcher e seus testes como comportamento atual. Corrigir textos ou adicionar comportamento seria uma alteração funcional separada da extração.

## Lógica compartilhada por vários comandos

| Bloco no `pokemon/core.cljs` | Responsabilidades que precisam acompanhar a extração |
| --- | --- |
| Consulta e preparação de espécies, linhas 276–590 | `buscar-golpe`, seleção/normalização de movimentos, stats suavizados, consulta PokeAPI, dados de espécie, evolução por nível/amizade/troca, cache em memória |
| Combate e recompensa, linhas 591–1383 | Tipos, raridade, chance de captura, habilidades, status, crítico, esquiva, alterações de atributos, turno, HP/XP, recompensas, rank, conclusão terminal |
| Renderização, linhas 1385–2066 e 2872–3428 | Download com fallback, sprites proporcionais, arenas, overlays de golpe, bolas/capturas, eventos, evolução, treinador, coleção, CSV e ficha pessoal |
| Persistência, linhas 59–206 | Snapshots de batalhas/caçadas por chat; EDN preserva keywords; remove objetos de runtime; estados intermediários não retomam; marcadores terminais impedem premiação duplicada |
| Coordenação, linhas 5627–5747 | Rodada indivisível por chat, evolução/XP pendente, turno automático, escolha da resposta, espera das gravações, contexto de desempenho |

Não basta enviar o dispatcher via HTTP e conservar golpes, evolução, imagens ou caches Pokémon no processo WhatsApp. Esses são componentes da mesma funcionalidade extraída.

## Fronteira WhatsApp que deve desaparecer do domínio

### Dados de entrada

Hoje o domínio recebe um objeto vivo `Message`. O contrato HTTP precisa de dados simples, pelo menos:

- Identificador estável da requisição/mensagem para deduplicação de mutações e correlação.
- Identificador de chat (`fromMe ? to : from`), jogador (`author || from`), comando externo e argumentos originais.
- Nome já resolvido com a mesma ordem `pushname`, `name`, `number`, `Alguém`.
- IDs mencionados normalizados preservando `@lid`/`@c.us`; o código usa o primeiro e evita `getMentions` por incompatibilidade de identidade.
- Autor da mensagem citada quando usado em doação/troca. Menção tem precedência; presente aceita apenas menção.
- Metadados para resposta citada, prefixo e contexto de diagnóstico, sem transmitir objetos `Client`, `Message`, Promises ou atoms.
- Se relatórios genéricos `bug` permanecerem no bot, extrair seu despacho antes de encaminhar Pokémon; se forem incluídos no serviço, enviar o conteúdo citado/anterior, versão e autorização administrativa explícita. Não instalar dependência de WhatsApp no serviço para resolver isso.

`getContact` é chamado em batalhas, perfil e raides. A sua demora atualmente ocupa a fila do jogo. Resolver esses dados no adaptador antes da requisição evita que o serviço dependa do navegador.

### Saídas ordenadas

O retorno atual não é só string: há `nil` (já houve envio), `{:texto :mentions}`, `{:media :texto :legenda}`, `{:documento :texto}` e suporte a `{:medias :legenda-ultima :texto}` no emissor do bot. Vários handlers enviam mensagens antes do retorno final.

Uma resposta HTTP precisa representar uma sequência ordenada de ações independentes do transporte. A sequência deve cobrir texto, menções, imagem/arquivo (MIME, nome, dados ou referência), legenda normal e curta alternativa, além de regra de fallback textual. O bot continua responsável por criar `MessageMedia` e executar WhatsApp. A renderização PNG, acesso a sprites/PokeAPI, seleção de arte e conteúdo do CSV pertencem ao serviço.

Pontos de envio direto a substituir por ações: `enviar-imagem`, `enviar-imagem-ginasio`, `enviar-cartao-evolucao!`, `enviar-cartao-evento!`, `enviar-imagem-vs`, `enviar-anuncio-batalha`, `pokedex/enviar-cartao`. Construção de `MessageMedia` também existe em `loja/ver-loja-com-imagem` e todos os cartões retornados pelo core.

Preservar os seguintes comportamentos do adaptador atual:

- Texto longo de imagem usa legenda curta e texto adicional, mantendo menções.
- Falha de envio de mídia ainda entrega resposta textual.
- CSV envia o texto primeiro e documento em seguida, com `sendMediaAsDocument`.
- Sequências de mídia são enviadas em ordem, sem paralelizar páginas.
- Mensagens já geradas durante evolução/anúncio não podem ser descartadas só porque o retorno final é `nil`.

### Ações sem requisição em andamento

| Origem | Comportamento atual | Necessidade HTTP |
| --- | --- | --- |
| Remoção de golpe | `setTimeout` de 30 s executa mutação e `.reply` | Agendamento e validação no serviço; entrega posterior autenticada ao adaptador por HTTP, com ID de evento/deduplicação |
| Prazo de caçada | 5 min; remove combate, quebra sequência, persiste e avisa | Serviço proprietário do relógio e da mutação; entrega de aviso por HTTP |
| Prazo de PvP/ginásio | 30 min; encerra/penaliza quando aplicável e avisa | Mesmo requisito; preservar que desistência não é vitória |
| Combates restaurados | `iniciar!` espera WhatsApp pronto e concede nova janela completa | Sinal explícito de disponibilidade do adaptador para o serviço; não começar o prazo durante desconexão |
| Aparição de raide | Verificação a cada 60 s; agenda persistida por chat, ocupa mesma fila, monta arte e envia | Agendamento no serviço; HTTP para notificação, sem exigir nova mensagem do jogador |
| Joy e XP de raid | Recolhimento/resgate preguiçoso ao comando, não timer de WhatsApp | Continuar no preâmbulo do serviço; não inventar notificações extras |

Para confiabilidade, a entrega HTTP posterior precisa retenção e confirmação de eventos, por exemplo registros locais/Cassandra de saída pendente mais callback ou consulta HTTP. Isso é mecanismo de entrega da própria aplicação, não requer broker. Repetir um evento de saída não pode reaplicar a regra de jogo nem conceder a recompensa novamente.

## Dependências fora da pasta Pokémon

| Dependência | Uso atual | Risco/fronteira |
| --- | --- | --- |
| `armazenamento` | Registro/hidratação de atoms e snapshots; aguardar gravações | Serviço deve ser o único escritor das chaves Pokémon. Compartilhar processo de armazenamento/caches antigos em dois processos permite sobrescrita |
| `rank` | Vitória Pokémon soma ponto; desistência retira um ponto do total | Rank também é escrito por velha/naval/quiz. A penalização não decremente apenas a parcela Pokémon; exige dono único do total e operação idempotente por HTTP, ou armazenamento preparado para concorrência. Replicar o atom em dois serviços viola a semântica e pode perder pontos |
| `bugs`, `historico`, `bloqueio` | `pk bug`, leitura citada/anterior e autorização administrativa | Funcionalidade genérica acessível por prefixo Pokémon. Pode ficar no adaptador sem regras de jogo, mantendo respostas/permissões; documentar esse limite |
| `config` | Prefixo, nome do bot, fuso de missões e dados de armazenamento | Separar config de domínio de credenciais/env do WhatsApp |
| `desempenho` | Medições por mensagem/etapa, tamanho PNG/base64 e predecessor da fila | Transportar ID de correlação; instrumentar HTTP separando rede, fila do serviço, jogo/renderização/persistência e envio WhatsApp |
| `traducao` | Pokédex traduz descrições/habilidades | Mover uso e cache pertencentes à Pokédex; preservar traduções e fallback |
| `whatsapp-web.js` | `MessageMedia`, cliente e métodos de mensagem | Só pode permanecer no adaptador do bot após a separação |
| `sharp`, `fs`, assets | Renderização pesada de imagens | Pertencem ao serviço para aliviar VM do navegador |

## Testes existentes e lacunas de aceitação

Existem **133 declarações `deftest`** em `test/zapbot/pokemon`: core 70, PC 17, professor 10, raides 10, loja 7, evento recomeço 5, aventuras 4, golpes 4, missões 4 e ajuda 2. Este levantamento contou e leu os testes; não é resultado de execução.

Também precisam ser preservados: 2 testes do router (`pk-treinador-despacha-para-o-comando-pokemon`, `bloqueio-de-pk-consulta-a-chave-pokemon`); testes de envio do `test/zapbot/core_test.cljs`; `bugs_test.cljs`; armazenamento, tradução e desempenho usados por Pokémon.

Para aceitar a extração, além dos testes de regra atuais, será necessário cobrir contrato/integração: todas as rotas externas e aliases, ordem de ações durante evolução, PNG/CSV e fallback, mentions `@lid`, citação e nome, fila por chat, indisponibilidade/timeouts HTTP, deduplicação após resposta perdida, restauração de combate, timers/notificações sem jogador ativo, rank compartilhado e ausência de importações Pokémon pesadas no build WhatsApp. A presença de teste de domínio relacionado não prova que o comando sobre HTTP será equivalente.


## Apêndice: nomes dos testes Pokémon existentes

### `ajuda_test.cljs` — 2 testes

- `ajuda-de-ataque-aceita-comando-e-atalho`
- `ajd-exibe-todos-os-atalhos-pokemon`

### `aventuras_test.cljs` — 4 testes

- `desbloqueio-de-ginasios-respeita-a-ordem`
- `pedra-solar-contem-as-evolucoes-suportadas`
- `validacao-de-troca-detecta-expiracao-e-alteracoes`
- `eventos-possuem-periodos-deterministicos`

### `core_test.cljs` — 70 testes

- `efetividade-de-tipos-considera-duplo-tipo-e-habilidade`
- `estagios-alteram-stats-e-respeitam-limites`
- `dano-de-status-e-cura-de-restos`
- `intimidacao-reduz-o-ataque-correto`
- `habilidades-de-hp-baixo-so-impulsionam-o-tipo-correto`
- `imunidade-produz-ataque-sem-dano`
- `bonus-das-bolas-respeita-o-limite`
- `captura-de-primeira-concede-xp-extra-apenas-no-sucesso`
- `barra-de-hp-nao-exibe-valor-negativo`
- `atalhos-pokemon-sao-expandidos`
- `atalhos-funcionam-tambem-nos-subcomandos`
- `identifica-ataques-que-devem-levar-foto-do-ginasio`
- `identifica-imagens-da-cacada-e-fugas`
- `arena-da-cacada-tem-grama-e-fumaca-apenas-na-fuga`
- `arena-da-cacada-muda-o-cenario-conforme-o-bioma`
- `pokebolas-de-captura-tem-cores-e-estados-visuais`
- `reconhece-comandos-e-resultados-de-captura`
- `fuga-da-captura-mostra-bola-aberta-com-fumaca`
- `permanencia-de-ginasio-formata-tempo-e-xp`
- `xp-grande-processa-varios-niveis-e-preserva-desmaio`
- `progresso-legado-acima-de-nove-e-corrigido-ao-carregar`
- `defensor-volta-do-ginasio-desmaiado`
- `favorito-volta-da-joy-saudavel-e-fica-ativo`
- `escalacao-nomeada-usa-identidades-sem-reservar-pokemons`
- `tentativa-de-ginasio-recompensa-participacao-e-nocautes`
- `pocao-sem-numero-usa-ativo-e-com-numero-cura-o-escolhido`
- `motivacao-do-ginasio-cai-com-o-tempo-e-enfraquece-defensores`
- `pocao-recupera-motivacao-e-defesa-vencida-desgasta-o-time`
- `fruta-recupera-motivacao-do-defensor`
- `clima-e-areas-possuem-rotacao-diaria-estavel`
- `amizade-titulos-e-descobertas-sao-persistentes`
- `imagens-estaticas-de-captura-sao-png`
- `menu-de-captura-centraliza-apenas-o-selvagem-derrotado`
- `efeitos-visuais-cobrem-golpes-status-shiny-e-substituicao`
- `menu-de-golpes-nao-cria-efeitos-de-status`
- `golpes-da-rodada-ficam-lado-a-lado-e-escalam-com-dano`
- `rodada-do-ginasio-remove-estado-intermediario`
- `rodada-do-ginasio-mostra-cabecalho-apenas-no-inicio`
- `derrota-no-ultimo-golpe-do-lider-preserva-o-dano`
- `vez-automatica-do-lider-nao-expoe-golpes-do-npc`
- `substituicao-do-lider-resolve-turno-pendente-na-mesma-rodada`
- `comandos-de-bug-nao-executam-rodada`
- `somente-acao-aceita-do-desafiante-autoriza-lider`
- `comandos-do-mesmo-chat-aguardam-a-rodada-completa`
- `erro-na-rodada-nao-bloqueia-proximo-comando`
- `diagnostico-identifica-predecessor-sem-liberar-fila`
- `raide-passa-contexto-para-fila-e-aguarda-conclusao`
- `efeito-visual-usa-o-golpe-escolhido`
- `tamanho-visual-respeita-a-altura-da-especie`
- `classifica-cartoes-dos-eventos-pokemon`
- `cartao-de-evolucao-mostra-as-duas-formas`
- `cartoes-da-joy-tratando-e-do-hospital-usam-imagens-distintas-no-tamanho-padrao`
- `cartao-das-missoes-usa-imagem-do-professor`
- `cartao-do-treinador-mostra-ash-e-pokemon-ativo`
- `batalhas-temporarias-preservam-keywords-e-removem-objetos-de-runtime`
- `persistencia-temporaria-descarta-estados-perigosos-e-expirados`
- `marcador-terminal-impede-ressurreicao-e-expira`
- `persistencia-atualiza-somente-o-chat-que-mudou`
- `dados-do-treinador-cabem-na-legenda-da-imagem`
- `candidatos-de-sprite-priorizam-jsdelivr-para-github-raw`
- `cartao-time-mede-png-e-base64-sem-alterar-a-imagem`
- `cartao-do-treinador-renderiza-pokemon-ativo`
- `cartao-do-treinador-sobrevive-a-sprite-indisponivel`
- `ataques-pvp-tambem-recebem-arena-visual`
- `moldura-do-time-de-ginasio-tem-tres-espacos`
- `marcador-do-ginasio-mostra-e-esvazia-a-motivacao`
- `imagem-do-ginasio-compoe-os-tres-defensores`
- `menu-de-ginasios-omite-pokemons-do-lider`
- `vitoria-sem-captura-premia-raridade-e-base-fixa-sem-bonus-de-primeira`
- `arena-pvp-e-opaca-e-entrada-nao-cobre-o-pokemon`

### `evento_recomeco_test.cljs` — 5 testes

- `novo-recomeco-dura-sete-dias-sem-renovar-no-reinicio`
- `bonus-antigo-nao-altera-capacidade-nem-compras`
- `recompensas-unicas-isoladas-persistidas-e-pendentes`
- `vitoria-selvagem-atualiza-menu-do-evento`
- `bolsa-cheia-preserva-xp-e-conta-vitoria-no-evento`

### `golpes_test.cljs` — 4 testes

- `identificador-aceita-slug-e-nome-traduzido`
- `traducao-preserva-dados-do-golpe`
- `golpes-unicos-usam-o-identificador-normalizado`
- `ataque-do-tipo-exige-tipo-classe-e-poder`

### `loja_test.cljs` — 7 testes

- `itens-de-evolucao-possuem-mapeamento-e-sao-equipaveis`
- `nomes-de-itens-sao-normalizados`
- `catalogo-diferencia-itens-equipaveis`
- `frutas-possuem-recuperacao-de-motivacao`
- `pocoes-possuem-precos-e-curas-diferentes`
- `sequencia-diaria-avanca-ou-reinicia`
- `catalogo-da-loja-usa-imagem-propria`

### `missoes_test.cljs` — 4 testes

- `missoes-diarias-escalam-com-o-nivel`
- `progresso-diario-e-limitado-e-renovado`
- `semana-comeca-na-segunda-feira`
- `resgate-semanal-entrega-e-marca-recompensas`

### `pc_test.cljs` — 17 testes

- `migracao-preserva-todos-os-registros-e-escalacoes`
- `migracao-de-conta-anterior-ao-pc-nao-corta-excedentes`
- `colecao-unificada-ao-carregar-e-gravada-na-primeira-consulta`
- `expansao-debita-preco-fixo-e-nao-cobra-sem-saldo`
- `retornos-e-doacoes-vao-a-colecao-sem-cortar-excedentes`
- `estoque-inclui-joy-e-ginasios-e-bloqueia-antes-da-bola`
- `pc-nao-move-pokemon-durante-batalha`
- `pc-preserva-estado-apos-recarregar-e-nao-libera-outro-inicial`
- `captura-acrescenta-a-colecao-sem-limite-de-seis`
- `retorno-real-do-ginasio-vai-a-colecao-e-mantem-xp`
- `estoque-cheio-permite-cacada-respeitando-cooldown`
- `comando-unifica-colecao-em-combate-sem-mudar-indices`
- `compras-do-pc-valem-na-colecao-sem-nova-cobranca`
- `doacao-revalida-capacidade-e-entrega-na-colecao`
- `paginas-do-pc-tem-doze-e-preservam-os-numeros-originais`
- `filtros-do-pc-usam-a-mesma-ordenacao-do-time`
- `time-envia-uma-imagem-com-doze-pokemon-e-proxima-pagina`

### `professor_test.cljs` — 10 testes

- `familia-compartilhada-entre-estagios`
- `transferencia-so-remove-ao-confirmar-e-premia-uma-vez`
- `transferencia-invalida-se-expirou-cancelou-ou-pokemon-mudou`
- `cartao-concede-xp-preservando-identidade-e-regras-de-nivel`
- `cartao-respeita-teto-desmaio-e-faixa-da-liga`
- `professor-bloqueia-batalha-cacada-raid-e-evolucao`
- `favorito-nao-gera-pedido-de-transferencia`
- `novo-pedido-usa-outro-codigo-curto-e-invalida-o-anterior`
- `comando-professor-exige-codigo-e-devolve-item-uma-vez`
- `comando-cartao-processa-subida-e-libera-bloqueio`

### `raids_test.cljs` — 10 testes

- `chefes-e-chances-respeitam-nivel`
- `aparicao-preserva-defensores-e-agenda-apos-reinicio`
- `vitoria-premia-uma-vez-e-oferece-captura-aos-participantes-ativos`
- `captura-nao-consome-bola-sem-espaco-e-entrega-especie-normal`
- `derrota-expiracao-e-inscricoes-nao-liberam-captura`
- `moldura-de-ginasio-renderiza-como-png`
- `rodizio-persistido-passa-por-todos-sem-sobrepor`
- `escalacao-automatica-ordena-aptos-e-filtra-a-liga`
- `gin-time-sem-numeros-salva-e-mostra-os-tres-mais-fortes`
- `desafiar-ginasio-com-raide-inscreve-sem-outro-comando`
