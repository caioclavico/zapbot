# A6 — Fase 12.3.1: Hotfix crítico de raides (golpe fatal + sprite Onix)

**Data (UTC):** 2026-11-01  
**Branch:** `hotfix/a6-raid-fase12.3.1` (isolada; base pós-Fase 12.2 / `fcd1ec9`)  
**Artefatos RC:** `~/odisseu-recovery/a6-fase12.3.1-rc-20261101T161034Z/`  
**Git SHA:** `e4dffcf257702b1355a0f9f05ed9b450d20eb300`  
**Modo:** correção + testes + RC imutável — **sem** deploy, recreate, restart, migrate, escrita em saves live ou auto-heal de raides.

Produção live (Fase 12.2) permanece intocada nesta fase.

---

## Decisão: **READY**

| Gate | Resultado |
| --- | --- |
| Causa raiz golpe fatal (`Compare failed: 0 vs nil`) | **PASS** (comprovada + corrigida) |
| Vitória única + recompensas idempotentes | **PASS** |
| Sprite Onix/Starmie sem caixa preta | **PASS** |
| Suites combate / economia / save-slots / outbox / M4.9 / homolog | **PASS** |
| Schemas/migrations | **intactos** (nenhuma migration nova) |
| Deploy / recreate / mutate saves live | **não executado** |
| Auto-heal / cancelamento de raide live | **não executado** |

`ALLOW_ALL_GROUPS=false` e raides automáticas **OFF** permanecem obrigatórios no próximo GO.

---

## 1. Causas raiz

### 1.1 BUG CRÍTICO — `Conflito ao gravar (outra ação no mesmo save)` no golpe fatal

**Sintoma (piloto WhatsApp):** ataques normais ok; no golpe que zera o HP do chefe → conflito de gravação; combate não conclui para o jogador.

**Cadeia:** WhatsApp → Node → bridge HTTP → `raid-attack!` → domínio (HP→0, fase `vitoria`) → TX Datomic com liquidação de recompensas (ledger operacional) → CAS em `:save/coins-operational`.

**Evidência reproduzida (Datomic sintético):**

1. Saves de cutover/recovery com `:save/economy-status` = `"unknown-pending"` **e** atributo `:save/coins-operational` **ausente** (`nil` no entity map — distinto de `0`).
2. Domínio/`with-ledger-defaults` trata operacional ausente como saldo lógico `0`.
3. `apply-delta-tx` gerava `[:db/cas … :save/coins-operational 0 after]` (crédito da raide, tipicamente +50).
4. Datomic: `Compare failed: 0 vs nil` → mapeado para `WRITER_FENCE_CONFLICT` / mensagem ao jogador *“Conflito ao gravar…”*.
5. **Não** era corrida real entre dois ataques no incidente principal: o CAS comparava `0` com atributo inexistente. Corrida no último turno continua protegida por turn-index / fase (um commit, outro rejeitado).

**Não observado:** commit silencioso com resposta de conflito; duplicação de recompensa no caminho do recibo idempotente (`operation-id` estável).

### 1.2 BUG VISUAL — Onix com caixa preta

**Causas conjugadas:**

1. **Asset/DTO:** chefes de ginásio em `raid.clj` **não** carregavam `:imagem` (artwork com alpha). O cartão caía em placeholder ou sprite sem pipeline explícito de alpha.
2. **Pipeline Sharp:** `spriteProporcional` não garantia `ensureAlpha`; falhas de decode podiam degradar sem placeholder seguro; composite do cartão não fixava `blend: 'over'`.

Artwork oficial PokeAPI (PNG com canal alpha) + `ensureAlpha` + resize `contain` com fundo `{alpha:0}` elimina o retângulo preto sobre o fundo roxo do ginásio (`#312e81`).

Evidências: `tmp-raid-hotfix-1231/onix-card-before.png` (simulação flatten preto) vs `onix-card-after.png` / `starmie-card-after.png`.

---

## 2. Arquivos alterados

