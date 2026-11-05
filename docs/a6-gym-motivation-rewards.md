# A6 — Ginásios: motivação e moedas por permanência

**Status:** CONDITIONAL (política econômica implementada e testada localmente; **ativação em produção desabilitada**)  
**Branch:** `feat/a6-gym-motivation-rewards`  
**Flag:** `POKEMON_GYM_PERMANENCE_REWARDS_ENABLED` (default `false`)  
**Migration aditiva (só local/testes):** `034-gym-permanence-daily.edn` — **não aplicar em produção sem GO**

---

## 1. Motivação (já no domínio)

| Parâmetro | Valor |
| --- | --- |
| Máxima | 100 |
| Mínima | 20 |
| Perda / hora | 5 (cálculo sob demanda a partir de `motivacao-em` / `desde`) |
| Perda / defesa | 12 |
| Efeito combate | `fator = 0.5 + 0.5*(mot/100)` em HP/stats **da cópia de defesa** |

HP real da coleção **não** é alterado pelo decaimento. Reinício JVM/Node não reinicia motivação (estado em Datomic + relógio injetável).

---

## 2. Política de moedas por permanência (aprovada para implementação)

| Regra | Valor |
| --- | --- |
| Taxa | **1 moeda** a cada **10 minutos** completos |
| Mínimo elegível | **30 minutos** |
| Teto | **100 moedas / dia / treinador** (todos os ginásios somados) |
| Fuso do dia | `America/Campo_Grande` (override: `POKEMON_GYM_PERMANENCE_TZ`) |
| Momento do pagamento | Somente no **encerramento válido** da ocupação (dethrone / occupy do desafiante) |
| Flag econômica | off por padrão — sem crédito real até homologação |

### Exemplos (bruta, sem teto)

| Permanência | Moedas |
| --- | --- |
| 9 min | 0 |
| 20 min | 0 |
| 30 min | 3 |
| 1 h | 6 |
| 3 h | 18 |
| 8h20 | 50 |
| 12 h | 72 |

Com saldo diário 99 e nova ocupação de 1 h: paga **1** (completa o teto). Com 100 já creditados: paga **0**.

---

## 3. Idempotência e ledger

- Identidade: `gym-reward:<occupation-key>:<desde-ms>` (`gym/occupation-reward-id`)
- Ledger: `source=gym-permanence`, `role=permanence` via `economy-store/apply-delta-tx`
- Teto diário: CAS em `:save/gym-permanence-daily-edn` `{day-key → coins}` na **mesma** TX do crédito
- Retry / receipt da resolução do desafio não reabre ocupação já substituída; reward-op por ocupação evita crédito duplicado se reprocessado
- unknown-pending: crédito operacional não promove histórico (invariante do ledger 028)

Não se credita em consulta de ginásio nem em tick de scheduler.

---

## 4. Ativação / reversão

```bash
# Homologação (local / GO futuro)
POKEMON_GYM_PERMANENCE_REWARDS_ENABLED=true
```

Produção permanece `false` até GO explícito + migration 034 aplicada.

Reversão: desligar a flag (crédito volta a 0; motivação/UX inalteradas).

---

## 5. Testes

| Suite | Cobertura |
| --- | --- |
| `gym_m35_test` / fixtures `legacy_gym.edn` | bruta 30min→3 … 12h→72; flag off na dethrone legado |
| `gym_permanence_rewards_a6_test` | flag off; mín. 30; 99+segundo ginásio; teto 100; reward-id |

```bash
clojure -M:test-m3-gym   # inclui m35 se no smoke
# ou
clojure -M:test/unit -e "(require 'pokemon.tests.gym-permanence-rewards-a6-test 'clojure.test) (clojure.test/run-tests 'pokemon.tests.gym-permanence-rewards-a6-test)"
```

---

## 6. Pendências (fora deste patch de política)

- Bugs visuais HP inicial / sprites quadrados (escopo reforma completa).
- UX barra de motivação + estimativa (só com flag on).
- Aplicar 034 e flip de flag em produção — **bloqueado** até homologação.

**Classificação:** CONDITIONAL — domínio e testes da política 100/dia prontos; economia desligada por default.
