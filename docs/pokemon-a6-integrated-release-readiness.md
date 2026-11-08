# Pokémon A6 — Release readiness / RC A+B+C+P0 + One-Mon + species-hp

**Atualizado:** 2026-11-08T14:20Z  
**Host:** `odisseu-core` (linux/amd64)  
**Live executável (One-Mon):** Node `617525d9…` / JVM `4a75eaa2…` @ `b0f0677` — **intacta**  
**RC candidata (species-hp):** Node `57a828ca…` / JVM `e1bb8ac5…` @ `5ed39b0` — carregada, **não** aplicada  
**Evidência prep species-hp:** `/home/caiohclavico/odisseu-recovery/a6-species-hp-prep-20261108T134333Z`  
**Evidência storage+ensaio:** `/home/caiohclavico/odisseu-recovery/a6-species-hp-storage-audit-20261108T140316Z`  
**Inbox tars:** `/home/caiohclavico/odisseu-recovery/rc-inbox/a6-species-hp-5ed39b0/`  
**Evidência cutover One-Mon:** `/home/caiohclavico/odisseu-recovery/a6-raid-one-mon-cutover-20261108T111814Z`

---

## 0h. Storage + ensaio migração Species-HP (2026-11-08T14:03Z→14:17Z) — **sem cutover/live migrate**

### A. Armazenamento

| Campo | Valor |
|-------|-------|
| Disco antes | **93%** · ~3,7 G livres |
| Limpeza segura | **4,79 GiB** — apenas `/tmp` staging tars/build ctx + containers `Created` de comparação + dangling 0-unique |
| Disco depois | **81%** · **9,3 G** livres |
| Meta 8–10 G | **atingida** |
| Produção após limpeza | WA **READY** · Node/JVM **healthy** · Started **inalterado** · Restarts=0 · flags intactas |
| Hold / CI | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · `deploy.yml` **`disabled_manually`** · in_progress=0 |
| Não removido | imagens One-Mon / Species-HP / Balance A · volumes Datomic · sessão WA · rc-inbox protegido · backups · recovery sessions |
| Proposta humana (extra) | rc-inbox antigos (`gym-hp`, `gameplay-audit`, `ai-drain` ≈1,6 G) + dirs de sessão de recovery (classificação incerta) — **não apagados** |
| Expansão disco VM | **opcional** agora; **recomendada** antes de cutover se quiser margem >15 G para cold dump + rollback simultâneos |

### B. Ensaio Species-HP (Datomic descartável)

| Campo | Valor |
|-------|-------|
| Dataset | export RO anonimizado (cópia volume; **0 writes live**) |
| Testados | **984** |
| Convertidos | **960** |
| Quarentena | **22** (formas/variantes) |
| Skip combate (ensaio) | **2** |
| Tempo lotes | ~0,28 s |
| Idempotência / retomada | **PASS** |
| Integridade amostra persistida | **PASS** · faint=0 **PASS** |
| Unit species-hp | **19/1337 PASS** |
| Raid boss | **320** |
| Live saves | **não** alterados |

| Veredito | |
|----------|--|
| Pedir janela cutover+migração futura | **GO condicional** (aliases de forma + schema 035 + freeze combates) |
| Cutover / migrate agora | **NO-GO** |

Detalhes: `docs/pokemon-a6-species-hp-migration-plan.md`.

---

## 0g. Prep operacional Species-HP (2026-11-08T13:42Z→13:56Z) — **sem cutover**

