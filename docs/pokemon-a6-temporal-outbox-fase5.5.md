# A6 — Fase 5.5: Homologação temporal e outbox

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-11-01  
**Base:** stack isolada Fase 5.4 (volumes `odisseu-a6-staging-*`)  
**Modo:** Datomic sintético · `WHATSAPP_TRANSPORT=disabled` · ticks HTTP explícitos  
**Sem:** produção, Core A5.6, WA real, merge, `down -v`, migrate em banco externo  

---

## Decisão: **READY**

Scheduler (intervalo mínimo 2 min), outbox com consumidor explícito, recovery pós-restart da JVM e kill switch global foram comprovados na stack A6 local. Regressões A6 e build Node passaram.

| Critério | Status |
| --- | --- |
| Spawn só em/após `next-at` | **PASS** |
| Uma raide por due lógico (duplicate/skip) | **PASS** |
| Lobby 2 min → cancel sem quórum | **PASS** |
| Lobby 2 min → combate com quórum | **PASS** |
| `next-at` ≥ due + 120000 ms | **PASS** (`5120001`) |
| Kill switch OFF bloqueia spawn | **PASS** |
| Outbox após abertura + pending | **PASS** |
| ID estável após restart JVM | **PASS** |
| Ack + ack duplicado | **PASS** |
| Unacked permanece pendente | **PASS** |
| Redelivery sem duplicar efeito de jogo | **PASS** |
| Recovery lobby após restart | **PASS** |
| Expire idempotente pós-deadline | **PASS** |
| Spawn antigo não recriado | **PASS** |
| Recompensa idempotente | **PASS** |
| Logs `raid_auto_*` sem segredos | **PASS** |
| Suítes regressão + `npm run build` | **PASS** |

---

## 1. Ambiente

| Item | Valor |
| --- | --- |
| Compose | `docker-compose.a6-staging.yml` |
| JVM tick | `ODISSEU_BRIDGE_RAID_AUTO_TICK=true` **só durante o lab** (wire novo em `http_server.clj`) |
| Node | `WHATSAPP_TRANSPORT=disabled`, `POKEMON_RAID_AUTO_TICK=false` |
| Intervalo de teste | `120000` ms (= `min-interval-ms` = duração do lobby) |
| Relógio | `agoraMs` injetado (sem espera de parede de 2 min) |
| Consumidor outbox | `ExplicitOutboxConsumer` em `scripts/a6-temporal-outbox-fase5.5.cjs` (sem ack automático) |

Após a homologação, `.env.a6-staging` voltou `ODISSEU_BRIDGE_RAID_AUTO_TICK=false`.

---

## 2. Scheduler (evidências)

Script: `scripts/a6-temporal-outbox-fase5.5.cjs` · run `t1793499326158` / recovery `t1793499338527`.

| Teste | Evidência |
| --- | --- |
| Sem spawn antes de `next-at` | `spawned=0` com `agoraMs = nextAt - 1` |
| Spawn no horário | `fase=inscricoes`, `acao=spawn` |
| Idempotência do due | replay → `duplicate=true` |
| `next-at` | `5120001` (≥ `5000000 + 120000`) |
| Cancel sem quórum | `expired[].acao=cancelada` |
| Start com quórum | `fase=combate` |

Kill switch (JVM recriada com flag false):

```json
{"enabled":false,"processed":[],"note":"raid-auto-tick-disabled"}
```

---

## 3. Outbox (consumidor explícito)

Fluxo: spawn → `GET /v1/events/pending` → (sem ack) → restart JVM → mesmo `id` ainda pendente → `POST .../ack` → ack duplicado → some da pending.

| Check | Resultado |
| --- | --- |
| Persistido pós-abertura | `id=raid-opened:raid-auto:…:5030000` `kind=raid-opened` |
| Unacked permanece | `pending` inclui o id |
| Estável pós-restart | `found=true` para o id pré-restart |
| Ack | `acked=true, duplicate=false` |
| Ack repetido | `acked=true, duplicate=true` |
| Removido da pending | id ausente após ack |
| Falha entrega→ack | replay tick → `duplicate=true` (sem segunda raide) |

**Fix de contrato:** `encodeURIComponent` no path quebrava IDs com `:`. Cliente agora usa `encodeOutboxEventId` (preserva `:` / `@`).

---

## 4. Recuperação

1. Preparou lobby `inscricoes` + 1 jogador (`A6_RECOVERY_MARKER`).  
2. `docker restart odisseu-a6-staging-jvm` (volumes intactos).  
3. Lobby recuperado: `fase=inscricoes`, `nextAt=8640000`.  
4. Tick após `expira` → transição cancelada (sem quórum).  
5. Replay do due antigo → `duplicate=true` (sem recriar spawn).  
6. `creditCoins` com mesmo `operationId` → `duplicate=true`.

---

## 5. Observabilidade

Logs JVM (stderr, EDN numa linha) observados, ex.:

```edn
{:event "raid_auto_spawn", :chat-id "…", :spawn-id "…", :due-at 5000000, :next-at 5120001, :result "spawn"}
{:event "raid_auto_spawn", … :result "duplicate"}
{:event "raid_auto_lobby_expired", … :result "cancelada"}
{:event "raid_auto_cancelled", …}
{:event "raid_auto_lobby_expired", … :result "combate"}
{:event "raid_auto_started", …}
```

Campos allowlist (sem tokens/PII de usuário). `raid_auto_delivery_failed` coberto nas suítes Fase 4 (caminho WA); neste lab HTTP-only não há send WhatsApp.

---

## 6. Regressão e build

| Suite | Resultado |
| --- | --- |
| `test-raid-lobby-fase2` | 23/23 · 76 assert |
| `test-raid-auto-fase3` | 19/19 · 76 assert |
| `test-raid-homolog-fase4` | 13/13 · 114 assert · `staging-touched? false` |
| `test-raid-save-slots-reconcile` | 3/3 |
| `test-a6-economy-capacity` | economy+capacity+idle PASS |
| `node --test` clients/isolation | PASS |
| `npm run build` | PASS (warnings promesa deprecated) |

---

## 7. Encerramento

```bash
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node stop
# sem down -v
```

Containers Exited; volumes `odisseu-a6-staging-{datomic,journal,maint,data}` preservados.

---

## 8. Riscos residuais

1. Tick JVM ligado só sob flag explícita — default OFF; não deixar `true` em deploy.  
2. Homolog usou `agoraMs` sintético (não wall-clock de 2 min reais).  
3. Outbox pending cresce se ninguém ack — operacionalmente o consumidor Node (quando WA existir) deve ack; neste lab o consumidor de teste é obrigatório.  
4. Activate/save durante combate continua permissivo (nota Fase 5.4).

---

## 9. Como repetir

```bash
# Ligar tick só no lab
# ODISSEU_BRIDGE_RAID_AUTO_TICK=true em .env.a6-staging
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging up -d odisseu-a6-jvm
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging --profile with-node up -d odisseu-a6-node

A6_DEFER_OUTBOX_ACK=1 A6_RECOVERY_PHASE=prepare-inscricoes \
  ODISSEU_BRIDGE_TOKEN=… node scripts/a6-temporal-outbox-fase5.5.cjs
# restart jvm; então recovery com A6_PRIOR_OUTBOX_ID + A6_ACK_PRIOR_OUTBOX=1
A6_F55_PROBE=kill-switch-off …  # com ODISSEU_BRIDGE_RAID_AUTO_TICK=false
```
