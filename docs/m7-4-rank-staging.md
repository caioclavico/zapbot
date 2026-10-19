# M7.4 — Rank via Datomic (somente staging)

**Status:** integração funcional em staging. Produção permanece Cassandra.  
**Base:** `42f08e5`  
**Sem dual-write. Sem cutover.**

## Produtores de pontuação

| Produtor | Op | ID estável |
|---|---|---|
| `!velha` vitória | `pontuar!` | `rank:velha:{msgId}` |
| `!naval` vitória | `pontuar!` | `rank:naval:{msgId}` |
| `!quiz` acerto | `pontuar!` | `rank:quiz:{msgId}` |
| `!adedonha` vencedores | `pontuar!` | `rank:adedonha:{rodadaId}:{pid}` |
| Pokémon HTTP | `aplicar-efeito!` | `effect.id` da JVM |
| Pokémon core (legado) | `pontuar!`/`penalizar!` | `batalha-id` / msgId |

## Contrato

`chat → player → {nome, pontos, jogos{}, efeitos-pokemon-http?}`  
Top 10 por pontos (omitindo `somente-efeitos-http`). Empate: ordenação secundária por nome.

## Idempotência

- Mesmo `operationId` + mesmo payload → duplicate, sem reaplicar.
- Eventos distintos (msgs/rodadas/efeitos diferentes) → pontuações independentes.
- Adedonha: `:id` UUID gerado **uma vez** no início da rodada (não a cada retry).

## Endpoints

- `GET /v1/platform/rank`
- `POST /v1/platform/rank/score|penalize|effect`

Schema: `migrations/platform/004-rank.edn`.

## Testes

```bash
./scripts/test-m7-4-rank.sh
./scripts/staging-sim.sh
```

## Próximo (M7.5)

**Lembretes** — timers + single-poller; risco de twin writers.
