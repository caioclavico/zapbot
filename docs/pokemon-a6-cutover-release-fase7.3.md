# A6 — Fase 7.3: Release Candidate (cutover)

**Branch:** `release/a6-cutover-rc-7.3`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-11-01  
**Modo:** build + testes locais — **sem** deploy, migrate live, restart, alteração de grupos, corte ou escrita em produção.

---

## Decisão: **READY** (artefato RC)

O RC imutável foi produzido e testado. O **corte** continua **NO-GO** até autorização (Fase 7.2).

| Item | Status |
| --- | --- |
| Branch isolada + commit auditável | **PASS** |
| Worktrees existentes preservados | **PASS** (`a6-save-slots`, `feature/a6-raid-readiness-fase5` em `1273fa4`) |
| Imagens `a6-cutover-<ts>-<sha>` | **PASS** (amd64) |
| Migrations 025–030 no schema + Datomic sintético | **PASS** (30/30) |
| Suítes relevantes | **PASS** (ver §3) |
| Allowlist / fail-closed | **PASS** (piloto literal comprovado; ticks OFF) |
| Deploy / corte | **não executado** |

---

## 1. Release candidate — git

### 1.1 Alterações locais

Antes do commit RC: worktree `feature/a6-raid-reconcile-fase5.1` com dezenas de arquivos modificados/untracked (fases 5.x–7.2).  
Outros worktrees **não** foram tocados.

### 1.2 Branch e commit

| Campo | Valor |
| --- | --- |
| Branch | `release/a6-cutover-rc-7.3` |
| Commit (full) | `f0065de6e4b9b72ac2cc145be9db98e9e81faf5a` |
| Short (tag) | `f0065de6e4b9` |
| Mensagem | `release(a6): consolidar RC cutover com raides, save-slots e migrations 025–030` |
| Parent | `1273fa4` |
| Diff | 207 files · +24154 / −1365 |

Worktrees após o RC:

```text
zapbot                1273fa4 [a6-save-slots]
zapbot-raid-lobby     1273fa4 [feature/a6-raid-readiness-fase5]
zapbot-raid-reconcile f0065de [release/a6-cutover-rc-7.3]
```

---

## 2. Build imutável

| Campo | JVM | Node |
| --- | --- | --- |
| Tag | `odisseu-jvm:a6-cutover-20261101T134329Z-f0065de6e4b9-amd64` | `odisseu-node:a6-cutover-20261101T134329Z-f0065de6e4b9-amd64` |
| Image ID | `sha256:714955b512bace20dd59b20813d55cfe4ab916189a968409fb4dac93e6baf8ed` | `sha256:5d7f5fc4736f5e6d6b4568637d6bcd80de65446bb7ac3fbc578a822f50cd7e33` |
| Digest local | `odisseu-jvm@sha256:714955b512ba…` (mesmo Id; imagem local sem registry) | `odisseu-node@sha256:5d7f5fc4736f…` |
| Platform | `linux/amd64` | `linux/amd64` |
| Size | ~249 MiB | ~371 MiB |

**Não** usadas: `latest`, `a6-staging-local`, tags `a64-pvp-*` do Core.

### 2.1 Comandos de build (executados)

```bash
STAMP=20261101T134329Z
SHA=f0065de6e4b9
docker build --platform linux/amd64 -f Dockerfile.jvm \
  -t odisseu-jvm:a6-cutover-${STAMP}-${SHA}-amd64 .
docker build --platform linux/amd64 -f Dockerfile \
  -t odisseu-node:a6-cutover-${STAMP}-${SHA}-amd64 .
```

### 2.2 Runtime / dependências

| Componente | Versão |
| --- | --- |
| Git SHA build | `f0065de6e4b9b72ac2cc145be9db98e9e81faf5a` |
| JVM na imagem | Temurin **21.0.13** LTS (`Dockerfile.jvm`) |
| Node na imagem | **v22.23.3** (`FROM node:22-bookworm-slim`) |
| Clojure (host testes) | 1.12.1 |
| Datomic Local (deps) | 1.0.301 |
| App `package.json` | 0.21.0 · engines node ≥18 |
| shadow-cljs | ^2.28.5 (build release OK) |