| Arquivo | Papel |
| --- | --- |
| `modules/pokemon/infrastructure/.../economy_store.clj` | CAS `nil→after` quando operacional ausente; bootstrap de status separado |
| `modules/pokemon/domain/pokemon/domain/raid.clj` | `:imagem` estático (dex) nos chefes de ginásio |
| `scripts/lib/pokemon-jvm-media.cjs` | `ensureAlpha`, placeholder seguro, `blend: 'over'` no cartão |
| `deps.edn` | alias `:test-raid-hotfix-fase1231` |
| `modules/pokemon/tests/.../raid_hotfix_fase1231_test.clj` | regressão golpe fatal / race / artwork |
| `apps/odisseu/src/odisseu/smoke/raid_hotfix_fase1231.clj` | smoke runner |
| `test/pokemon-jvm-raid-hotfix-fase1231.test.cjs` | regressão visual Onix/Starmie/fallback |
| `docs/pokemon-a6-raid-hotfix-fase12.3.1.md` | este relatório |

**Não alterados:** migrations, save-slots, allowlist, scheduler auto, schema Datomic, motor de recompensa paralelo.

---

## 3. Solução

### Combate / economia (Etapa A atômica já existente)

Reutiliza a TX única de ataque+vitória+créditos:

- Se `:coins-operational` **ausente:** um único `[:db/cas sk :save/coins-operational nil after]` (+ earned/spent nil→valores).
- Se presente: CAS `before→after` como antes.
- `unknown-pending`: **não** espelha em `:save/coins` legado (placeholder histórico preservado).
- Recibo de ledger por `operation-id`+role; retry do mesmo op → `:duplicate`, sem novo crédito.
- CAS de fence/revision real continua rejeitando concorrência genuína — **sem** retry cego nem novos operation-ids.

Liquidação permanece no motor existente (sem segundo motor). Etapa B (reliquidação) = reenvio com mesmo `operation-id` / leitura de recibo.

### Imagem

- URLs estáticas `official-artwork/{dex}.png` nos templates (sem fetch extra por golpe).
- Sharp: `ensureAlpha` + contain transparente; asset inválido → placeholder SVG (sem throw).
- Composite do cartão com `blend: 'over'` sobre SVG opaco do ginásio.

---

