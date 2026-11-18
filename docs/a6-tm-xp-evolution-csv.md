# A6 — `!pk tm`: Time Pokémon, XP, evolução e CSV

**Status:** READY (homologação local; sem deploy / sem alteração de saves reais)

## Escopo e nomenclatura

`!pk tm` é alias de **`time`** (coleção / **Meu Time Pokémon**).

**Não** é Máquina Técnica. O sistema de MT item permanece em `!pk mt` / `!pk aprender` (separado).

Aliases preservados: `tm` / `time` / `meus` / `equipe`.

## Comandos

| Comando | Resposta |
|---|---|
| `!pk tm` / `!pk time` | **Somente card** PNG. Legenda curta: `🎮 Meu Time Pokémon` + nav/atalhos. Sem listagem na caption. |
| `!pk tm txt` | **Somente texto** (sem PNG). Mesmo modelo canônico do card. |
| `!pk tm csv` | Documento CSV `odisseu-time-pokemon.csv` (UTF-8 BOM). Sem PNG. |
| `!pk tm ativo` | Inalterado (ficha do ativo). |

## Fórmula de XP canônica (confirmada)

Fonte: `pokemon.domain.xp` ≡ `zapbot.pokemon.treinador` (master/legado) ≡ fixtures `legacy_xp.edn`.

| Constante | Valor |
|---|---|
| `xp-por-nivel` | **9** |
| `fator-crescimento-por-nivel` | 1.03 |
| `nivel-maximo` | 100 |
| Campo persistido | `xp-desde-nivel` = progresso **parcial** no nível atual (não total acumulado) |
| Vitória / derrota | +3 / +1 XP |

Node espelha `XP_POR_NIVEL_POKEMON = 9` só como fallback quando o DTO já marca XP conhecido.  
`XP ?` = dados desconhecidos; **leituras não mutam** Datomic (teste `leitura-progresso-nao-muta-datomic`).

## Evolução automática (auditoria TX)

### Comportamento

Após `apply-pokemon-xp-tx` / `grant-pokemon-xp!`, se o registro for elegível no catálogo linear `pokemon.domain.level-evolution`, evolui **na mesma TX Datomic**, no **mesmo `id-pokemon`**.

### Proteções

Catálogo **omite** pedra, troca, amizade, chuva, golpe conhecido e ramificadas (Eevee, etc.).  
Testes Datomic: Pikachu / Eevee / Kadabra **não** mudam de espécie com XP.

### Paridade com legado

Um evento de XP → **no máximo uma etapa** evolutiva (igual `verificar-evolucao!` chamado uma vez).  
Charmander que sobe vários níveis vira Charmeleon; Charizard exige evento seguinte.

### Idempotência

| Caminho | Idempotência |
|---|---|
| Captura / ginásio / raide / reward com `operation-id` + receipt | Retry → `:duplicate true`; XP/evo **não** reaplicados |
| `ganhar-xp-pokemon!` (sem receipt) | Cada chamada aplica XP de novo (API interna de teste/admin) |

Identidade preservada: `id-pokemon`, shiny, XP residual, nível, índice na coleção, golpes (normalizados).

### Divergência intencional vs master

Master resolve cadeia via PokeAPI em runtime. A6 usa catálogo versionado offline (`pokeapi-level-evolutions-2026-11`). Espécies fora do catálogo **não** auto-evoluem e **não** inventam próxima evo no card.

## Apresentação (card / txt / csv)

Mesma fonte: `GET collection` → DTO enriquecido → `buildTeamPresentationModel` / CSV `team-csv`.

| Formato | PNG | Listagem | XP / evo |
|---|---|---|---|
| `!pk tm` | sim | só na imagem | no PNG |
| `!pk tm txt` | não | texto WhatsApp | no texto |
| `!pk tm csv` | não | arquivo | colunas |

## CSV

- Filename: `odisseu-time-pokemon.csv`
- UTF-8 com BOM (Excel)
- Separador `,` (RFC 4180)
- Anti formula-injection (`'` em `= + - @`)
- Campos ausentes vazios (`numero_pokedex`, `xp_total`)
- `pokemon_id` = id público da entidade (não JID)
- Somente leitura; sem importação

## Testes

```bash
node --test test/pokemon-jvm-tm-xp.characterization.test.cjs test/pokemon-jvm-tm-xp.test.cjs
./scripts/test-a6-tm-xp-evolution.sh
```

Datomic descartável cobre: XP canônico, auto-evo, especiais protegidas, multi-nível uma etapa, ultrapassado, nível máx, CSV BOM/injection, sync card/txt/csv, leitura sem mutação.

### Preexistentes (fora de escopo)

- `formatError` trade gate em `pokemon-jvm-present.test.cjs`
- `team-a552b` falha liga bronze / `use-named-team!` ineligible (CSV filename já alinhado)

## Evidências

`test/fixtures/a6-tm-xp/`:

- `team-grid-xp.png`, `xp-baixo.png`, `xp-quase.png`, `sem-evo.png`, `nivel-max.png`, `lista-longa.png`
- `tm-txt-exemplo.txt`, `tm-card-caption.txt`
- `odisseu-time-pokemon.sample.csv`

## Commits desta finalização

(além dos 6 commits anteriores `bb32fd7`…`9e24fa1`)

- Nomenclatura Time Pokémon + CSV `odisseu-time-pokemon.csv`
- Testes TX Datomic de evolução / XP / CSV
- Docs READY

## Restrições respeitadas

Sem deploy, migration, backfill, restart, alteração de saves reais, raids automáticas ou Announcer.

## Classificação

**READY** — nomenclatura correta; três comandos consistentes; XP canônico 9; auto-evo transacional com especiais protegidas e uma etapa por evento; CSV válido; testes locais verdes no escopo.
