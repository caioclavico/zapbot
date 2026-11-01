# A6 — Fase 3: Raides automáticas

**Branch:** `feature/a6-raid-auto-fase3` (worktree `zapbot-raid-lobby`)  
**Base:** Fase 2.1 validada (lobby cooperativo)  
**Worktree `a6-save-slots`:** preservado  
**Não feito:** deploy, migrate, backfill, merge, produção

## Objetivo

Aparições automáticas por grupo, reutilizando lobby Fase 2 + combate/recompensas M3.6/M4.9, com scheduler recuperável (Datomic) e anúncios via outbox (`GET /v1/events/pending` + `POST /v1/events/:id/ack`).

## Desenho

| Peça | Detalhe |
| --- | --- |
| Agenda | `:raid-auto-schedule/*` por `chat-id`; `enabled` default **false** |
| Intervalo | default `raid/intervalo-proxima` (6h); `next-at` é a fonte de verdade |
| Ginásio/chefe | round-robin no catálogo A6 + `boss-do-ginasio` |
| Spawn | TX atômica: receipt `raid-auto:{chat}:{due-at}` + raid `origem=auto` + CAS ponteiro + agenda + outbox |
| Lobby | 2 min / máx 5 / mín 2 (Fase 2) |
| Expire | tick **primeiro** expira lobbies; ≥2 → combate; senão cancela; outbox se `origem=auto` |
| Kill switch | Bridge `raid-auto-tick-enabled?` default false + Node `POKEMON_RAID_AUTO_TICK=false` |
| Manual | Comandos Fase 2 intactos; chat busy bloqueia spawn automático |

Schema aditivo: `migrations/pokemon/030-raid-auto.edn` (agenda + outbox + `:raid/origem`).

## Ativação (explícita)

1. Aplicar migrate 030 em ambiente alvo (fora desta entrega).
2. Subir bridge com `:raid-auto-tick-enabled? true`.
3. `POKEMON_RAID_AUTO_TICK=true` no Node (poll de eventos JVM já chama tick).
4. Por grupo: `PUT /v1/raids/auto/schedule` com `enabled: true` (e opcionalmente `nextAt` / `intervalMs`).

Enquanto qualquer um dos passos 2–4 faltar, **nada aparece**.

## Testes executados

| Suite | Resultado |
| --- | --- |
| `./scripts/test-raid-auto-fase3.sh` (13 testes / 48 asserts) | OK |
| `./scripts/test-raid-lobby-fase2.sh` | OK |
| `./scripts/test-m4-raid.sh` | OK |

Cobertura: kill switch, spawn+outbox, idempotência spawn-id, skip se raide ativa, expire cancel/start + anúncio, reinício, ack idempotente, multi-grupo, manual Fase 2.

## Riscos residuais

- Sem migrate/deploy: produção sem 030 e sem tick ligado.
- Anúncios at-least-once (mesmo contrato do outbox de ovos): ack após WhatsApp; falha mantém pendente.
- Tick Node só efetiva se a bridge também tiver `raid-auto-tick-enabled?`.
- Ordem expire→spawn no tick evita sobrescrever lobby no instante do prazo; intervalos curtos (< 2 min) podem abrir nova raide no mesmo ciclo após cancelar.
- Combat/economia **não** alterados; recompensas só na vitória M4.9.
