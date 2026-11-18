# Odisseu A6 — Incidente: saltos de nível em caçada

**Timestamp UTC:** 2026-11-13T17:25Z–17:45Z  
**Severidade:** P0 (progressão)  
**Live tip implantado:** `8b05886f696441fabdce06823a2513c7d8997446`  
**Imagem JVM:** `odisseu-jvm:a6-pr80-20261112T225903Z-8b05886f6964-amd64`  
**Imagem Node:** `odisseu-node:a6-pr80-…-8b05886…`  
**Evidência host:** `/home/caiohclavico/odisseu-recovery/a6-xp-incident-20261113T172822Z`  
**PR #84 (growth-v2):** **não** mergeado / **não** implantado (separado)

---

## 1. Causa raiz (demonstrada)

No tip live (growth-v1), o primeiro grant de XP de caçada chama `ensure-growth-v1` → `convert-legacy-residual` para Pokémon **sem** `:xp-progress-version`.

A conversão antiga fazia:

```text
extra = quot(residual, 9)   ;; até dezenas/centenas de níveis
nível' = nível + extra
```

Quando `:xp` / `:xp-desde-nivel` continha um valor **grande** (residual oficial ou acumulado anômalo, não o flat-9 0..8), a caçada “convertia” e teletransportava o Pokémon.

Reprodução local idêntica aos saves:

| Caso | Antes | Após convert/hunt | Match live |
|---|---|---|---|
| Blastoise | L74, residual **8478**, unversioned | L**100**, `xp-total=1059860`, legacy-nivel=74 | **exato** |
| Tyranitar | L90, residual **25102** | L**100**, legacy-nivel=90 | **exato** |
| Staryu (ainda vulnerável) | L13, residual **478** | L**66** (+53) no 1º grant | simulado |

A fórmula de atividade `(pts/9)×span` com cap 60% **não** explica sozinha saltos de dezenas de níveis a partir de residual 0; o salto ocorre **na conversão legado**, antes/durante o grant.

Não há evidência de XP duplicado Node+JVM neste incidente (JVM é a writer; flag `recompensas-batalha-concedidas?` evita regrant na mesma batalha).

---

## 2. Quantidade afetada (snapshot dirty da live)

Auditoria em cópia FS do volume Datomic live (`odisseu-a6-restore-xpinc-20261113T1728`):

| Métrica | Valor |
|---|---|
| Pokémon totais | 1079 |
| Unversioned | 1017 |
| growth-v1 | 62 |
| Nível ≥ 50 | 4 |
| Nível 100 | 4 |
| Unversioned com residual ≥ 9 (**ainda vulneráveis**) | **19** |
| Já convertidos com `legacy-residual ≥ 9` (salto no passado) | **4** (inclui Blastoise/Tyranitar) |

Pokémon L100 (IDs canônicos; save = hash):

- `e9250170-…` blastoise — **afetado** (legacy-nivel 74, legacy-residual 8478)
- `edb589f9-…` tyranitar — **afetado** (legacy-nivel 90, legacy-residual 25102)
- `aeec3011-…` onix — legacy-nivel 100 / residual legado 0 (provável legítimo ou outro caminho)
- `6424da8f-…` nuzleaf — L100 unversioned residual 0 (investigar à parte)

---

## 3. Eventos responsáveis

- Trigger: **primeiro** `apply-pokemon-xp-tx` / `aplicar-xp-atividade` em caçada (vitória ou captura) sobre registro unversioned com residual ≥ 9.
- Período: desde cutover growth-v1 / schema 037 na live (tip pós-PR #76/#80) até agora; saltos concretos gravados quando cada Pokémon vulnerável recebeu o 1º XP.

---

## 4. Impacto

- Níveis inflados (até 100) com stats/evolução/golpes por nível potencialmente aplicados no mesmo TX.
- Saves, espécies, IVs, shiny, moedas, itens: não apagados.
- 19 Pokémon **ainda** saltarão no próximo grant se não houver hotfix/freeze.

---

## 5. Contenção (estado)

**Não existe** kill-switch seletivo só de XP Pokémon no tip live (`pokemon-xp-grant-scale` é constante de código, não env).

Opções seguras (nenhuma aplicada automaticamente nesta sessão):

| Opção | Efeito | Risco |
|---|---|---|
| A. Hotfix `convert-legacy` sem level-up (branch abaixo) | Remove causa; caçadas seguem | Requer GO deploy JVM |
| B. Maintenance JVM `frozen`/`quiescent` | Para **todas** mutações | Impacto amplo (hunts/raids/loja) |
| C. Desligar caçada no Node (se flag existir) | Mitiga trigger principal | Não cobre gym/raid/PvP grants |

**Estado agora:** live ainda `phase=open`, WhatsApp READY, **19 vulneráveis**.  
**Recomendação imediata:** GO para hotfix A **ou** freeze curto B até o patch.

PR #84 **não** deve ser usado como contenção improvisada.

---

## 6. Patch de correção

Branch: `fix/a6-xp-legacy-convert-level-freeze` (base `8b05886`)

Alteração em `pokemon.domain.growth/convert-legacy-residual`:

- `extra := 0` (nunca sobe nível na conversão)
- fração do span usa apenas `residual mod 9`
- preserva `:xp-legacy-residual` / `:xp-legacy-nivel` para reparo

Testes: `growth_curves_a6_test` + regressão Blastoise/Staryu.  
Suite `clojure -M:test-a6-pokemon-growth`: **PASS** (58/1063).

---

## 7. Estratégia de reparação (sem GO — não executar)

Para registros com `:xp-legacy-nivel` &lt; nível atual e `:xp-legacy-residual` ≥ 9:

1. Restaurar `:nivel` ← `:xp-legacy-nivel` (quando confiável).
2. Recalcular `:xp-total` / residual no nível restaurado (política hotfix).
3. Reverter evoluções/golpes/stats **somente** se o histórico TX mostrar que foram efeito do salto (comparar datoms pré/pós).
4. Ensaiar 100% em Datomic descartável; fingerprints pré/pós.
5. **Não** baixar nível sem legado auditável (ex.: onix L100).

---

## 8. Growth-v2 (PR #84)

A conversão direta flat-9→v2 do PR #84 também **não** sobe nível e eliminaria esta classe de bug para legados.  
**Mantido separado.** Não merge/deploy/backfill do #84 durante o incidente.

---

## 9. GO / NO-GO

| Item | Decisão |
|---|---|
| Entender causa | **GO** (reproduzida) |
| Deploy hotfix level-freeze | **NO-GO** até autorização explícita (patch pronto) |
| Freeze/contenção live | **NO-GO** operacional até escolha A/B/C pelo operador |
| Reparo de níveis live | **NO-GO** (só proposta) |
| Merge PR #84 | **NO-GO** |

**Prioridade:** autorizar hotfix A antes que os 19 residual≥9 unversioned recebam XP.
