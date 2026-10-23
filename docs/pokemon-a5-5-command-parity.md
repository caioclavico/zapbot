# A5.5 — Auditoria de paridade dos comandos Pokémon

Base auditada: `10c0bd3`.

Esta matriz separa a existência do handler da validação ponta a ponta. Um
endpoint ou um alias não é considerado prova de paridade sem teste do fluxo e
da resposta.

| Funcionalidade/comandos | Core atual | Evidência | Estado | Próximo risco |
|---|---|---|---|---|
| Onboarding, treinador e saves (`iniciar`, `inicial`, `treinador`, `save`, `saves`, `ativar`) | Bridge e cliente JVM | `pokemon_http.cljs`, `bridge.clj`, testes M4 | funcional | smoke HTTP completo ainda depende de loopback |
| Coleção, ficha e Pokédex (`time`, `pokedex`, `dex`, `pdx`) | DTOs, filtros, ficha e leitura Datomic | `pokemon-jvm-present.test.cjs`, `pokemon_http.cljs` | funcional | mídia e paginação ainda precisam de smoke real |
| Caçada e captura (`cacar`, `capturar`, `cap`) | Plano, início, turno e captura | `capture_m33_test.clj`, `pokemon-jvm-capture.test.cjs` | funcional no contrato | E2E WhatsApp completo não executado nesta auditoria |
| Batalha PvE (`atacar`, `defender`, `escolher`, `curar`, `pocao`, `sair`) | Turnos JVM e CAS de save | `battle_m34_test.clj`, `pokemon_http.cljs` | funcional no domínio | falta fluxo HTTP público em ambiente com socket |
| Ginásios (`ginasio`, `gin`, desafio, time, cura, ranking, histórico) | Leitura, escalação, desafio e turnos | `gym_m35_test.clj`, `gym_m36_test.clj`, `gym_m47_test.clj`, `gym_m48_test.clj` | parcial | cobertura ponta a ponta e recompensas após reinício |
| PvP (`batalhar`, `abrir`, `entrar`, `atacar`, `defender`, `sair`) | Estado e turnos Datomic/bridge | `pvp_a3_test.clj`, `pokemon-jvm-a3.test.cjs` | parcial | autorização e resposta WhatsApp real |
| Raids (`raid`/`raide`, abrir, entrar, iniciar, atacar, capturar, sair) | Cliente, bridge e domínio | `raid_m36_test.clj`, `raid_m49_test.clj`, `pokemon-jvm-raid.test.cjs` | parcial | teste Node→JVM com múltiplos participantes |
| Joy (`joy`, `enfermeira`, `enfermaria`, `hospital`) | Envio, coleta e disponibilidade | `joy_eggs_m410_test.clj`, `pokemon-jvm-joy-eggs.test.cjs` | funcional no contrato | smoke WhatsApp completo |
| Ovos (`ovos`, `ovo`, `incubar`, `incubadoras`) | Status, incubação e eclosão | `joy_eggs_m410_test.clj`, `pokemon-jvm-joy-eggs.test.cjs` | funcional no contrato | concessão administrativa deve permanecer protegida |
| Loja e mochila (`loja`, `mochila`, compra, inventário) | Compra, limites e uso | `shop_m411_test.clj`, `pokemon-jvm-shop.test.cjs` | funcional | validar mídia e erros pelo WhatsApp |
| Evolução e golpes (`evoluir`, `aprender`, `reaprender`, `mt`, `removergolpe`) | Operações persistentes e recibos | `evolution_moves_a54_test.clj`, `pokemon_http.cljs` | funcional no domínio | catálogo completo de espécies ainda não exaustivo |
| Negociação (`troca`, criar, aceitar, confirmar, recusar, cancelar) | CAS, fencing, recibos e evolução por troca | `trade_a441_test.clj`, `trade_http_a443_test.clj`, `pokemon_http_test.cljs` | funcional no domínio; HTTP bloqueado nesta execução | executar com Docker/loopback |
| Missões e presentes (`missoes`, `presente`) | Clientes JVM e respostas | `pokemon-jvm-missions.test.cjs`, `pokemon-jvm-present.test.cjs` | funcional no contrato | não fazem parte da migração transacional auditada aqui |
| Clima e eventos (`clima`, `areas`, `mapa`, `eventos`) | Leitura JVM e apresentação | `pokemon_http.cljs`, `pokemon-jvm-present.cjs` | parcial | falta teste público de cada alias |
| Shiny histórico (`shiny`, `time shiny`) | Não há caso JVM equivalente completo | legado em `pokemon/core.cljs`; nenhum handler correspondente em `pokemon_http.cljs` | ausente/parcial | preservar histórico e mídia antes de liberar |
| Professor/cartões (`professor`) | Sem rota JVM correspondente | legado em `pokemon/core.cljs` | ausente | inventário, envio e uso de cartões |
| Título e amizade (`titulo`, `amizade`) | Sem caso JVM correspondente | legado em `pokemon/core.cljs` | ausente | persistência e apresentação |
| PC/espaço (`pc`, `computador`, `centro`, `espaco`) | Sem fluxo JVM equivalente | legado em `pokemon/core.cljs` | ausente | leitura/gestão de armazenamento |
| CSV e configuração de times (`time csv`, `times`, `liga`) | Apenas partes cobertas por coleção/ginásio | legado e handlers parciais | parcial | contrato específico e resposta de arquivo |

