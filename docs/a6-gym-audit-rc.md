# RC local — auditoria agregada dos ginásios A6

Data: 2026-11-06 16:07 UTC
Estado: **CONDITIONAL — artefato local construído; produção bloqueada pelos gates administrativos e de quiescência**

## Escopo e proveniência

O RC adiciona somente uma rota autenticada de diagnóstico agregado à JVM existente. Não altera Node, regras de batalha, HP, economia, saves, scheduler, eventos ou grupos. O endpoint usa `d/db` sobre a conexão que a JVM já abriu, não cria outro cliente Datomic, não aceita consultas do cliente e não executa transações.

Commits de código, todos na branch `codex/a6-gym-audit-endpoint`:

| Conteúdo | Commit |
| --- | --- |
| Rota e conexão com a sessão Datomic existente | `bea6780341fe7fe12d80d998f9f1f155fc020bd8` |
| Consultas agregadas, UNKNOWN, limites e testes HTTP | `454a05a5906c1fd11b2ce287e404bb7b8e101525` |
| Fixtures agregadas com schema 033/034 | `61cca09e583c31a2f21da0adcb5470e8f207d42e` |

**Commit final do código:** `61cca09e583c31a2f21da0adcb5470e8f207d42e`

**Git tree:** `973b7e53bb5d98fdca250ab54e5b2baf393c07ce`

O commit de código tem como ancestral a base P0 usada pelo clone (`5461375`, `bf8f5c6`). A comparação do código JVM entre essa base e o RC restringe-se ao entrypoint HTTP, bridge HTTP, novo agregador `gym_audit.clj` e smoke de manutenção. O Node não mudou; os commits de correção P0 de HP/sprites, kill-switch de desafios, Pokédex, time, trocas, raids/eventos e Announcer permanecem na ancestralidade. A imagem atual de produção não foi substituída.

## Contrato e segurança

Rota: `GET /ops/maintenance/a6-gym-state-audit`.

- Requer `X-A6-Maintenance-Token`; a comparação usa o mecanismo constante já existente. Sem token ou token inválido retorna 401.
- A rota só existe quando bridge, gate e token administrativo estão configurados.
- Não aceita corpo, parâmetros de consulta, EDN ou seleção de atributos; corpo presente é rejeitado com 400.
- Executa um único audit por JVM; concorrência adicional recebe 429.
- Timeout HTTP padrão de 5 s, limitado por configuração interna a 100–15.000 ms. No timeout, a operação ativa continua contabilizada pelo gate até o worker realmente terminar; se a query ignorar interrupção, o drain permanece bloqueado (fail-closed).
- Resposta limitada a 16 KiB. Excesso retorna erro curto e sanitizado.
- A resposta contém contagens, datas agregadas e estados apenas. Não retorna JIDs, chat/player/save/battle/Pokémon IDs, valores de saves ou payloads.
- A rota adquire o maintenance gate durante a leitura: drain pode aguardar a query e, depois de fechada a admissão, a rota retorna 503.
- A rota não fecha nem reabre a conexão; lê um snapshot via `d/db` da conexão JVM existente. Nenhuma segunda conexão, writer ou lock Datomic é iniciado.
- A porta JVM ativa observada em produção está publicada no host somente como `127.0.0.1:18092`; não há bind público. A rota ainda não está implantada.

Campos agregados: presença de migrations 033/034; batalhas totais/ativas/ginásio abertas e inconsistentes; vínculo entre receipts de início e batalhas; HP e campos ausentes agregados; ocupações inválidas; número/estado/menor próximo horário dos schedulers; contagem de eventos temáticos; marcadores duplicados conhecidos nos schedulers; marcadores diários da mochila. Se o schema/atributo não existir, o estado é `UNKNOWN`. O estado específico do kit diário e de mutações atrasadas também é `UNKNOWN`. O schema de receipts não representa uma fila `pending`; não se infere que zero requests ativos signifique zero batalhas ou jobs retomáveis.

`gym-last-activity-before-p0` mede a última atividade anterior ao cutoff configurado, não a criação da batalha. `duplicate-marker-groups` cobre apenas marcadores persistidos do scheduler; não prova ausência de toda duplicação em raids/eventos. Os agregados de HP precisam ser comparados com a referência P0 e com os dois HP zero legítimos já documentados; a rota não decide reparos.

## Testes e build

Todos executados localmente em Datomic descartável; loopback autorizado apenas para os harnesses locais.

