# A6 — Incidente WhatsApp RECOVERING + recuperação controlada

**Host:** `odisseu-core` (us-central1-a)
**Quando (UTC):** incidente `2026-11-08T01:26Z` → recuperação `03:32Z`–`03:43Z`
**Escopo:** diagnóstico RO + **recuperação operacional** (drain → stop → snapshot frio → `docker start` mesma imagem). Sem troca de imagem, overlay gameplay, ACL write, deploy RC, migration, restore Datomic ou alteração de flags.

---

## Sumário executivo

**Antes:** Node `unhealthy`, WhatsApp **`RECOVERING`** (inject stuck pós-reload ~01:33, loading 75%), Chromium vivo, JVM/Datomic healthy, maint `open`, imagem `eefacb92…`.

**Depois:** WhatsApp **`READY` estável**, health Docker **`healthy`** / `FailingStreak=0`, mesma Image ID, sessão LocalAuth **preservada** (boot **sem** novo QR), JVM intocada, flags Pokémon preservadas (`temáticos/ginásios/raids/recompensas` = `true`).

**Causa proximal (diagnóstico):** `whatsapp-startup` reentrou em `inject()` após reload da página WA Web e ficou em `recovering` sem concluir loading/READY. Sem evidência de `auth_failure`/`disconnected`.

**Resultado da recuperação:** **SUCESSO**. Observação pós-READY **~8 min** (32 amostras / 0 ruins) — janela maior que o ciclo de falha anterior (~5 min). **Nova intervenção imediata: não necessária.** Deploy RC gameplay permanece **NO-GO** até decisão separada; risco residual de novo inject/RECOVERING na mesma imagem permanece como dívida de código.

---

## 11. Recuperação executada (2026-11-08)

### 11.1 Estado antes / depois

| Item | Antes (preflight ~03:32Z) | Depois (pós-observe ~03:42Z) |
|------|---------------------------|------------------------------|
| Image ID | `sha256:eefacb920e88a82a8093889758b9ff237251e639bd9cf8f97c0a00bfadbe2b53` | **mesma** |
| Tag | `odisseu-node:a6-ai-drain-sandbox-chromium155-getchatfix-20261108T001331Z-16b5987-amd64` | **mesma** |
| WhatsApp | `RECOVERING` | **`READY`** |
| Health HTTP | `degraded` | **`ok`** |
| Docker health | `unhealthy` | **`healthy`** (`FailingStreak=0`) |
| Chromium | vivo (1 browser + zygotes) | vivo (1 browser + zygotes; `user-data-dir=…/session-core`) |
| Maintenance Node | `open` → drain `quiescent` → resume `open` | **`open`** (`resumedAt=2026-11-08T03:34:21.133Z`) |
| JVM / bridge | `ok` / `up` / maint `open` / `raidAutoTickEnabled=true` | **inalterado** (container `odisseu-a6-jvm` não reiniciado) |
| QR no boot | (incidente original tinha QR 01:27) | **nenhum** QR nos logs; artefatos `qr-*` com mtime ainda `01:27` |
| Overlay / UID / seccomp / volumes | — | **não alterados** (`docker start` do container existente) |

### 11.2 Snapshot protegido

| Item | Resultado |
|------|-----------|
| Root | `/home/caiohclavico/odisseu-recovery/a6-wa-recovery-snapshots/20261108T033214Z` (`0700`, root) |
| Metadados | `session-acls.txt`, `session-tree-pre.txt`, compose + `.env` snapshot (`0600`), health/JVM/flags before |
| Cópia fria sessão | **após** drain + `docker stop` + Chromium `lsof=0` |
| Tamanho | `179M` cold == `179M` live bind |
| Conteúdo sensível | **não** impresso em logs; sem cópia para `/tmp` world-readable |
| LocalAuth live | **não apagado**; Datomic **não** restaurado/tocado |
| SingletonLock | ausente no live e no snapshot no momento da cópia |

### 11.3 Drain

| Item | Resultado |
|------|-----------|
| API | `POST /ops/maintenance/enter?timeoutMs=60000` (token via env; **não** logado) |
| HTTP | **200** |
| Body | `quiescent=true`, `phase=quiescent`, `activeOperations=0`, `durable=true` |
| Marker | `{"phase":"quiescent","startedAt":"…03:32:50…","quiescentAt":"…03:32:50…"}` |
| Operações pendentes | nenhuma impeditiva |

