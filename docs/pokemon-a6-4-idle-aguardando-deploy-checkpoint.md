# Checkpoint — Deploy idle aguardando/waiting (Odisseu Core)

**Quando:** 2026-10-29 ~06:47–06:55 UTC  
**Autorização:** GO controlado (chat) — correção timeout 300s  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Escopo

| Incluído | Excluído |
|----------|----------|
| Idle em **active**, **waiting** e **aguardando-captura** | Deploy do comando `!pk sair` |
| Sweeper ~30s (`POKEMON_BATTLE_IDLE_SWEEP_MS=30000`) | `force=true` via HTTP |
| Timeout **300s** | Alteração Cassandra / economia / saves / grupos |
| Rebuild **somente JVM** | Rebuild/redeploy Node |

---

## Imagens / digests

| Serviço | Tag | Digest / ID |
|---------|-----|-------------|
| JVM (antes) | `odisseu-jvm:a64-battle-idle-20261029T0216-amd64` | `sha256:4a093e1c…` |
| **JVM (depois)** | `odisseu-jvm:a64-idle-aguardando-20261029T0648-amd64` | `sha256:c29f358e634b5227c5598ff324cdcb8441b35448cf89a549a692d8d7d77a2a34` |
| Node (inalterado) | `odisseu-node:a64-battle-idle-20261029T0216-amd64` | (mesmo digest prévio) |

Compose: `docker-compose.odisseu-core.yml` + `docker-compose.release-battle-idle.yml` + `docker-compose.allow-all-groups.yml`.

Env JVM:
- `POKEMON_BATTLE_IDLE_TIMEOUT_SECONDS=300`
- `POKEMON_BATTLE_IDLE_SWEEP_MS=30000`
- `POKEMON_BATTLE_LEAVE_ENABLED=false` (rota `/v1/battles/leave` → **404**)

`POST /v1/battles/expire-idle` **sempre** `force:false` (corpo `force=true` ignorado).

---

## Backup frio + restore

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-idle-fix-cold-20261029T064735Z/` |
| Manifest SHA | `7c61c73deb51e54935f00177246fa58dc6afab97e27654b614eeb57e7b6c5b14` |
| File count | **17** |
| Restore check | `…/a64-idle-fix-restore-check-20261029T064735Z` — **17=17** |
| Volumes | `odisseu-core-a56-{datomic,journal,maint,data}` preservados (sem `down -v`) |

### Contagens (cold pré-deploy)

| Métrica | Valor |
|---------|-------|
| players | **20** |
| saves | **59** |
| pokemon | **1108** |
| battles | **16** |
| active-battles | **0** |
| open-raids | **0** |
| migration 026 | **presente** |

---

## Testes locais pré-deploy

| Suite | Resultado |
|-------|-----------|
| `pokemon.tests.battle-idle-test` | **10 tests / 47 asserts — OK** |
| `pokemon.tests.battle-leave-test` | **9 tests / 60 asserts — OK** (código local; **não** habilitado em prod) |

---

## Pós-deploy

| Check | Resultado |
|-------|-----------|
| JVM health | `up` · **healthy** |
| Node health | **healthy** (imagem anterior) |
| Idle timeout | **300** |
| Sweep interval | **30000** |
| `LEAVE_ENABLED` | **false** |
| `POST /v1/battles/expire-idle` | `force:false`, `expiredBattles:0` |
| `force=true` no body | resposta ainda `force:false`, `expiredBattles:0` |
| `POST /v1/battles/leave` | **404** `not_found` |
| `GET /v1/events/pending` | `events:[]` |
| Backfill 1ª passagem | `backfilledBattles:1` (janela completa 300s; **sem** expire) |
| Encerramentos inesperados | **0** (`expiredBattles:0`) |

Interpretação do backfill: uma batalha aberta legada (provável aguardando-captura/waiting sem attr completo) recebeu janela de 300s a partir do boot — política segura, sem expire retroativo.

---

## Proteções

1. **`force=true` não exposto** na bridge HTTP.
2. **`!pk sair` / leave** desligado (`POKEMON_BATTLE_LEAVE_ENABLED=false`).
3. Node **não** redeployado → WhatsApp não ganha `leaveBattle`.
4. Sem Cassandra / moedas / aliases / grupos.

---

## Rollback

Redeploy tag anterior sem destruir volumes:

```bash
# em docker-compose.release-battle-idle.yml
# image: odisseu-jvm:a64-battle-idle-20261029T0216-amd64
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm
```

---

## Próximo (não autorizado nesta janela)

- GO de deploy de `!pk sair` (com `POKEMON_BATTLE_LEAVE_ENABLED=true` + Node).
- E2E live 300s em captura/waiting (opcional; coberto por testes).
