# Checkpoint — GO capacidade inicial Pokémon 30→50 (Odisseu Core)

**Quando:** 2026-10-29 ~16:51–16:57 UTC  
**Autorização:** GO (chat) — inicial Pokémon 50; marcos/expansões preservados  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| `capacidade-inicial-pokemon` **30 → 50** | Migrations / reimport / Cassandra |
| Help/present alinhados (50; 119/100) | Alterar moedas, inventário, saves, níveis |
| Preservar marcos + expansões + legado `max` | Liberar compra com `unknown-pending` |
| Mochila inicial continua **50** | Rollback destrutivo automático |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `capacity-storage` + `storage-a552` + bag | **20 / 121 OK** |
| present Node | **12/12 OK** |
| Diff aprovado | inicial 30→50 (+ testes/ajuda); **sem** migration nova |
| Migrations live | **001–027** (inalteradas) |
| Cold | `~/odisseu-recovery/a64-capacity-inicial50-cold-20261029T165144Z/` |
| Restore | **17=17** · mom `7534aa41…befa17` |
| Pré-integridade | **119/80 (+39)** · bag 3/100 · fence 2 · coins 0 · econ false |

Imagens **antes:**
- JVM `a64-capacity-storage-20261029T1620-amd64` · `sha256:5e56e787…`
- Node `a64-capacity-storage-20261029T1620-amd64` · `sha256:f00c4c52…`

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-capacity-inicial50-20261029T1655-amd64` | `sha256:28399cd70844beacde88f1516d0b6b930190b9d8a9c0ff75fc95e56160870742` |
| **Node** | `odisseu-node:a64-capacity-inicial50-20261029T1655-amd64` | `sha256:e52c17b3eecf25e3cd8d07ef64d7a9c26a4784158b8273028f2c44b5357238b7` |

Compose: `odisseu-core.yml` + `release-battle-idle.yml` + `allow-all-groups.yml`  
Backup compose: `docker-compose.release-battle-idle.yml.bak-pre-inicial50-a64-capacity-inicial50-20261029T1655-amd64`

Env preservado: `HUNT=1800` `JOY=1800` `IDLE=300` `ALLOW_ALL_GROUPS=true` `POKEMON_BAG_KIT_DAILY_ENABLED=true` `ODISSEU_BRIDGE_RESET=false`

---

## Capacidade antes / depois

| | Antes | Depois |
|--|-------|--------|
| Fórmula nv.10 (0 exp) | 30+50=**80** | 50+50=**100** |
| Seu storage | **119/80 — CHEIO (+39)** | **119/100 — CHEIO (+19)** |
| Mochila itens | 3/100 | **3/100** |
| Expansões Pokémon | 0 | **0** |

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** `2026-10-29T16:57:16.814Z` |
| Storage user | **`119/100 — CHEIO (19 acima)`** · `colecaoCheia=true` |
| Hunt | `somenteBatalha=true` · captura bloqueada; batalha permitida |
| Buy expansão | **`economy_unreliable`** — sem debitar |
| Integridade | 12/12 · 39 · 119 · **158** · active=`recovery-v1` · fence user=**2** · coins=0 |
| Volumes | `a56-datomic/journal/maint/data` |

Arquivos: `a64-capacity-inicial50-pre-integrity.json`, `a64-capacity-inicial50-post-integrity.json`.

### WhatsApp (usuário)

- `!pk status` / `!pk time` / `!pk espaco` → **119/100 — CHEIO (19 acima)**  
- `!pk cacar` → batalha ok; captura bloqueada  
- `!pk espaco comprar` → economia não confiável (sem debitar)

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-inicial50-a64-capacity-inicial50-20261029T1655-amd64 \
   docker-compose.release-battle-idle.yml
# ou tags a64-capacity-storage-20261029T1620-amd64
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Cold `a64-capacity-inicial50-cold-20261029T165144Z` disponível para restore ensaiado fora do live.
