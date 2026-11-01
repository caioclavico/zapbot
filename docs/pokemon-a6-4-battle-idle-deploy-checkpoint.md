# Checkpoint — Deploy timeout idle 300s (Odisseu Core)

**Quando:** 2026-10-29 ~02:16–02:24 UTC  
**Autorização:** GO controlado (chat)  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Imagens / digests

| Serviço | Tag | Digest / ID |
|---------|-----|-------------|
| JVM (antes) | `odisseu-jvm:a64-full-remaining-amd64` | `sha256:66eb7b87eaaa…` |
| Node (antes) | `odisseu-node:a64p6-1273fa4-amd64` | `sha256:0566a56834bc…` |
| **JVM (depois)** | `odisseu-jvm:a64-battle-idle-20261029T0216-amd64` | `sha256:4a093e1c4b41367f129a6255b28aab0ff8eb6bc1c263d4ef964a4b774ed2de37` |
| **Node (depois)** | `odisseu-node:a64-battle-idle-20261029T0216-amd64` | `sha256:2fda718aa34f06be2a86ae8f357e479e39fce3e300d332d37c23806789aadabd` |

Compose: `docker-compose.odisseu-core.yml` + `docker-compose.release-battle-idle.yml` + `docker-compose.allow-all-groups.yml` (somente env `ALLOW_ALL_GROUPS`; **sem** pin de imagem antiga).

Env: `POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS=300` (JVM + Node).  
Sweeper: `POKEMON_BATTLE_IDLE_SWEEP_MS=30000`.

---

## Backup frio + restore

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-idle-cold-20261029T021405Z/` |
| Manifest | `ec18df562635071e041d0f5ed95cd8c1bdf1cabdb58feec0df3dcd11df7e2e88` |
| Restore check | `…/a64-idle-restore-check-20261029T021516Z` — **17=17 arquivos**, SHA lista idêntica |
| Volumes preservados | `odisseu-core-a56-{datomic,journal,maint,data}` (sem `down -v`) |

---

## Migration 026

| Etapa | Resultado |
|-------|-----------|
| Ensaio em cópia do cold | 025 presente → **026 aplicada**; players/saves/pokemon **inalterados** |
| Live pós-boot JVM | **026 presente** (`pokemon/026-battle-idle`) |
| Contagens pré (ensaio/cold) | players **20** · saves **59** · pokemon **1108** · battles **16** · active **0** · raids **0** |
| Contagens pós (snap live) | players **20** · saves **59** · pokemon **1108** · battles **16** · active **0** · raids **0** |

Política legado: batalhas ativas **sem** `idle-expires-at` recebem backfill de janela completa no sweeper (não expiram retroativamente). Live: **0** ativas → backfill 0.

---

## Health / WhatsApp

| Check | Resultado |
|-------|-----------|
| JVM `/health` | `up` · healthy |
| Node | healthy |
| WhatsApp | `[WhatsApp] READY.` (02:23:35Z) |
| Mounts JVM | `a56-datomic` / `a56-journal` / `a56-maint` |
| Mounts Node | `.wwebjs_auth_core` + `a56-data` |

---

## Eventos / sweeper

| Endpoint | Resultado |
|----------|-----------|
| `GET /v1/events/pending` | `{"ok":true,"events":[]}` |
| `POST /v1/battles/expire-idle` | `expiredBattles:0`, `backfilledBattles:0` |
| Ack Node | só após `sendMessage` resolver (promise) |

Testes locais pré-deploy: **9 tests / 39 asserts — OK**.

---

## Riscos residuais

1. **E2E 300s live não ensaiado** nesta janela (0 batalhas ativas; `ALLOW_TEST_RNG=false`). Comportamento coberto por testes + rehearsal de schema.
2. Override `allow-all-groups.yml` foi ajustado para **não** fixar imagem antiga — se alguém restaurar o pin `allow-all-groups-47ff8a1`, o Node perde o poll de eventos idle.
3. Rollback não destrutivo: redeploy tags anteriores (`a64-full-remaining` / `a64p6-1273fa4`) com os **mesmos** volumes `a56-*`. Attrs 026 permanecem no schema (inofensivos).

---

## Não feito (conforme restrições)

- Sem `docker compose down -v`
- Sem alteração Cassandra / economia / saves / aliases de grupo
- Sem rollback destrutivo automático
EOF
