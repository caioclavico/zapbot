# M4.4 — Primeira captura real pela bridge JVM

Integra `!pk capturar` ao fluxo Node → bridge loopback → Datomic, reutilizando as fórmulas M3.3 sem duplicar regras no Node.

## Caça (`!pk cacar`)

Spawn completo migrado no **M4.5** (ver `docs/m4-5-hunt-encounters.md`). M4.4 cobre a captura pós-vitória.

## Recorte seguro (captura)

| Passo legado | JVM M4.4 |
| --- | --- |
| Vitória selvagem → `preparar-captura-pos-batalha` | Turno de batalha carimba `chance-base-captura` + `tentativas-captura=0` e **mantém** `active-battle-id` enquanto `aguardando-captura?` |
| `!pk capturar <bola>` | `POST /v1/encounter/capture` com save-id + fence + operationId |
| Consumo de bola + resultado | Mesma TX Datomic (inventário + estado da batalha + coleção/XP/moedas) |
| Idempotência | Recibo `encounter-capture` + payload-hash; cliente reconcilia TIMEOUT via `GET /v1/operations/:id` |
| Alternância A↔B no meio | Bloqueada (`battle_pending`) enquanto aguarda captura |
| Callback de save antigo | `stale_writer_fence` / `battle_save_mismatch` |

## Comandos JVM

Requer `APP_ENV=development` e `POKEMON_ADAPTER=jvm`.

| Comando | Status |
| --- | --- |
| `!pk capturar` / `cap` `[bola\|1\|2\|3]` | **Integrado** — menu sem args; tentativa com bola |
| `!pk cacar` | Ver M4.5 |
| Demais (batalhar PvP, gin*, raid*, loja, …) | Continua indisponível no adaptador |

Harness HTTP (dev/e2e, não WhatsApp): `POST /v1/battles/start`, `POST /v1/battles/turn`, `GET /v1/encounter`.

## Imagens

Cartões PNG da caça/bola do legado **não** são gerados no caminho JVM: dependem do pipeline de mídia do pokemon-service (PokeAPI + sharp) e não há Cassandra. Respostas são texto no envelope `{messages,effects}`. Se o encontro carregar URLs de sprite no futuro, a renderização pode ser reativada no Node sem regras de captura.

## Arquivos

- `store.clj` — `attempt-encounter-capture!`; ponteiro pós-vitória; A↔B bloqueado
- `bridge_reads.clj` / `bridge_tx.clj` / `bridge.clj` — encounter + capture (+ battle harness)
- `pokemon-jvm-client.cjs` / `pokemon-jvm-present.cjs` / `pokemon_http.cljs`
- testes: `encounter_m44_test.clj`, `pokemon-jvm-capture.test.cjs`, `scripts/test-m4-capture.sh`

## Diferenças de comportamento vs legado

1. Sem spawn/`cacar` no JVM.
2. Sem cartão de imagem da bola/caça.
3. Missões semanais / `registrar-missao!` / eventos de captura ainda não portados.
4. Rolls de captura/fuga são RNG no JVM (testes injetam `roll`/`fugaRoll` via HTTP).

## Testes

```bash
./scripts/test-m4-capture.sh
```

Cobre: sucesso/fuga, uma bola, coleção/XP/moedas, retry/timeout, A↔B, concorrência, isolamento, regressões M3.3/M3.4/M4.1–M4.3.
