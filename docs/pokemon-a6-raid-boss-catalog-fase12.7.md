# A6 — Fase 12.7: catálogo dinâmico de chefes

**Status:** implementado e testado · **auto-raides em produção: OFF** (aguarda GO)  
**Branch de trabalho:** `hotfix/a6-raid-capture-rewards-fase12.6`  
**Sem migrate / sem deploy / sem alteração de saves, economia, cooldowns ou piloto**

## Princípio

> O catálogo define quem pode aparecer. A raridade influencia a frequência. Os atributos do Pokémon determinam a dificuldade.

## Fonte de verdade

Snapshot versionado `modules/pokemon/domain/pokemon/domain/data/raid_boss_catalog.edn`:

- Origem: CSVs oficiais PokeAPI (`pokemon_species`, `pokemon`, `pokemon_stats`, `pokemon_types`)
- Escopo: **1025** espécies do National Dex (mesmo `total-pokemons` do legado)
- Stats: `suavizar-stat` legado (média 75, fator 0.5)
- Flags reais: `is_legendary`, `is_mythical`, paradox (heurística de nomes)
- Regeneração: `scripts/a6_build_raid_boss_catalog.py`

Não há lista fixa de chefes de raid. `raid_auto/plan-spawn` chama `raid-boss/select-boss-for-ginasio`, que descobre elegíveis no catálogo. Ginásio A6 só define **nível/liga/local**; a espécie deixa de ser o “ás” hardcoded do time do ginásio.

`boss-do-ginasio` (Onix/Starmie/…) permanece para abertura **manual** legada / harness.

## Elegibilidade

Raridade via `hunt/classificar-raridade` (flags API + BST/taxa — **não** só estágio evolutivo).

Elegíveis para raid automática: `raro` | `epico` | `lendario` | `mitico`.

### Auditoria estática

| Métrica | Valor |
|---|---:|
| Espécies cadastradas | **1025** |
| Elegíveis | **571** |
| Excluídas (`:raridade-baixa` comum/incomum) | **454** |
| Raro | 423 |
| Épico | 34 |
| Lendário | 91 |
| Mítico | 23 |
| Gerações cobertas (elegíveis) | 1–9 |

Exemplos excluídos: Caterpie (comum), Metapod (incomum), Pidgey (comum) — fracos demais para o pool de chefes.

Evidência de lista não-fixa:

- 571 espécies distintas em 10k sorteios
- Top espécie ≈ 1% (Archeops 106/10000)
- Teste com catálogo mínimo: `novomon`/`mitimon` entram sem alterar código de seleção
- Spawn automático diverge dos 5 ases de ginásio (Onix/Starmie/…)

## Pesos por categoria (configuráveis)

| Categoria | Peso alvo | 10k seed=42 |
|---|---:|---:|
| Raro | 40% | 40,04% |
| Épico | 30% | 30,22% |
| Lendário | 20% | 19,84% |
| Mítico | 10% | 9,90% |

Pesos são **normalizados** entre categorias que possuem espécies. Dentro da categoria, peso ∝ BST (com leve bônus de variedade por geração).

## Dificuldade

`fator-dificuldade` ∈ `[0,80 ; 1,55]` a partir de BST, ofensiva, defesa e HP do chefe (após escala ao nível do ginásio).

No início do combate:

- `hp-chefe = round(hp-por-jogador × n × fator)`
- defesa/def-esp misturam média dos jogadores (55%) com stats do chefe (45%)

Dois raros podem ter fatores diferentes. Captura/recompensas **não** foram rebalanceadas (só base `epico=20` alinhada à tabela de chance).

## Anti-repetição

- Janela curta: últimos **8** chefes do chat
- Histórico reconstruído das raids `origem=auto` no Datomic (sem migration nova)
- Espécie recente é evitada, não banida permanentemente
- RNG injetável (`:rng`) → testes reproduzíveis

## Integração

| Peça | Mudança |
|---|---|
| `pokemon.domain.raid-boss` | catálogo, seleção, auditoria, simulação |
| `pokemon.domain.raid-auto` | `plan-spawn` usa seleção dinâmica |
| `pokemon.domain.raid` | HP/defesa modulados por `fator-dificuldade` |
| `store/spawn-auto-for-chat!` | injeta `recent-species` das raids auto |

Kill switch global e `enabled` por chat **permanecem false** por padrão.

## Testes

```bash
./scripts/test-raid-boss-fase127.sh   # 8 testes / 38 asserts
./scripts/test-raid-auto-fase3.sh     # regressão (19/76) OK
```

Simulação 10k: em memória, sem criar raids reais.

## Ativação (somente com GO explícito)

1. Deploy das imagens que incluem este código  
2. Manter migrate em **031** (nada novo obrigatório)  
3. Ligar tick global + Node `POKEMON_RAID_AUTO_TICK`  
4. `enabled: true` no schedule do grupo autorizado  

**Não ativar em produção nesta entrega.**

## Riscos residuais

- Snapshot do catálogo precisa ser regenerado quando o projeto subir o teto além de 1025
- Golpes do chefe são STAB sintéticos (combate atual usa sobretudo HP/defesa; golpes importam no snapshot de captura)
- `boss-do-ginasio` manual ainda é lista curta — fora do escopo da seleção automática
