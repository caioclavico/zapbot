# Odisseu A6 — Progressão oficial de XP dos Pokémon (growth-v1)

## Auditoria do modelo legado

| Aspecto | Legado (pré-037) | growth-v1 |
|---|---|---|
| Semântica de `:pokemon/xp-desde-nivel` | **Residual** no nível atual | Residual derivado do total |
| Limiar por nível | Fixo `9` para todas as espécies | Intervalo da curva oficial |
| XP acumulado | Não existia | `:pokemon/xp-total` |
| Curva | Nenhuma (flat) | 6 curvas PokéAPI |
| Treinador | PE progressivo (036) — **inalterado** | Inalterado |

Ganhos de gameplay existentes (`xp-por-vitoria=3`, `xp-por-derrota=1`, raids, etc.) permanecem nos mesmos pontos de chamada; passam pela escala `pokemon-xp-grant-scale` (default `1`) antes das tabelas oficiais.

## Curvas

Fonte congelada em:

- `modules/pokemon/domain/pokemon/domain/data/growth_rate_tables.edn`
- `modules/pokemon/domain/pokemon/domain/data/species_growth_rates.edn`

Mapeamento PokéAPI → chave Odisseu:

| PokéAPI | Odisseu |
|---|---|
| slow-then-very-fast | `:erratic` |
| fast | `:fast` |
| medium | `:medium-fast` |
| medium-slow | `:medium-slow` |
| slow | `:slow` |
| fast-then-very-slow | `:fluctuating` |

Espécie-base via `growth-rate-for-species` + `species-form-aliases` (formas alternativas).

## Conversão legado → growth-v1

Precedência do residual flat-9: `xp-desde-nivel` → `xp` → `vitorias-desde-nivel × 3` → 0.

1. Nível legado é **piso** (nunca reduz).
2. `extra = floor(residual / 9)` (cap 100) → sobe níveis como o cljs `normalizar-xp-registro`.
3. `rem = residual mod 9`; `xp-total = total(nível') + floor((rem/9) × span(nível'))`.
4. Residual múltiplo de 9 → nível seguinte com residual 0 (sem pousar no limiar L+1 com nível antigo).
5. Grava `:xp-legacy-residual`, `:xp-legacy-nivel`, `:xp-progress-version=growth-v1`.
6. Idempotente: se a versão já é `growth-v1`, não reconverte.

Não há backfill live nesta entrega — conversão lazy no primeiro grant/leitura de progresso.

## Evolução e troca

- Evolução: `after-evolution` mantém `xp-total` e nível (nunca reduz); ajusta o piso da nova curva.
- Troca: campos de XP viajam com o registro; sem reset.

## Balanceamento

Ver `docs/a6-pokemon-growth-balance.md`. Grants de combate/raid/ginásio usam
`calcular-ganho-pokemon` (pontos de atividade → fração do span oficial). Ritmo
alvo ≈ 3 vitórias/nível (~297 vitórias até L100 medium-fast). Tabelas PokéAPI
intactas.

## Migration

`migrations/pokemon/037-pokemon-growth-xp.edn` — aditiva após 036:

- `:pokemon/xp-total`
- `:pokemon/xp-progress-version`
- `:pokemon/xp-legacy-residual`
- `:pokemon/growth-rate`

## Drain

Escritas de XP continuam apenas via `transact-with-writer!` / gate de manutenção existente. Nenhuma operação nova bypassa `open → draining → quiescent → frozen`.

## Presenter WhatsApp

DTO JVM já expõe `xp-nivel-atual` / `xp-proximo-nivel` / `nivel-maximo?`. O Node mostra barra percentual e bloco de progresso sem reimplementar curvas.

### Exemplos de saída

Nível intermediário (medium-fast, Nv.24, ~47% do span):

```
⚡ *Pikachu* — Nv. 24
✨ XP: 420 / 890
████░░░░░░ 47%
⬆️ Próximo nível: 25
```

Nível máximo:

```
⚡ *Dragonite* — Nv. 100
✨ XP: MÁXIMO
██████████ 100%
```

Subida multi-nível (narrativa de batalha):

```
🌟 *Charmander* subiu 3 níveis e chegou ao Nv. 12!
```

## Não executado

- Deploy live
- Migration live / backfill dos 759 Pokémon
- Alteração do Release Announcer

Ver também: `docs/a6-pokemon-growth-runbook.md`, `docs/a6-pokemon-growth-compat-sim.md`.
