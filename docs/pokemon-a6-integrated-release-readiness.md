# Pokémon A6 — Release readiness / RC A+B+C+P0

**Atualizado:** 2026-11-08T06:40Z  
**Host:** `odisseu-core` (linux/amd64)  
**Branch integrada:** `rc/a6-ai-drain-sandbox` @ `18fa15f` (merge PR #37)  
**Evidência cutover:** `/home/caiohclavico/odisseu-recovery/a6-abc-p0-cutover-20261108T063319Z`

---

## 0. Veredito operacional

| Campo | Valor |
|-------|-------|
| Resultado | **SUCCESS — ODISSEU A6 GAMEPLAY A+B+C+P0 EM PRODUÇÃO** |
| PR | [#37](https://github.com/caioclavico/zapbot/pull/37) **MERGED** (`18fa15f`) |
| Node ativo | `sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade` |
| JVM ativa | `sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c` |
| WhatsApp | **READY** (listeners `hasSynced_*`) |
| Chromium | Debian **156.0.8078.79** · UID **1000** · CapEff **0** · NoNewPrivs **1** |
| Datomic | Volume `odisseu-a6-cutover-datomic` preservado (cold backup feito) |
| Flags | Temáticos/raids/ginásios/kit/permanência **preservados** (`true`; `STAGING_ALLOW_QR=0`) |
| Deploy hold / CI/CD | `ODISSEU_PRODUCTION_DEPLOY_HOLD=true` · workflow CI/CD `disabled_manually` (**não** alterados) |
| Migrations / backfill | **Não** executados |

---

## 1. Commits

| Commit | Papel |
|--------|-------|
| `9d2a345` | P0 raridade/captura, ginásio NPC, UI raid |
| `c5ab41d` | Chromium 156, hasSynced+listeners, HEALTH_BIND |
| `1a8eaf8` | Pins Image IDs + docs/verify |
| `18fa15f` | Merge PR #37 → `rc/a6-ai-drain-sandbox` |

Labels das imagens: `git-sha=c5ab41d…` · `content-tree=8428d03b…` (conteúdo executável homologado; tip merge é documental + pins).

---

## 2. Rollback registrado (pré-cutover)

| Camada | Image ID |
|--------|----------|
| Node | `sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c` |
| JVM | `sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c` |

---

## 3. Preflight (PASS)

- PR integrado; Image IDs RC íntegros no host; live = rollback IDs acima.
- WA READY sustentado; Node/JVM healthy; maint `open`.
- Hold `true`; CI/CD `disabled_manually`; runs ativos = 0.
- Disco liberado (76% após prune seguro de imagens a64 legadas).
- Sem migrations obrigatórias; temáticos ON compatíveis.

---

## 4. Backup / drain / ACL

| Etapa | Resultado |
|-------|-----------|
| Backup compose/meta/flags/AI history | PASS (`…/backup/`) |
| Cold sessão WA (1058 arquivos) | PASS pós-stop Node |
| Cold Datomic | PASS pós-stop JVM |
| Node drain → quiescent/durable | PASS |
| JVM drain → quiescent (`X-A6-Maintenance-Token`) | PASS |
| ACL u:1000 apply + probe rw | PASS |
| Writers simultâneos | Nenhum no mid-cutover |

---

## 5. Cutover

1. Overlay runtime pin Node `289a0407…` + JVM `7bc8f341…` + `HEALTH_BIND_HOST=0.0.0.0` (sem override de flags Pokémon).
2. JVM recriada → healthy / bridge up / 89 caps / flags `true`.
3. Resume JVM `open` **antes** do Node definitivo (evita `platform` fail-closed).
4. Node recriado → READY `2026-11-08T06:38:05Z` com `hasSynced_listeners_attached`.
5. Resume Node `open`.

---

## 6. Validação pós-cutover

| Check | Resultado |
|-------|-----------|
| Caps raid/capture/gym/learn/thematic | PASS (89; needs presentes) |
| `GET /v1/gyms` | PASS |
| `GET /v1/gyms/npc-roll` | Rota auth OK (`unknown_gym` com id fixture) |
| `GET /v1/raids/active` | Rota OK (`invalid_query` sem chat/player — esperado) |
| Present raridade/raid/npc-roll / sem `--no-sandbox` | PASS |
| AI history file | PASS |
| Sandbox CapEff/NNP/Chromium 156 | PASS |

Observação de estabilidade ≥20 min: ver §7 / evidência `logs/stability-20m.log`.

---

## 7. Estabilidade

| Campo | Valor |
|-------|-------|
| READY_AT | `2026-11-08T06:38:05Z` |
| Observação | `2026-11-08T06:39:20Z` → `06:59:04Z` (40× / 30 s) |
| Resultado | **STABILITY_20M_PASS** (`2026-11-08T06:59:34Z`) |
| Critério | READY + status ok + Image IDs RC + JVM healthy · sem QR · sem restart inesperado |
| Node | `sha256:289a0407…` · healthy · restarts=0 |
| JVM | `sha256:7bc8f341…` · healthy · restarts=0 |
| Health final | `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Evidência | `…/logs/stability-20m.log`, `stability-ok-at.txt`, `health-after-20m.json` |

---

## 8. Riscos residuais (live atual)

- Raids party-1 abertas na JVM antiga expiram; joins novos usam party ≤3.
- Janela exigiu resume JVM antes do Node final (marker quiescent persistido).
- Validação de gameplay foi **não destrutiva** (sem mutar saves/inventários de jogadores).
- Live ainda **não** inclui HP sync / bolas alias-debit / UI caça / moldura pokedex pessoal (candidata §10).

---

## 9. Referências

- Audit P0: `docs/pokemon-a6-raid-gym-capture-audit.md` · `docs/pokemon-a6-gameplay-audit.md` §P0
- Evidência: `a6-abc-p0-cutover-20261108T063319Z`
- Overlay runtime: `…/a6-cutover-runtime-20261101T141408Z/docker-compose.a6-ai-drain-sandbox.overlay.yml`

---

## 10. RC seguinte — HP/golpes/bolas/UI + Pokédex moldura (candidata)

**Status:** código homologado localmente; **sem deploy**. Live permanece `289a0407` / `7bc8f341`.

### 10.1 Escopo integrado

| Item | Incluído |
|------|----------|
| A+B+C+P0 cutover (base) | sim (ancestral `1a8eaf8` / live) |
| HP raid → coleção + leave + XP sem cura full | sim |
| Golpes string-key (Wartortle) + status válido | sim |
| `inv-qty` / débito alias (`great-ball`) + % motor | sim |
| UI raid = caça (emoji/mensagem) | sim |
| Pokédex pessoal = moldura espécie | sim |
| Drain / IA dinâmica / sandbox / hold | preservados (sem alteração) |

### 10.2 Homologação descartável

| Suite | Resultado |
|-------|-----------|
| `:test-a6-lote-a` | PASS |
| `:test-a6-lote-b` (+ `raid-hp-sync-p0-a6-test`) | PASS |
| `:test-a6-lote-c` | PASS |
| `:test-a6-homolog-abc` | PASS (213 asserts) |
| Node P0 / raid / pokedex | PASS |

Evidência HP: ataque → `hp-atual` ↓; leave preserva; vitória+XP `hp-atual` = snapshot (≠ max).

### 10.3 Produção (somente leitura)

| Campo | Valor (inspecionado) |
|-------|----------------------|
| Node live | `sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade` · healthy · restarts=0 |
| JVM live | `sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c` · healthy · restarts=0 |
| Health | `{"status":"ok","whatsapp":"READY","chromium":true}` |
| Rollback cutover | Node `05b36c5a…` · JVM `ad0b7c1b…` (§2) |

### 10.4 Imagens candidatas / pins

| Campo | Valor |
|-------|-------|
| PR | [#38](https://github.com/caioclavico/zapbot/pull/38) **MERGED** |
| Commit P0 | `400a5ffda862e409d62197a772bd7e6e123ec366` |
| Commit integrado (tip RC) | `6cef8f43b0dce617af0ecf0b0374f6a541388104` |
| Tree | `50babd2f4dc1e4bd1e83ca5b91a7ad2628773304` |
| Node tag | `odisseu-node:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64` |
| JVM tag | `odisseu-jvm:a6-p0-hp-ui-20261108T072116Z-6cef8f43b0dc-amd64` |
| Node Image ID | `sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305` |
| JVM Image ID | `sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60` |
| Labels | `git-sha=6cef8f43…` · `content-tree=50babd2f…` · platform `linux/amd64` |
| Node checks | UID **1000** · Chromium **156.0.8078.79** · sem `--no-sandbox` · `HEALTH_BIND` no bundle |
| JVM checks | Temurin **21.0.13** · curl health · modules presentes |
| Overlay / verify | `scripts/verify-a6-gameplay-rc-images.sh` pinado às candidatas |
| Rollback desta RC | **live atual** Node `289a0407…` / JVM `7bc8f341…` |

### 10.5 Riscos residuais (candidata)

- Parties de raid **já** em combate mantêm golpes slim antigos até leave/rejoin.
- Débito alias consome o primeiro ID com qty suficiente (canônico preferido).
- Sem smoke destrutivo em saves reais nesta etapa.
- ACL pós-stop / CapEff / NNP: revalidar na janela (mesmo procedimento do cutover §4–6); não reexecutado com restart aqui.

### 10.6 GO/NO-GO para solicitar janela

| Critério | Estado |
|----------|--------|
| Código + testes A+B+C+P0 local | **GO** |
| PR #38 integrado na RC | **GO** (`6cef8f4`) |
| Image IDs Node+JVM do mesmo commit | **GO** (`bec910bf` / `9e87bb6f`) |
| Overlay/verify/rollback documentados | **GO** (verify script; rollback=live) |
| Deploy hold / CI/CD intocados | **GO** (não alterados) |
| Tars carregados no host de produção | **PENDENTE** (ops de carga na janela) |
| **Pedido de janela** | **GO CONDICIONAL** — autorizar cutover só após `verify-a6-gameplay-rc-images.sh` PASS no host e ACL/preflight iguais ao §3–4 |
