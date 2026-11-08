# Pokémon A6 — Homologação integrada A+B+C / Release readiness

**Atualizado:** 2026-11-08T03:00Z
**Worktree:** `/private/tmp/zapbot-a6-gameplay-audit`
**Escopo:** integração Git + construção/homologação de RC. **Sem deploy produção, migrations live, backfill, force push, reset ou alteração de saves/ginásios/raids live.**

---

## 0. Entrega da RC (resumo executivo)

| Item | Valor |
|------|-------|
| Commit A+B+C | `ca06cff69f13c060c37f7af030940abf68ded3f8` |
| PR | https://github.com/caioclavico/zapbot/pull/36 (**MERGED**) |
| Commit integrado (merge) | `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` |
| Base RC pré-merge | `16b59876b448323a870c74a2dddfa27416685273` |
| Content tree | `7645f59f7445f58d0f733aa578a4296bd342fa63` |
| Branch RC | `rc/a6-ai-drain-sandbox` @ `21771ec` |
| CI GitHub Actions | Sem checks no PR (workflow `deploy.yml` só dispara em `master`) — **CI local completo PASS** |
| Staging isolado | JVM em `127.0.0.1:18092`, Datomic volume descartável, **sem** sessão WhatsApp |
| Image Node | ver §15 |
| Image JVM | ver §15 |
| **GO/NO-GO deploy produção** | **NO-GO** |
| **GO/NO-GO RC candidata** | **GO** (pronta para janela futura; flag temática permanece off) |

---

## 1. Commits / base de cada componente

| Componente | Commit | Notas |
|------------|--------|-------|
| Base drain+sandbox+imagem Node | `16b5987` | Tip pré-integração de `rc/a6-ai-drain-sandbox` |
| Lotes A+B+C | `ca06cff` | Único commit de gameplay audit |
| Integração | `21771ec` | Merge commit do PR #36 (non-ff) |
| Schema temático | `032-thematic-events` já na base | **Nenhuma migration nova** |
| Branch publicada no GitHub | `rc/a6-ai-drain-sandbox` criada no remoto a partir de `16b5987` (não existia no origin GitHub) | Justificativa: base necessária para PR; tip idêntica à RC local pré-existente |

---

## 2. Arquivos do commit A+B+C (34)

Config: `.env.example`, `deps.edn`
Domínio: `battle`, `capture`, `damage`, `moves`, `raid`, `shop`
App/infra: `bridge_tx`, `raid_store`, `store`
Node: `pokemon-jvm-media`, `pokemon-jvm-present`, `pokemon-pokeapi`
Docs/smokes/testes: audit + readiness + homolog A/B/C + Node present/sprite

**Não tocados:** `a6-shutdown.cjs`, `a6-maintenance.cjs`, overlay Compose drain/sandbox, Dockerfile(s) de runtime, workflows de deploy hold, código de IA dinâmica.

Hashes drain (inalterados vs RC anterior):

- `a6-maintenance.cjs` SHA-256 `04b5b61d082a5dc8669c3633f0c8e611fa7e2740c7dab714f21964e90e7e4d80`
- `a6-shutdown.cjs` SHA-256 `16dae2a86eb98437316bdc67406c230095e395fb9cfaf048ad79ec6c2383ad68`

---

## 3. Confirmação A/B/C

Marcadores A1–A4, B1–B5, C1–C3 presentes no tip `21771ec` (ver auditoria `docs/pokemon-a6-gameplay-audit.md`).

---

## 4. Suítes / CI executados

| Suite | Resultado |
|-------|-----------|
| `clojure -M:test-a6-homolog-abc` | **PASS** 32 testes / 213 asserts |
| Node `pokemon-a6-*.test.cjs` | **PASS** 3/3 |
| `npm run test:resources` | **PASS** 80/80 |
| `npm run test:ai` | **PASS** 44/44 |
| A6 shutdown+maintenance+AI drain invariants | **PASS** 16/16 |
| Overlay compose isolado | **PASS** |
| `clojure -M:test-m3-raid` | **PASS** |
| `clojure -M:test-m4-capture` | **PASS** |
| `npm run test:http` | **PASS** 287/287 |
| Deploy script unit (`test-ci-changes` / `test-ci-ssh` / registry) | **PASS** |
| `npm run test:m4-jvm` | **34/37** — 3 falhas **pré-existentes em `16b5987`** (não regressão A+B+C): asserts legados `/seu time/`, `/ativo=a/`, trade `formatError` |
| GitHub Actions no PR #36 | Nenhum check configurado para base ≠ `master` |