| Verificação | Resultado |
| --- | --- |
| HTTP/JVM/Datomic: auth ausente/inválida/válida | PASS; 401/401/200 |
| Corpo malformado/presente | PASS; rejeitado 400 sem parse de entrada |
| Concorrência | PASS; segunda auditoria recebe 429 |
| Timeout | PASS; 504; gate fica contabilizado até terminar o worker |
| Falha simulada de consulta/Datomic | PASS; 503 genérico, sem mensagem interna |
| Limite de resposta | PASS; resposta grande substituída por erro curto |
| Sem transações/IDs | PASS; datom count estável e resposta sem campos identificadores |
| Gate de manutenção | PASS; leitura contabilizada, chamada após fechar admissão recebe 503 |
| Schema presente e fixtures sintéticas | PASS; migrations 033/034, batalha, receipt, HP, ocupação, raids, eventos e mochila agregados |
| Schema ausente | PASS; batalha e scheduler reportados como `UNKNOWN` |
| Reinício/resume do harness | PASS; smoke de manutenção preservado |
| M3 gym | PASS; zero falhas/erros |
| M4.8/M4.7/M3.5 gym + HTTP | PASS; respectivamente 12/77, 5/18 e 11/98, mais smoke HTTP |
| A5.2 gym/PvP HTTP | PASS |
| M4.1 bridge | PASS |
| M4.2 bridge transacional | PASS |
| Raid auto Fase 3 | PASS; 19 testes, 81 assertions |
| Eventos Fase 12.8.1 | PASS; 24 testes, 239 assertions |
| Migrations 034, recompensas de permanência, HP de batalha e integridade da coleção | PASS; 19 testes, 80 assertions |
| Load do namespace `odisseu.bridge.http-server` | PASS |
| Container RC real, `linux/amd64`, health + rota autenticada | PASS; somente tmpfs e token fictício; health `up`, 401 sem/invalid, 200 válido |

Falha de acesso ao Docker socket no sandbox foi resolvida com execução local autorizada do Docker Desktop. A construção usou um contexto temporário contendo apenas `deps.edn`, `Dockerfile.jvm`, `apps`, `shared`, `modules` e `migrations`; não incluiu `.env`, backups ou dados locais. Nenhuma imagem foi publicada ou transferida.

## Imagem imutável

- Tag local: `odisseu-jvm:a6-gym-audit-61cca09e583c-tree-973b7e53bb5d-amd64-rc1`
- Docker Image ID / digest local: `sha256:72f920bff5050bc756aecb45133cc062d0511cca19c7303e6519d3d642af166e`
- Arquitetura: `linux/amd64`
- Labels: revision `61cca09e583c31a2f21da0adcb5470e8f207d42e`; content-tree `973b7e53bb5d98fdca250ab54e5b2baf393c07ce`
- Tar: `/private/tmp/odisseu-a6-gym-audit-jvm-amd64-20261106T160721Z.tar` (249 MiB)
- SHA-256 do tar: `ef2401c139f3d19ef3d55409e7045ce697e0c65cd08c2098d1ddb365cd2511d0`
- Digest do config presente no tar: `sha256:c01541bfa82dfaace988a2a76ebe8457d9fd7489caa50821940fe405de9973d3`
- Manifesto de artefato: `rc-artifacts/a6-gym-audit-rc-20261106/manifest.json`

Node não precisa de rebuild; a atualização futura é JVM-only, mantendo a imagem Node P0 ativa. Nenhuma migration nova é necessária. O endpoint lê atributos existentes; migrations Pokémon 033/034 foram instaladas apenas nos fixtures descartáveis para teste. Não foi executada migration live.

## Estado operacional e gates

O preflight read-only de 2026-11-06 14:50 UTC observou Node/JVM healthy, WhatsApp READY, JVM `up`, maintenance `open`, `activeRequests=0`, raides automáticas ON, desafios OFF, recompensas de permanência OFF e Announcer OFF. A inspeção posterior confirmou que a porta da JVM está limitada ao loopback do host. Imagens P0 observadas: Node `sha256:5023873836935272c7fcea4a58a7b00de683121cf571c329a6e2e4081277a42b`; JVM anterior `sha256:f63800695178bfb8d7295ae035c4f9497a728d6ef67eda09924e30a189b4969d`.

Gates ainda não aprovados:

1. O valor efetivo de `ODISSEU_PRODUCTION_DEPLOY_HOLD` permanece UNKNOWN; a leitura administrativa GitHub não estava disponível. Zero runs ativos não prova hold.
2. Não há prova de contenção global/drain de batalhas, writers externos e jobs.
3. O token de manutenção antigo está comprometido; não foi usado, consultado nem rotacionado. A rotação requer sua própria janela segura e armazenamento em cofre.
4. Auditoria live de batalhas, saves, scheduler/eventos e Bag kit ainda não foi executada. A rota só existe na imagem RC local.

## Plano futuro de implantação e rollback

1. Administrador confirma hold `true`, sem execuções concorrentes, e reconcilia os controles de deploy.
2. Estabelecer contenção operacional de todos os writers; não tratar Node drain ou `activeRequests=0` como quiescência global.
3. Planejar rotação do token comprometido em janela controlada, atualizando fonte e consumidores coordenadamente; nunca reativar o segredo antigo. Não registrar o novo valor.
4. Se a operação JVM exigir parada, executar backup cold consistente e validar restore isolado sob procedimento próprio autorizado.
5. Validar digest, labels, arquitetura e compatibilidade do artefato local; preservar imagem JVM anterior e todos os volumes. Não há migration nova.
6. Cutover somente JVM, manter desafios e recompensas OFF, raids/events e Node atuais preservados; validar health, WhatsApp READY, bridge e rota com token novo.
7. Executar auditoria agregada, revisar `UNKNOWN` e comparar HP com a referência P0 antes de qualquer decisão sobre reabertura.
8. Em falha, voltar somente a imagem JVM anterior quando seguro; manter dados/volumes atuais. Não restaurar backup antigo nem reabrir desafios automaticamente.

## Decisão

**CONDITIONAL.** RC local construído e testado. A implantação em produção não está autorizada por este documento e permanece bloqueada até hold, contenção/drain, token e janela operacional estarem comprovados. Desafios permanecem OFF; nenhuma alteração de produção foi feita.
