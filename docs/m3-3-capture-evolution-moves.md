# M3.3 — Captura, evolução e golpes (JVM/Datomic)

## Escopo

| Operação | API | Fence | Recibo |
|---|---|---|---|
| Captura | `pokemon.application.capture/attempt-capture!` | CAS ativo+geração | kind `capture` |
| Evolução | `pokemon.application.evolution/evolve!` | idem | kind `evolve` |
| Golpe | `pokemon.application.moves/learn-move!` | idem | kind `learn-move` |

Batalhas, ginásios e raids **não** migrados. Admin seed continua explícito e fora do caminho de gameplay.

## Regras portadas (legado → domain)

- `chance-captura` / `chance-base-pos-batalha` / `chance-com-bola` / `fuga-raridade` (`core.cljs`)
- Recompensa pós-captura: XP por raridade + bônus de sequência + primeira tentativa; moedas `2+min(10,seq)` (`encerrar-cacada!`)
- Capacidade de coleção (`armazenamento-regras` via `pokemon.domain.capacity`)
- `evoluir-no-indice!` (stats/imagem/shiny/HP; desmaiado permanece 0)
- `preparar-aprendizado` + `garantir-ataque-do-tipo` + máximo 4 golpes

## Garantias

1. Mutações exigem `operation-id` + save ativo + `fence-generation`
2. Mesma TX: fence + inventário/coleção/sequência/recibo
3. Callback A após B → `:stale_writer_fence`
4. Retry mesmo payload → `:duplicate true`
5. Preserva shiny, id-pokemon, HP, status, golpes (EDN)

## Como rodar

```bash
./scripts/test-m3-capture-datomic.sh
git diff --check
```

## Resultados (2026-10-17)

| Suite | Resultado |
|---|---|
| capture-m33 | 11 tests / 56 asserts OK |
| reward + xp + collection + M2 | 23 tests / 124 asserts OK |
| `git diff --check` | limpo |
| RSS | ~231→267 MB (`-Xmx256m`) |

## Divergências

- IVs: atributo reservado (`:pokemon/ivs-edn`); legado atual não gera IVs
- Catálogo completo de nomes PT dos golpes permanece no Node (`golpes.cljs`); JVM usa slug normalizado
- Resolução de cadeia evolutiva (PokeAPI) continua fora do domínio — caller passa stats prontos
- XP de Pokémon ativo na captura só aplica se já houver Pokémon na coleção (sem batalha migrada)
