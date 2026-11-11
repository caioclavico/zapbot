# Balanceamento de XP dos Pokémon (growth-v1)

## Premissa

Tabelas oficiais PokéAPI **intactas**. Ritmo via `calcular-ganho-pokemon`:

```
xp_oficial ≈ floor(span(nível) × pts / 9) × ajustes
```

Cap anti-exploit: 60% do span (`pokemon-xp-grant-span-cap`).
Knocker global: `pokemon-xp-grant-scale` (default 1).

## Simulação dinâmica (testes)

Suite `dynamic-balance-*` em `growth_curves_a6_test.clj` usa
`aplicar-xp-atividade` / `calcular-ganho-pokemon` de verdade.

Observado (vitórias comuns a partir do Nv.1):

| Curva (espécie) | →L10 | →L20 | →L30 | →L50 | faixa alvo |
|---|---:|---:|---:|---:|---|
| medium-fast (pikachu) | ~27–36 | ~57–76 | ~87–116 | ~147–196 | 3–4 wins/nível |
| medium-slow / slow / fast / erratic / fluctuating | idem banda | idem | idem | idem | 3–4 wins/nível |

Marcos altos (medium-fast, simulação executável):

| Alvo | Vitórias observadas (banda do teste) |
|------|--------------------------------------|
| 75 | 222–296 |
| 100 | 297–396 |

Variantes (Nv.30 pikachu): derrota < vitória; raid raro ≤ épico ≤ lendário ≤ mítico ≤ cap; ginásio/PvP ≥ vitória; adversário alto aumenta / baixo reduz.

## Antes × depois (estimativa teórica medium-fast)

| | 3 XP oficial/vitória | Balanceado (pts→span) |
|---|---:|---:|
| → L50 | ~41 667 | ~147 |
| → L100 | ~333 333 | ~297 |

A estimativa ~147 é confirmada pela banda de teste dinâmico (3–4 wins/nível).

## Perfis (premissa de design, não PokéAPI)

- Casual ~5 atividades/dia · Regular ~15 · Dedicado ~40
- L50 regular ≈ 10 dias · dedicado ≈ 4 dias
