# Balanceamento de XP dos Pokémon (growth-v1)

## Premissa

As tabelas oficiais da PokéAPI **não são alteradas**. O ritmo jogável vem de
`pokemon.domain.xp/calcular-ganho-pokemon`, que converte **pontos de atividade**
legados (vitória=3, derrota=1, raid=6, …) em XP oficial proporcional ao span do
nível atual:

```
xp_oficial ≈ floor(span(nível) × pts / 9) × ajustes
```

Isso preserva o pacing histórico de ~3 vitórias por nível, independentemente da
curva. Cap anti-exploit: 60% do span por grant (`pokemon-xp-grant-span-cap`).

Ajustes:
- Nível do adversário: razão clampada em `[0.5, 1.5]`
- Ginásio ×1.10 · PvP ×1.05
- Raid: épica ×1.25 · lendária ×1.50 · mítica ×1.75
- `pokemon-xp-grant-scale` (default 1) — knocker global testável, separado das tabelas

PE do treinador permanece independente.

## Marcos (medium-fast, ~3 vitórias/nível)

| Nível | Vitórias ≈ | Casual (5/dia) | Regular (15/dia) | Dedicado (40/dia) |
|------:|----------:|---------------:|-----------------:|------------------:|
| 10 | 27 | ~5 dias | ~2 dias | <1 dia |
| 20 | 57 | ~11 dias | ~4 dias | ~1–2 dias |
| 30 | 87 | ~17 dias | ~6 dias | ~2 dias |
| 50 | 147 | ~1 mês | ~10 dias | ~4 dias |
| 75 | 222 | ~1,5 mês | ~15 dias | ~6 dias |
| 100 | 297 | ~2 meses | ~20 dias | ~7–8 dias |

Curvas mais lentas (slow/fluctuating) exigem o mesmo **número de atividades** por
nível (o span cresce, o grant cresce junto). Erratic/fast variam o span, não a
política de pontos.

## Antes × depois (medium-fast L50 / L100)

| | Escala 1 crua (3 XP oficial/vitória) | Balanceado (pts→span) |
|---|---:|---:|
| Vitórias → L50 | ~41 667 | ~147 |
| Vitórias → L100 | ~333 333 | ~297 |

## Perfis simulados (premissas)

- Casual: 3 caçadas + 2 raids leves / dia
- Regular: 10 batalhas + 3 raids + ginásio / dia
- Dedicado: 25 batalhas + 8 raids + ginásio/PvP / dia

Números são estimativas de design para RPG WhatsApp — **não** regras PokéAPI.