### 11.4 Restart

| Passo | Resultado |
|-------|-----------|
| Encerrar Node | `docker stop -t 60 odisseu-a6-node` → **ExitCode=0** em ~1 s (`03:33:07`→`03:33:08Z`) |
| Chromium | `chrome_lsof_lines=0`; sem processo na sessão |
| Snapshot frio | concluído (acima) |
| Reinício | `docker start odisseu-a6-node` (`03:33:32Z`) — **mesmo** container/Image ID |
| Overlay novo | **não** aplicado |
| JVM | **não** modificada |
| Boot WA | `Inicializando` → (RECOVERING transitório de inject) → `Autenticado` / loading 100% → **`READY` `03:33:53Z`** |
| Resume | `POST /ops/maintenance/resume` → `phase=open` (`03:34:21Z`) |

### 11.5 Validação e estabilidade

| Check | Resultado |
|-------|-----------|
| WhatsApp READY | **sim** (`{"status":"ok","whatsapp":"READY","chromium":true}`) |
| Estabilidade | **~486 s** de observação; **32** amostras READY/`ok`; **0** ruins; sem reentrada em inject/loading/`RECOVERING` nos logs filtrados |
| Chromium funcional | processo principal Chromium 155 + perfil `session-core` |
| Maintenance | Node `open`; JVM `open` |
| Bridge JVM | `status=up`, capabilities ginásio/raid/temáticos presentes |
| Ginásios | `POKEMON_GYM_CHALLENGES_ENABLED=true` (Node env preservado) |
| Raids | `POKEMON_RAID_AUTO_TICK=true` (Node + JVM `raidAutoTickEnabled=true`) |
| Recompensas | `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`; `POKEMON_BAG_KIT_DAILY_ENABLED=true` |
| Eventos temáticos | **`POKEMON_THEMATIC_EVENTS_ENABLED=true`** (Node + JVM) — valor pré-incidente preservado |
| Comandos básicos | canal READY + maint `open` (admissão restaurada); `/diagnostics` sem operações pendentes |
| Recorrência inject→RECOVERING | **não observada** na janela de 8 min |

### 11.6 Flags preservadas (amostra)

Antes = depois (printenv Node):

- `POKEMON_THEMATIC_EVENTS_ENABLED=true`
- `POKEMON_GYM_CHALLENGES_ENABLED=true`
- `POKEMON_RAID_AUTO_TICK=true`
- `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true`
- `STAGING_ALLOW_QR=1` (**ainda** ligado; recomenda-se GO separado para `0` após READY estável)

### 11.7 Necessidade de nova intervenção

| | |
|--|--|
| Intervenção imediata | **Não** — READY estável na imagem atual |
| Se voltar RECOVERING | **Não** loop de restart; coletar evidências; planejar incidente de código/`inject` (Etapa 5) |
| Deploy RC gameplay (`e7488e4f` / `21771ec`) | Continua **NO-GO** até janela própria; esta recuperação **não** autoriza cutover |
| Opcional (auth separada) | `STAGING_ALLOW_QR=0` para endurecer restore |

---

## 1. Identidade live no diagnóstico RO

| Item | Valor |
|------|-------|
| Container | `odisseu-a6-node` |
| Image ID | `sha256:eefacb920e88a82a8093889758b9ff237251e639bd9cf8f97c0a00bfadbe2b53` |
| Tag | `odisseu-node:a6-ai-drain-sandbox-chromium155-getchatfix-20261108T001331Z-16b5987-amd64` |
| Chromium | 155.0.8059.79 · UID efetivo 1000 |
| Started (pré-rec) | `2026-11-08T01:26:48Z` · `RestartCount=0` |
| Health (pré-rec) | `unhealthy` · body `RECOVERING` |
| JVM | `odisseu-a6-jvm` healthy · bridge `up` · maint `open` |

---

## 2. Timeline READY → RECOVERING (incidente)

| UTC | Evento |
|-----|--------|
| 01:26:49 | Health HTTP sobe; `Inicializando…` |
| 01:26:59 | **QR Code gerado** (`STAGING_ALLOW_QR=1` → `restoreOnly=false`) |
| 01:28:03–09 | Loading 100% → Autenticado |
| 01:28:13 | **READY** (“conectado e pronto”) |
| 01:28–01:32 | Sessão ativa |
| **01:33:10** | `onState('recovering')` ×2 |
| 01:33:17–22 | Loading 0%→75%; **sem** novo READY |
| ~03:32 | Preflight ainda `RECOVERING` |

