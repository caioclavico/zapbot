# A6 — `!pk tm`: card, texto, XP, evolução e CSV

**Status:** CONDITIONAL (pronto localmente; sem deploy / sem alteração de saves reais)

## Escopo e nomenclatura

No A6, `!pk tm` é alias de **`time`** (coleção/time do treinador), **não** da Máquina Técnica de item (`!pk mt` / `!pk aprender`).

Esta entrega reformula a apresentação e os DTOs da listagem do time, rebatizada visualmente como **Máquinas Técnicas** no card/texto, preservando aliases `tm` / `time` / `meus` / `equipe`.

Não misturar com a Pokédex de espécies (`!pokedex`).

## Comandos

| Comando | Resposta |
|---|---|
| `!pk tm` / `!pk time` | **Somente card** PNG (paginação preservada). Legenda curta: `🎴 Máquinas Técnicas` + nav/atalhos. Sem listagem na caption. |
| `!pk tm txt` / `!pk time txt` | **Somente texto** (sem PNG). Mesmo modelo canônico do card (nível, XP, evolução, filtros). |
| `!pk tm csv` / `!pk time csv` | Documento CSV `odisseu-mt-pokemon.csv` (UTF-8 BOM). Sem PNG. |
| `!pk tm ativo` | Inalterado (ficha do ativo). |
| Aplicação de MT (`!pk mt`, aprender, etc.) | Fora desta entrega (regras de uso preservadas). |

Argumentos de filtro/página (`tipo`, `raridade`, `nivel N`, `>`, `<`, número de página) continuam válidos em card e texto.

## Comportamento anterior → novo

| Aspecto | Antes | Depois |
|---|---|---|
| `!pk tm` | PNG + legenda = listagem completa (`formatCollection`) | PNG + legenda curta; listagem só no PNG |
| XP no PNG | Só barra de HP | HP + XP (atual/necessário) + barra; `NÍVEL MÁXIMO` no cap |
| XP no texto | `XP a/9` (zero inventado se ausente) | Modelo canônico; `XP ?` se não confiável |
| Evolução na listagem | Ausente | Próxima evo por nível (catálogo) quando linear |
| CSV | `;` + colunas PT; XP lia `:xp`/`:xp-necessario` → **0;0** | `,` RFC4180 + colunas estáveis; XP via `xp-desde-nivel` / `xp-por-nivel=9` |
| Filename CSV | `meu-time-pokemon.csv` | `odisseu-mt-pokemon.csv` |
| Evolução pós-XP (JVM) | Só `evolve!` manual | Auto por nível via catálogo linear (sem ramificadas/especiais) |

## Fórmula de XP canônica

Fonte: `pokemon.domain.xp`

- `xp-por-nivel` = **9** (Pokémon)
- `fator-crescimento-por-nivel` = **1.03**
- `nivel-maximo` = **100**
- Persistido: `xp-desde-nivel` (XP **dentro** do nível atual, não total acumulado)
- Treinador: custos 5, 7, 9, 11… (`progresso-treinador`) — **não** usado no card de MT

Leitura: `xp/progresso-pokemon` → DTO (`xpNivelAtual`, `xpProximoNivel`, `progressoXpPercentual`, `nivelMaximo?`, `xpConhecido?`).

Node **não** inventa fórmula: usa o DTO; fallback do denominador 9 só quando o DTO já marcou XP conhecido.

## Regras de evolução

### Legado (master CLJS)

`verificar-evolucao!` → `tentar-evoluir!` → PokeAPI chain + `proxima-evolucao` (level-up simples).

### A6 JVM (antes)

- `elegivel-nivel?` / `aplicar-evolucao` / `evolve!` (manual, pedra/item)
- `apply-pokemon-xp-tx` **não** evoluía

### A6 JVM (depois)

Catálogo versionado `pokemon.domain.level-evolution` (`catalog-version: pokeapi-level-evolutions-2026-11`):

- Somente cadeias **lineares** por nível
- **Omitidas:** pedra, troca, amizade pura, chuva, golpe conhecido, ramificadas (Eevee, etc.)
- Após XP (`apply-pokemon-xp-tx` / `grant-pokemon-xp!`): se elegível no catálogo → evolui **no mesmo id-pokemon** (preserva shiny, XP, nível, dono, golpes normalizados)

