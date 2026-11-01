# Checkpoint — GO capacidade Pokémon / CHEIO (Odisseu Core)

**Quando:** 2026-10-29 ~16:16–16:25 UTC  
**Autorização:** GO (chat) — `docs/pokemon-a6-4-capacity-storage-diagnosis.md`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| Sinalização 119/80 CHEIO (status/time/espaço/caça) | Reimport V1; Cassandra; aliases; grupos; cooldowns |
| `ocupação >= capacidade` → cheio; captura bloqueada | Aumentar capacidade artificialmente |
| `espaco-pokemon` (+50); `espaco comprar` não usa mochila | Gastar moedas com economia `unknown-pending` |
| Compra expansão bloqueada se economia não confiável | Migrations novas |

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `capacity_storage_test` | **7 / 42 OK** |
| present Node | **12/12 OK** |
| Migrations novas | **nenhuma** (027 já live) |
| Cold | `~/odisseu-recovery/a64-capacity-cold-20261029T161614Z/` |
| Restore | **17=17** · mom `9fbe65011ae34ad90794ab80f937a7c5966a588a6ad9844ed8b3562ca36c726f` |
| Pré-integridade | 12/12 · sum12=39 · user 119 · sum13=158 · fence 2 · coins 0 |

Imagens **antes:**
- JVM `a64-bag-kit-daily-20261029T1525-amd64` · `sha256:8e65fdd5…`
- Node `a64-bag-kit-daily-20261029T1525-amd64` · `sha256:d7c4a978…`

---

## Economia (gate de compra)

Import report user: `economy.status=unknown-pending` — `coins 0` **não** é saldo histórico (V1/V2/op divergentes).

| Decisão | Evidência |
|---------|-----------|
| Capacidade informativa **ligada** | shop/collection/hunt |
| Compra `espaco-pokemon` / expansões **bloqueada** | HTTP `economy_unreliable` — sem debitar |

---

## Imagens / digests (depois)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-capacity-storage-20261029T1620-amd64` | `sha256:5e56e7873dfa9d0ff928a1d954ea0a924bf4fdedee34850fcd594137e1a54c38` |
| **Node** | `odisseu-node:a64-capacity-storage-20261029T1620-amd64` | `sha256:f00c4c52a502dbc1586a459335d2bf779f3a36433c01899d3a8c97da6c6bb4bf` |

Compose: `odisseu-core.yml` + `release-battle-idle.yml` + `allow-all-groups.yml`  
Backup: `docker-compose.release-battle-idle.yml.bak-pre-capacity-a64-capacity-storage-20261029T1620-amd64`

Env preservado: `HUNT=1800` `JOY=1800` `IDLE=300` `ALLOW_ALL_GROUPS=true` `POKEMON_BAG_KIT_DAILY_ENABLED=true` `ODISSEU_BRIDGE_RESET=false`

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| WhatsApp | **READY** `2026-10-29T16:25:00.786Z` |
| Storage user | **`119/80 — CHEIO (39 acima)`** · `colecaoCheia=true` |
| Hunt | `somenteBatalha=true` · captura bloqueada; batalha permitida |
| Buy expansão | **`economy_unreliable`** (sem gasto) |
| Mochila itens | **3/100** (independente) |
| Integridade | 12/12 · 39 · 119 · **158** · active=`recovery-v1` · fence=**2** · coins=0 inalterado |
| Volumes | `a56-datomic/journal/maint/data` |

Arquivos: `a64-capacity-pre-integrity.json`, `a64-capacity-post-integrity.json`.

### WhatsApp (usuário)

- `!pk status` / `!pk time` / `!pk espaco` → linha CHEIO 119/80  
- `!pk cacar` → batalha ok; captura recusada se tentar  
- `!pk espaco comprar` → mensagem de economia não confiável (sem debitar)

---

## Rollback

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-capacity-a64-capacity-storage-20261029T1620-amd64 \
   docker-compose.release-battle-idle.yml
# ou tags a64-bag-kit-daily-20261029T1525-amd64
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm odisseu-node
```

Cold `a64-capacity-cold-20261029T161614Z` disponível para restore ensaiado fora do live.
