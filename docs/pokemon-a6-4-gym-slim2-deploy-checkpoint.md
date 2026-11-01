# Checkpoint — GO ginásio slim2 (gzip + allowlist) · Odisseu Core

**Quando:** 2026-10-30 ~19:19–19:21 UTC  
**Motivo:** pós-slim1 o desafio Pedra ainda falhava com `stateEdn` **6556 > 4000**  
**Autorização:** continuação do GO `datomic_value_too_large` nos ginásios  
**Host:** `odisseu-core` · `35.192.113.146`

---

## Diagnóstico (produção)

| Tentativa | Payload |
|-----------|---------|
| Pré-slim1 | `stateEdn` **14503** |
| Pós-slim1 (`a64-gym-slim-…2223`) | `stateEdn` **6556** (assert JVM) |
| Causa residual | 6 Pokémon × ~1 KiB (golpes com zeros/defaults + campos extras) |

Sem efeitos parciais (TX abortada / `encounter=null`).

---

## Correção slim2

Arquivos: `battle.clj` · `store.clj` (`edn-read` → `decode-compact-edn`)

1. Allowlist de campos de combate no snapshot.  
2. Drop de defaults V1 nos golpes (`cura/dreno/recuo/chance=0`, `alteracoes=[]`, `precisao=100`, `alvo=adversario`).  
3. Se o EDN plain ainda > 4000 → **`odisseu.gz;` + gzip + Base64** (ainda sob o limite).  
4. `datomic_value_too_large` permanece se compactado também exceder.  
5. Canônicos / Cassandra / economia **não** alterados.

---

## Pré-deploy

| Check | Resultado |
|-------|-----------|
| `test-m4-gym-battle` | **OK** · 12 testes / **73** asserts |
| `hunt-m45` unit | **17 / 17** |
| `encounter-m44` | **10 / 10** |
| Smoke hunt HTTP | `area_unavailable` (calendário; flaky conhecido) |
| Cold | `~/odisseu-recovery/a64-gym-slim2-cold-20261030T191958Z/` |
| Restore | **16=16** · sha `8f6c62d0…c78d9` |

---

## Imagens / digests

| Serviço | Tag | ID |
|---------|-----|----|
| **JVM** | `odisseu-jvm:a64-gym-slim2b-20261030T195530Z-amd64` (hotfix decode+sprites) | `sha256:b28b66083d7d7882e4dcbb3c4e4c24daa8b0e5cb16533ed8dc97b59a362e0c18` |
| JVM slim2 | `a64-gym-slim2-20261030T191958Z-amd64` (superseded) | `sha256:bac2e43b…c7c7d0` |
| Node | `odisseu-node:a64-capture-ux-20261029T2124-amd64` (inalterado) | — |

Compose bak: `docker-compose.release-battle-idle.yml.bak-pre-gym-slim2-a64-gym-slim2-20261030T191958Z-amd64`

Env: `HUNT=1800` `JOY=1800` `IDLE=300` `LEAVE=true` `ALLOW_ALL_GROUPS=true`  
Volumes: `a56-*` preservados · sem migrations/import.

---

## Pós-deploy (sem combate artificial)

| Check | Resultado |
|-------|-----------|
| JVM / Node | **healthy** |
| Bridge `/health` | **up** · caps gym/hunt |
| WhatsApp | Node **healthy** 22h (sessão preservada; comandos recentes OK) |
| Pedra | `desbloqueado: true` · `ocupacao: null` |
| Encounter | `null` |
| Save | `recovery-v1` · fence **2** · coleção **302** (pós import remaining) |
| Size gate (pior time real ×2) | plain **5160** → compact **1351** ≤ 4000 |
| team-edn (3) | **2393** ≤ 4000 |

Arquivo: `~/odisseu-recovery/a64-gym-slim2-post-integrity.json`

Validação de desafio Pedra ao vivo: **aguardando jogador** (sem batalha artificial).

---

## Hotfix slim2b (2026-10-30 ~19:55Z)

Sintomas pós-slim2: arena com placeholder (sem sprite) + `mission_event_identity_conflict` no ataque.

Causa: `mission_events` lia `state-edn` gzip com `edn/read-string` cru (`odisseu.gz;` vira símbolo; owner check falha). Sprites dos ativos tinham sido dropados no slim de ginásio.

Correção: `decode-compact-edn` em mission_events / battle_idle / pvp_store; ativos mantêm `imagem*`.

**Batalha aberta sem sprites:** `!pk sair` e desafiar de novo para regenerar o snapshot com imagens. Ataques na batalha gzipada atual devem funcionar após slim2b.

---

## Riscos residuais

1. Batalha Pedra aberta antes do slim2b ainda sem sprites até sair/redesafiar.  
2. Se o compactado passar de 4000, o erro explícito permanece.

---

## Rollback (não destrutivo)

```bash
cd /home/caiohclavico/odisseu-core
cp docker-compose.release-battle-idle.yml.bak-pre-gym-slim2-a64-gym-slim2-20261030T191958Z-amd64 \
   docker-compose.release-battle-idle.yml
docker compose --env-file .env.odisseu-core \
  -f docker-compose.odisseu-core.yml \
  -f docker-compose.release-battle-idle.yml \
  -f docker-compose.allow-all-groups.yml \
  up -d odisseu-jvm
```
