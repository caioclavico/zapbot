# Fase 12.8.1 — READY FOR GO (eventos temáticos)

**Status:** implementação completa em ambiente descartável · **produção NÃO ativada**  
**Pré-requisito:** Fase 12.8.0 estável no piloto (`POKEMON_THEMATIC_EVENTS_ENABLED=false`)  
**Flag:** `POKEMON_THEMATIC_EVENTS_ENABLED` (default `false`)

---

## 1. Auditoria (catálogo + infra)

| # | Item | Achado |
|---|---|---|
| 1 | Catálogo 7 eventos | `pokemon.domain.events/catalog` — Festival Dragões, Noite Sombras, Tempestade Elétrica, Maré Misteriosa, Despertar Floresta, Era Fósseis, Eclipse Místico |
| 2 | Início/término | Janelas em ocorrências (`inicio-ms`/`fim-ms`), não no catálogo |
| 3 | `active-events` | Flag off → `[]`; com ocorrências materializadas + flag on |
| 4 | Flag | `POKEMON_THEMATIC_EVENTS_ENABLED` |
| 5 | Missões diárias/semanais | Preservadas (`mission-catalog` / `mission-progress`) |
| 6 | Missões de evento | `event-missions` por `occurrence-id` |
| 7 | Persistência progresso/resgate | `save/missoes-eventos-edn` + recibo `missions-claim-event` |
| 8 | Recompensas | Itens via `mission-rewards/allocate` + Doces em `professor-cards-edn` |
| 9 | Doces | Mesmo ledger `professor-cards-edn` (sem sistema paralelo) |
| 10 | Caça/clima | `resolver-tipos-encontro` 4 args: evento → clima 35pp → bioma |
| 11 | Scheduler/outbox | `thematic-event-store/tick-chat!` + `:pokemon-outbox/*` |

---

## 2. Arquitetura do scheduler

1. Agenda por chat (`thematic-event-schedule/*`, **enabled default false**).  
2. Tick (`POST /v1/thematic-events/tick`) também chamado no poll Node.  
3. Encerramento idempotente + outbox `thematic-event-ended`.  
4. Ativação: elegíveis, anti-consecutivo, anti-retroativo (graça 2h), máx. 1 principal, intervalo mínimo 72h.  
5. Force admin via `policy-edn` `{force-catalog-id}` (limpo após uso).  
6. Fuso: `America/Sao_Paulo` (override `POKEMON_EVENTS_TIMEZONE`).

---

## 3–8. Catálogo / frequência / elegibilidade / bias / missões / recompensas

- Frequências: comum 2×/sem 24h · especial 1×/sem 48h · raro 1×/mês 72h (`event-schedule/default-policy`, env overrides).  
- Elegibilidade dinâmica por tipos/famílias do catálogo; fósseis via `fossil-family-roots` verificável.  
- Bias: `min(35, max bias)` no primeiro band; clima 35pp; resto bioma; total ≤ 100.  
- Missões e recompensas: ver `events/catalog` (Frambo, bolas, cartão-presente, Doces por família).  
- Claim: `POST /v1/missions/claim/event` — fence + recibo + allocate + doces idempotentes (`candy-picks`).

---

## 9–12. WhatsApp / raids / admin / resumo

- `!pk missoes` / `!pk missoes evento(s)` / `missões` — present atualizado com remaining + recompensas.  
- Resgate: `!pk missoes evento resgatar`.  
- Anúncios start/end via outbox (kinds `thematic-event-started|ended`).  
- Raids: progresso `raid-join` / `raid-won` via `mission-events` (mesmo raid não conta 2×).  
- Admin: `GET/PUT /v1/thematic-events/schedule`, `POST .../cancel`, `GET .../active|history` (bridge loopback + bearer).  
- Resumo compacto: ≤2 diárias, ≤2 semanais, evento ativo + ≤2 missões de evento.

---

## 13. Testes executados

```bash
clojure -M:test-fase1281
```

Cobertura: catálogo, flag off, scheduler, bands caça+clima, progresso/claim/doces, snapshot, anúncios, tick/outbox/restart, claim idempotente (Datomic descartável).

---

## 14–16. Arquivos / migration / riscos

**Migration:** `migrations/pokemon/032-thematic-events.edn` (aditiva; **não aplicar em prod sem GO**).

**Principais arquivos:** `events.clj`, `event_schedule.clj`, `event_missions.clj`, `mission_catalog.clj`, `mission_suggest.clj`, `mission_events.clj`, `mission_queries.clj`, `thematic_event_store.clj`, `thematic_events.clj`, `hunt.clj` (app), `ports.clj`, `store.clj`, `bridge.clj`, `pokemon-jvm-present.cjs`, `pokemon-jvm-client.cjs`, `pokemon_http.cljs`, testes + smoke.

**Riscos residuais:** balanceamento de bias; credit de `raid-won` só no writer do golpe final; rollout exige enable por chat + flag.

---

## 17. Rollback

1. `POKEMON_THEMATIC_EVENTS_ENABLED=false`  
2. `enabled=false` nas agendas  
3. Código anterior 12.8.0 se necessário (migration 032 é aditiva/no-op se não usada)

---

## 18. Plano de ativação (após GO explícito)

**A** — descartável (este relatório) · **B** — piloto: deploy código, flag false → health → 1 evento forçado curto · **C** — agenda recorrente / expansão só com novo GO.

**READY FOR GO:** implementação pronta para revisão. **Sem deploy / sem flag / sem migration em produção até GO.**
