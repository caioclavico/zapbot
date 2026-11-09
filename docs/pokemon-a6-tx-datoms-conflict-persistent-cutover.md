# A6 — Cutover hotfix `tx_datoms_conflict` persistente (PR #65)

**PR:** [#65](https://github.com/caioclavico/zapbot/pull/65) **MERGED**  
**Merge RC:** `8ef41c8da1c28bfb69d57bade81a5a41e9225d44` → `rc/a6-abc-p0-integrated`  
**Content-tree:** `849129daf4cf12d14de69097c23dfebfdb20bebc`  
**Stamp build/cutover:** `20261109T100054Z` · fase `a6-tx-datoms-conflict-persistent-hotfix`  
**Artefatos:** `artifacts/a6-tx-datoms-conflict-persistent-hotfix-20261109T100054Z/`  
**Evidência remota:** `/home/caiohclavico/odisseu-recovery/a6-tx-datoms-conflict-persistent-cutover-20261109T100054Z/`

---

## 1. Causa e correção

Vitória de caçada ainda gerava `:db.error/datoms-conflict` após o hotfix #63: `apply-pokemon-xp-tx`, `evolution-tx-fragments` e `pokemon-hp-status-tx` reescreviam `:pokemon/hp-atual` (valores divergentes sob Species-HP v1).

Correção (`c6d773e`): evo-tx só identidade de espécie; XP com `:omit-hp-atual?` no turno; `pokemon-hp-status-tx` único writer canônico de HP de combate.

Incidente RO pré-cutover: `2026-11-09T00:19:46Z` / `00:19:51Z` · player `133109710377204@lid` · já nas imagens #63.

---

## 2. Imagens

| | Tag | Image ID |
|--|-----|----------|
| Node | `odisseu-node:a6-tx-datoms-conflict-persistent-hotfix-20261109T100054Z-8ef41c8da1c2-amd64` | `sha256:a87abb519681dc5aefbf83feaf295ec4427989f76386a10e5970a6d2d004dbd7` |
| JVM | `odisseu-jvm:a6-tx-datoms-conflict-persistent-hotfix-20261109T100054Z-8ef41c8da1c2-amd64` | `sha256:e93a0f54c944f97089b61c7b15d2548611bf67cce2823f039708b6bab44b1274` |

Labels: `git-sha=8ef41c8…` · `content-tree=849129da…` · arch **amd64**.

### SHA256 tars

```
0ae78cf911020a34eeca68d52c03ac3a4915a631f6af32b1da48a8aef68a4982  odisseu-node-…-8ef41c8da1c2-amd64.tar
46813b7b07230ff41e30c1e32fa6b85cc40b4736e33489ea93f6d63a3edbb3c3  odisseu-jvm-…-8ef41c8da1c2-amd64.tar
```

### Rollback (pré-#65)

Node `sha256:2870175c58b040614d11b9291d48d5c238fe7235cad3a179a6ed53de1bfa5396` · JVM `sha256:08da9ff2f4734a255d9ecc035cc41d35a200b642fcd7775fa7e294b62c02784f` (#63).

---

## 3. Cutover

| Etapa | Resultado |
|-------|-----------|
| Preflight (disco ≥1.5G, imagens, WA, schema 035, rollback) | PASS |
| Drain Node+JVM (`activeRequests=0`) | PASS |
| Recreate JVM → healthy · SCHEMA035 | PASS |
| Recreate Node · UID 1000 · Chromium 156 · CapEff=0 | PASS |
| WA READY persist 3/3 · resume open | PASS |
| Smoke fixtures (classifier, idle, Explosion/final-hit, markers) | PASS |
| Observe 20m | **39/39** · FAIL=0 · `TX_DATOMS` pós=0 |

**CUTOVER_RESULT=SUCCESS** · live = imagens acima · hold/CI inalterados · sem migrations/backfill/reparo de saves.

---

## 4. Nota sobre a RC tip

A tip da RC pode avançar após `8ef41c8` (ex.: PE/treinador). **Produção deste cutover permanece em `8ef41c8` / Image IDs acima** até nova autorização de deploy.
