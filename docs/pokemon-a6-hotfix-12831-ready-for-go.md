# HOTFIX 12.8.3.1 — Início auto / Frambo UI / comandos `!pk raid` — READY FOR GO

**Status:** implementação + testes locais prontos. **Sem deploy / sem migration / sem mutação de produção.**  
**Base produção:** **12.8.3**  
**Ativação:** somente após GO explícito (redeploy JVM+Node; sem PUT scheduler / force spawn).

---

## 1. Causas raiz

| # | Sintoma | Causa |
|---|---------|--------|
| 1 | `!pk raid iniciar` falha / UX ruim em raid **automática** | Domínio só liberava qualquer inscrito quando `:criador` era `nil`. Se restasse criador residual, `RAID_NOT_CREATOR`. Erros genéricos (`RAID_NOT_OPEN` / `NOT_IN_RAID`) não distinguiam combate já iniciado vs. não inscrito. Resposta de start sem `:sua-vez` omitia “É a sua vez”. |
| 2 | Frambo confirma sem reabrir bolas/chances | Node chamava só `formatRaidFrambo` (stub curto) após `raidFrambo`, **sem** re-fetch de `getActiveRaid` / `captura-ui`. Caça selvagem já reapresentava `formatCaptureMenu`. |
| 3 | Comandos fora do padrão | UI/outbox usavam `!pk raide …` e nomes de bola; captura do chefe não numerava 1/2/3. Alias `raide` já existia no parser. |

---

## 2. Comandos canônicos e aliases

| Canônico (UI) | Alias aceito |
|---------------|--------------|
| `!pk raid …` | `!pk raide …` (ALIASES) |
| `!pk raid entrar` / `entrar auto` / `entrar <n>` | `ent` |
| `!pk raid iniciar` | `ini` |
| `!pk raid atacar <1-4>` | `atk` |
| `!pk raid fruta` | `frambo` / `framb` / `razz` |
| `!pk raid capturar 1\|2\|3` | `pokebola` / `grande-bola` / `ultra-bola` (normalize-ball) |
| `!pk raid sair` | leave lobby via `/v1/raids/leave` (corrigido; antes chamava leaveBattle) |
| `!pk raid cancelar` / `abrir` / status vazio | intactos |

---

## 3. Correção do início antecipado

Em `pokemon.domain.raid/iniciar`:

- **`origem = "auto"`** → qualquer **inscrito** inicia (criador humano irrelevante).
- **Manual** → se há `:criador`, só ele.
- Não inscrito → `:raid_start_not_joined` → *"⚠️ Entre na raid antes de iniciar a batalha."*
- Já em combate → `:raid_already_started` → *"⚔️ Esta raid já está em andamento."*
- `bridge_tx/start-raid!` enriquece `:sua-vez` no DTO para o 1º turno.
- CAS/`operation-id` / expire-lobby por timeout **inalterados**.

---

## 4. Formatter de captura compartilhado

`formatRaidCaptureUi(data, prefix, { afterFrambo })` é a **única** superfície:

- Inicial: `!pk raid capturar` (sem args) → UI completa.
- Pós-Frambo: prepare → **re-fetch** `getActiveRaid` → mesma UI com banner Frambo.

Chances = `captura-ui.chances` do domínio (nunca percentuais fixos na string).

---

## 5. Exemplo — mensagem inicial (captura)

```
🎯 *CAPTURA DO CHEFE — DRAGONITE*
Tentativas: 0/3
🍓 Bônus Frambo: inativo

Escolha sua Poké Bola:

1️⃣ Poké Bola — 8% · estoque 10
2️⃣ Super Bola — 12% · estoque 5
3️⃣ Ultra Bola — 16% · estoque 3

🍓 Frutas restantes: 4

*Comandos:*
`!pk raid capturar 1`
`!pk raid capturar 2`
`!pk raid capturar 3`
`!pk raid fruta` — oferecer Frambo
```

*(percentuais ilustrativos — vêm do domínio)*

---

## 6. Exemplo — pós-Frambo