### Interpretação (código)

`scripts/lib/whatsapp-startup.cjs` define `state('recovering')` sempre que `client.inject` é (re)entrado. Reload da página WA Web ~01:33 → inject incompleto → health preso em `RECOVERING` com Chromium ainda vivo.

---

## 3–6. Diagnóstico RO (resumo)

- Sessão LocalAuth em bind `…/a6-cutover-session-from-core/session-core`; ACL `u:1000`; um Chromium writer.
- Disco `/` ~85% · 7.3 G livres.
- Sem `auth_failure`/`disconnected` no período stuck.
- Temáticos live `true` (vs RC homologada `false`) — **não** alterados na recuperação.
- JVM/Datomic healthy durante todo o incidente; corte era o canal WhatsApp.

---

## 7. Procedimento (executado)

Preferência 2 concluída: drain HTTP → `docker stop` → snapshot frio → `docker start` mesma imagem → resume → observação >5 min.

Rollback (não necessário): snapshot em `…/a6-wa-recovery-snapshots/20261108T033214Z/session-core-cold` + Image ID `eefacb92…`; JVM intocada.

---

## 8. O que esta recuperação **não** foi

- Não foi deploy da RC A+B+C (`21771ec` / `e7488e4f`).
- Não foi migration/backfill/reset Datomic.
- Não alterou flags temáticas/raids/ginásios.
- Não removeu deploy hold / CI disable (quando presentes na config de release).
- Não apagou LocalAuth nem forçou QR.

---

## 9. GO / NO-GO atualizado

| Decisão | Veredito |
|---------|----------|
| Recuperação operacional (mesma imagem) | **EXECUTADA — sucesso** |
| Cutover Chromium 157 (`e7488e4f`) | **NO-GO permanente** (falhou; rollback SUCCESS) |
| Solicitar janela Node-only WA (`446dfc2b` + JVM live) | **GO** — ver §13; **EXECUTADA — SUCCESS** (§14) |
| Cutover gameplay Node+JVM sem READY prévio | **NO-GO** |
| Nova intervenção WA agora (produção) | **NÃO** — produção em `05b36c5a…` / READY + listeners (§14) |

---

## 10. Evidências (sem segredos)

- Diagnóstico: timeline 01:26–01:33; health `RECOVERING` contínuo até drain.
- Recuperação: drain 200 quiescent; stop Exit 0; start Image ID idêntico; READY 03:33:53Z sem QR; observe 8 min PASS.
- Artefato host: `…/a6-wa-recovery-snapshots/20261108T033214Z/` (ACLs, trees, cold session, health/jvm/flags before/after).
- Código de referência: `scripts/lib/whatsapp-startup.cjs`, `src/zapbot/core.cljs`, `src/zapbot/whatsapp_saude.cljs`.
- P1: §12 abaixo; readiness `docs/pokemon-a6-integrated-release-readiness.md`.

---

## 12. P1 — Cutover RC falhou + correção (sem novo deploy)

**Quando (UTC):** cutover ~04:03Z → rollback SUCCESS; correção P1 ~04:35–04:45Z.  
**Restrições respeitadas:** sem deploy/restart de produção; sem sessão real em teste; sem JVM/Datomic/migrations/flags/hold/CI.

### 12.1 Cutover e rollback (contexto)

| Item | Valor |
|------|-------|
| Node RC aplicada | `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| JVM RC | `sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| Sintoma | Auth + loading 100% → **RECOVERING** sem `READY.` |
| Overlay | **sem** `HEALTH_BIND_HOST=0.0.0.0` → health no host inacessível |
| Rollback | Node `eefacb92…` + JVM `ad0b7c1b…` → READY; produção **intocada** após P1 |

### 12.2 Diff determinante live vs RC falha

| Camada | Live `eefacb92` | RC `e7488e4f` | Classificação |
|--------|-----------------|---------------|---------------|
| Browser | Debian **chromium 155** | **Google Chrome 157** | **Regressão / causa proximal** |
| `whatsapp-startup.cjs` | SHA idêntico | idêntico | OK |
| package-lock / wwjs / puppeteer | idênticos | idênticos | OK |
| main.js | gensyms + A+B+C | quase igual | Não explica WA stuck |
| Overlay HEALTH_BIND | (produção via env) | omitido no cutover | Ops — só listen HTTP |

