# Odisseu A6 — Drain completo da JVM (freeze fail-closed)

**Estado:** implementação e ensaios em ambiente descartável.  
**Não executado:** freeze/backup na JVM live, migration 036, RC #66, anúncios, alteração de hold/CI.

## Dependência de imagem JVM

A JVM live (`sha256:5cfca31b…`) já possui o gate `open/draining/quiescent`, mas **não** inclui:

- fase `:frozen`
- admissão separada de leituras seguras vs mutações
- contabilidade `admitted/completed/failed/rejected`
- endpoint `POST /ops/maintenance/freeze`
- classificação fail-closed de rotas/ticks

Portanto o mecanismo completo **exige implantação autorizada e separada** de uma imagem JVM construída a partir deste branch antes de qualquer janela de cold backup real que dependa dessas garantias. Até lá, o drain live permanece operacionalmente incompleto (raid auto + maintenance `open`).

## Matriz de writers

| Writer | Gatilho | Persistência | Bloqueio no drain |
| --- | --- | --- | --- |
| Bridge HTTP mutações (POST/PUT/PATCH/DELETE) | Node / clientes autenticados | Datomic via stores | `acquire-write!` só em `:open` |
| Bridge HTTP leituras classificadas | Node | nenhuma (RO) | permitidas em `open/draining/quiescent`; bloqueadas em `:frozen` |
| `POST /v1/raids/auto/tick` | poller Node (`pokemon-raid-auto-tick`) | schedule/spawn/receipts | mutação; recusada após enter |
| `POST /v1/thematic-events/tick` | poller Node | ocorrências/outbox | mutação; recusada após enter |
| Idle battle sweeper JVM | timer interno | expire batalhas | `acquire-write!` |
| Gym aggregate audit | token de manutenção | nenhuma | `acquire-read!` |
| Scripts/cutover/migrations diretas | operador | Datomic direto | **fora do gate** — excluir por preflight |
| Release Announcer | Node | platform DB | independente; manter `suspended` |

## Arquitetura da barreira

Estados verificados (arquivo durável `bridge-maintenance.edn` + contadores em memória):

1. **open** — mutações e leituras admitidas.
2. **draining** — mutações fechadas; writes admitidos drenam; leituras seguras OK.
3. **quiescent** — `active-writes=0` comprovado; leituras seguras OK.
4. **frozen** — sem writes e sem leituras de jogo; pronto para shutdown controlado.

Regras:

- Contador de write é incrementado **antes** de retornar sucesso na admissão.
- Quiescência ignora reads ativas.
- Marcador inválido/tmp abandonado → fail-closed (`draining`/`quiescent`).
- Após crash, contadores zeram — **não** provar consistência; exigir novo enter/freeze + reconciliação de receipts/journal.
- Rotas GET desconhecidas são tratadas como mutação (fail-closed).

## Endpoints

- `GET /ops/maintenance/status`
- `POST /ops/maintenance/enter?timeoutMs=`
- `POST /ops/maintenance/freeze`
- `POST /ops/maintenance/resume`

Token: `X-A6-Maintenance-Token` (distinto do bearer da bridge).

## Runbook da janela real (futuro — não executar agora)

1. Autorização específica de cold backup.
2. Confirmar digests Node/JVM, hold, CI disabled, Announcer `suspended`.
3. **Implantar JVM com este mecanismo** (GO separado) se a live ainda for `5cfca31b…`.
4. Fechar admissão Node (`a6-maintenance` → quiescent).
5. `POST /ops/maintenance/enter` na JVM; confirmar `quiescent` + `activeWrites=0`.
6. Confirmar ticks de raid recusados (503) e schedule preservado (GET).
7. `POST /ops/maintenance/freeze`.
8. Stop controlado Node → JVM; `restart=no`; sem writers nos volumes.
9. `a6_cutover_backup.py backup` nos volumes `odisseu-a6-cutover-*`.
10. `verify` + `restore-isolated`; comparar fingerprint/agregados.
11. Restart JVM→Node; manter Announcer suspenso; `resume` só após validação.
12. Se drain falhar (timeout/contador/ambiguidade): **abortar sem copiar** volumes como backup consistente.

`quiescent`/`frozen` ≠ backup concluído. O cold só começa após shutdown dos writers e verificação de ausência de escritas nos mounts.

## Ensaios locais

| Suite | Comando | Esperado |
| --- | --- | --- |
| Unit drain/freeze | `clojure -M:test-a6-maintenance-drain` | PASS |
| Smoke HTTP | `clojure -M:smoke-a6-maintenance-quiescence` | PASS |
| Drain+cold descartável | `clojure -M:smoke-a6-drain-cold-rehearsal` | PASS |

## Compatibilidade

- Schema **035** preservado; **036** não introduzida.
- RC #66 / PE progressivo não ativados.
- Receipts, ledger, raids existentes e Recovery V1 intocados pelo gate.
- Contratos Node: 503 `maintenance_active` em mutações; leituras podem continuar até `freeze`.

## Vereditos

- **A.** Drain em ambiente descartável: **PASS** (`test-a6-maintenance-drain` 6/6, `smoke-a6-maintenance-quiescence` PASS, `smoke-a6-drain-cold-rehearsal` PASS com fingerprint estável).
- **B.** Pronto para solicitar autorização de implantação do mecanismo de drain: **READY** (código + ensaios locais; exige GO explícito de deploy JVM, sem 036/#66).
- **C.** Pronto para solicitar backup cold real: **NO-GO** até implantar a JVM com este mecanismo na live, executar drain/freeze comprovado na VM e só então abrir a janela cold.
