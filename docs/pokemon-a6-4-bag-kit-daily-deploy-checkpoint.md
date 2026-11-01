# Checkpoint — GO `!pk mochila kit` / `!pk mochila diario` (Odisseu Core)

**Quando:** 2026-10-29 ~15:17–16:01 UTC  
**Autorização:** GO explícito (chat) — ordem 1→8  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| Migration `027-bag-daily` via boot JVM | Capacidade/coleção “119 cheio” (tarefa separada) |
| Backfill **só flags** kit/diário (sem itens) nos 13 recovery | Escrita Cassandra; import Pokémon; reset cooldown |
| Node `POKEMON_BAG_KIT_DAILY_ENABLED=true` após gates | Recompensas artificiais / admin-seed de teste |

---

## 1. Backup frio + restore

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-bag-kit-daily-cold-20261029T151706Z/` |
| Restore check | `…/a64-bag-kit-daily-restore-check-20261029T151706Z/` |
| File count | **17 = 17** |
| Manifest-of-manifest | `f7b09a7af6ce48cc8842772f3c090d32d9e382142e9d3e0cb61275052d312a86` (cold ≡ restore) |
| Volumes | `odisseu-core-a56-{datomic,journal,maint,data}` preservados (sem `down -v`) |

---

## 2. Ensaio descartável (027 + flags)

| Item | Resultado |
|------|-----------|
| Cópias | `a64-bag-027-rehearsal(-2)-20261029T151706Z` |
| Migration 027 | aplicada no ensaio |
| Flags | 13 kits; dailies só com prova Cassandra |

Testes locais pré-GO: `bag-rewards` + `bag-kit-daily` **11/48 OK**; present Node bag **12/12**.

---

## 3. Deploy JVM (flag ainda off)

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-bag-kit-daily-20261029T1525-amd64` | `sha256:8e65fdd5cbd167535628fe21f62ee44cac8088aa0dd1ddb8585308327f2faba2` |
| Rollback JVM | `odisseu-jvm:a64-item-too-large-fix-20261029T1455-amd64` | `sha256:b56aa169…` |

Compose: `odisseu-core.yml` + `release-battle-idle.yml` + `allow-all-groups.yml`.  
Backup compose pré-JVM: `docker-compose.release-battle-idle.yml.bak-pre-bag-a64-bag-kit-daily-20261029T1525-amd64`.

Health JVM: **healthy** · capabilities incluem `bag-kit` / `bag-daily`.  
`POKEMON_BAG_KIT_DAILY_ENABLED=false` nesta fase.

---

## 4–6. Backfill live + validação

Manifesto: `~/odisseu-recovery/a64-bag-flags-20261029.json`  
Nota: *Daily only when proven.* Kit marcado claimed para recovery sem loja Cassandra (= bloqueio; não presume “nunca resgatou”).

| Check | Resultado |
|-------|-----------|
| Migration live | **027-bag-daily** presente (probe RO snap) |
| Kit flags | **13/13** em todos os saves (`a`/`b`/`recovery-v1`) |
| Daily proven | **2/2** players (`2026-09-15` seq0; user `2026-10-05` seq1) |
| Pokémon | sum12=**39** · user recovery-v1=**119** · sum13=**158** |
| Fence user | **2** · active=`recovery-v1` · coins=0 · inv recovery=[] |
| Kit claim pós-backfill | `kitAlreadyClaimed=true`, `qty=0`, idempotente (mesmo `operationId`) |
| Itens concedidos no backfill | **nenhum** |

Snap RO pós-backfill: `~/odisseu-recovery/a64-bag-live-ro-snap-20261029T155504Z/`.

---

## 7. Enable Node

| Serviço | Tag | ID |
|---------|-----|----|
| **Node** | `odisseu-node:a64-bag-kit-daily-20261029T1525-amd64` | `sha256:d7c4a9787a74bc7e07d64f2030b8d96c0e784030f2e293b76fe0fe1d01defc91` |

- `.env.odisseu-core`: `POKEMON_BAG_KIT_DAILY_ENABLED=true`
- Node env confirmado: `BAG=true`, `IDLE=300`, `ALLOW_ALL_GROUPS=true`, `POKEMON_ADAPTER=jvm`
- JVM env: `HUNT=1800`, `JOY=1800`, `IDLE=300`, `ODISSEU_BRIDGE_RESET=false`

---

## 8. Pós-enable

| Check | Resultado |
|-------|-----------|
| Node health | **healthy** |
| WhatsApp | **READY** `2026-10-29T16:01:10.751Z` |
| Integridade | 12/12 · 39 · 119 · 158 · fence 2 |
| Kit API | already claimed, qty 0 (sem grant) |
| Diário API | **não** chamado no GO (concederia bolas reais; validação WhatsApp pelo usuário) |

Arquivos: `a64-bag-post-jvm-integrity.json`, `a64-bag-pre-enable.json`, `a64-bag-post-enable-integrity.json`.

---

## Validação WhatsApp (usuário)

1. `!pk mochila kit` → deve responder que o kit **já foi resgatado** (sem +10 bolas).  
2. `!pk mochila diario` → resgate legítimo do dia (último histórico user `2026-10-05`); não é seed artificial.  
3. Confirmar caça/Joy ainda ~30 min e grupos acessíveis.

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
# desligar comandos imediatamente
sed -i 's/^POKEMON_BAG_KIT_DAILY_ENABLED=.*/POKEMON_BAG_KIT_DAILY_ENABLED=false/' .env.odisseu-core
# Node imagem anterior (fence-fix) se necessário
# JVM: bak-pre-bag-… → item-too-large-fix tag
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-node   # e/ou odisseu-jvm
```

Cold `a64-bag-kit-daily-cold-20261029T151706Z` disponível para restore ensaiado fora do live.
