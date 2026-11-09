# A6 — Integração hotfix caçada (timeout/conflict/moves/final-hit)

**PR:** [#63](https://github.com/caioclavico/zapbot/pull/63) **MERGED**  
**Merge:** `156727974b863b8361c72341bb9f36eefdfd4742` → `rc/a6-abc-p0-integrated`  
**Content-tree:** `06348d411778effb010bba5e2e35f1292ea76ff4`  
**Base live preservada:** `2afd997` / Node `77d0d9a2` / JVM `9443b801`  
**Stamp build:** `20261108T234229Z` · fase `a6-hunt-timeout-conflict-hotfix`  
**Artefatos:** `artifacts/a6-hunt-timeout-conflict-hotfix-20261108T234229Z/`

---

## 1. Identidade pré-merge

| Check | Resultado |
|-------|-----------|
| Destino | `rc/a6-abc-p0-integrated` @ `1ce993a` (ancestral de live `2afd997`) |
| Hotfix commits `2295c71`…`0724dea` | **não** estavam na RC antes do merge |
| Funcionalidades `2afd997` | preservadas (5 commits + merge) |

---

## 2. Review (pontos P0)

| Item | Status |
|------|--------|
| `:hp-atual-override` alinha XP ↔ HP combate; Explosion HP 0 | OK |
| Fail-fast `:tx_datoms_conflict` (sem retry cego) | OK |
| CAS `idle-expires-at` + guard `deadline-not-due` | OK |
| Outbox idle única/idempotente | OK |
| `moves-recovery` dry-run only (apply só em testes) | OK |
| `formatBattleTurn` mostra lastAction antes capturar/liberar | OK |

---

## 3. Gates

**PASS (locais):** hunt/capture/economy/idle · species-hp · homolog-abc · gym-battle · freeze · Node hunt/present.

**Pré-existente na RC (não bloqueia):** 5 asserts Node stale (`derrotado` / `captura concluída` / alias `batalhar`) — reproduzem em `1ce993a` sem o hotfix.

CI GitHub: `disabled_manually` / hold — rollup vazio; merge sob gates locais.

---

## 4. Imagens candidatas (VERIFY PASS — sem apply)

| | Tag | Image ID |
|--|-----|----------|
| Node | `odisseu-node:a6-hunt-timeout-conflict-hotfix-20261108T234229Z-156727974b86-amd64` | `sha256:2870175c58b040614d11b9291d48d5c238fe7235cad3a179a6ed53de1bfa5396` |
| JVM | `odisseu-jvm:a6-hunt-timeout-conflict-hotfix-20261108T234229Z-156727974b86-amd64` | `sha256:08da9ff2f4734a255d9ecc035cc41d35a200b642fcd7775fa7e294b62c02784f` |

Labels: `git-sha=1567279…` · `content-tree=06348d41…` · arch **amd64**.

### SHA256 tars

```
0da1ae2d94dd8b97c84ef433141fa27ef933dacf4ab93aea43047a022e956de9  odisseu-node-…-156727974b86-amd64.tar
b3f466c5521333279ea45e41a593eeabc486ba760ce554b00b931e58df3b5f80  odisseu-jvm-…-156727974b86-amd64.tar
```

### Overlay pinado (local)

`artifacts/…/overlay/docker-compose.a6-ai-drain-sandbox.overlay.prepared-hunt-hotfix-1567279.yml`

### Rollback pré-#63

Node `sha256:77d0d9a2…464d2b` · JVM `sha256:9443b801…2198d1` @ `2afd997`.

---

## 5. Cutover #63 (executado)

**SUCCESS** · stamp evidência `20261108T235854Z` · live passou a Node `2870175c…` / JVM `08da9ff2…` · WA READY · schema 035 · smoke + 20m PASS.

Posteriormente substituído em produção pelo hotfix #65 (`docs/pokemon-a6-tx-datoms-conflict-persistent-cutover.md`).

---

## 6. Proibições da fase de prep (históricas)

Na prep: sem deploy até autorização. Cutover #63 e #65 ocorreram sob GO explícito separado · sem migrations/backfill · sem reparo de saves · hold/CI inalterados.

