# Pokémon A6 — Homologação integrada A+B+C / Release readiness

**Atualizado:** 2026-11-08T03:10Z
**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Escopo:** validação final pré-pedido de janela. **Sem deploy produção, migrations live, backfill, alteração de saves, remoção de deploy hold ou reabilitação de CI/CD de produção.**

---

## 0. Veredito atual

| Pergunta | Resposta |
|----------|----------|
| RC gameplay integrada e imagens rastreáveis a `21771ec`? | **SIM** |
| Pedir autorização de janela de implantação **agora**? | **NO-GO** |
| Motivos bloqueantes do pedido | (1) overlay/cutover ainda pinam imagem `fefab4c`/`ce429153`, não a RC gameplay `e7488e4f`; (2) `chrome://sandbox` **não** revalidado nativo AMD64 neste Image ID (falhou em emulação Mac); (3) ausência de evidência READY com sessão WhatsApp de **teste** independente |
| Deploy hold / CI produção | **Intocados** (`ODISSEU_PRODUCTION_DEPLOY_HOLD=true`; workflow `CI/CD` = `disabled_manually`) |
| `POKEMON_THEMATIC_EVENTS_ENABLED` | **`false`** (`.env.example`; staging anterior; overlay não redefine) |
| Flags ginásio/raid/recompensa/evento | Overlay **não menciona** — preservadas por omissão |

Para reabrir o pedido de janela: checklist §16.

---

## 1. Identidade Git

| Item | SHA |
|------|-----|
| Commit A+B+C | `ca06cff69f13c060c37f7af030940abf68ded3f8` |
| Merge integrado (fonte das imagens) | `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` |
| Content tree das imagens | `7645f59f7445f58d0f733aa578a4296bd342fa63` |
| Tip docs RC | `1334214…` (somente readiness; **não** entra na imagem — Dockerfile não copia `docs/`) |
| PR | https://github.com/caioclavico/zapbot/pull/36 MERGED |
| Base pré-A+B+C | `16b59876b448323a870c74a2dddfa27416685273` |

---

## 2. Digests completos das imagens RC (= commit `21771ec`)

Stamp de build: `20261108T024926Z` · platform `linux/amd64` · builder `a6builder` · working tree **CLEAN** no build.

### Node

| Campo | Valor completo |
|-------|----------------|
| Tag | `odisseu-node:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Image ID | `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| Repo digest local | `odisseu-node@sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| Config blob SHA-256 | `4c7c23f3d089ce6056e5f436fc94d03db83db29f05ff703ea6f253ecb8baedfa` |
| Label `io.zapbot.git-sha` | `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` (**match**) |
| Label `io.zapbot.content-tree` | `7645f59f7445f58d0f733aa578a4296bd342fa63` (**match**) |
| Tar | `/tmp/odisseu-node-a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64.tar` |
| SHA-256 tar | `878ba298cba5e4434225032e108fb95fc139926c7242ed383e08ae4998e706e7` |

### JVM

| Campo | Valor completo |
|-------|----------------|
| Tag | `odisseu-jvm:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Image ID | `sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| Repo digest local | `odisseu-jvm@sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| Config blob SHA-256 | `9dae9bb51548279d600d58383bff88db5931cd1fe4cc3d10a061df624d6bed7d` |
| Label `io.zapbot.git-sha` | `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` (**match**) |
| Label `io.zapbot.content-tree` | `7645f59f7445f58d0f733aa578a4296bd342fa63` (**match**) |
| Tar | `/tmp/odisseu-jvm-a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64.tar` |
| SHA-256 tar | `c048e8e8c517aef87dbe4ddfe4d0616a5869e96ec138676e59331d6ec460787e` |

---

## 3. Validação Node RC (item 2)

| Check | Resultado |
|-------|-----------|
| UID/GID efetivos na imagem | **1000:1000** |
| `a6-maintenance.cjs` | SHA-256 `04b5b61d…7e4d80` (idêntico à produção/drain RC) |
| `a6-shutdown.cjs` | SHA-256 `16dae2a8…3ad68` (idêntico) |
| Flags proibidas em `target/main.js` | **ausentes** (`--no-sandbox`, `--disable-setuid-sandbox`, `--disable-seccomp-filter-sandbox`) |
| Chromium presente | `/usr/bin/chromium` → Chrome/157.0.8103.38 |
| IA (`ai-client`, `ai-content-generator`, `ai-content-history-file`) | presentes; `generateDetailed` / content-history marcados |
| `npm run test:ai` | **44/44 PASS** |
| Drain/maintenance/invariants | **16/16 PASS** |
| Preflight `SESSION_ACCESS_DENIED` | **PASS** em fixture Linux descartável: UID 1000 sem ACL → EACCES em sessão + marker (antes de `setfacl`) |
| `chrome://sandbox` neste Image ID | **NÃO REVALIDADO em AMD64 nativo.** Em Docker Desktop Mac (emulação amd64): `Seccomp-BPF sandbox=No`, relatório “NOT adequately sandboxed”, assert do harness FAIL. Linhagem Dockerfile/seccomp igual à prova nativa `ce429153` (PASS em `odisseu-core`). |

