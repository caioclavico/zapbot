# A6 — Fase 3.1: Hardening de raides automáticas

**Branch:** `feature/a6-raid-auto-fase3.1`  
**Base:** `feature/a6-raid-auto-fase3`  
**Worktrees:** `a6-save-slots` preservado  
**Não feito:** deploy, migrate, backfill, merge, produção

## Alterações

### Intervalo e `next-at`
- Intervalo mínimo = duração do lobby (`2 min` / `raid/duracao-inscricoes`); valores menores → `:raid_auto_interval_invalid`.
- `advance-next-at` é **determinístico** a partir do due lógico: `max(due + interval, due + lobby + 1)`.
- Impede reaparição no mesmo tick após expire/cancel (next-at cai depois do fim do lobby).
- Um vencimento lógico → um `spawn-id` (`raid-auto:{chat}:{due}`); receipts preservados.

### Scheduler
- Slot bloqueia spawn se fase ∈ `{inscricoes, combate}` (manual **nunca** substituída pelo CAS de auto).
- Tick: expire → spawn.
- Conflito/timeout: TX rollback **não** avança agenda; retry com mesmo `spawn-id` devolve duplicate sem segundo avanço.
- Corrida multi-process: CAS de ponteiro + revision + receipt.

### Outbox
- IDs estáveis: `raid-opened:{raid-id}`, `raid-started:{raid-id}:{lobby-expira}`, `raid-cancelled:{raid-id}:{lobby-expira}`.
- At-least-once: envio WA → ack; reenvio da **mesma** mensagem possível se falhar entre send e ack.
- Reenvio **não** recria operação de jogo (receipt/spawn-id).

### Observabilidade (stderr, EDN numa linha)
| Evento | Quando |
| --- | --- |
| `raid_auto_spawn` | spawn OK / duplicate / persist_failed |
| `raid_auto_skip` | chat ocupado |
| `raid_auto_lobby_expired` | expire materializado |
| `raid_auto_started` | quorum → combate |
| `raid_auto_cancelled` | sem quorum |
| `raid_auto_delivery_failed` | Node: falha ao enviar/ack outbox |

Campos: `chat-id`, `raid-id`, `spawn-id`/`due-at`, `result` — sem PII além do necessário.

## Testes executados

| Suite | Resultado |
| --- | --- |
| `./scripts/test-raid-auto-fase3.sh` (19 / 76) | OK |
| `./scripts/test-raid-lobby-fase2.sh` | OK |
| `./scripts/test-m4-raid.sh` | OK |
| `npm run build` | OK |

Regressões 3.1: intervalo curto, ticks concorrentes, retry/recibo, manual protegida, outbox IDs + ack duplicado.

## Riscos residuais

- Duplicação **visual** WhatsApp ainda possível (send OK, ack falhou).
- Kill switch / migrate 030 / `enabled` por chat continuam necessários para ligar o recurso.
- Logs em stderr (sem agregador); correlacionar por `spawn-id`/`raid-id`.
- Sem deploy desta branch.