## Integridade observada

O cliente Node usa `operationId` estável e reconcilia recibos em timeout. As
rotas de escrita enviam Bearer e `X-Actor-Player-Id`; o bridge valida o ator e
o chat antes de delegar ao `TrainerStore`. As operações Datomic usam CAS,
fencing e recibos. O fluxo de negociação não possui fallback para o legado
quando a JVM falha.

Ainda não foi demonstrada, nesta execução, a corrida ponta a ponta entre todos
os escritores de batalha/Joy e uma troca, nem a entrega real por uma sessão
WhatsApp. O teste HTTP de negociação também não pôde abrir loopback no
sandbox.

## Validação executada

- `npx shadow-cljs compile test && node target/test.js`: **229 testes, 1.380 asserções, 0 falhas, 0 erros**.
- `npm run test:m4-jvm`: **11 aprovados; 6 testes bloqueados por `listen EPERM`** no sandbox; os testes puros de alias/apresentação passaram.
- `CLOJURE_BIN=$(command -v clojure) npm run test:trade-e2e`: bloqueado porque a JVM não pôde abrir `127.0.0.1:18093`; o runner registrou exit code e stderr.

Para validação completa, executar em Docker ou no Mac com loopback:

```bash
npx shadow-cljs compile test && node target/test.js
npm run test:m4-jvm
CLOJURE_BIN="$(command -v clojure)" npm run test:trade-e2e
```

## Conclusão

Estado: **BLOCKED para declarar paridade total A5.5**. Os comandos listados
como ausentes precisam de escopo próprio; os fluxos funcionais precisam de
smoke HTTP/WhatsApp fora da restrição de loopback antes de serem classificados
como totalmente migrados.

## A5.5.5 — revisão no commit e098c82

Esta seção atualiza a matriz acima para o estado efetivamente verificado neste
commit. A classificação separa domínio, contrato HTTP e entrada WhatsApp; a
existência de um handler não é considerada teste ponta a ponta.

| Área | Domínio JVM/Datomic | HTTP | WhatsApp simulado | Estado atual |
|---|---|---|---|---|
| Ginásios | 13 testes de M4.8, incluindo derrota, HP/status e recompensa idempotente | Testes preparados, mas loopback bloqueado | Presenter e aliases testados | **parcial**: ranking/histórico ainda não persistidos no Core |
| PvP | Cobertura A3 de desafio, aceitação, turnos e retry | Testes preparados, loopback bloqueado | Alias `batalhar` e apresentação testados | **parcial operacional** |
| Raids | Cobertura M3.6/M4.9 de entrada, turnos, captura e recompensas | Testes preparados, loopback bloqueado | Aliases e subcomandos testados | **parcial operacional**; abertura/cancelamento continuam administrativos |
| Clima/eventos | Plano de caça JVM e regras determinísticas | Rota existe, sem smoke neste sandbox | `clima`, `eventos`, `tempo` e `evt` cobertos na apresentação | **implementado, aguardando smoke HTTP** |
| Equipes/liga/CSV | IDs canônicos, faixas, HP, migration 022/023 e exportação | Rotas implementadas | Formatação de documento CSV testada | **implementado no domínio; HTTP pendente** |

### Ranking e histórico de ginásios

O legado possui `ginasios/ranking` e `ginasios/historico`, alimentados por
`ginasios-estatisticas` em armazenamento próprio. O Core atual persiste
ocupação, defesa e recompensas no Datomic, mas não possui entidade ou migration
equivalente para essas estatísticas. O presenter continua respondendo que as
operações estão indisponíveis. Isso é uma lacuna conhecida e foi mantida como
etapa separada (A5.5.5b), sem inventar critérios de ordenação ou pontuação.

### Execução desta regressão

- `npx shadow-cljs compile test && node target/test.js`: **231 testes, 1.389
  asserções, 0 falhas, 0 erros**.
- Domínio ginásio/PvP/raid: **33 testes, 142 asserções, 0 falhas, 0 erros**.
- Presenter Node: **10 testes, 10 aprovados**.
- Instalação das migrations 001–023 em banco descartável, seguida de segunda
  instalação: **idempotente**.
- Testes HTTP de negociação, perfil e equipes: bloqueados antes da primeira
  requisição por `SocketException: Operation not permitted` ao abrir loopback.

Comando recomendado no Mac/Docker para concluir os smoke tests:

```bash
docker run --rm --network bridge -v "$PWD:/app" odisseu-contract-tests \
  bash -lc 'npx shadow-cljs compile test && node target/test.js && npm run test:m4-jvm && CLOJURE_BIN="$(command -v clojure)" npm run test:trade-e2e'
```

O estado permanece **BLOCKED** para A5.6 até os contratos HTTP e o fluxo
WhatsApp simulado serem executados em ambiente com loopback permitido, e até a
decisão A5.5.5b sobre ranking/histórico ser implementada ou formalmente
retirada do escopo.
