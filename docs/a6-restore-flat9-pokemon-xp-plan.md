# Odisseu A6 — Retorno controlado à progressão original flat-9

**Estado:** IMPLEMENTADO em `feat/a6-restore-flat9-pokemon-xp` (PR #86). Sem merge/deploy/backfill em produção até GO separado.
**Base:** live pós-hotfix #85 (`5f3b716`) e reparação dos quatro Pokémon.
**Exclui:** PR #84 (growth-v2), PR #83 e mudança de PE do treinador, PI dos ovos ou economia.
**Relatório:** `docs/a6-restore-flat9-pokemon-xp-report.md`

## Contrato funcional

- Cada Pokémon requer **9 XP residuais** para subir de nível, independentemente de espécie ou nível.
- Vitória comum **3 XP**; derrota **1 XP**.
- O restante é preservado entre ganhos; no nível 100 não ganha níveis adicionais.
- Progressão **não** usa curvas PokéAPI. Multiplicadores leves de atividade (ginásio/PvP/raid/adversário) atuam sobre pontos flat, com cap 18.
- Uma operação de conversão ou leitura **jamais** altera nível, espécie, golpes, stats ou amizades.
- Só eventos reais de gameplay concedem níveis e atualizam stats.
- A conversão lazy ocorre somente dentro de transação idempotente do evento confirmado.
- A UI apresenta `XP: n/9`, nunca total oficial acumulado.

## Modelo canônico

- `xp-progress-version = "flat-9-v2"`
- `xp-desde-nivel` e `xp`: residual 0..8 (0 no nível 100)
- `xp-total` sintético `(nivel-1)*9+residual` para compatibilidade
- Preservar `:xp-legacy-residual`, `:xp-legacy-nivel` e o histórico Datomic
- XP desconhecido permanece desconhecido em leitura; registros sem versão e residual ≥9 **falham fechados** em grants/evolução, sem zerar seu progresso. Somente após reconciliação histórica e transação idempotente podem usar `flat-9-v2`.

## Migração

1. **growth-v1:** `floor((resid/span)*9)` → 0..8; preserva nível
2. **Legado flat-9 (0..8):** preserva residual
3. **Ambíguo (sem versão, residual ≥9):** fail-closed; sem `quot`/`mod` como progresso
4. **growth-v2:** fração/preservação segura
5. **XP desconhecido:** não inventar 0 na leitura

## Gates

1. Implementação e testes CI verdes — ver relatório
2. Ensaio isolado — 0 mudanças de nível na conversão (1080 Pokémon)
3. Ensaio inicial histórico: 19 ambíguos (1080 Pokémon). Inventário live RO posterior: **18** ambíguos, nenhum ID novo; `d11cc9ad-…` (Salamence L3 residual 1) já `growth-v1` e saiu do conjunto. Auditoria e reconciliação dos 18 ensaiadas em restore separado, 18/18 aplicados, 0 mudanças de nível, 0 UNRESOLVED, retry idempotente. Ver `docs/a6-flat9-ambiguous-xp-reconciliation-report.md`.
4. **Sem backfill live:** os 18 continuam na produção até GO para apply, com cold fresco de cutover e fingerprint reconfirmado.
5. GO separado para merge/deploy e para apply em produção; nenhum GO decorre dos ensaios.

**Proíbe interpretar abertura/atualização do PR como aprovação de produção.**
