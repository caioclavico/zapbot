# Checkpoint — GO `!pk sair` / battle-leave (Odisseu Core)

**Quando:** 2026-10-29 ~19:10–19:14 UTC  
**Autorização:** GO (chat) — referência `docs/pokemon-a6-4-battle-leave-prepare.md`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| `POKEMON_BATTLE_LEAVE_ENABLED=true` | Migrations / reimport / Cassandra |
| Patch 503 `feature_disabled` ≠ 404 `not_found` | `force=true` / expire-idle global |
| Capability `battle-leave` no health (só com flag on) | Mutação artificial de batalhas |
| Present: `FEATURE_DISABLED`, `NOT_FOUND`, `formatLeaveResult` | Economia / grupos / cooldowns |
| Rebuild JVM + Node | Rollback destrutivo |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `battle-leave-test` | **11 / 77 OK** |
| present Node | **16 / 16 OK** |
| Modalidades | wild · aguardando-captura · gym · raid individual · PvP (testes) |
| Cold | `~/odisseu-recovery/a64-battle-leave-cold-20261029T191016Z/` |
| Restore | **17=17** · mom `ad0454429d0d…8d5b` |

Imagens **antes:**
- JVM/Node `a64-party-fix-20261029T1825-amd64`  
  · `sha256:e60a85c1…` / `sha256:d9a77ec9…`  
  · leave flag **false**

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-battle-leave-20261029T1910-amd64` | `sha256:65a716710417911046b673ffc658fc51da4282f1a33d1c4497f10766d88f9e08` |
| **Node** | `odisseu-node:a64-battle-leave-20261029T1910-amd64` | `sha256:727ac34febd84eab26951aa54e3c25919a0b11b73eec229099d72c20f7c3272f` |

Compose backup: `docker-compose.release-battle-idle.yml.bak-pre-leave-a64-battle-leave-20261029T1910-amd64`

Env JVM: `LEAVE=true` `HUNT=1800` `JOY=1800` `IDLE=300` `BRIDGE_RESET=false`  
Env Node: `ALLOW_ALL_GROUPS=true` `IDLE=300`  
Volumes: `odisseu-core-a56-{datomic,journal,maint,data}` preservados

---

## Contrato HTTP validado

```
POST /v1/battles/leave
→ 200 { status: "no_battle", saveId, fenceGeneration, operationId, duplicate:false }
```

| Probe | Resultado |
|-------|-----------|
| Leave sem batalha (Caio / recovery-v1) | **200 `no_battle`** (não 404) |
| Present | `⚠️ Você não está participando de nenhuma batalha.` |
| Rota inventada `/v1/battles/leave-does-not-exist` | **404 `not_found`** |
| Fence / coleção após probe | fence **2** · **119** inalterados |
| Health caps | **`battle-leave`** presente |

PvP/raid: saída individual já no domínio (PvP notifica adversário; raid remove só o jogador). Sem bloqueio adicional — cobertos pelos testes 11/77.

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** `2026-10-29T19:14:05.873Z` |
| Integridade | 12/12 · 39 · 119 · **158** · 13 treinadores |
| Cooldowns | Hunt **1800** · Joy **1800** · Idle **300** |
| Groups | `ALLOW_ALL_GROUPS=true` |

Arquivo: `~/odisseu-recovery/a64-battle-leave-post-integrity.json`

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-leave-a64-battle-leave-20261029T1910-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Não executar rollback destrutivo automaticamente.
