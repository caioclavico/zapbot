# Homologação integrada — PR #126 (hotfix A6)

**Decisão (fase 1):** `HOMOLOG_PARTIAL`  
**Decisão (fase 2 / atual):** `HOMOLOG_BLOCKED` (`STAGING_BLOCKED` + `WHATSAPP_E2E_BLOCKED`)  
**Deploy:** `DEPLOY_NO_GO` (sem autorização; hold ativo)  
**Produção:** inalterada (`c4deb6a` em execução)

## Identidade

| Campo | Valor |
|-------|-------|
| Master SHA homologado | `741034c517a5f583287826324d6a069ee67f3b7c` |
| Conteúdo aprovado do hotfix | `525e1bfcafcd5d751a79abacb4e0eae0b2544159` (ancestral de master) |
| Release live | `c4deb6a878fdc914a02532af54c169cb1c738dda` |
| Data (UTC) | 2026-11-23 |
| Node | v22.23.3 |
| JVM | OpenJDK 21.0.13 |
| Hold | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` |
| Auto-hatch prod | `ADVENTURE_AUTO_HATCH=false` (política operacional mantida) |

## Ambiente

| Item | Uso |
|------|-----|
| Tipo | Isolado local (host do engenheiro) |
| Datomic | Datomic Local descartável por namespace de teste (`.datomic-local/*-tests`) |
| Bridge smokes | M4.1/M4.2 com storage em `$TMPDIR` + token de maintenance sintético |
| WhatsApp | **Não utilizado** — `STAGING_WHATSAPP_CONFIRM` vazio, `DEV_GROUP_ID` vazio |
| Sessão staging | Arquivos em `.wwebjs_auth_staging/` existem, mas **sem confirmação explícita** → não acionados |
| Produção VM/volumes/sessão | **Não acessados** |

Identidade Datomic de homologação (exemplos sintéticos): sistemas `odisseu-egg-a6-tests`, storages de teste por suíte, e cenários in-memory de domínio (`homolog-r1`, players `ash`/`misty`).

## Snapshot inicial (sintético)

Antes dos cenários de domínio/raid potion:

- Treinadores/saves: criados sob demanda pelas suítes (não há base compartilhada de jogadores reais).
- Combates ativos: nenhum estado live; fixtures montam raid/caça por teste.
- Inventário de poção (cenário raid): declarado `2` Poções Máximas (débito validado nas suítes de store/TX, não no domínio puro).

## Matriz de cenários

| # | Cenário | Método | Resultado |
|---|---------|--------|-----------|
| A | Captura → +15 PI, sem PI antecipado na vitória | `egg-activity` + `adventure-activity` detect-targets + cenário isolado | **PASS** |
| B | Liberar → `:wild-victory` +15 | detect-targets `encounter-release` | **PASS** |
| C | Teto diário / crédito parcial | `egg-activity-a6-test` (`teto-diario-e-pending-inventory`) | **PASS** |
| D | Dois ovos incubando — divisão de PI | `aplicar-pontos-divide-entre-incubadoras` | **PASS** |
| E | Anúncios ovo/incubadora (sem revelar espécie) | present tests + egg suites | **PASS** (automatizado) |
| F | Raid `pocao-maxima` 12→120, revide 24 → 96; próximo turno 96→72 | cenário `raid-pocao-maxima-hp-persist` | **PASS** |
| G | HP sync party/coleção mid-raid | `raid-hp-sync-p0-a6-test` | **PASS** |
| H | PI em derrota via `raid-item` (event-id idempotente) | cenário + hotfix tests | **PASS** |
| I | Isolamento roteamento cacar/pvp/raid + ambiguidade | Node contract tests | **PASS** |
| J | Poções multi-contexto / mock HTTP `getEncounter` | `pokemon-hotfix-potion-http-contract` | **PASS** |
| K | Raids auto cadence / skip / duplicate | `raid-auto-cadence-fase1282-test` | **PASS** |
| L | Homolog fase4 auto/manual (processo isolado) | `raid-homolog-fase4-test` | **PASS** (114 assertions) |
| M | Reinício / recibos M4.2 timeout-reconcile | `npm run test:m4-jvm` | **PASS** |
| N | WhatsApp E2E comandos reais | — | **WHATSAPP_E2E_BLOCKED** |
| O | Suíte `raid-defeat-rewards-fase1283` | Clojure | **FAIL local** (também em `c4deb6a` — pré-existente, não regressão #126) |

## Evidências principais

### PI / ovos (cenário isolado)

- Vitória selvagem com `aguardando-captura?` → **sem** targets de PI.
- `hunt-complete` → `pi-granted = 15`, egg `40 → 55/100`.
- Liberar → activity `:wild-victory`.

### Poção Máxima na raid (domínio canônico)

| Passo | Valor |
|-------|-------|
| HP antes | 12/120 |
| HP após cura | 120/120 |
| `revide-chefe` | `ceil(120/5) = 24` |
| HP após revide | 96/120 |
| Turn index após poção | 2 |
| HP no ataque seguinte (após novo revide 24) | 72/120 |
| Turn index após ataque | 3 |

### Contrato HTTP

- Auditoria estática: **0** métodos CLJS ausentes no client.
- Poção top-level usa `getEncounter` → `.-encounter` (não `getActiveEncounter`).
- Ambíguo: sem POST de mutação.
- `raidUseItem` retry → `duplicate: true`.

### Artefatos gerados (fora do git)

- `/tmp/a6-pr126-homolog/isolated-datomic-report.txt`
- `/tmp/a6-pr126-homolog/tech-summary.txt` (`npm test|http|m4-jvm|build` = 0)
- `/tmp/a6-pr126-homolog/node-hotfix.log` (24/24)
- `/tmp/a6-pr126-homolog/raid-defeat-c4deb6a.log` (mesmas falhas que master)

Harness: `scripts/a6-pr126-homolog-isolated.clj`, `scripts/a6-pr126-homolog-run.sh`.

## CI técnico (local)

| Comando | Exit |
|---------|------|
| `git diff --check` | 0 |
| `npm test` | 0 |
| `npm run test:http` | 0 |
| `npm run test:m4-jvm` | 0 |
| `npm run build` | 0 |

## WhatsApp E2E

**Status:** `WHATSAPP_E2E_BLOCKED`

Motivo: homologação exige sessão/grupo de teste com `STAGING_WHATSAPP_CONFIRM=1` e `DEV_GROUP_ID` de teste. Sem essa autorização explícita, a sessão staging não foi iniciada e a de produção **não** foi usada.

Mensagens WhatsApp reais: **não verificadas**.

## Falhas / não-regressões

1. **`raid-defeat-rewards-fase1283-test`** — 6 fail + 1 error no ambiente local. Reproduzido em worktree `c4deb6a` (produção base). **Não atribuído ao hotfix #126.** Risco residual de suíte desatualizada/flaky de balanço; não bloqueia evidência dos cenários do #126 já cobertos por outras suítes.
2. **WhatsApp real** — pendente por política de isolamento.

## Riscos residuais

- Sem E2E WhatsApp, cards/menções/ordem de entrega não foram observados em canal real.
- `planPotionRoute` espelha o cljs (risco de drift se só um lado mudar).
- Suíte fase1283 de derrota continua vermelha localmente também na base de produção.
- Homologação não exercitou restart completo de containers Docker de staging (apenas smokes M4 e testes Datomic Local).

## Gate final

| Critério | Status |
|----------|--------|
| Cenários obrigatórios automatizados do #126 | Passaram |
| WhatsApp real isolado | Bloqueado |
| Deploy | Não executado |
| Produção / saves | Preservados |

**Decisão: `HOMOLOG_PARTIAL`**

Próximo passo para `HOMOLOG_PASS`: ambiente WhatsApp de teste autorizado + execução dos comandos reais da matriz, sem tocar produção.


---

## Fase 2 — WhatsApp E2E e riscos (2026-11-23)

### Auditoria de staging

| Gate | Resultado |
|------|-----------|
| Compose | `docker-compose.staging.yml` → project `odisseu-staging`, volume `odisseu-staging-datomic` (≠ prod cutover) |
| Sessão path | `WWEBJS_DATA_PATH=.wwebjs_auth_staging` / `clientId=staging` — scripts **recusam** `.wwebjs_auth` |
| `APP_ENV` | `staging` no `.env.staging` |
| `STAGING_WHATSAPP_CONFIRM` | **EMPTY** → `staging-up.sh node` exit 4 |
| `DEV_GROUP_ID` | **EMPTY** → exit 6 |
| `STAGING_GROUP_CONFIRMED` | **EMPTY** → exit 7 |
| Allowlist código | `config.cljs` + `group-allowlist.cjs`: staging exige `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED=1` |
| Tokens bridge | Presentes no `.env.staging` (não placeholder); **não** reutilizados de prod nesta auditoria |
| `ADVENTURE_AUTO_HATCH` | EMPTY no staging env (não ligado em prod; hold prod permanece) |
| Scheduler raid auto | Defaults OFF (`POKEMON_RAID_AUTO_TICK` / bridge tick) |
| Prod session path | `.wwebjs_auth` **ausente** neste host |
| Sessão staging files | Existem (~628 arquivos), mas **sem grupo autorizado confirmado** |

**Veredito ambiente:** `STAGING_BLOCKED` — requisitos essenciais de WhatsApp E2E não satisfeitos.  
**Ação tomada:** nenhum Node WhatsApp iniciado; nenhum QR; nenhum envio de mensagem.

Instrução para desbloquear (manual, conta de **teste**):

1. Descobrir grupo: `./scripts/staging-bootstrap-group.sh` (modo bootstrap, sem processar cmds).
2. Preencher `DEV_GROUP_ID` no `.env.staging`.
3. Definir `STAGING_GROUP_CONFIRMED=1` e `STAGING_WHATSAPP_CONFIRM=1`.
4. Opcional: `STAGING_ALLOWED_USERS=…` para remetentes.
5. Só então `./scripts/staging-integrated.sh` / `staging-up.sh node`.

### Matriz E2E WhatsApp (fase 2)

| Cenário | Status |
|---------|--------|
| Captura / PI / ovos E2E | `BLOCKED` |
| Liberar / ovo / incubadora E2E | `BLOCKED` |
| Raid pocao-maxima E2E | `BLOCKED` |
| Batalhas simultâneas E2E | `BLOCKED` |
| Raids auto/manual E2E | `BLOCKED` |
| Mensagem antiga / menções | `BLOCKED` |

### Suíte legada fase1283

Ver `docs/a6-pr126-fase1283-analysis.md`.

| Item | Resultado |
|------|-----------|
| Reproduz em master | Sim |
| Reproduz em `c4deb6a` | Sim |
| Regressão #126 | **Não** |
| Causa principal | Fixture Body Slam vs Onix com HP 320×n → derrota; asserções de vitória obsoletas |
| Impacto jogadores | Não evidenciado |
| Correção | Proposta em PR separado (fixtures), sem merge automático |

### Drift `planPotionRoute` ↔ `pokemon_http.cljs`

Mitigação na fase 2 (branch de evidências):

- Testes `anti-drift` em `test/pokemon-hotfix-potion-http-contract.test.cjs` amarram o oracle ao **fonte cljs** (`getEncounter`, `ambiguousBattlePrompt` kind potion, `raidUseItem`, handlers cacar/pvp) e aos exports do client.
- Runtime permanece em `pokemon_http.cljs`; o planner continua espelho para matrizes — drift agora falha CI se o cljs divergir dos contratos assertados.

| Item | Resultado |
|------|-----------|
| Risco residual | Reduzido (ainda não é single-source runtime) |
| Extração de módulo único | Não feita (evitar componente novo sem necessidade) |

### Evidências adicionais

- `/tmp/a6-pr126-homolog/fase1283-detail.log`
- `/tmp/a6-pr126-homolog/raid-defeat-c4deb6a.log`
- `docs/a6-pr126-fase1283-analysis.md`
- Testes anti-drift Node (16/16 no contrato de poções após acréscimo)

### Produção

| Item | Status |
|------|--------|
| Hold | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` |
| Release live | `c4deb6a` |
| Deploy / cutover / sessão live | **Não executados** |