Display no card/CSV: `proxima-evolucao` + `nivel-proxima-evolucao` via `level-evo/proxima` (mesmo catálogo).

## Bugs identificados e corrigidos

1. **CSV XP sempre 0** — lia `:xp`/`:xp-necessario` inexistentes no registro; corrigido para `xp-desde-nivel` + `xp-por-nivel`.
2. **Card sem XP** — `renderTeamGrid` só tinha HP; portado progresso canônico.
3. **Legenda duplicava listagem** — `!pk tm` agora usa caption curta.
4. **XP ausente virava 0** — agora `XP ?` / `xpConhecido? false`.
5. **Sem auto-evo pós-XP no JVM** — restaurada para espécies do catálogo linear (paridade parcial com master; sem PokeAPI no hot path).

### Fora de escopo / preexistente

- `team-a552b` falha em `use-named-team!` ineligible (liga) — não relacionada a TM/XP.
- `formatError` trade gate — falha preexistente em `pokemon-jvm-present.test.cjs`.
- Catálogo level-evo não cobre todas as espécies PokeAPI (só lineares versionadas).
- `!pk mt` (item) ainda ausente de `ALIASES` no present JVM (dead code path) — não alterado nesta entrega.

## Contratos Node ↔ JVM

```
!pk tm|time|meus|equipe
  → resolveCommand = time
  → GET collection (DTO enriquecido)
  → buildTeamPresentationModel / formatCollection / formatCollectionCardCaption
  → renderTeamGrid (Sharp) | texto | getTeamCsv
```

DTO aditivo em `pokemon-dto`:

- `xp-conhecido?`, `nivel-maximo?`, `xp-nivel-atual`, `xp-proximo-nivel`, `xp-necessario`
- `progresso-xp-percentual`
- `proxima-evolucao`, `proxima-evolucao-species`, `nivel-proxima-evolucao`

JSON bridge: kebab → camelCase.

## Colunas CSV

Ordem estável (vírgula, UTF-8 BOM para Excel):

`pokemon_id, numero_pokedex, nome, especie, nivel, xp_total, xp_nivel_atual, xp_proximo_nivel, progresso_xp_percentual, proxima_evolucao, nivel_proxima_evolucao, tipos, situacao, raridade, hp_atual, hp_maximo, status, item, golpes, collection_index`

- Campos ausentes ficam vazios (não fabricados): `numero_pokedex`, `xp_total`
- `pokemon_id` = id público da entidade (não JID)
- Formula injection: prefixo `'` em células iniciadas por `= + - @`

## Segurança

- Export só do save/jogador autenticado na bridge
- Sem JID/telefone/tokens/caminhos de servidor
- Sem dual-write; leituras não concedem XP
- Renderer Sharp não consulta Datomic

## Testes

```bash
node --test test/pokemon-jvm-tm-xp.characterization.test.cjs test/pokemon-jvm-tm-xp.test.cjs
./scripts/test-a6-tm-xp-evolution.sh
```

Cobertura: card-only, txt, CSV, XP parcial/máximo/ausente, evolução linear/exata/acima/sem evo/ramificada omitida, identidade preservada, injection CSV, equivalência XP legado.

## Evidências

Em `test/fixtures/a6-tm-xp/`:

- `team-grid-xp.png`, `xp-baixo.png`, `xp-quase.png`, `sem-evo.png`, `nivel-max.png`, `lista-longa.png`
- `tm-txt-exemplo.txt`
- `odisseu-mt-pokemon.sample.csv`

## Compatibilidade com master

- Alias `tm→time` igual à master
- XP Pokémon 9/nível igual
- Auto-evo por nível: master via PokeAPI; A6 via catálogo versionado (subconjunto)
- CSV filename/colunas mudam (breaking cosmético do anexo; comando igual)

## Riscos e pendências

1. Espécies fora do catálogo não auto-evoluem nem mostram próxima evo (CONDITIONAL).
2. Kirlia→Gardevoir no catálogo; Gallade (pedra) continua manual — correto.
3. Sem WA smoke manual nesta entrega.
4. **Não executado:** deploy, migration, backfill, restart, alteração de saves reais.

## Classificação

**CONDITIONAL** — aceito localmente com evidências e testes verdes no escopo; catálogo de evo incompleto vs PokeAPI total e smoke WhatsApp pendente.
