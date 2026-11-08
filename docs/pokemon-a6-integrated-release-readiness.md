# Pokémon A6 — Release readiness / RC A+B+C+P0 + One-Mon + species-hp

**Atualizado:** 2026-11-08T22:10Z  
**Host build:** local macOS → imagens `linux/amd64` via `a6builder`  
**Live:** Combat UX + Eventos `3bc5076` (`619b4756`/`09e7605e`) — Species-HP/035/One-Mon/HP 320 intactos · StartedAt inalterado  
**Base RC integrada:** `github/rc/a6-abc-p0-integrated` @ `2afd997`  
**PRs desta fatia:** [#59](https://github.com/caioclavico/zapbot/pull/59) Explosion/liberar/1º frame · [#60](https://github.com/caioclavico/zapbot/pull/60) homolog testes  
**Docs:** `docs/pokemon-a6-explosion-wild-battle-rewards.md` · `docs/pokemon-a6-raid-first-frame-p1.md`  
**Veredito:** prep operacional host **GO** (SCP/load/VERIFY/pin) · **NO-GO** deploy/cutover/migrations/backfill/WA/hold/CI

---

## 0q. RC Explosion + Hunt + Raid 1º frame (2026-11-08T20:36Z→22:10Z) — build + prep host (**sem** deploy)

| Campo | Valor |
|-------|-------|
| Commit executável | `2afd9975ef695163a4d8f98eed632c644431c72f` (merge #60 sobre #59/`09dc5b5`) |
| Content-tree | `ad8a3cf41c4851237b535b15fad82e8e024b92f0` |
| PR #59 | Explosion HP0 · liberar · streak-unit-v1 · 1º frame raid · homolog visual |
| PR #60 | asserts Species-HP/Lote A · flake `:dodge` hunt-m45 · smoke área climática |
| Node tag / ID | `odisseu-node:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64` · `sha256:77d0d9a255125c74d59168713dc025c42d9d189d9bd7278bf1813be981464d2b` |
| JVM tag / ID | `odisseu-jvm:a6-explosion-hunt-raid-integrated-20261108T203624Z-2afd9975ef69-amd64` · `sha256:9443b80134bf5f2bb47009354b705785f065ff438c31de10496621a35d2198d1` |
| Tar SHA256 Node | `739e61fb04c1625a7f48afcab1e51be5ea020e85374d82942d502a11f137a100` |
| Tar SHA256 JVM | `152d5523faf06ebd65501db96976bca6197fdcbb401d603d1fe80cdd5b753f7c` |
| Chromium / UID | `156.0.8078.79-1~deb12u1` · `USER 1000:1000` · CapEff=`0` |
| VERIFY host | **PASS** (candidata ≠ live Combat UX) |
| Overlay | pinado candidata · bak `…overlay.yml.bak-pre-explosion-hunt-prep-20261108T220453Z` · prepared `…prepared-explosion-hunt-2afd997` · **não** aplicado |
| Artefatos locais | `artifacts/a6-explosion-hunt-raid-integrated-20261108T203624Z/` |
| Inbox host | `/home/caiohclavico/odisseu-recovery/rc-inbox/a6-explosion-hunt-raid-2afd997/` |

### Testes (homolog integrada)

| Suite | Resultado |
|-------|-----------|
| Explosion win-persist + autodesmaio | PASS |
| Hunt streak sim 10k (`streak-unit-v1`) | PASS — tabela inteira; moedas só ≥5ª |
| hunt-m45 unit 10× + smoke HTTP M4.5 | PASS (flake dodge eliminado; área climática) |
| capture-m33 (Species-HP Charmeleon 95/76) | PASS após assert alinhado |
| present formatError/Unavailable + commands | PASS após asserts Lote A |
| Raid first-frame Node + homolog sharp | PASS · **12/12** (com rede) |
| Species-HP / schema035 / freeze | PASS |
| One-Mon / opened-media / lote A+B+C / raridade `:legado` | PASS |
| Economy / bridge / gym / raid / PvP smokes | PASS |

### Economia / políticas preservadas

- `politica-recompensa-captura-versao = streak-unit-v1` (sem alteração vs docs homologados)
- Moedas UI exclusivamente **💰** (`textoMoedas`)
- `chance-surto = 25` · `raid/hp-por-jogador = 320` · raridade default `:legado`
- Species-HP v1 / schema 035 / One-Mon intactos

### Prep operacional odisseu-core (2026-11-08T20:53Z→22:10Z) — **sem cutover**

| Campo | Valor |
|-------|-------|
| Autorização | GO prep only (SCP→sha→inbox→load→VERIFY→pin) — **sem** deploy/drain/restart/migrations/backfill/flags/WA/hold/CI |
| Preflight (já concluído; **não** refeito) | Live Combat UX `619b4756`/`09e7605e` · WA READY · hold `true` · `deploy.yml` `disabled_manually` · rollbacks presentes · disco ~7G |
| SCP | staging `/tmp/a6-explosion-hunt-raid-2afd997-staging` · pid **826730** concluído · staging limpo |
| SHA256 inbox | **OK** (`IMAGES.sha256` + `REMOTE.sha256` — Node+JVM) |
| `docker load` | **PASS** — IDs `77d0d9a2…464d2b` / `9443b801…2198d1` |
| VERIFY | **PASS** · amd64 · labels `2afd997` / `ad8a3cf4` / `a6-explosion-hunt-raid-integrated` |
| Disco pós-load | **6.5G** livres · **87%** |
| Live Node | StartedAt=`2026-11-08T17:17:51.337099983Z` · Restarts=0 · Image=`619b4756…` · healthy |
| Live JVM | StartedAt=`2026-11-08T17:16:32.699831734Z` · Restarts=0 · Image=`09e7605e…` · healthy |
| Health | `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Overlay pin | active == prepared (`…2afd9975ef69-amd64`) · bak Combat UX `…bak-pre-explosion-hunt-prep-20261108T220453Z` · **compose up NÃO executado** |
| Rollback imediato | Combat UX live (imagens + bak overlay) |
| Evidências | `artifacts/…/prep-evidence/` (scp · checksum · load · verify · overlay · inbox-sha) |
| Cutover / restart / migrations / WA / hold / CI | **NÃO executados** |

### Riscos residuais

| Risco | Notas |
|-------|-------|
| Live ainda Combat UX | cutover requer GO operacional separado |
| Disco ~6.5G | margem justa; staging limpo; inbox ~636 MB ainda no host |
| Overlay on-disk já candidata | apply = `compose up` apenas; rollback = restaurar bak Combat UX |
| Homolog sprites | precisa rede (PokeAPI); fallback silhueta OK offline |

### Veredito

| Item | Status |
|------|--------|
| RC integrada reproduzível (Explosion + liberar + streak + 1º frame) | **GO** |
| Prep operacional no servidor (SCP/load/VERIFY/pin) | **GO** |
| Deploy / drain / restart / migrations / backfill / WA / flags / hold / CI | **NO-GO** |

---

## 0p. Lote A UX + modelo oficial de raridade (2026-11-08) — PRs #56 / #57

| Campo | Valor |
|-------|-------|
| Base | `7caf43e74e0b2acfab306d3511f4810355d91127` (`rc/a6-abc-p0-integrated`) |
| PR1 commit | `c5744c3` — `feature/a6-lote-a-ux` → [#56](https://github.com/caioclavico/zapbot/pull/56) |
| PR2 commit | `fe2a2a3` — `feature/a6-official-rarity` → [#57](https://github.com/caioclavico/zapbot/pull/57) |
| PR1 escopo | 💰 · efetividade `Super efetivo!🔥` · `!pk ajuda` + testes |
| PR2 escopo | catálogo oficial v1 · `rarity.clj` · hook `capture` · default **`:legado`** |
| Dependência PR1↔PR2 | **nenhuma** (arquivos disjuntos) |
| Testes PR1 | ux-help-coins · gameplay-present · lote-c · raid-gym-capture + fase12 (**20 pass**) |
| Testes PR2 | `official_rarity_a6_test` — **10 tests / 59 asserts / 0 fail**; `politica-ativa=:legado` |
| Compatibilidade | Species-HP / schema 035 / One-Mon / HP chefe 320 / economia / chance-surto 25% — **preservados** |
| Ativação raridade oficial | **NO-GO** (requer aprovação; `:oficial-a`/`:oficial-b` só sob binding) |
| Deploy / migrations / backfill / saves | **NÃO** |
| Riscos | mensagens JVM legadas até rebuild; UBs/sublendários na calibração B; PR3 docs após merge #56+#57 |

### Veredito próxima RC

| Item | Status |
|------|--------|
| Integrar Lote A (#56) na RC | **GO** |
| Homologar WhatsApp (💰 / efetividade / ajuda) | **GO** (pendente visual) |
| Ativar política oficial de raridade | **NO-GO** |
| Pedir janela de deploy desta fatia | **NO-GO** até merge + homolog |

---

## 0o. Prep operacional Combat UX + Eventos (2026-11-08T16:54Z→17:05Z) — **sem cutover**

| Campo | Valor |
|-------|-------|
| Autorização | GO preparação operacional (transfer/load/VERIFY/overlay) — **sem** deploy/restart/migrations |
| Live Node/JVM | Final `17520a1e…` / `c13140fa…` · healthy · Restarts=0 · WA **READY** |
| Commit executável RC | `3bc507679a4511aa850e5dd015e6bde56e38fca3` |
| Content-tree | `452ff59358a9530a2c358239eba3d1ac8510c2d6` |
| Node tag / ID host | `odisseu-node:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` |
| JVM tag / ID host | `odisseu-jvm:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` |
| SHA256 Node tar | `83796ce12151b5ed39050da7f293ee5c5e12053fb1665c72796b15c0974a537f` |
| SHA256 JVM tar | `48cb0e87c1712bc3a09e0719be83c0045d30048dd4d2ba722799dbd9e97ce22d` |
| SHA256 host | **OK** (bateu com local) |
| VERIFY host | **PASS** · amd64 · labels `3bc5076` / `452ff593` / `a6-combat-ux-events` |
| Disco antes → pós-load → final | **8.2G → 7.0G → 7.6G** livres (83%→86%→85%) |
| Hold / CI | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · `deploy.yml` **`disabled_manually`** · sem deploy concorrente |
| Overlay | pinado candidata · bak `…overlay.yml.bak-pre-combat-ux-events-prep-20261108T165411Z` · **não** aplicado |
| Rollback imediato | Species-HP Final (live atual) |
| Rollback extras | One-Mon + Balance A **preservados** no host |
| Schema 035 | **aplicado** (`migration-035? true`) |
| Saves | total **989** · `species-hp-v1` **984** · `legacy-unset` **5** · invalid-hp **0** |
| Combates ativos | batalhas **0** · raids **0** |
| Flags | preservadas (thematic/raid-auto/bag-kit/QR off) — sem mudança |
| Homolog candidata (sem volumes reais) | Wartortle legado · Explosion · surto 25% · 🪙 · revide · raid HP **320** · Chromium **156** · UID **1000** · **PASS** |
| Cutover / restart / migrations | **NÃO executados** |

### Riscos residuais (pré-janela)

- **5** Pokémon `legacy-unset` (sem `hp-formula-version` / sem `hp-base`) apareceram após o cutover Final — avaliar política na janela (converter aditivo vs. aceitar leitura legado).
- Disco ~7.6G livres — margem justa para cold backup + dual image no cutover; considerar liberar staging `/tmp` ou inbox antigos **só com GO**.
- Eevee ainda nos 4 pools de surto sob 25%.

### Veredito janela

| Item | Status |
|------|--------|
| Prep host completa | **GO** |
| Solicitar janela de deploy | **GO** (pronto para agenda) |
| Executar cutover agora | **NO-GO** (não autorizado) |

---
## 0n. RC Combat UX + Eventos 25% (2026-11-08T16:43Z→16:50Z) — **sem deploy**

| Campo | Valor |
|-------|-------|
| Autorização | GO integração local + build RC (**sem** deploy/restart/migrations) |
| Base | Species-HP Final `72bffdb` / tree `8b793ae1` (live) |
| Feature commits | `d35b58b` Combat UX · `8b37460` chance-surto 25% |
| Commit integrado | `3bc507679a4511aa850e5dd015e6bde56e38fca3` (merge PR #53) |
| Content-tree | `452ff59358a9530a2c358239eba3d1ac8510c2d6` |
| Branch RC | `rc/a6-combat-ux-events` ≡ tip `github/rc/a6-abc-p0-integrated` |
| Node tag / ID | `odisseu-node:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:619b4756b3bf0b92f75f5ac4e75d8b6d11783219f98faacc8328dd4423a03d1e` |
| JVM tag / ID | `odisseu-jvm:a6-combat-ux-events-20261108T164301Z-3bc507679a45-amd64` · `sha256:09e7605e94e6c01217df2366c3729470ff4bc7a73aaf23e289f72d8817129d40` |
| SHA256 tars | ver `artifacts/a6-combat-ux-events-20261108T164301Z/SHA256SUMS.txt` |
| Labels | `git-sha=3bc507679a45…` · `content-tree=452ff59358a9…` · `fase=a6-combat-ux-events` · **amd64** |
| Chromium / UID | **156.0.8078.79** · UID **1000** |
| Species-HP / One-Mon / HP 320 | **preservados** (sem diff em fórmula/schema035/aliases) |
| `chance-surto` | **25** (sim 10k pct=0.25) |
| Overlay | pinado em `docker-compose.a6-ai-drain-sandbox.overlay.yml` — **não** aplicado |
| VERIFY | **PASS** |
| Deploy / cutover | **NO-GO** |

### Testes

| Suite | Resultado |
|-------|-----------|
| `:test-a6-lote-b` (+ moves-audit + event-sim) | **PASS** |
| `:test-a6-lote-c` | **PASS** |
| `:test-a6-species-hp` (1421 asserts) | **PASS** |
| schema035 / freeze | **PASS** |
| Wartortle legado / present UX / raid 320 | **PASS** |

Detalhes combate/eventos: `docs/pokemon-a6-combat-ux-events-audit.md`.

---
## 0m. GO produção Species-HP Final (2026-11-08T15:26Z→15:54Z) — **SUCCESS**

| Campo | Valor |
|-------|-------|
| Autorização | **GO expresso** cutover + schema 035 + migrate + reopen |
| T0 | `20261108T151915Z` · 986 legado · 0 combate · hold `true` · deploy `disabled_manually` |
| Stamp cutover | `20261108T152613Z` |
| Backup | hot+cold Datomic · cold WA 1483 files · ACL u:1000 · `BACKUP_PRE_DRAIN`/`DRAIN_STOP_ACL` |
| Schema 035 | `pokemon/035-species-hp-formula` via `schema/install!` (idempotente) |
| Migrate pass1 | **converted 986** · quarantine **0** · fainted 20 preservados · full 952 · partial 14 |
| Migrate pass2 | **noop 986** · convert **0** · `IDEMPOTENT_NOOP=PASS` |
| Pós-migrate | todos `species-hp-v1` · `invalid-hp 0` · raid boss HP **320** · One-Mon `max-party=1` |
| Live Node/JVM | `sha256:17520a1e…2a0` / `sha256:c13140fa…3ee` |
| WhatsApp | **READY** @ `2026-11-08T15:32:39Z` · Chromium **156** · UID **1000** · CapEff **0** · NNP **1** |
| Flags | preservadas (thematic/raid/gym/kit/QR) |
| Gates | Node reaberto após validate · JVM bridge open · `RESUME=PASS` |
| Ponto sem retorno | **atingido** em `CHECKPOINT_PRE_V1=2026-11-08T15:28:26Z` — forward-fix Species-HP |
| Observação | `OBSERVE_START=15:33:49Z` → `15:54:37Z` · **41/41 READY** · fail **0** · `STABILITY_20M_PASS` · `CUTOVER_RESULT=SUCCESS` |

**Nota operacional:** no cutover, a bridge JVM precisa estar `open` antes do boot WhatsApp do Node (platform fail-closed sob maintenance). Gate de jogo ficou no Node até validate. Runbook atualizado.

---

## 0l. Preflight final Species-HP (2026-11-08T15:08Z→15:15Z) — **sem cutover/freeze live**

| Campo | Valor |
|-------|-------|
| Autorização | **GO preflight RO + ensaios isolados** |
| Live Node/JVM | `sha256:617525d9…304b1` / `sha256:4a75eaa2…816b` · healthy · restarts **0** · Started inalterado |
| RC Node/JVM | `sha256:17520a1e…2a0` / `sha256:c13140fa…3ee` · amd64 · labels `72bffdb` / `8b793ae1` / `a6-species-hp-final` |
| VERIFY | **PASS** (containers ativos **não** modificados) |
| Rollback imgs | One-Mon · Balance A · species-hp pré-aliases — **presentes** |
| WhatsApp / bridge | **READY** · JVM **up** · maint `open` · `activeRequests=0` |
| Chromium / UID / CapEff / NNP | **156** · UID **1000** · CapEff **0** · NoNewPrivs **1** · seccomp ativo (Node) |
| Overlay | Final pinado · **não** aplicado |

### GitHub (autenticado; GH_TOKEN env inválido contornado via keyring)

| Check | Resultado |
|-------|-----------|
| `ODISSEU_PRODUCTION_DEPLOY_HOLD` | **`true`** (Actions variable; updated 2026-11-05) |
| `deploy.yml` state | **`disabled_manually`** |
| Runs `in_progress` / `queued` | **0** / **0** |
| Residual | `AUTO_DEPLOY_ENABLED=true` — **mitigado** enquanto workflow permanecer `disabled_manually` (pré-condição da janela) |

### Disco e orçamento de pico

| Item | Estimativa |
|------|------------|
| Livre agora | **~8.55 GiB** (82% used; inodes 5%) |
| Cold WA (sessão 297 M ×2 + margem) | ~0.6 G |
| Cold Datomic (30 M ×2 + margem) | ~0.1 G |
| Schema 035 + migrate working + evid/logs | ~0.3 G |
| Margem de segurança | **2.0 G** |
| **Pico total estimado** | **~3.5 G** |
| Após pico | ~5 G livres |
| Veredito disco | **SUFICIENTE** (não exige 10 G arbitrários; expansão = conforto opcional) |

### Auditoria RO atual (hot copy; agregados)

| Métrica | Valor (2026-11-08T15:11Z) |
|---------|---------------------------|
| Total Pokémon | **986** (era 984 na prep — reconsultar em T0) |
| `legacy-unset` / `species-hp-v1` | **986** / **0** |
| com `hp-base` | **0** |
| full / partial / fainted | **952** / **14** / **20** |
| Raids / batalhas ativas | **0** / **0** |
| Quarentena formas (ensaio) | **0** |

### Freeze / drain (inspeção + ensaio isolado; **não** ativado em prod)

| Item | Resultado |
|------|-----------|
| Enforcement operacional | **maintenance-gate** `acquire!` fail-closed em **todas** as rotas da bridge |
| Background | `raid-auto` tick + idle-sweeper também passam por `acquire!` |
| Smoke isolado | **PASS** (draining→quiescent→resume; mutating blocked; reads allowed) |
| Checklist `species-hp-freeze` | classifica subset HP-mutating; ops writeish fora da lista (shop/missions/…) **ainda bloqueadas pelo gate** |
| Prod | freeze **não** ativado |

### Schema 035 + migração (Datomic descartável)

| Cenário | Resultado |
|---------|-----------|
| Schema 035 aditivo / idempotente / legado OK | **PASS** |
| Ensaio migrate (export anon) | convert **982** · quarantine **0** · skip **2** |
| **A** falha antes do 1º lote | 100% legado → rollback imagens One-Mon **seguro** |
| **B** falha mid-backfill | resume **PASS** (78→904) · 2ª passagem `noop=982` · faint=0 · proporção OK · **preferir forward-fix JVM Species-HP** |
| **C** pós-backfill completo | integridade v1 **PASS** · One-Mon **não** assumido seguro se recalcular max |
| Regra | **nunca** restaurar cold antigo automaticamente sobre progresso novo |

### Funcional isolado pós-fórmula

species-hp 1421 · homolog A+B+C+One-Mon 247 · tm/xp/evo · raid/gym/bridge · PvP A5.2 — **PASS** · raid boss **320**

### Runbook definitivo (futura janela — não executar agora)

1. Preflight T0 (IDs, hold/CI, disco, RO counts, battles/raids=0)  
2. Freeze via maintenance enter (draining→quiescent)  
3. Cold Datomic + cold WA  
4. Schema 035  
5. Cutover coordenado JVM+Node (overlay Final)  
6. Migração lotes idempotente  
7. Verify agregados + amostra faint/partial  
8. WA READY + health  
9. Resume gates  
10. Observação ≥20 min  

**Ponto sem retorno One-Mon:** após o **primeiro lote** convertendo saves para `species-hp-v1`. Antes disso, rollback = repin One-Mon.

| Veredito | |
|----------|--|
| Submeter janela à autorização humana | **GO** |
| Executar cutover/schema/backfill agora | **NO-GO** |

---

## 0k. Prep operacional Species-HP Final (2026-11-08T14:48Z→15:05Z) — **sem cutover**

| Campo | Valor |
|-------|-------|
| Autorização | **GO prep only** (transfer/load/VERIFY/pin; **sem** deploy/drain/restart/schema live/backfill/WA/flags) |
| Commit / tree | `72bffdb63be62d8b35e61bcc75c0436949b6f189` · `8b793ae169ace916ec37c0b7350ce68e8404d3fa` |
| Live Node/JVM | `617525d9…` / `4a75eaa2…` · **healthy** · Started **11:24Z / 11:19Z** (inalterado) |
| WhatsApp | **READY** `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Bridge | **up** · maintenance `phase=open` · caps intactas |
| Disco antes | **81%** · avail **9 656 292 KB** (~9.2 G) · **DISK_OK** (≥6 G margem) |
| Pico (stage+inbox) | **84%** · ~8.0 G (antes do load) |
| Após `docker load` | **84%** · avail **8 332 416 KB** (Δ layers ~45 MB) |
| Após limpeza stage | **82%** · avail **8 968 540 KB** (~8.6 G) |
| SHA256 local=host | **OK** Node `37d36b15…cae8` · JVM `c17706cf…f399` |
| `docker load` | **PASS** |
| Image IDs Engine | Node `sha256:17520a1e…2a0` · JVM `sha256:c13140fa…3ee` · **amd64** · labels `72bffdb` / `8b793ae1` / `a6-species-hp-final` |
| `VERIFY_A6_GAMEPLAY_RC_IMAGES` | **PASS** |
| Overlay | pinado Final no runtime cutover · bak `…overlay.yml.bak-pre-species-hp-final-prep-20261108T145105Z` · **não** aplicado (containers One-Mon) |
| Rollback imgs presentes | One-Mon · Balance A · species-hp pré-aliases |
| Hold / CI | última confirmação prep `134333Z`: hold **true** · CI **`disabled_manually`** · in_progress=0; revalidação gh nesta sessão **401** (não alterado no host) |
| Schema 035 / backfill / cutover | **não** executados |

### Auditoria RO pré-janela (hot copy; agregados)

| Métrica | Valor |
|---------|-------|
| Pokémon | **984** |
| `legacy-unset` | **984** (100%) |
| `species-hp-v1` | **0** |
| com `hp-base` | **0** |
| full / partial / fainted | **952** / **12** / **20** |
| Raids ativas / batalhas ativas | **0** / **0** |
| Quarentena formas (ensaio prévio c/ aliases) | **0** |

### Plano documental de cutover + migração (**não executar**)

1. Preflight RO (health, hold, disco ≥ margem cold+rollback, battles/raids=0).  
2. Bloquear ops que mutam HP (`species-hp-freeze` via maintenance-gate).  
3. Drain → quiescent (sem matar TX em voo).  
4. Backup cold Datomic + snapshot cold WhatsApp.  
5. Schema **035** (aditivo).  
6. Cutover coordenado JVM+Node (overlay Final) — sem reabrir jogo se código/schema/HP incompatíveis.  
7. Migração idempotente saves (`plano-registro`; batches 25–50; skip combate).  
8. Validar integridade (faint=0, proporção, raid boss 320, noop na reexecução).  
9. WA READY + health.  
10. Resume gates + observação ≥20 min.

### Rollback por estágio

| Estágio | Ação | Seguro? |
|---------|------|---------|
| **Antes do backfill** (só imagens/código; saves ainda `legacy-unset`) | Repin overlay → One-Mon `617525d9`/`4a75eaa2` + compose up | **Sim** (attrs 035 opcionais ignorados pelo código antigo) |
| **Backfill parcial** | Retomar lotes (idempotente); **não** restaurar cold automaticamente sobre progresso novo; se abortar: cold da janela **somente** com GO + confirmação de zero progresso útil | Condicional |
| **Backfill completo** (`species-hp-v1`) | Rollback só de **código** para One-Mon **não** é seguro se o código legado recalcular max sem checar versão — preferir forward-fix ou cold da janela com GO | **Não** assumir One-Mon seguro |
| Nunca | Restaurar snapshot antigo sobre saves já convertidos sem auditoria | Proibido |

Checksums tars (local = host):
- Node `37d36b152903d59e675006cabceb1a983ae0be66b4de698b8439303e03edcae8`
- JVM `c17706cf0b0dc885559228e95ec51ba521520dcfbff3d03b2793fa5ca906f399`

| Veredito | |
|----------|--|
| Solicitar autorização da **janela** cutover+migração | **GO condicional** (disco ~8.6 G — preferível ≥10 G antes do cold; revalidar hold/CI via API; battles/raids=0 agora) |
| Cutover / schema live / backfill agora | **NO-GO** |

Detalhes migração: `docs/pokemon-a6-species-hp-migration-plan.md`.

---

## 0j. RC Species-HP Final (2026-11-08T14:33Z→14:45Z) — build + homolog (**sem** deploy)

| Campo | Valor |
|-------|-------|
| Autorização | **GO** integração/build/testes/artefatos locais — **sem** cutover/schema live/backfill/restart/drain/WA |
| Branch / tip | `rc/a6-species-hp-final` ≡ `github/rc/a6-abc-p0-integrated` @ **`72bffdb`** |
| PRs | #45 (fórmula) · #46 (pins) · **#50** (form-aliases + schema035/freeze) |
| Content-tree | `8b793ae169ace916ec37c0b7350ce68e8404d3fa` |
| Stamp | `20261108T144109Z` |
| Node tag / ID | `odisseu-node:a6-species-hp-final-20261108T144109Z-72bffdb63be6-amd64` · `sha256:17520a1ec6fcb88687c2f208b8cd276c384944f01cde57a3b0793c217532e2a0` |
| JVM tag / ID | `odisseu-jvm:a6-species-hp-final-20261108T144109Z-72bffdb63be6-amd64` · `sha256:c13140fa9965b8cb78faa7edc1d95a95a5abc3ed1287203554e65748e52683ee` |
| Tar SHA256 Node | `37d36b152903d59e675006cabceb1a983ae0be66b4de698b8439303e03edcae8` |
| Tar SHA256 JVM | `c17706cf0b0dc885559228e95ec51ba521520dcfbff3d03b2793fa5ca906f399` |
| Labels | `git-sha=72bffdb…` · `content-tree=8b793ae1…` · `fase=a6-species-hp-final` · **amd64** |
| Chromium / UID | **156.0.8078.79** · UID **1000** · chrome-sandbox setuid · sem `--no-sandbox` |
| One-Mon / raid boss | `max-party=1` · `hp-por-jogador=320` intactos |
| Schema 035 | na imagem JVM · homolog descartável **PASS** · **não** live |
| Freeze | maintenance-gate **PASS** · **não** altera prod |
| Overlay | pinado Final em `docker-compose.a6-ai-drain-sandbox.overlay.yml` · **não** aplicado |
| VERIFY | **PASS** (IDs antigos species-hp + live na deny-list) |
| Disco build local | ~91 G livres · **DISK_OK** (build não em produção) |
| Hold / CI / flags / WA | **não** alterados |

### Regressões Final

| Suite | Resultado |
|-------|-----------|
| species-hp | **PASS** 22 / 1421 |
| homolog A+B+C+One-Mon | **PASS** 42 / 247 |
| schema035 / freeze | **PASS** / **PASS** |
| ensaio migrate (export RO) | convert **982** · quarantine **0** · noop **982** · resume **PASS** · faint=0 · boss 320 |
| tm-xp / evo | **PASS** |
| hunt unit + HTTP `lago` | **PASS** (`praia` rotação = `area_unavailable`, não HP) |
| raid / gym / bridge / PvP / eggs | **PASS** |
| Node hunt/a2/gym + One-Mon UX + maint | **PASS** |

| Veredito | |
|----------|--|
| Prep operacional no servidor (SCP/load/VERIFY/pin) | **GO** |
| Cutover / schema 035 live / backfill / restart / drain | **NO-GO** |

Detalhes: `docs/pokemon-a6-species-hp-balance.md` · `docs/pokemon-a6-species-hp-migration-plan.md`.

---

## 0i. Bloqueios migração fechados (2026-11-08T14:22Z→14:30Z) — **só local**

| Campo | Valor |
|-------|-------|
| Autorização | **GO implementação/homolog local** (sem deploy/cutover/schema live/backfill) |
| Formas resolvidas | **22/22** via `species-form-aliases` (explícito) |
| Quarentena restante (ensaio) | **0** |
| Ensaio v2 | 984 dataset · convert **982** · skip **2** · idempotente **PASS** |
| Schema 035 | validado em Datomic descartável · **não** em prod |
| Freeze | maintenance-gate + `species-hp-freeze` · **PASS** |
| Regressão | species-hp 1421 · homolog 247 · raid/gym/bridge/tm-xp/maint **PASS** · hunt HTTP `area_unavailable` (rotação; unit hunt PASS) |
| Nova RC | **GO** → executada em §0j |
| Cutover / migrate live | **NO-GO** |

Detalhes: `docs/pokemon-a6-species-hp-migration-plan.md`.

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