## 4. Testes executados

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-hotfix-fase1231` | 4 testes / 28 assert · **PASS** |
| `clojure -M:test-raid-fase12` | 8 / 25 · **PASS** |
| `clojure -M:test-raid-lobby-fase2` | 23 / 76 · **PASS** |
| `clojure -M:test-raid-auto-fase3` (outbox/scheduler) | 19 / 76 · **PASS** |
| `clojure -M:test-raid-homolog-fase4` | 13 / 114 · **PASS** (`staging-touched? false`) |
| `clojure -M:test-raid-save-slots-reconcile` | 3 / 13 · **PASS** |
| `clojure -M:test-a6-economy-capacity` | economy 9/37 · capacity 9/72 · idle 10/47 · **PASS** |
| `clojure -M:test-m4-raid` (M3.6/M4.9 + smoke HTTP) | **PASS** |
| `node --test test/pokemon-jvm-raid-hotfix-fase1231.test.cjs` | 3 / 3 · **PASS** |
| `node --test test/pokemon-jvm-raid-fase12.test.cjs` | 8 / 8 · **PASS** |

Cobertura hotfix JVM: operacional ausente + golpe fatal; 1 HP→0; race último turno; retry mesmo `operation-id`; artwork Onix/água; `:save/coins` legado imutável em `unknown-pending`.

---

## 5. Evidências

- Repro automatizado: `strip-operational!` + combate até vitória → **antes** CAS `0 vs nil`; **depois** fase `vitoria`, `rewards-paid?`, +50 operacional idempotente.
- Visuais: `tmp-raid-hotfix-1231/onix-card-{before,after}.png`, `starmie-card-after.png`.
- Produção: **somente** consultas RO autorizadas se/quando necessário; **nenhuma** mutação de raide/save live nesta fase.

---

## 6. Riscos residuais

| Risco | Tratamento |
| --- | --- |
| Raide live já travada no golpe fatal (pré-hotfix) | **Não** auto-heal; auditoria RO + GO explícito futuro |
| Saves com operacional ausente ainda em prod | Hotfix torna o primeiro crédito operacional correto; não altera histórico |
| Deploy parcial (só Node ou só JVM) | Proibido — Node (media) + JVM (CAS/artwork DTO) coordenados |
| CDN artwork indisponível | Placeholder seguro; cartão pode ser null → fallback textual já existente |

---

## 7. Compatibilidade Node / JVM

| Lado | Mudança | Contrato |
| --- | --- | --- |
| JVM | Ledger CAS + `:imagem` no chefe do DTO de abertura | JSON existente; campo `imagem` preenchido |
| Node | Pipeline Sharp do cartão | Consome `imagem` / species; sem mudança de rotas |

Ordem de deploy futuro: **JVM → Node** (igual Fase 12.2). Rollback: tags live 12.2 (`82e1a06c…` / `dbcb6c02…`).

---

## 8. Impacto em persistência e economia

- Nenhuma migration.
- Não zera moedas; não remove recibos; não altera identidades/fences indevidamente.
- Primeiro crédito operacional em save com attr ausente **cria** o attr via CAS `nil→N` (comportamento desejado).
- `unknown-pending`: spendable = operacional; `:save/coins` legado permanece.
- Baseline histórico (20 jogadores / 59 saves / 1072 Pokémon / 181 moedas) é referência — não invariante pós-jogo legítimo.

---

## 9. Deploy e rollback (GO futuro — **não executar agora**)

### Pré-GO

1. Backup A6 gated.  
2. RO: raides ativas no piloto — abortar se combate/inscrições vivos.  
3. Confirmar `ALLOW_ALL_GROUPS=false`, auto tick OFF, volumes intactos.  
4. `docker load` dos tars do RC 12.3.1.  
5. Snapshot rollback = imagens **12.2** atuais.

### Janela

1. Freeze comandos (Node).  
2. Recreate **JVM** tag RC 12.3.1 (`--no-deps`, sem `-v`, sem migrate).  
3. Health JVM + `raidAutoTickEnabled=false`.  
4. Recreate **Node** tag RC 12.3.1 (mesma sessão WA).  
5. WA READY sem QR.  
6. Smoke: abrir→entrar→iniciar→atacar até vitória (grupo piloto).  
7. Visual: cartão Onix sem caixa preta.

### Rollback

Restaurar tags 12.2 (`odisseu-jvm/node:a6-raidfix-20261101T152138Z-5b9a97e60228-amd64`) sem tocar volumes/Datomic/sessão. **Não** rollback de saves/T0.

---

## 10. Release Candidate

**READY** — RC `a6-raid-hotfix1231-20261101T161034Z-1eede78e365c` (Node+JVM amd64) validado.  
Aguardando **GO explícito** de implantação coordenada (fase seguinte). Produção **não** alterada.

| | Node | JVM |
| --- | --- | --- |
| Tag | `odisseu-node:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` | `odisseu-jvm:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` |
| Image ID | `sha256:f1833b2772459a7f7a79c55d15492eb4ab9053f539fcef8470467e28feb6ffbd` | `sha256:04f22e2bb33f13216f9913f932fc7b42ad4c70a174983a1c795176e0322300b2` |
| Platform | `linux/amd64` | `linux/amd64` |
| Tar | `images/odisseu-node-a6-raid-hotfix1231.tar` (~371 MiB) | `images/odisseu-jvm-a6-raid-hotfix1231.tar` (~249 MiB) |
| Tar SHA-256 | `42be4e97305b6c111b2f3e72e4fd4a2c6c7401e46e44a5a16ef8320d66de4b78` | `e3bb7d983946af4978cb95ef57b565a4ccebf20ae58b3c43e9d0111806e22fc4` |
| Labels | `git-sha=e4dffcf…` · `content-hash=1eede78e365c…` · `release=a6-raid-hotfix-fase12.3.1` | idem |

**Content hash (paths do RC):** `1eede78e365cd8b1c16dbc6c93f908c380a327fda7a08a3650977b5970114830`  
**Sem** tags mutáveis (`latest` / `a6` soltas).

### Smoke imagem

Node: `ALLOWLIST_OK` · `MEDIA_OK` · `PRESENT_OK` · `ENSURE_ALPHA_OK` · `BLEND_OVER_OK`  
JVM: `ECONOMY_STORE_OK` · `NIL_CAS_OK` · `ARTWORK_OK` · `MIGRATIONS_PRESENT_OK` (30)

### Live atual (não alterado nesta fase)

| Container | Tag | Image ID |
| --- | --- | --- |
| `odisseu-a6-jvm` | `…a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` | `sha256:82e1a06c…` |
| `odisseu-a6-node` | `…a6-raidfix-20261101T152138Z-5b9a97e60228-amd64` | `sha256:dbcb6c02…` |

### Status final

**READY** — hotfix comprovado (CAS nil operacional + alpha Onix), regressões verdes, RC imutável empacotado.  
**NO-GO** de deploy até autorização explícita.
