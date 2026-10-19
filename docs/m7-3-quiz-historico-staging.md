# M7.3 — Quiz-histórico via Datomic (somente staging)

**Status:** integração funcional em staging. Produção permanece Cassandra.  
**Base:** `ef14190`  
**Sem dual-write. Sem cutover. Rank NÃO migrado.**

## Contratos preservados

| Item | Comportamento |
|---|---|
| Shape | `chatId → [texto-pergunta]` (últimas 12) |
| Append | ao **iniciar** pergunta (`!quiz`), não ao responder |
| Anti-repetição | banco estático filtra textos já usados; IA recebe histórico no prompt |
| Rodada ativa | atom `quizzes` só em memória |
| Rank | `rank/pontuar!` só no acerto — **continua Cassandra** |
| Participantes | não usados pelo Quiz |

## Fronteira com rank

Histórico e pontuação **não** compartilham transação no legado:
- histórico grava no start;
- rank grava no acerto.

Migração independente é segura. Sem proposta de tx conjunta necessária nesta etapa.

## Endpoints JVM

- `GET /v1/platform/quiz-history` (`?chatId=` opcional)
- `POST /v1/platform/quiz-history/append` `{chatId, question, operationId}`

Schema: `migrations/platform/003-quiz-historico.edn`.

## Node

`PLATFORM_ADAPTER=datomic` (staging/dev). Cache atualiza só após commit. Boot hidrata histórico antes de aceitar rodadas. Fail-closed sem fallback Cassandra.

## Testes

```bash
./scripts/test-m7-3-quiz.sh
./scripts/test-m7-2-enquetes.sh
./scripts/test-m7-1-platform.sh
./scripts/staging-sim.sh
```

## Cassandra ainda usado pelo Quiz

- `rank` (pontuação no acerto)
- (não usa `participantes`)

## Próximo (M7.4 sugerido)

**Rank** — ou **lembretes** se priorizar timers/single-poller.