---

## 5. Sequência homologada (Datomic descartável)

Missão evento → claim idempotente → inventário Grande Bola → captura → batalha + `chances` → raid faint+reserva → reopen save/evento. Raid com reserva validada. Flag temática só redefinida em processo de teste.

---

## 6. Staging isolado (sem WhatsApp de produção)

| Campo | Valor |
|-------|-------|
| Imagem | `odisseu-jvm:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Container | `odisseu-a6-gameplay-rc-jvm` |
| Porta | `127.0.0.1:18092` → 18090 |
| Datomic | volume `odisseu-a6-gameplay-rc-datomic` (descartável; system `odisseu-a6-gameplay-rc`) |
| Sessão WA | **não usada** (`WHATSAPP_TRANSPORT` não ligado; profile Node não iniciado) |
| `POKEMON_THEMATIC_EVENTS_ENABLED` | `false` (confirmado via `printenv` no container) |
| Health | `{"ok":true,"status":"up","maintenance.phase":"open","raidAutoTickEnabled":false}` |
| Smoke HTTP | `POST /v1/trainer/ensure` → created; `GET /v1/inventory` → mochila vazia capacidade 50 |
| Nota config | Exige `ODISSEU_BRIDGE_MAINTENANCE_TOKEN` junto do marker em `storage/.a6-maintenance` |

Node staging com Chromium/WhatsApp **não** foi levantado (restrição explícita: sem reutilizar sessão de produção).

---

## 7. Schema / config / dependências

| Item | Status |
|------|--------|
| Migration nova | Nenhuma |
| `032-thematic-events` | Já na árvore; não reexecutar se já aplicada no alvo |
| Env | `POKEMON_THEMATIC_EVENTS_ENABLED=false` |
| Deploy hold / CI produção | **Não modificado** |

---

## 8. Idempotência / saves

Claim duplicate, receipts M3/M4 e reopen de save/party/missão homologados. Sem backfill. Staging criou save `a` sintético em DB exclusivo.

---

## 9. Problemas encontrados

| # | Problema | Tratamento |
|---|----------|------------|
| 1 | Trailing whitespace docs bloqueou `git diff --check` | Removido antes do commit |
| 2 | `gh` no sandbox sem keyring | Execução com permissões adequadas |
| 3 | Staging JVM falhou sem `ODISSEU_BRIDGE_MAINTENANCE_TOKEN` | Reinício com token sintético |
| 4 | Build Node em background abortou | Rebuild foreground OK |
| 5 | 3 falhas `test:m4-jvm` | Pré-existentes na base `16b5987`; fora do escopo A+B+C |

---

## 10. Riscos residuais (gravidade)

### Alta (só se mal configurado na janela)

- Ativar eventos temáticos sem GO separado / schedule+tick
- Deploy produção sem GO humano

### Média

- Raids legadas com 1 Pokémon
- Estoque histórico `great-ball`
- Recompensas pending (mochila cheia)
- Golpes antigos sem `:autodesmaio`
- Learnset PokeAPI incompleto
- Reidratação sprites / CDN
- 3 asserts legados `test:m4-jvm` (dívida da base RC)

### Baixa / intencional

- STAB ausente; crit fora da fórmula de raid
- Fallback `chanceComBola` residual

---

## 11. Plano de integração Git (executado)

1. Commit `ca06cff` em `fix/a6-gameplay-audit-impl` (sem amend).
2. Publicação de `rc/a6-ai-drain-sandbox` @ `16b5987` no GitHub (branch remota inexistente).
3. Push `fix/a6-gameplay-audit-impl` + PR #36 → base RC.
4. Merge **non-ff** → `21771ec`.
5. FF local da RC; backup push para origin path local.
6. Build imagens **somente** com working tree limpa @ `21771ec`.

---

## 12. Plano de rollback

| Cenário | Ação |
|---------|------|
| Regressão pós-merge | Reverter merge `21771ec`; voltar tip `16b5987` / imagens drain-sandbox anteriores (`ce429153…` / tag `…fefab4c…`) |
| Só gameplay | Hotfix pontual; party EDN v1 compatível |
| Eventos | Manter flag `false` |
| Staging | `docker rm` + `docker volume rm` do volume descartável |

---

## 13. GO / NO-GO

### Construção da RC: **GO** (concluída)

### Janela de implantação em produção: **NO-GO**

| Critério deploy | Status |
|-----------------|--------|
| Commit integrado identificável | GO (`21771ec`) |
| Imagens reproduzíveis com labels/digest | GO |
| Drain/sandbox/IA intactos na imagem Node | GO (hashes + banned flags vazios + uid 1000) |
| Staging JVM isolado health+HTTP | GO |
| Flag temática off | GO |
| CI Actions em `master` / aprovação produção | Pendente — **não** reabilitado neste GO |
| Smoke WhatsApp staging com sessão de teste | Pendente (propositalmente não ligado) |
| Migrations/backfill/prod restart | **Proibidos** neste GO |
| Autorização humana de janela | Ausente → **NO-GO deploy** |

### Próxima etapa objetiva

1. Agendar janela com GO humano explícito de produção.
2. Opcional: smoke Node staging com **sessão WA de teste nova** (nunca produção).
3. Opcional: alinhar 3 asserts legados `test:m4-jvm` em PR separado.
4. Manter `POKEMON_THEMATIC_EVENTS_ENABLED=false` até GO separado de eventos.
5. Só então carregar tars/imagens na VM e seguir runbook de cutover — **fora deste documento executar**.

---

## 14. Referências

- Auditoria: `docs/pokemon-a6-gameplay-audit.md`
- Homolog: `clojure -M:test-a6-homolog-abc`
- Taxas captura: wild 78/58/28/14/5/3 · raid 35/35/35/20/8/4 · bolas/Frambo ×1/1.5/2/1.5 · teto 95%
- PR: https://github.com/caioclavico/zapbot/pull/36

---

## 15. Identidade das imagens RC

Stamp: `20261108T024926Z` · Commit: `21771ec372eb2e6623f67a8e3ea2f5c334dd4006` · Tree: `7645f59f7445f58d0f733aa578a4296bd342fa63` · Platform: `linux/amd64` · Builder: `a6builder`

### Node

| Campo | Valor |
|-------|-------|
| Tag | `odisseu-node:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Image ID / digest local | `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| Config digest (export) | `sha256:4c7c23f3d089ce6056e5f436fc94d03db83db29f05ff703ea6f253ecb8baedfa` |
| Manifest digest (export) | `sha256:e7488e4f4e6d4dca43f375bf41c8bdaea8f11bc60477fe9276d3ab2920f2de79` |
| Tar | `/tmp/odisseu-node-a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64.tar` (371 MB) |
| SHA-256 tar | `878ba298cba5e4434225032e108fb95fc139926c7242ed383e08ae4998e706e7` |
| Runtime | Node 22.23.4 · Chrome 157.0.8103.38-1 · USER 1000:1000 |
| Sandbox | sem `--no-sandbox` / `--disable-setuid-sandbox` no `target/main.js` |

### JVM

| Campo | Valor |
|-------|-------|
| Tag | `odisseu-jvm:a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64` |
| Image ID / digest local | `sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| Config digest (export) | `sha256:9dae9bb51548279d600d58383bff88db5931cd1fe4cc3d10a061df624d6bed7d` |
| Manifest digest (export) | `sha256:cf9a029cfb1e79a3c066ccfb0bc0f6bd21f2dc0ad29a82e08c613f32c0d42652` |
| Tar | `/tmp/odisseu-jvm-a6-gameplay-audit-20261108T024926Z-21771ec372eb-amd64.tar` (249 MB) |
| SHA-256 tar | `c048e8e8c517aef87dbe4ddfe4d0616a5869e96ec138676e59331d6ec460787e` |

Labels comuns: `io.zapbot.git-sha=21771ec…` · `io.zapbot.content-tree=7645f59f…` · `io.zapbot.fase=a6-gameplay-audit` · `io.zapbot.source-base=16b5987…`
