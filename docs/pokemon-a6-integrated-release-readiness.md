# Pokémon A6 — Release readiness / RC A+B+C+P0 + HP/UI/captura

**Atualizado:** 2026-11-08T09:45Z  
**Host:** `odisseu-core` (linux/amd64)  
**Live executável:** tip P0 `6cef8f43…` imagens `bec910bf` / `9e87bb6f`  
**Candidata preparada no host:** Raid Balance A `043b846…` imagens `002c83e8` / `e044cce1`  
**Evidência prep:** `/home/caiohclavico/odisseu-recovery/a6-raid-balance-a-prep-20261108T094200Z`  
**Inbox tars:** `/home/caiohclavico/odisseu-recovery/rc-inbox/a6-raid-balance-a-043b846`  
**Evidência cutover P0:** `/home/caiohclavico/odisseu-recovery/a6-p0-hp-ui-cutover-20261108T074953Z`  
**Artefatos P0:** `/home/caiohclavico/odisseu-recovery/a6-p0-hp-ui-artifacts-20261108T072116Z`

---

## 0b. RC A6 RAID BALANCE A — PREPARADA NO HOST (sem cutover)

| Campo | Valor |
|-------|-------|
| Resultado prep | **RC A6 RAID BALANCE A PREPARADA NO HOST** |
| Commit executável | `043b84697c0a2771b855e9e0c8d4781ccf5cfeae` |
| Content tree | `eed2f0cfd30b2a580a3a47163c9b8f6007023b43` |
| Node candidata | `odisseu-node:a6-raid-balance-a-20261108T091954Z-043b84697c0a-amd64` · `sha256:002c83e83485ad5ad7fad936ab76dec416b810a4e446ce5ebeda4a3399c21b3a` |
| JVM candidata | `odisseu-jvm:a6-raid-balance-a-20261108T091954Z-043b84697c0a-amd64` · `sha256:e044cce13d9588691a110ba866743f6450fca5f3b4172553622c11479dbe3d94` |
| Tar Node | `odisseu-node-a6-raid-balance-a-043b84697c0a-amd64.tar` · SHA256 `b4ee6f1c5a00e8f424910c4bc04007d85c74b9699508908f830a02016bdc1c5c` |
| Tar JVM | `odisseu-jvm-a6-raid-balance-a-043b84697c0a-amd64.tar` · SHA256 `fbae335e0e18baafaa36153c03c43fa97a2a73814fca20aa33d7bcc9f81f89f8` |
| Arch | **linux/amd64** (local + host) |
| Transferência | SCP → `/tmp` → inbox; `sha256sum -c` OK local e remoto |
| `docker load` | OK; containers **não** recriados (IDs `ad1768a38d72` / `a8d391405e41` estáveis) |
| Verify host | `VERIFY_A6_GAMEPLAY_RC_IMAGES PASS` |
| Overlay aplicado (live) | ainda P0 `bec910bf` / `9e87bb6f` + `HEALTH_BIND_HOST=0.0.0.0` + UID 1000 |
| Overlay preparado | inbox + `…overlay.yml.prepared-raid-balance-a-043b846` (**não** aplicado) |
| Live Node | `sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305` · WA **READY** · healthy |
| Live JVM | `sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60` · healthy · maint `open` |
| Rollback | live P0 imagens **presentes** no Docker Engine |
| Flags amostradas | temáticos/raids/ginasios/kit `true`; `STAGING_ALLOW_QR=0`; `allowTestRng=false` |
| CI/CD | workflow `CI/CD` = `disabled_manually` (API GitHub; **não** alterado) |
| Deploy hold | política operacional documentada; **não** alterada neste prep (sem env `HOLD` nos containers — igual ao padrão P0) |
| Pedido de janela cutover | **GO para solicitar** (prep completa) — **cutover ainda NO-GO** sem nova autorização |

Parâmetros/simulações: `docs/pokemon-a6-gameplay-audit.md` §Balanceamento.

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
