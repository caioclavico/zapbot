# Checkpoint — Deploy fence-fix `!pk cacar` (Odisseu Core)

**Quando:** 2026-10-29 ~13:00–13:45 UTC  
**Autorização:** GO (chat) — correção `WRITER_FENCE_CONFLICT`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| Node: fence fresco pós-pokeapi + replan se save mudou + 1 retry só `STALE_WRITER_FENCE` | Caça de teste no Datomic live |
| JVM: reclassificação `stale_writer_fence` vs `writer_fence_conflict` | `force=true` / leave habilitado |
| Rebuild Node **e** JVM | Migrations novas; Cassandra; economia; grupos |

---

## Imagens / digests

| Serviço | Tag | ID |
|---------|-----|----|
| JVM (antes) | `odisseu-jvm:a64-idle-aguardando-20261029T0648-amd64` | `sha256:c29f358e…` |
| **JVM (depois)** | `odisseu-jvm:a64-fence-fix-20261029T1315-amd64` | `sha256:b0981d9de4344f5c4bc8f4cc40d0e386611b76c651224c8ebf46eb6669248705` |
| Node (antes) | `odisseu-node:a64-battle-idle-20261029T0216-amd64` | `sha256:2fda718a…` |
| **Node (depois)** | `odisseu-node:a64-fence-fix-20261029T1315-amd64` | `sha256:416eb5f67c606431e113a30c5c414018f2bf4d6d17cc3d194d25d77b5e53c1ac` |

Compose: `docker-compose.odisseu-core.yml` + `docker-compose.release-battle-idle.yml` + `docker-compose.allow-all-groups.yml`.

Env (inalterados):
- `POKEMON_HUNT_COOLDOWN_SECONDS=1800`
- `POKEMON_JOY_HEAL_SECONDS=1800`
- `POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS=300`
- `POKEMON_BATTLE_IDLE_SWEEP_MS=30000`
- `POKEMON_BATTLE_LEAVE_ENABLED=false`
- `recoveryCatalogReady=false`

---

## Backup frio

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-fence-fix-cold-20261029T130014Z/` |
| Manifest SHA | `e93304f095fb511e2e987b470eca09ae8798248afdaeeed8437c9fc003f428a7` |
| File count | **17** |
| Restore check | `…/a64-fence-fix-restore-check-20261029T130014Z` — **17=17** |
| Volumes | `odisseu-core-a56-{datomic,journal,maint,data}` preservados (sem `down -v`) |

---

## Testes locais pré-deploy

| Suite | Resultado |
|-------|-----------|
| `pokemon.tests.hunt-m45-test` | **16 / 80 — OK** |
| `pokemon.tests.battle-idle-test` | **10 / 47 — OK** |
| `node --test` hunt + present | **16 / 16 — OK** |

---

## Integridade

| Check | Antes | Depois |
|-------|-------|--------|
| 12 trainers recovery-v1 | 39 / active recovery-v1 | 39 / active recovery-v1 |
| User `207700…@lid` | recovery-v1=119, a=1, fence=2 | (revalidar no relatório live) |
| Soma 13 | **158** | **158** |
| Import automático | off (`recoveryCatalogReady=false`) | off |

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** (2026-10-29T13:43:25Z) |
| Node main.js | `start_hunt_jvm`, `STALE_WRITER_FENCE`, `pokemon_jvm_hunt_start_retry` |
| Leave HTTP | **404** (`LEAVE_ENABLED=false`) |
| expire-idle `force:true` body | forçado `force:false` |

---

## Rollback

```bash
# Restaurar tags anteriores em docker-compose.release-battle-idle.yml
# odisseu-jvm:a64-idle-aguardando-20261029T0648-amd64
# odisseu-node:a64-battle-idle-20261029T0216-amd64
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Backup compose: `docker-compose.release-battle-idle.yml.bak-pre-fence-a64-fence-fix-20261029T1315-amd64`