| Campo | Valor |
|-------|-------|
| Autorização | **GO prep only** (load/verify/pin overlay; **sem** deploy/drain/restart/migration) |
| Live Node/JVM | `617525d9…` / `4a75eaa2…` · healthy · Started **inalterado** |
| WhatsApp | **READY** `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Hold / CI | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · `deploy.yml` **`disabled_manually`** · runs in_progress=0 |
| Disco antes | **91%** · avail **4 576 432 KB** (~4.4 G) |
| Disco após SCP | **94%** · avail **3 302 396 KB** (pico com stage+inbox) |
| Disco após load | **93%** · avail **3 891 820 KB** (layers compartilhadas; Δ≈46 MB) |
| Disco final | **93%** · avail **3 872 160 KB** |
| `sha256sum -c` local/host | **OK** |
| `docker load` | **PASS** (Node+JVM) |
| Image IDs carregados | Node `sha256:57a828ca…5332` · JVM `sha256:e1bb8ac5…44a8` |
| Labels | `git-sha=5ed39b0…` · `content-tree=80546bd3…` · `fase=a6-species-hp` · **amd64** |
| `VERIFY_A6_GAMEPLAY_RC_IMAGES` | **PASS** |
| Overlay | pinado species-hp + seccomp · bak `…bak-pre-species-hp-prep-20261108T135118Z` · **não** aplicado |
| Auditoria Datomic RO | **983** Pokémon · **100%** `legacy-unset` · `hp-base=0` · partial **13** · fainted **20** · full **950** · raids ativas **0** · battles **1** |
| Migração de saves | **não** executada (plano em `docs/pokemon-a6-species-hp-migration-plan.md`) |
| Pedido de cutover | **GO condicional** (disco 93% — monitorar; preferir prune **não destrutivo** só com GO) |
| Cutover agora | **NO-GO** |

Checksums tars:
- Node `5f3c6abeaa0c43f14127b2bbc72f0d8e43e00282634ccfd8fb8b787098d480d8`
- JVM `708c5c509ba00cf7d21ac595a341632e39d8f94c12829431702a7ffcf7f6a3e0`

---

## 0f. RC species-hp-v1 (2026-11-08T13:35Z) — integração + build (**sem** cutover)

| Campo | Valor |
|-------|-------|
| Autorização | **GO integração/build/homolog RC** (sem deploy/migration/backfill/flags/hold/CI) |
| Feature commit | `53a6b9539a24d0063a1f532c7a38eb4587b0e15f` |
| PR | [#45](https://github.com/caioclavico/zapbot/pull/45) merged |
| Commit integrado | `5ed39b0306addeaf96d4cbb3ad8480268adb3ae0` |
| Content-tree | `80546bd3a04663b80a2e09750f48c5fa2a4cecf9` |
| Source-base | `3f9edc38b1d0987db7d781317350e146b9c5b82e` |
| Fórmula | `hp-max = round(hp-ref(n) × clamp((base/80)^0.85, 0.60, 2.20))` |
| Node Image ID | `sha256:57a828ca6b6187b1415e0a5ea6f71c8b7ec12dd7ffc7fc63e5e6563c2aee5332` |
| JVM Image ID | `sha256:e1bb8ac5df0ab5ea81b1ab96ca689a602d47e14d41daf07fd0429fafd9df44a8` |
| Tags | `odisseu-node:a6-species-hp-20261108T133538Z-5ed39b0306ad-amd64` · `odisseu-jvm:…-5ed39b0306ad-amd64` |
| Arch / UID / sandbox | **amd64** · Node `1000:1000` · sem `--no-sandbox` · Chromium 156 |
| Health | Node `scripts/healthcheck.js` · JVM `127.0.0.1:18090/health` · overlay `HEALTH_BIND_HOST=0.0.0.0` |
| VERIFY_A6_GAMEPLAY_RC_IMAGES | **PASS** |
| Overlay | pins species-hp + seccomp Chromium (**não** aplicado em produção) |
| Testes | species-hp 19/1337 · homolog 42/247 · tm-xp · hunt · raid+gym smoke · bridge · Node 18 |
| Raid boss HP | **320** intacto |
| Saves / flags / WA / hold / CI | **não** alterados |
| Prep operacional | **GO condicional** (load tars + VERIFY no host) |
| Cutover / deploy | **NO-GO** até autorização operacional |

Relatório detalhado: `docs/pokemon-a6-species-hp-balance.md`.

---

## 0e. Cutover One-Mon (2026-11-08T11:18Z→11:47Z) — **SUCCESS**

| Campo | Valor |
|-------|-------|
| Autorização | **GO PRODUÇÃO** (cutover coordenado Node+JVM; sem migrations/backfill/flags/hold/CI/HP-espécie) |
| Resultado | **ODISSEU A6 ONE-MON EM PRODUÇÃO** · `CUTOVER_RESULT=SUCCESS` · `STABILITY_20M_PASS` |
| Commit | `b0f0677786c44177478852db60bb4e1da6b2c5a1` |
| Content-tree | `f3866c5eb008d56b8f22154711bc41b1d708af50` |
| Node final | `sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1` |
| JVM final | `sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b` |
| Tags | `odisseu-node:a6-raid-one-mon-20261108T105345Z-b0f0677786c4-amd64` · `odisseu-jvm:…-b0f0677786c4-amd64` |
| Preflight | **PASS** — live pré=`002c83e8`/`e044cce1`; WA READY; maint open; VERIFY PASS; overlay pin; disco 4.7 G (após builder prune); hold GitHub **`true`**; CI/CD **`disabled_manually`**; runs in_progress=0 |
| Backup | **PASS** — compose/flags/AI history; Datomic hot **20**; cold sessão **1231**; cold Datomic **20** |
| Drain/ACL | **PASS** — Node+JVM quiescent/durable; stop ordenado; writers=0; `APPLY_DONE` u:1000 |
| Cutover | Overlay One-Mon + seccomp Chromium; JVM→resume→Node; flags preservadas |
| Incidente mid-cutover | (1) overlay sem `seccomp=` → Chromium setuid fail → **SECCOMP_FIX**; (2) Node boot com JVM quiescent → `maintenance_active`; (3) `SingletonLock` residual → **SINGLETON_CLEAR** · READY `2026-11-08T11:25:09Z` |
| Validação RO | **PASS** — caps **89**; `max-party=1`; `hp-por-jogador=320`; `select-boss-for-ginasio`; `formatRaidAttack`/`barraHp`/`Frambo`/`Ultra Bola`; CapEff=0; NNP=1; Chromium **156**; UID **1000**; sem `--no-sandbox`; restarts 0/0 |
| Observação | `11:26:22Z`→`11:47:10Z` · **41/41** READY · FAIL=0 · sem QR · sem restart |
| WhatsApp final | `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Datomic / saves / flags | Preservados · temáticos/raids/ginásios/kit **true** · `STAGING_ALLOW_QR=0` |
| Hold / CI/CD | **não** alterados (`ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · `disabled_manually`) |
| Migrations / backfill / HP espécie | **Não** executados |
| Rollback | **não** necessário · imagens Balance A intactas |

---

## 0d. Prep operacional One-Mon (2026-11-08T11:08Z) — sem cutover

| Campo | Valor |
|-------|-------|
| Autorização | **GO prep only** (load/verify/pin overlay; **sem** deploy/drain/restart) |
| Commit executável | `b0f0677786c44177478852db60bb4e1da6b2c5a1` |
| Content-tree | `f3866c5eb008d56b8f22154711bc41b1d708af50` |
| Node Image ID | `sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1` |
| JVM Image ID | `sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b` |
| Tar Node | `odisseu-node-a6-raid-one-mon-b0f0677786c4-amd64.tar` |
| Tar JVM | `odisseu-jvm-a6-raid-one-mon-b0f0677786c4-amd64.tar` |
| SHA256 Node | `77a4cda15d207b66da4502ba828a69719d3b3d3d88fd7cc928571b71e5234435` |
| SHA256 JVM | `a12227cd56b077e139cb65be9bceb55daee258cf2292f10fa22c7b37f0adf6dc` |
| Arch | **linux/amd64** (ambos) |
| Labels | `git-sha=b0f0677…` · `content-tree=f3866c5e…` · `fase=a6-raid-one-mon` |
| `sha256sum -c` local | **OK** |
| `sha256sum -c` host | **OK** |
| `docker load` | **PASS** (ambas tags carregadas) |
| `VERIFY_A6_GAMEPLAY_RC_IMAGES` | **PASS** |
| Overlay host | pin One-Mon (**não** aplicado; bak `…bak-pre-one-mon-prep-20261108T110904Z`) |
| Live Node/JVM | ainda Balance A `002c83e8…` / `e044cce1…` · Started inalterado · healthy |
| WhatsApp | **READY** (`{"status":"ok","whatsapp":"READY","chromium":true}`) |
| JVM bridge | **ok** via `docker exec` `/health` (host :18090 não publicado) |
| Datomic | volume `odisseu-a6-cutover-datomic` montado · preservado |
| Flags amostradas | temáticos/raids/kit **true** · `STAGING_ALLOW_QR=0` |
| CI/CD deploy.yml | **`disabled_manually`** (não reativado) |
| Rollback Balance A | imagens **presentes** no engine (`002c83e8` / `e044cce1`) |
| HP espécie global | **não** aplicada |
| Pedido de janela cutover | **GO** (prep completa) → executado em §0e |
| Cutover agora | ver §0e (**SUCCESS**) |

---

## 0c. Candidata 1-mon + UX raid + catálogo (RC pronta)

| Campo | Valor |
|-------|-------|
| Escopo | `max-party=1` (sem auto-troca); 2 barras HP; raridade chefe+jogador; empty/footer captura; Frambo≠presente; chefes manuais via catálogo (5→**571**); Proposta A intacta (HP 320 / var 85–100%) |
| PR código | [#43](https://github.com/caioclavico/zapbot/pull/43) **MERGED** |
| PR pins | [#44](https://github.com/caioclavico/zapbot/pull/44) **MERGED** |
| Commit integrado | `b0f0677786c44177478852db60bb4e1da6b2c5a1` |
| Content tree | `f3866c5eb008d56b8f22154711bc41b1d708af50` |
| Node | `odisseu-node:a6-raid-one-mon-20261108T105345Z-b0f0677786c4-amd64` · `sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1` |
| JVM | `odisseu-jvm:a6-raid-one-mon-20261108T105345Z-b0f0677786c4-amd64` · `sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b` |
| Tar SHA256 Node | `77a4cda15d207b66da4502ba828a69719d3b3d3d88fd7cc928571b71e5234435` |
| Tar SHA256 JVM | `a12227cd56b077e139cb65be9bceb55daee258cf2292f10fa22c7b37f0adf6dc` |
| Artefatos locais | `artifacts/a6-raid-one-mon-20261108T105345Z/` |
| Homolog local | homolog-abc **42/247** · lobby **24/80** · homolog-f4 **13/114** · auto-fase3 **19/81** · m4-raid+HTTP · Node **36/36** — **PASS** |
| Prep host (§0d) | **PASS** |
| Overlay | pin candidata (**sem apply**) |
| Deploy | **NÃO** |
| Pedido de janela | **GO** |
| Fórmula HP global | **NO-GO** (proposta só) |

Detalhes: `docs/pokemon-a6-gameplay-audit.md` §Revisão raids.

---

## 0b. Rollback Balance A (pré-One-Mon)

| Campo | Valor |
|-------|-------|
| Escopo | `hp-por-jogador` **320**; variação dano **85–100%**; lifecycle/faint/golpes/UI pré-1-mon |
| Commit | `043b84697c0a2771b855e9e0c8d4781ccf5cfeae` |
| Node | `sha256:002c83e83485ad5ad7fad936ab76dec416b810a4e446ce5ebeda4a3399c21b3a` |
| JVM | `sha256:e044cce13d9588691a110ba866743f6450fca5f3b4172553622c11479dbe3d94` |
| Status | **rollback disponível** (foi live até cutover One-Mon) |

---

## 0. Veredito operacional (live atual)

| Campo | Valor |
|-------|-------|
| Resultado | **SUCCESS** — **LIVE = One-Mon** @ `b0f0677` · `STABILITY_20M_PASS` |
| Node ativo | `sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1` |
| JVM ativa | `sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b` |
| WhatsApp | **READY** sustentado (≥20 min) |
| Chromium | Debian **156** · UID **1000** · seccomp host + CapEff=0 + NNP |
| Datomic | Volume `odisseu-a6-cutover-datomic` preservado |
| Flags | Temáticos/raids/kit **true** · `STAGING_ALLOW_QR=0` |
| CI/CD | workflow `deploy.yml` **`disabled_manually`** |
| Deploy hold / CI/CD | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · workflow CI/CD `disabled_manually` (**não** alterados) |
| Migrations / backfill / saves | **Não** executados / **não** alterados |
| HP espécie global | **não** aplicada |

---

## 1. Commits / imagens

| Campo | Valor |
|-------|-------|
| Commit P0 | `400a5ffda862e409d62197a772bd7e6e123ec366` |
| Tip integrado | `6cef8f43b0dce617af0ecf0b0374f6a541388104` |
| Tree | `50babd2f4dc1e4bd1e83ca5b91a7ad2628773304` |
| Node tag | `odisseu-node:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64` |
| JVM tag | `odisseu-jvm:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64` |
| Tar SHA256 Node | `8b361b9b01cabb26b519c93eb369fe6266c24997f5c705c44c72728ef5dcea49` |
| Tar SHA256 JVM | `48b016a6bc837f61a79055a308cdfc124333b6426f819c5829906f55af964a0e` |

---

## 2. Rollback (pré-janela, confirmado)

| Camada | Image ID |
|--------|----------|
| Node (pré-cutover) | `sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade` |
| JVM (pré-cutover) | `sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c` |

Overlay bak: `docker-compose.a6-ai-drain-sandbox.overlay.yml.bak-pre-p0-cutover-20261108T074953Z`.

---

## 3. Preflight (PASS)

- Live IDs = rollback §2; candidatas carregadas; `sha256sum -c` OK; `VERIFY_A6_GAMEPLAY_RC_IMAGES PASS`.
- WA READY; Node/JVM healthy; bridge `up`; maint `open`.
- Hold `true`; CI/CD `disabled_manually`; runs ativos = 0.
- Overlay pin candidata; disco ~8.9 G livres; sem migrations necessárias.
- Flags amostradas: temáticos/raids/ginásios/kit `true`, `STAGING_ALLOW_QR=0`.

---

## 4. Backup / drain / ACL

| Etapa | Resultado |
|-------|-----------|
| Backup compose/meta/flags/AI history | PASS (`…/backup/`) |
| Cold sessão WA | PASS **1089** arquivos |
| Cold Datomic | PASS **20** arquivos |
| Node drain → quiescent/durable | PASS |
| JVM drain → quiescent (`X-A6-Maintenance-Token`) | PASS |
| ACL u:1000 apply | PASS (`APPLY_DONE`; verify “missing node” esperado com containers parados) |
| Writers simultâneos | Nenhum no mid-cutover |

---

## 5. Cutover

1. Overlay pin Node `bec910bf…` + JVM `9e87bb6f…` + `HEALTH_BIND_HOST=0.0.0.0` (sem override de flags Pokémon).
2. JVM recriada → healthy / bridge up / **89** caps / flags `true`.
3. Resume JVM `open` **antes** do Node definitivo.
4. Node recriado → READY `2026-11-08T07:52:26Z` com `hasSynced_listeners_attached` (RECOVERING transitório de inject/hasSynced no boot — esperado).
5. Resume Node `open`.

---

## 6. Validação pós-cutover

| Check | Resultado |
|-------|-----------|
| Caps raid/capture/gym/thematic (89; needs presentes) | PASS |
| Rotas `/v1/gyms`, `/v1/gyms/npc-roll`, `/v1/raids/active` | PASS (auth/validação de query) |
| Bundle Node: `formatRaidAttack`, `Ultra Bola`, `Frambo`, `HEALTH_BIND`; sem `--no-sandbox` | PASS |
| AI history file | PASS (1435 B) |
| Sandbox CapEff/NNP/Chromium 156 / UID 1000 | PASS |
| Flags pós-cutover = pré | PASS |
| Restarts | Node **0** · JVM **0** |

Validação de gameplay: **somente leitura / contratos** (sem mutar saves/inventários).

---

## 7. Estabilidade

| Campo | Valor |
|-------|-------|
| READY_AT | `2026-11-08T07:52:26Z` |
| Observação | `2026-11-08T07:55:33Z` → `08:15:20Z` (40× / 30 s) |
| Resultado | **STABILITY_20M_PASS** (`2026-11-08T08:15:50Z`) |
| Critério | READY + status ok + Image IDs RC + JVM healthy/open · sem QR · sem restart |
| Scan pós-READY | vazio (sem RECOVERING/QR) |
| Health final | `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Evidência | `…/logs/stability-20m.log`, `stability-ok-at.txt`, `health-after-20m.json` |

---

## 8. Riscos residuais

- Parties de raid **já** em combate podem manter golpes slim antigos até leave/rejoin.
- Débito alias consome o primeiro ID com qty suficiente (canônico preferido); remap de estoque legado `great-ball` **não** executado.
- Smoke destrutivo em contas reais **não** foi feito nesta janela (proibido pelo GO).
- Disco host ~82% — monitorar antes do próximo cutover.

---

## 9. Referências

- Audit P0: `docs/pokemon-a6-gameplay-audit.md` §P0
- Evidência: `a6-p0-hp-ui-cutover-20261108T074953Z`
- Overlay runtime: `…/a6-cutover-runtime-20261101T141408Z/docker-compose.a6-ai-drain-sandbox.overlay.yml`
- Verify: `scripts/verify-a6-gameplay-rc-images.sh`
