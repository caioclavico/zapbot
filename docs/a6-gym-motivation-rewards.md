# A6 — Ginásios completos: HP, sprites, motivação e economia por permanência

**Status:** CONDITIONAL em produção (cutover Node+JVM + migration 034 aplicados; **crédito econômico ainda OFF** — ver `docs/a6-gym-production-cutover-report.md`)  
**Branch:** `feat/a6-gym-motivation-rewards`  
**Flag:** `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED` (default `false`)  
**Migration aditiva:** `migrations/pokemon/034-gym-permanence-daily.edn` — aplicada em produção no cutover 2026-11-05 (flag econômica permanece OFF)

---

## 1. Bugs corrigidos

### 1.1 HP inicial “vazio” na batalha de ginásio

**Causas raiz (combinadas):**

1. `aplicar-motivacao-pokemon` escalava só `:hp` e deixava `:hp-atual` stale (ex.: `0` do snapshot de ocupação) → `create-gym-battle` abria com HP 0 na barra/DTO.
2. DTO de `start-gym-challenge!` omitia `:hp-atual`; render Node da arena de ginásio não passava `hpX`/`hpO` explícitos.

**Correções:**

- Cópia de defesa: `:hp` e `:hp-atual` começam no max motivado (coleção do dono intacta).
- `battle-start-hp`: `nil` → max; `0` legítimo permanece; clamp ao max.
- Bridge inclui `:hp-atual`; `pokemon_http` passa barras a partir de `battle.hp`.

**Não** cura desmaiados reais; Centro Pokémon inalterado.

### 1.2 Sprites com “quadrado” artificial

**Causa:** `spriteProporcional` chamava `ensureAlpha()` em todo buffer, incluindo JPEG/artwork sem alpha, gerando canvas opaco no padding.

**Correção:** `ensureAlpha` só se `meta.hasAlpha`; composite com `blend: 'over'`; padding `alpha: 0`.

Evidências: `docs/evidence/a6-gym/` (`battle-hp-*.png`, `sprite-*.png`, `report.json` com `corners*=0`).

---

## 2. Motivação (domínio)

| Parâmetro | Valor |
| --- | --- |
| Máxima | 100 |
| Mínima | 20 |
| Perda / hora | 5 (sob demanda a partir de `motivacao-em` / `desde`) |
| Perda / defesa | 12 |
| Efeito combate | `fator = 0.5 + 0.5*(mot/100)` em stats da **cópia** de defesa |

HP persistido da coleção **não** cai com o tempo. Consultas não mutam ocupação. Reinício JVM não “reseta” motivação (relógio + Datomic).

---

## 3. Política econômica definitiva

**Moedas só por permanência** no encerramento válido da ocupação.  
Vitória / conquista / batalha / consulta: **0 moedas** (mesmo com flag OFF — a flag **não** restaura a antiga recompensa de 100/25).

| Regra | Valor |
| --- | --- |
| Mínimo elegível | 30 minutos |
| Máximo remunerado | 8 horas |
| Moedas por 8 h | 100 |
| Teto / ocupação | 100 |
| Teto / dia / treinador | 100 (todos os ginásios) |
| Fuso do dia | `America/Campo_Grande` |
| Flag | off por padrão |

### Fórmula canônica

```
seg = min(permanencia, 28800)
moedas = 0                           se permanencia < 1800s
moedas = min(100, floor(seg * 100 / 28800))   senão
```

Depois aplica teto diário restante. Aritmética inteira / floor.

| Permanência | Moedas (bruta) |
| --- | ---: |
| 10–29 min | 0 |
| 30 min | 6 |
| 1 h | 12 |
| 2 h | 25 |
| 4 h | 50 |
| 6 h | 75 |
| 8 h | 100 |
| 12 h | 100 |

---

## 4. Ledger e idempotência

- Receipt: `gym-reward:<occupation-key>:<desde-ms>`
- Ledger: `source=gym-permanence`, `role=permanence`
- Teto diário: CAS `:save/gym-permanence-daily-edn` na mesma TX do crédito
- Retry / receipt de resolve não duplica
- `unknown-pending` do ledger operacional **não** é promovido automaticamente
- Ocupações distintas (mesmo Pokémon) → receipts distintos

---

## 5. UX

- Detalhe do ginásio: tempo defendendo, motivação %, barra, estimativa **só se flag ON**
- Vitória: não anuncia moedas por conquista
- Retorno do defensor: `formatGymDefenderReturn` (só valores confirmados)
- Ajuda atualizada (sem “100 moedas por vitória” / “50 por 6h”)

---

## 6. Migration 034

Campos aditivos para mapa diário de permanência. Compatível com saves legados (ausência = `{}`).  
**Executada só em Datomic descartável nos testes.** Ativação futura: backup cold + quiescência + flip de flag.

---

## 7. Testes

| Suite | Cobertura |
| --- | --- |
| `gym_battle_hp_a6_test` | HP nil/parcial/0; motivação; sem mutação em consulta |
| `gym_permanence_rewards_a6_test` | fórmula 8h/100; vitória=0; flag; teto 99+1; reward-id |
| `gym_m35_test` + fixtures | equivalência + resolve sem moedas |
| `gym_m36_test` / `gym_m48_test` | regressão CAS / batalha HTTP |
| `test/pokemon-jvm-gym.test.cjs` | presenters |

```bash
clojure -M -e '(require '"'"'pokemon.tests.gym-permanence-rewards-a6-test '"'"'clojure.test) (clojure.test/run-tests '"'"'pokemon.tests.gym-permanence-rewards-a6-test)'
node --test test/pokemon-jvm-gym.test.cjs
node scripts/a6-gym-visual-evidence.cjs
```

---

## 8. Implantação / rollback

1. Nunca flipar a flag sem migration 034 em produção.
2. Deploy de código com vitória=0 + formula nova **pode** ir antes da flag (flag off → sem crédito de permanência; vitória já sem moedas).
3. Rollback econômico: `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=false`.
4. Não estornar créditos históricos.

---

## 9. Classificação

**CONDITIONAL** — cutover produção Node+JVM + migration 034 feitos (`docs/a6-gym-production-cutover-report.md`); crédito de permanência permanece OFF até GO econômico; smoke WA visual ainda pendente.
