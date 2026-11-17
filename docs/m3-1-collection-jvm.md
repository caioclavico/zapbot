# M3.1 — Treinador e coleção no Clojure/JVM

**Status:** implementado (dev, sem migração Cassandra)  
**Branch:** `refactor/modular-monolith-datomic`

## Escopo migrado

| Regra legado (`pokemon-service`) | Destino JVM |
|---|---|
| `adicionar-pokemon!` / `pokemon->registro` | `pokemon.application.collection/adicionar-pokemon!` |
| `equipe` / coleção unificada | `list-collection` + `consultar-treinador` |
| `atualizar-no-indice!` (HP/status) | `atualizar-hp-status!` |
| `ganhar-xp-no-indice!` / `normalizar-xp-registro` | `pokemon.domain.xp` + `ganhar-xp-pokemon!` |
| `progresso-treinador` / `xp-treinador` / vitórias | `perfil-treinador` + `registrar-vitoria-treinador!` |
| moedas / inventário (recorte) | `creditar-moedas!` / `ajustar-inventario!` com recibo |

## Writer fence (save ativo)

Toda mutação de gameplay M3.1 congela `{save-id, fence-generation}` e CAS-a ambos na TX. Após A→B, writers de A falham com `:stale_writer_fence`.

## Fora deste recorte (preservado no legado)

- Golpes / aprendizado / reaprender  
- Ginásios, raids, Adventure, PvP  
- Insígnias derivadas do catálogo (M3.1 usa cache `xp-insignias`)  
- Capacidade PC/mochila e loja completa  
- Recompensas: ver M3.2 — gameplay com fence; admin-seed explícito; `grant-reward!` bloqueado

## Como rodar

```bash
./scripts/test-m3-collection-datomic.sh
git diff --check
```

## Divergências documentadas

1. **Insígnias/missões:** legado recalcula XP de insígnias do catálogo; JVM soma campos persistidos (`xp-insignias` / `xp-missoes`), default 0 até import.
2. **Golpes e raridade API:** não migrados; registro JVM guarda stats/tipos/shiny/HP necessários ao recorte.
3. **Recompensas (atualizado em M3.2):** gameplay unificado com fence; seed admin explícito; sem bypass silencioso.

## Resultados (2026-10-17)

| Suite | Resultado |
|---|---|
| xp-equivalence | 2 tests / 23 asserts OK |
| collection-m31 | 6 tests / 37 asserts OK |
| trainer-saves (M2) | 7 tests / 41 asserts OK |
| `git diff --check` | limpo |
| Heap / RSS | ~41–54 MB heap / ~247 MB RSS (`-Xmx256m`) |