---

## 4. ACL mask Linux + runbook pós-stop (item 3)

Ensaio descartável `debian:bookworm-slim` + pacote `acl`, ownership espelhando produção (`sess` `1001:1002`/`0700`, descendentes root, data root/`0755`, marker `0600`):

| Etapa | Resultado |
|-------|-----------|
| Antes ACL → UID 1000 | `SESSION_ACCESS_DENIED_PREFLIGHT=PASS` |
| `setfacl` + `mask::rwx` + `user:1000` | `ACL_MASK_PRESENT=PASS` · `ACL_PROBE=PASS` (histório `1000`/`0600`) |
| Pós-stop: `setfacl -x u:1000` / `-k` | `POST_STOP_REVERT_SESSION_DENIED=PASS` |
| Owners após revert | `1001:1002` preservados (`OWNERS_PRESERVED=PASS`) |
| Live / produção | **não tocado** |

Runbook pós-stop (preparado, não executado live): restaurar snapshot `getfacl`; não apagar sessão nem volume data; se janela aplicou ACL, reverter antes/depois conforme `docs/evidence-a6-homolog-ce429153/evidence/acl-commands.sh`.

---

## 5. Sessão WhatsApp de teste (item 4)

**AUSÊNCIA EXPLÍCITA DE EVIDÊNCIA.**

Nenhuma sessão WhatsApp de teste independente/autorizada encontrada nos paths verificados (`/tmp/zapbot-wa-test-session`, `…/a6-staging-session-test`, `~/.wwebjs_auth_a6_staging_test`, env `WWEBJS*`/`WA_TEST*`/`STAGING_ALLOW_QR`).
**Não** foi copiada nem montada sessão de produção. Autenticação/READY WhatsApp **não** revalidados nesta etapa.

---

## 6. Falhas `test:m4-jvm` vs base sem A+B+C (item 5)

| Árvore | Resultado |
|--------|-----------|
| `16b5987` (pré-A+B+C) | 34/37 · **3 fail** |
| Tip com A+B+C (`21771ec`/docs tip) | 34/37 · **3 fail** |
| Diff das linhas `not ok` | **idêntico** (`DIFF_EXIT=0`) |

Falhas (legado da base, não regressão A+B+C):

1. `comandos simulados: treinador e coleção formatados` (espera `/seu time/`)
2. `A→B→A por comando…` (espera `/ativo=a/`)
3. `formatError… troca` (espera `/não participa|própria conta/`)

---

## 7. Compose / overlay / cutover vs imagens RC (item 6)

| Artefato | Pin atual | Corresponde à RC gameplay? |
|----------|-----------|----------------------------|
| `docker-compose.a6-ai-drain-sandbox.overlay.yml` | `odisseu-node:a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64` (+ comentário digest `ce429153…`) | **NÃO** — deveria pin `…21771ec372eb…` / `@sha256:e7488e4f…` |
| `scripts/homolog-compose-overlay-check.sh` | exige substring `fefab4c` | **NÃO** alinhado à RC gameplay |
| Overlay redefine flags Pokémon? | Não (GYM/RAID/THEMATIC/REWARD/EVENT **ausentes**) | Preservação de flags: **OK por omissão** |
| `homolog-compose-overlay-check.sh` (user/seccomp/NNP/grace/volumes/flags) | **PASS** estrutural | Segurança overlay OK; **imagem errada** |

Isto **bloqueia** o pedido de janela até o pin ser atualizado (commit docs/ops na RC) **sem** aplicar live.

---

## 8. Sequência Node/JVM coordenada — preparada, **não executada** (item 7)

Ordem sugerida para janela futura (após GO humano + pins corretos):

### A. Pré-janela (ops)

1. Confirmar hold ainda `true` e workflow `CI/CD` `disabled_manually`.
2. Snapshot ACL sessão + data (`getfacl -pR`) em artefato protegido.
3. Backup frio/warm dos volumes Node data + bind de sessão + (se JVM entrar) Datomic — **scripts existentes; não rodar agora**.
4. Carregar tars RC na VM AMD64; verificar Image IDs `e7488e4f…` / `cf9a029c…`.
5. Atualizar overlay runtime para `image: odisseu-node@sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79`.
6. Reprovar `chrome://sandbox` nativo com volumes **descartáveis**.
7. `POKEMON_THEMATIC_EVENTS_ENABLED=false`; não alterar GYM/RAID/recompensas/eventos.