```
🍓 *FRUTA FRAMBO UTILIZADA!*

O chefe está mais fácil de capturar!

🎯 *CAPTURA DO CHEFE — DRAGONITE*
Tentativas: 0/3
🍓 Bônus Frambo: *ATIVO*

Escolha sua Poké Bola:

1️⃣ Poké Bola — 12% · estoque 10
2️⃣ Super Bola — 18% · estoque 5
3️⃣ Ultra Bola — 24% · estoque 3

🍓 Frutas restantes: 4

*Comandos:*
`!pk raid capturar 1`
`!pk raid capturar 2`
`!pk raid capturar 3`
```

Prepare **não consome** Frambo (consumo na tentativa de captura — igual 12.6 / selvagem).  
UI distingue **PREPARADA** (bônus carimbado, estoque intacto) de **consumida** (mensagem só após `capturar`).

---

## 7. Fórmula de captura preservada

- Base raid: `raid/chance-captura` por raridade (raro 35 / épico 20 / lendário 8 / mítico 4).
- Bola: `capture/chance-com-bola-frambo` — mult poké 1 / grande 1.5 / ultra 2; Frambo ×1.5; caps por bola.
- Tentativas máx. 3; janela `captura-expira`; prepare idempotente (`frambo_already_prepared`).

Recompensas 12.8.3 **intactas**:

| Outcome | Moedas | PE | XP mon | Captura |
|---------|--------|----|--------|---------|
| Vitória | 50 | 6 | 6 | sim |
| Derrota (`dano>0`) | 0 | 3 | 3 | não |

---

## 8. Testes executados (local)

| Suite | Resultado |
|-------|-----------|
| `clojure -M:test-raid-lobby-join-hotfix` (lobby+12.7.1+fase2) | **PASS** 0 fail (incl. `iniciar-auto-ignora-criador-fantasma`) |
| `clojure -M:test-raid-defeat-rewards-fase1283` | **PASS** 15/80 |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** 15/75 (Frambo prepare/consume) |
| `clojure -M:test-raid-hotfix-fase1231` | **PASS** 4/28 |
| `clojure -M:test-raid-turn-gym-level-hotfix` | **PASS** 0 fail |
| `node --test` raid fase12 + lobby + raid.test + capture | **PASS** |

---

## 9. Arquivos modificados

- `modules/pokemon/domain/pokemon/domain/raid.clj` — `iniciar`
- `modules/pokemon/domain/pokemon/domain/raid_auto.clj` — textos `!pk raid …`
- `modules/pokemon/application/pokemon/application/bridge_tx.clj` — `sua-vez` no start
- `modules/pokemon/infrastructure/.../http/bridge.clj` — novos códigos 409
- `src/zapbot/pokemon_http.cljs` — Frambo re-UI; `sair`→leaveRaid; copy
- `scripts/lib/pokemon-jvm-present.cjs` — formatters + erros
- testes Node/JVM alinhados + `docs/pokemon-a6-hotfix-12831-ready-for-go.md`

---

## 10. Evidência recompensas 12.8.3

`test-raid-defeat-rewards-fase1283` e assertions de vitória/derrota em `pokemon-jvm-raid-fase12.test.cjs` / `pokemon-jvm-raid.test.cjs` — **PASS** sem alteração de constantes `recompensa-*`.

---

## 11. Migration

**Não.** Sem schema novo; erros são só códigos de domínio; Frambo/captura usam attrs existentes.

---

## 12. Plano de deploy / rollback

**Deploy (após GO):** rebuild+push imagens Node+JVM da revisão do hotfix; restart compose piloto; **sem** PUT schedule / migration / backfill.

**Smoke piloto:**

1. Raid auto → `!pk raid entrar` → `!pk raid iniciar` (inscrito) → 1º turno + golpes numerados.
2. Não inscrito → mensagem “Entre na raid…”.
3. Vitória → captura UI numerada → `!pk raid fruta` → UI completa com chances ↑.
4. `!pk raide iniciar` / `!pk raide capturar 1` (alias).
5. Derrota → PE/XP 3 sem captura.

**Rollback:** imagens **12.8.3** anteriores.

---

## 13. READY FOR GO

| Item | Status |
|------|--------|
| Código hotfix 12.8.3.1 | pronto |
| Testes locais obrigatórios | PASS |
| Produção tocada | **não** |
| Migration / PUT / force spawn | **não** |
| Aguarda | **GO explícito** para deploy piloto |

**READY FOR GO — HOTFIX 12.8.3.1 (local). Deploy somente com seu GO.**