### 12.3 HEALTH_BIND_HOST — prova

- Usado só por `zapbot.config` → `whatsapp_saude/servir!` (`server.listen`).
- **Não** entra em inject/READY/LocalAuth/Puppeteer.
- Isolado P1: sem bind → host connection reset, health **interno** ok; com `0.0.0.0` → host ok. WA state independente.
- Teste: `test/health-bind-host.test.cjs`.

### 12.4 READY ausente / stuck RECOVERING

- `installStartupRecovery` põe `recovering` a cada `inject()`; READY da app só após inject concluir.
- Logs cutover: autenticado / loading 100% / recovering; **sem** `READY.`.
- Hipótese confirmada por eliminação: browser Chrome 157 incompatível com caminho inject→READY desta stack (wwjs/puppeteer iguais ao live).
- Instrumentação: `whatsapp_startup_stage` (`inject_*`, `ready_deferred_*`, `ready_accepted`, …) — sem watchdog de restart infinito.

### 12.5 Correção + nova Image ID

| Campo | Valor |
|-------|-------|
| Fix Dockerfile | Debian Chromium **156.0.8078.79** (155 ausente no apt) |
| Tag | `odisseu-node:a6-gameplay-chromium156-p1-20261108T043525Z-21771ec372eb-amd64` |
| Image ID | `sha256:446dfc2bdd7aba4f79c1e9e1e27590373ae4539be46a71aaefd69635743a6043` |
| Tar SHA-256 | `f33b0eb1158fc37fa9b670a869c54753459b3b8523086b63a4223e7d782624a0` |
| `e7488e4f` | **não** reutilizada |

### 12.6 Validação isolada AMD64

UID 1000 · CapEff 0 · NoNewPrivs · seccomp · overlay com HEALTH_BIND · volumes descartáveis · Chromium 156 presente · health host OK.  
**`WA_READY_TEST_NOT_AVAILABLE`** — sem sessão de teste autorizada; sessão de produção **não** montada em paralelo.

### 12.7 Testes

`whatsapp-startup` + hung/deferred · health-bind · `test:resources` 84/84 · `test:ai` 44/44 · overlay homolog · drain/sandbox invariants — PASS.  
Falhas preexistentes `test:m4-jvm` (base 16b5987): documentadas, fora do escopo P1.

### 12.8 Veredito P1

| # | Entrega |
|---|---------|
| 1 | Diferença determinante: **Chrome 157 vs Debian chromium** (não wwjs/startup/lock) |
| 2 | Causa: browser RC; HEALTH_BIND **não** causal de RECOVERING |
| 3 | HEALTH_BIND: só reachability do endpoint |
| 4 | Isolado: boot + health + sandbox OK; READY real N/A |
| 5 | Testes unitários/recursos/IA PASS |
| 6 | Novo Image ID `446dfc2b…` |
| 7 | Prep: **GO solicitar janela Node-only WA**; cutover gameplay coordenado permanece separado |

---

## 13. Preparação pós-P1 (sem deploy) — 2026-11-08T04:52Z

### 13.1 Conteúdo da imagem `446dfc2b`

| Check | Evidência |
|-------|-----------|
| Commit gameplay | Label `io.zapbot.git-sha=21771ec372eb2e6623f67a8e3ea2f5c334dd4006` |
| Content tree | `7645f59f7445f58d0f733aa578a4296bd342fa63` |
| IA dinâmica | `/app/scripts/lib/ai-client.cjs` + `gemini`/`ai-client` no bundle |
| Graceful drain / maint | `a6-maintenance.cjs` + `a6-shutdown.cjs` + `createShutdown` no bundle |
| UID / sandbox | USER `1000:1000`; smoke CapEff=`0`, NoNewPrivs=`1`, seccomp host profile |
| Browser | Debian Chromium **156.0.8078.79** · sem `google-chrome-stable` |

### 13.2 Overlay / verify

- Overlay: image tag P1, Image ID `446dfc2b…` no comentário, JVM `cf9a029c…` documentada, `HEALTH_BIND_HOST=0.0.0.0`, **sem** override de flags Pokémon.
- `verify-a6-gameplay-rc-images.sh`: Node `446dfc2b` + JVM `cf9a029c` + commit `21771ec`.
- Homolog compose isolado: **PASS**.

