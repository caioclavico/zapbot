# Checkpoint — Deploy `Item too large` / slim `battle/state-edn` (Odisseu Core)

**Quando:** 2026-10-29 ~14:47–14:55 UTC  
**Autorização:** GO (chat) — correção falsa `WRITER_FENCE_CONFLICT`  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| JVM: `encode-state-edn` + slim de blobs de coleção no snapshot de batalha | Node / Cassandra / economia / aliases / grupos |
| Classificação `:datomic_value_too_large` (HTTP 500), sem retries CAS | Caça artificial na conta do usuário |
| Volumes `a56-*` preservados | Migrations novas; leave; force expire |

---

## Imagens / digests

| Serviço | Tag | ID |
|---------|-----|----|
| JVM (antes / rollback) | `odisseu-jvm:a64-fence-fix-20261029T1315-amd64` | `sha256:b0981d9de4344f5c4bc8f4cc40d0e386611b76c651224c8ebf46eb6669248705` |
| **JVM (depois)** | `odisseu-jvm:a64-item-too-large-fix-20261029T1455-amd64` | `sha256:b56aa169258f6244a7143d1530dad50a885c66fedd04c7ad08b495a4b0f09f19` |
| Node (inalterado) | `odisseu-node:a64-fence-fix-20261029T1315-amd64` | (mesmo digest prévio) |

Compose: `docker-compose.odisseu-core.yml` + `docker-compose.release-battle-idle.yml` + `docker-compose.allow-all-groups.yml`.  
Backup compose: `docker-compose.release-battle-idle.yml.bak-pre-item-a64-item-too-large-fix-20261029T1455-amd64`

Env (inalterados):
- `POKEMON_HUNT_COOLDOWN_SECONDS=1800`
- `POKEMON_JOY_HEAL_SECONDS=1800`
- `POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS=300`
- `POKEMON_BATTLE_IDLE_SWEEP_MS=30000`
- `POKEMON_BATTLE_LEAVE_ENABLED=false`
- `ODISSEU_BRIDGE_RESET=false`
- `recoveryCatalogReady=false`

---

## Backup frio

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-item-too-large-cold-20261029T144700Z/` |
| Restore check | `~/odisseu-recovery/a64-item-too-large-restore-check-20261029T144700Z/` |
| File count | **17 = 17** |
| Manifest (conteúdo relativo) | `c4400eecd869dde847c53369a1a7cc04a25f09cd953e5569ee2c90acc2a8a8da` |
| Volumes | `odisseu-core-a56-{datomic,journal,maint,data}` preservados (sem `down -v`) |

---

## Testes locais pré-deploy

| Suite | Resultado |
|-------|-----------|
| `battle-state-edn-slim-evita-item-too-large` + classificação `Item too large` | **5/5 runs OK** (40 asserts) |
| `pokemon.tests.battle-idle-test` | **10 / 47 — OK** |
| `pokemon.tests.battle-leave-test` | **9 / 60 — OK** |
| `hunt-m45` completo | flaky pré-existente em `cooldown-zero-nao-retorna-apos-derrota` / `defender-e-pocao` (não ligados ao slim); suite verde em runs limpos |

Compatibilidade: estados legados com `historico-golpes` no EDN continuam legíveis; o próximo write aplica slim. Rollback JVM antiga lê slim sem problema (campos extras ausentes não são necessários no combate).

---

## Integridade pós-deploy (RO)

| Check | Resultado |
|-------|-----------|
| 12 trainers recovery | **12/12** · sum12=**39** |
| User `207700…@lid` | active=`recovery-v1`, fence=**2**, slots a=**1** / b=0 / recovery-v1=**119**, ativoIndex=**93**, `activeBattleId=null` |
| Hunt plan | `cooldownOk=true`, `cooldownRestanteS=0`, `somenteBatalha=true`, chat livre |
| Soma 13 | **158 = 158** |
| Import automático | off |

---

## Pós-deploy health

| Check | Resultado |
|-------|-----------|
| JVM | **healthy** · tag nova |
| Node | **healthy** · imagem fence-fix (não redeployada) |
| WhatsApp | **READY** (sessão contínua; último READY log `2026-10-29T13:43:25Z`) |
| Volumes montados | `a56-datomic` / `a56-journal` / `a56-maint` |

---

## Validação caça real

**Aguardando** o usuário enviar `!pk caçar` pelo WhatsApp (sem caça artificial).

Após o comando, correlacionar RO:
- HTTP ≠ 409 `writer_fence_conflict` com `cause=Item too large`
- `activeBattleId` set; receipt `hunt-start`; cooldown avançado
- fence inalterado; coleção 119 intacta; sem duplicata de operação

---

## Rollback (não destrutivo)

Imagem anterior mantida localmente no host. Sem `down -v`.

```bash
cd /home/caiohclavico/odisseu-core
# restaurar tag JVM anterior no release compose
cp docker-compose.release-battle-idle.yml.bak-pre-item-a64-item-too-large-fix-20261029T1455-amd64 \
   docker-compose.release-battle-idle.yml
# ou sed para odisseu-jvm:a64-fence-fix-20261029T1315-amd64
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm
```

Dados gravados pela nova JVM (state-edn slim) são **compatíveis** com a JVM anterior — não há schema break. Cold `a64-item-too-large-cold-20261029T144700Z` disponível se precisar ensaiar restore fora do live.
