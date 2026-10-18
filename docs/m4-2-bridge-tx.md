# M4.2 — Bridge transacional Node ↔ JVM

Primeiro fluxo completo de gameplay (leitura + escrita + A↔B) via adaptador Node → bridge loopback → Datomic Local. Isolado em **development**; sem cutover de produção.

## Contratos HTTP (explícitos por caso de uso)

Auth Bearer em rotas de domínio. Bind `127.0.0.1` apenas. Sem endpoint genérico de mutação e sem `/admin*`.

| Método | Path | Body / query | Efeito |
| --- | --- | --- | --- |
| GET | `/health` | — | `capabilities` + status |
| GET | `/v1/trainer` | `chatId`, `playerId` | M4.1 |
| GET | `/v1/collection` | + `saveId?` | M4.1 |
| GET | `/v1/active-save` | | M4.1 |
| GET | `/v1/saves` | | lista A/B + fence |
| POST | `/v1/trainer/ensure` | `{chatId,playerId}` | cria treinador+saves se preciso |
| POST | `/v1/saves/activate` | `{chatId,playerId,saveId,expectedFenceGeneration?}` | ativa save (fence↑) |
| POST | `/v1/rewards/coins` | `{chatId,playerId,saveId,fenceGeneration,operationId,coinsDelta}` | mutação representativa |
| GET | `/v1/operations/:operationId` | | reconciliação pós-timeout |

Erros estruturados (HTTP):

| code | status | significado |
| --- | --- | --- |
| `stale_writer_fence` | 409 | save/fence obsoleto |
| `writer_fence_conflict` / `activate_conflict` | 409 | corrida CAS |
| `receipt_payload_mismatch` | 409 | mesmo `operationId`, payload diferente |
| `operation_pending` | 409 | mesmo `operationId` ainda em voo |
| `operation_not_found` | 404 | recibo ausente (pré-commit) |
| `unauthorized` | 401 | Bearer inválido |

## Identidade e idempotência

- Node obtém `chatId`/`playerId` do contexto WhatsApp (`from`/`author`), **não** do texto digitado.
- Escritas exigem `saveId` + `fenceGeneration` + `operationId` explícitos (congelados numa leitura prévia).
- Retry **deve** reutilizar o mesmo `operationId`. O cliente NÃO gera id novo em timeout; tenta `GET /v1/operations/:id` e, se 404, reenvia o mesmo body.
- Falha de JVM → erro de rede/timeout; **sem** fallback Cassandra.

## WhatsApp (só `POKEMON_ADAPTER=jvm` + `APP_ENV=development`)

| Comando | Ação |
| --- | --- |
| `!pk iniciar` | ensure |
| `!pk ativar a\|b` | activate com fence esperado |
| `!pk moedas N` | creditCoins (fence do save ativo) |
| leituras M4.1 | inalteradas + `saves-list` |

## Arquivos

- `modules/pokemon/application/.../bridge_tx.clj`
- `modules/pokemon/infrastructure/.../http/bridge.clj` (GET+POST)
- `scripts/lib/pokemon-jvm-client.cjs`
- `apps/odisseu/src/odisseu/smoke/m4_tx.clj`
- `scripts/test-pokemon-jvm-tx-contract.cjs`, `scripts/test-m4-tx.sh`
- `src/zapbot/pokemon_http.cljs`, `docs/m4-2-bridge-tx.md`

## Testes

```bash
chmod +x scripts/test-m4-tx.sh
./scripts/test-m4-tx.sh
```

Cobre: Node↔JVM↔Datomic; A→B→A; fence obsoleto; retry idempotente; reconcile; corrida escrita×activate; JVM down; admin 404; reabertura DB; regressões M2 + M3.2 + M4.1.

## Fora de escopo (ainda legado)

Captura, batalha, ginásio, raid, Adventure, loja, mídia/eventos — sem bridge nesta etapa.

## Riscos restantes

1. `operation_pending` só cobre concorrência no mesmo processo JVM (mapa in-memory).
2. Timeout real pós-commit depende do cliente reconciliar; não há outbox Node.
3. Renderização WhatsApp ainda é texto mínimo (sem envelope rico do pokemon-service).
4. Produção continua forçando `POKEMON_ADAPTER=legacy`.