### 13.3 Chromium 155↔156

Mesmo executável path, mesmos args Puppeteer da app, mesmos wwjs/puppeteer, mesma LocalAuth (`session-core`). Única delta material: **versão do pacote Debian** (+ stages no startup).

### 13.4 Homolog isolado refeito

Chromium smoke · HEALTH_BIND A/B · ACL pós-stop · JVM RC health · sandbox AMD64 · produção READY intocada — **PASS**.  
`WA_READY_TEST_NOT_AVAILABLE` (sem sessão teste independente; não montar prod em dois processos).

### 13.5 Node novo × JVM antiga

Superfície cliente Node live↔cand **idêntica** nos marcadores JVM. Caps bridge: RC ⊆ live (`ONLY_RC=[]`).  
→ Janela **Node-only WA** contra JVM `ad0b7c1b` é **compatível**.  
→ Cutover **parcial** como release gameplay A+B+C: **proibido** (JVM RC necessária para o pacote A+B+C).  
→ Flags live (incl. `POKEMON_THEMATIC_EVENTS_ENABLED=true`) preservadas; overlay não as altera.

### 13.6 GO/NO-GO solicitação de janela

| Pedido | Veredito |
|--------|----------|
| Solicitar janela Node-only WA (`446dfc2b` + JVM live + rollback `eefacb92`) | **GO** → **EXECUTADA (§14)** |
| Cutover completo Node+JVM agora | **NO-GO** (JVM RC / A+B+C ainda separado) |
| Reaplicar Chrome 157 / `e7488e4f` | **NO-GO** |

---

## 14. Janela Node-only WA — SUCCESS (2026-11-08)

**Escopo:** só Node; JVM live `ad0b7c1b…` intocada; sem migrations/flags/hold/CI.

### 14.1 Tentativas e correções

| Passo | Image ID | Resultado |
|-------|----------|-----------|
| Base P1 Chromium 156 | `446dfc2b…` | base OK; sozinha ainda podia stick em RECOVERING pós-auth |
| Catch-up `hasSynced` → LoadUtils + sync | `74118090…` | **READY** rápido, mas **sem** `attachEventListeners` → bot não respondia no grupo |
| Catch-up + ClientInfo + `attachEventListeners` + emit ready | `sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c` | **READY** + `hasSynced_listeners_attached` |

Tag live: `odisseu-node:a6-gameplay-chromium156-hasynced-p1-20261108T052337Z-21771ec372eb-amd64`  
Evidências: `/home/caiohclavico/odisseu-recovery/a6-node156-wa-validation-20261108T045335Z/` (`stability-ok-at.txt`).

### 14.2 Causa do “READY sem resposta”

O catch-up antigo, ao ver `hasSynced` true com `window.WWebJS` ainda indefinido, injetava LoadUtils e forçava sync/READY **sem** passar pelo caminho wwjs que chama `attachEventListeners`. Health ficava `ok`/`READY`, mas `message_create` não era ligado.

### 14.3 Validação pós-deploy (`05b36c5a`)

| Check | Resultado |
|-------|-----------|
| Stages | `hasSynced_probe` → `hasSynced_loadutils_inject` → `hasSynced_clientinfo` → **`hasSynced_listeners_attached`** → `hasSynced_catchup_ready_emit` → `READY.` |
| Health | `{"status":"ok","whatsapp":"READY","chromium":true}` · Docker `healthy` |
| Maint | drain → recreate → resume **`open`** |
| JVM | `sha256:ad0b7c1b…` **inalterada** |
| Estabilidade | **≥20 min** READY/`ok`/`healthy` (40 amostras / 30 s; `STABILITY_OK_20MIN` ~05:24–05:44Z) |
| Rollback para `eefacb92` | **não** necessário |

### 14.4 Veredito

| Decisão | Veredito |
|---------|----------|
| Janela Node-only WA (Chromium 156 + catch-up listeners) | **SUCCESS** |
| Produção Node | `05b36c5a…` |
| Produção JVM | `ad0b7c1b…` (live) |
| Cutover gameplay Node+JVM (`cf9a029c`) | **ainda NO-GO** até janela coordenada A+B+C |