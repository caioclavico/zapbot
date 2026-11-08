# Pokémon A6 — Release readiness / RC A+B+C+P0 + HP/UI/captura

**Atualizado:** 2026-11-08T10:45Z  
**Host:** `odisseu-core` (linux/amd64)  
**Live executável (Balance A):** Node `002c83e8…` / JVM `e044cce1…` @ `043b846`  
**Candidata local (1-mon + UX + catálogo):** branch `fix/a6-raid-one-mon-ux` — ver §0c  
**Evidência cutover Balance A:** STABILITY_20M_PASS (janela anterior)  
**Evidência cutover P0:** `/home/caiohclavico/odisseu-recovery/a6-p0-hp-ui-cutover-20261108T074953Z`

---

## 0c. Candidata 1-mon + UX raid + catálogo (RC pronta)

| Campo | Valor |
|-------|-------|
| Escopo | `max-party=1` (sem auto-troca); 2 barras HP; raridade chefe+jogador; empty/footer captura; Frambo≠presente; chefes manuais via catálogo (5→**571**); Proposta A intacta (HP 320 / var 85–100%) |
| PR código | [#43](https://github.com/caioclavico/zapbot/pull/43) **MERGED** |
| Commit integrado | `b0f0677786c44177478852db60bb4e1da6b2c5a1` |
| Content tree | `f3866c5eb008d56b8f22154711bc41b1d708af50` |
| Node | `odisseu-node:a6-raid-one-mon-20261108T105345Z-b0f0677786c4-amd64` · `sha256:617525d90f3022455b71d81f4cf063924861f5bbc048b3cdbe0287b54dd304b1` |
| JVM | `odisseu-jvm:a6-raid-one-mon-20261108T105345Z-b0f0677786c4-amd64` · `sha256:4a75eaa241c0c6ce45d541dada52e5966b456989f19ecd71e3b409661111816b` |
| Tar SHA256 Node | `77a4cda15d207b66da4502ba828a69719d3b3d3d88fd7cc928571b71e5234435` |
| Tar SHA256 JVM | `a12227cd56b077e139cb65be9bceb55daee258cf2292f10fa22c7b37f0adf6dc` |
| Artefatos | `artifacts/a6-raid-one-mon-20261108T105345Z/` |
| Homolog local | homolog-abc **42/247** · lobby **24/80** · homolog-f4 **13/114** · auto-fase3 **19/81** · m4-raid+HTTP · Node **36/36** — **PASS** |
| Verify host | **PASS** (labels git-sha/tree/amd64) · live **UNTOUCHED** |
| Overlay | pin candidata (**sem apply**) |
| Deploy | **NÃO** |
| Pedido de janela | **GO para preparar** (hold intacto; sem deploy automático) |
| Fórmula HP global | **NO-GO** (proposta só) |

Detalhes: `docs/pokemon-a6-gameplay-audit.md` §Revisão raids.

---

## 0b. Live Proposta A (já em produção)

| Campo | Valor |
|-------|-------|
| Escopo | `hp-por-jogador` **320**; variação dano **85–100%**; lifecycle/faint/golpes/UI pré-1-mon |
| Commit | `043b84697c0a2771b855e9e0c8d4781ccf5cfeae` |
| Node | `sha256:002c83e83485ad5ad7fad936ab76dec416b810a4e446ce5ebeda4a3399c21b3a` |
| JVM | `sha256:e044cce13d9588691a110ba866743f6450fca5f3b4172553622c11479dbe3d94` |
| Status | **LIVE** pós-cutover Balance A · STABILITY_20M_PASS |

---

## 0. Veredito operacional (live atual)

| Campo | Valor |
|-------|-------|
| Resultado | **SUCCESS — ODISSEU A6 P0 EM PRODUÇÃO** (live; candidata §0b ainda sem deploy) |
| PR base | [#38](https://github.com/caioclavico/zapbot/pull/38) **MERGED** (`6cef8f43`) |
| Node ativo | `sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305` |
| JVM ativa | `sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60` |
| Labels | `git-sha=6cef8f43…` · `content-tree=50babd2f…` · `linux/amd64` |
| WhatsApp | **READY** sustentado (`hasSynced_listeners_attached`) |
| Chromium | Debian **156.0.8078.79** · UID **1000** · CapEff **0** · NoNewPrivs **1** |
| Datomic | Volume `odisseu-a6-cutover-datomic` preservado (hot+cold backup) |
| Flags | Temáticos/raids/ginásios/kit/permanência **preservados** (`true`; `STAGING_ALLOW_QR=0`) |
| Deploy hold / CI/CD | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · workflow CI/CD `disabled_manually` (**não** alterados) |
| Migrations / backfill / saves | **Não** executados / **não** alterados |

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
