# Pokémon A6 — Release readiness / RC A+B+C+P0 + HP/UI/captura

**Atualizado:** 2026-11-08T11:47Z  
**Host:** `odisseu-core` (linux/amd64)  
**Live executável (One-Mon):** Node `617525d9…` / JVM `4a75eaa2…` @ `b0f0677`  
**Rollback Balance A:** Node `002c83e8…` / JVM `e044cce1…` (imagens **presentes**)  
**Evidência cutover One-Mon:** `/home/caiohclavico/odisseu-recovery/a6-raid-one-mon-cutover-20261108T111814Z`  
**Inbox tars:** `/home/caiohclavico/odisseu-recovery/rc-inbox/a6-raid-one-mon-b0f0677/`

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
