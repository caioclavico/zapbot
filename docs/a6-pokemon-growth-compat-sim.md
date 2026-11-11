# Compatibilidade XP — simulações e dry-runs

## Escopos (não misturar)

| Escopo | O que é | Status |
|---|---|---|
| Cold real RC#66 (restore descartável) | `cold-rc66-a6-20261111T135754Z` | **Executado** — **1015** Pokémon |
| Offline autorizado M5.3A | Dump histórico | Executado — 119 Pokémon |
| Sintético 759 | Cobertura de política | Executado (não substitui cold real) |
| Cold “mais recente que a live agora” | Pós-writes após 13:58Z | Capturar na **janela** de cutover |

Nenhuma escrita em volumes live. Nenhum backfill. Dry-run em memória / schema install só no restore descartável.

## Dry-run — cold RC#66 restaurado (1015)

Fonte: `/home/caiohclavico/odisseu-recovery/a6-cold-live-private/cold-rc66-a6-20261111T135754Z`  
Imagem: tip `9780ecb` growth-037 · evidência host `/tmp/a6-growth037-evid/`

```
quantidade              1015
owners                    27
niveis-reduzidos           0
niveis-aumentados          0
hp_alterado                0
shiny_alterado             0
golpes_alterado            0
ivs_alterado               0
species_blank              0
sum-nivel-antes         1599
sum-nivel-depois        1599
sum-residual-legado      990
sum-xp-total (proj.)  727797
curvas  erratic=18 fast=45 fluctuating=15 medium-fast=458 medium-slow=264 slow=215
legacy-nivel-ok         true
pokemon-count-stable    true
036 após install        true
037 após install        true (idempotente)
```

Sem PII no repositório — só agregados.

## Política final de conversão

1. Residual: `xp-desde-nivel` → `xp` → `vitorias-desde-nivel × 3` → 0
2. `extra = floor(R/9)` (cap 100−L); `rem = R mod 9`
3. `L' = L + extra`; `xp-total = total(L') + floor((rem/9)×span(L'))`
4. Auditoria: `:xp-legacy-residual` + `:xp-legacy-nivel` (imutável)
5. Nível nunca diminui

## Rollback (resumo)

Após writes growth-v1: somente JVM tip 037+growth. RC #66 036-only = NO-GO.
