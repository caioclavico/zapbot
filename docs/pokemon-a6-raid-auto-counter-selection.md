# A6 — Seleção inteligente `!pk raid entrar auto`

## Objetivo

Escolher o Pokémon mais adequado ao **chefe da raid** (efetividade, dano esperado,
sobrevivência ao revide canônico), não apenas o de maiores atributos brutos.

## Domínio

- `pokemon.domain.raid-counter-selection` — pontuação pura, determinística.
- Bridge: `pokemon.application.bridge-tx/raid-auto-index` delega ao domínio.

### Critérios (espelham regras já suportadas)

| Fator | Fonte |
|-------|--------|
| Dano ofensivo vs chefe | `raid/calcular-dano` + `types/multiplicador-vs-tipos` (sem STAB, como combate) |
| Ataque físico/especial | Stats do mon + classe do golpe |
| Defesa vs golpes do chefe | Mesma fórmula de raid com papéis invertidos |
| Sobrevivência | HP atual ÷ `ceil(HP máx / 5)` (revide do chefe) |
| Precisão | `damage/chance-acerto` quando o golpe tem precisão |
| Imunidades / dupla tipagem | Tabela de tipos + Levitate em Ground |

**Não** usa: STAB (ausente na raid), nome/espécie do chefe, bônus por lendário/nível alto.

RNG de score fixo: `{:raid-damage-var 15}` → variação 100% (determinismo).

### Fallback

Sem `:chefe` na raid → critério legado (soma de stats + maior poder), compatível com lobby incompleto.

## Comandos inalterados

- `!pk raid entrar` — ativo
- `!pk raid entrar auto` — melhor counter elegível **deste save**
- `!pk raid entrar N` — índice manual

## Comparação old vs new (exemplo água)

| Candidato | Índice | Legacy score | Combat score | Escolha |
|-----------|--------|--------------|--------------|---------|
| Snorlax lendário (normal) | 0 | ~600+ | baixo (neutro) | **Old** |
| Bulbasaur (grass) | 1 | ~250 | alto (super efetivo) | **New** |

Com chefe Água, o legado favorecia tank de stats; o novo favorece counter tipado com dano efetivo.

## CI

```bash
clojure -M:test-raid-counter-selection-a6
```

## Escopo

Sem migration Datomic, sem deploy, branch isolada `feat/a6-raid-auto-counter-selection`.