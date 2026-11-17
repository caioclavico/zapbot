# M7.2 — Enquetes via Datomic (somente staging)

**Status:** integração funcional em staging. Produção permanece Cassandra.  
**Base:** `a189aaf`  
**Sem dual-write. Sem cutover de produção.**

## Contratos públicos preservados (Node)

| API | Comportamento |
|---|---|
| `!enquete Pergunta \| Op1 \| Op2 …` | cria (2–10 opções); uma ativa por chat |
| `!enquete` | mostra enquete ativa |
| `!enquete fechar` | encerra e mostra resultado |
| `!votar <n>` | registra/substitui voto do eleitor (1-based) |

Sem timers, sem deadline, sem mensagens WhatsApp além da resposta do comando.

## Seleção de backend

Mesmo gate M7.1: `PLATFORM_ADAPTER=datomic` só em `development|staging`. Produção força Cassandra.

## Endpoints JVM

- `GET /v1/platform/polls` (`?chatId=` opcional)
- `POST /v1/platform/polls/create`
- `POST /v1/platform/polls/vote`
- `POST /v1/platform/polls/close`

Schema: `migrations/platform/002-enquetes.edn`.

## Cache Node

Atom local só atualiza **depois** do commit Datomic. Boot staging hidrata enquetes junto com bloqueio/admins; falha da bridge impede atendimento (fail-closed).

## Idempotência

`operationId` protege retries HTTP do mesmo create/vote/close. O legado Node não tinha recibos — documentado como diferença. Reenvio WhatsApp gera novo `operationId` (como no legado).

## Testes

```bash
./scripts/test-m7-2-enquetes.sh
./scripts/test-m7-1-platform.sh
./scripts/test-m7-0-platform.sh
./scripts/staging-sim.sh
```

## Próximo (M7.3)

**Quiz-histórico** → ver `docs/m7-3-quiz-historico-staging.md`.  
M7.4 sugerido: **rank** (dedupe HTTP) ou **lembretes**.