### 2.3 Migrations incluídas (025–030)

Confirmadas em `pokemon.infrastructure.datomic.schema/migration-files` e em Datomic sintético:

| ID | Resultado |
| --- | --- |
| `pokemon/025-save-slot-metadata` | presente |
| `pokemon/026-battle-idle` | presente |
| `pokemon/027-bag-daily` | presente |
| `pokemon/028-economy-ledger` | presente |
| `pokemon/029-raid-lobby` | presente |
| `pokemon/030-raid-auto` | presente |

Synth: storage `.datomic-local/a6-rc-synth-20261101T134329Z` · system `odisseu-a6-rc-synth` · **30** migrations · **sem produção**.

---

## 3. Testes

| Suíte | Resultado |
| --- | --- |
| `a6-staging-static-audit.sh` | PASS (FAIL=0) |
| `test-raid-save-slots-reconcile` | 3/13 PASS |
| `test-a6-economy-capacity` | economy 9/37 · capacity 9/72 · idle 10/47 PASS |
| `test-raid-lobby-fase2` | 23/76 PASS |
| `test-raid-auto-fase3` (scheduler/outbox) | 19/76 PASS |
| `test-raid-homolog-fase4` | 13/114 PASS |
| `test-m4-raid` (M3.6/M4.9) | 30/30 PASS |
| `clojure -M:test-m2-trainer` (save integrity) | 11/66 PASS |
| `node --test` isolation + raid-lobby | 11 tests · fail 0 |
| `npm run build` (shadow release) | EXIT 0 |
| Datomic sintético `schema/install!` 025–030 | PASS |

Logs: `/tmp/a6-rc-tests/*.log` (host local).

---

## 4. Grupos / fail-closed

| Gate | Valor |
| --- | --- |
| Piloto (literal comprovado RO Core, Fase 7.2) | `120363427684548487@g.us` |
| Deduzido de hash? | **Não** — ID veio de `printenv DEV_GROUP_ID` |
| Template | `.env.a6-cutover-rc.example` |
| `ALLOW_ALL_GROUPS` | `false` |
| `POKEMON_RAID_AUTO_TICK` | `false` |
| `ODISSEU_BRIDGE_RAID_AUTO_TICK` | `false` |
| Identidade piloto | **comprovada** → não BLOCKED |

`STAGING_ALLOWED_USERS` permanece para preenchimento manual no GO (não auto-popular).

---

## 5. Pin para o corte (quando autorizado)

```yaml
# docker-compose.release-a6-cutover.yml (derivar do .example)
services:
  odisseu-jvm:
    image: odisseu-jvm:a6-cutover-20261101T134329Z-f0065de6e4b9-amd64
    # preferível: odisseu-jvm@sha256:714955b512bace20dd59b20813d55cfe4ab916189a968409fb4dac93e6baf8ed
  odisseu-node:
    image: odisseu-node:a6-cutover-20261101T134329Z-f0065de6e4b9-amd64
    # preferível: odisseu-node@sha256:5d7f5fc4736f5e6d6b4568637d6bcd80de65446bb7ac3fbc578a822f50cd7e33
```

Carregar imagens na VM Core via `docker save | ssh … docker load` **somente após GO** (não feito nesta fase).

---

## 6. Bloqueios restantes (corte)

- Autorização explícita de execução (7.2 §5)  
- Cold T0 do dia D + manifesto  
- Overlay sem herdar `ALLOW_ALL_GROUPS=true` do battle-idle  
- Transferência das imagens pinadas para a VM  

**Nada disso foi executado agora.**

---

## 7. Artefatos

| Item | Path / valor |
| --- | --- |
| Relatório | `docs/pokemon-a6-cutover-release-fase7.3.md` |
| Env allowlist | `.env.a6-cutover-rc.example` |
| Commit RC | `f0065de6e4b9b72ac2cc145be9db98e9e81faf5a` |
| Tags | `a6-cutover-20261101T134329Z-f0065de6e4b9-amd64` |