### B. Node (cutover típico Node-first)

1. Drain Node via gate/token **já existente** (não criar token novo).
2. Aguardar marker `quiescent` + exit limpo (`stop_grace` 60s).
3. Health pré-stop (leitura).
4. `compose … -f overlay up -d odisseu-a6-node` com digest pinado.
5. Health pós-start: UID 1000, WhatsApp READY (sessão autorizada), maintenance `open`.

### C. JVM (somente se a janela incluir gameplay JVM)

1. Backup Datomic do alvo.
2. Drain/maintenance gate JVM.
3. Recreate `odisseu-a6-jvm` com `odisseu-jvm@sha256:cf9a029c…`.
4. Health `:18090` + probe autenticado.
5. **Sem** migration/backfill/reset/seed.

### D. Rollback separado

| Camada | Rollback |
|--------|----------|
| Node | Voltar digest/imagem anterior (`1d00dd01…` breakglass ou `ce429153…` drain-sandbox); **não** apagar sessão/data; restaurar ACL do snapshot se aplicada |
| JVM | Voltar imagem JVM anterior; **não** tocar Datomic além do restore de backup se corrompido |
| Flags/eventos | Não reabrir nesta janela |

---

## 9. Flags (itens 8–9)

| Flag / classe | Estado validado |
|---------------|-----------------|
| `POKEMON_THEMATIC_EVENTS_ENABLED` | `false` no `.env.example`; não forçada pelo overlay |
| Ginásios / raids / recompensas / eventos | Overlay não redefine → **preservar valores live atuais** na janela |
| Deploy hold | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` (GitHub Actions variable; **não alterada**) |
| Workflow produção | `CI/CD` = `disabled_manually` (**não reabilitado**) |

---

## 10. Riscos residuais (atualizados)

### Bloqueantes para pedir janela

1. Overlay/check ainda pinam RC drain-sandbox antiga (`fefab4c`/`ce429153`), não gameplay `e7488e4f`.
2. Sandbox Chromium deste Image ID sem prova nativa AMD64.
3. Sem READY WhatsApp em sessão de teste independente.

### Altos (janela)

4. Pacote `acl` precisa existir no host live antes do `setfacl`.
5. `docker/chromium-seccomp.json` no working_dir do compose live.

### Médios

6. Raids legadas 1 Pokémon; estoque `great-ball`; pending mochila cheia; golpes antigos; learnset PokeAPI; sprites/CDN.
7. Três asserts legados `test:m4-jvm` (dívida da base `16b5987`).

### Baixos / intencionais

8. STAB ausente; crit fora da fórmula de raid; fallback `chanceComBola`.

---

## 11. Suítes desta validação final

| Suite | Resultado |
|-------|-----------|
| Labels git-sha/tree vs `21771ec` | PASS |
| Drain hashes + banned flags + UID 1000 na imagem | PASS |
| `test:a6-shutdown/maintenance/ai-drain-invariants` | 16/16 |
| `npm run test:ai` | 44/44 |
| ACL Linux + SESSION_ACCESS_DENIED + pós-stop | PASS |
| `test:m4-jvm` base≡HEAD (3 fails idênticos) | PASS (não-regressão) |
| Overlay estrutural | PASS; pin de imagem **desatualizado** |
| Sandbox `chrome://sandbox` Image `e7488e4f` nativo | **NÃO EXECUTADO** (emulação Mac FAIL) |
| WhatsApp READY teste | **AUSENTE** |

---

## 12. Checklist para virar GO de *pedido* de janela (§16)

- [ ] Commit na RC atualizando overlay + `homolog-compose-overlay-check.sh` para digest `e7488e4f…` / tag gameplay-audit.
- [ ] Prova `chrome://sandbox` nativa AMD64 no Image ID `e7488e4f…` (volumes descartáveis).
- [ ] (Recomendado) READY com sessão WA de **teste** nova/autorizada.
- [ ] Reconfirmar hold + workflow disabled imediatamente antes do pedido.
- [ ] Manter temática `false` e demais flags live intactas.

Só então: **GO para solicitar autorização** de janela (ainda **NO-GO** para executar deploy até GO humano da janela).

---

## 13. Referências

- Auditoria lotes: `docs/pokemon-a6-gameplay-audit.md`
- Drain/sandbox linhagem: `docs/ai-dynamic-content.md` · evidência `docs/evidence-a6-homolog-ce429153/`
- Homolog gameplay: `clojure -M:test-a6-homolog-abc`
- PR: https://github.com/caioclavico/zapbot/pull/36
