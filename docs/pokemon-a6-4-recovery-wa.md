# A6.4 — Recuperação V1/V2 pelo WhatsApp (slots independentes)

Fecha o fluxo jogador → prévia → confirmação → importação administrativa,
reutilizando A6.2 (slots dinâmicos) e A6.3 (discover/confirm/import).

## Princípios

- Cassandra permanece **somente leitura** (exportador offline).
- O Node/WhatsApp **nunca** conecta ao Cassandra.
- A JVM carrega um dump autorizado via `ODISSEU_RECOVERY_DUMP_PATH`.
- Identidade do jogador vem só do contexto WhatsApp (`chatId`/`playerId`).
- Alias `@lid` ↔ histórico só se declarado em `:meta :identity-aliases` no dump.
- Importação exige `ODISSEU_RECOVERY_IMPORT_TOKEN` (header `X-Recovery-Import-Token`).
- Slots `recovery-v1` / `recovery-v2` **não** são ativados automaticamente.
- Save ativo e progresso atual permanecem intactos.

## Comandos WhatsApp

| Comando | Efeito |
|---------|--------|
| `!pk recuperar` | Lista versões disponíveis (resumo público) |
| `!pk recuperar v1\|v2\|ambos` | Registra prévia (TTL ~10 min) |
| `!pk recuperar confirmar` | Confirma intenção do jogador |
| `!pk saves` / `!pk save` | Alterna após importação admin |

A confirmação do jogador **não** importa. A importação é operacional.

## HTTP (Bearer bridge)

- `GET /v1/recovery/available`
- `POST /v1/recovery/request` `{chatId, playerId, choice}`
- `POST /v1/recovery/confirm` `{chatId, playerId, requestId?}`
- `GET /v1/recovery/status`
- `POST /v1/recovery/import` + `X-Recovery-Import-Token`

## Operação — dump e import

```bash
# 1) Túnel SSH + export SELECT-only (máquina autorizada)
node pokemon-service/scripts/export-historical-saves.cjs
# ou presença sem payload:
# --probe-player  → {chatId, playerId, versions}

# 2) Colocar dump EDN no host JVM (fora do Git), mode 600
# 3) Reiniciar JVM com:
#    ODISSEU_RECOVERY_DUMP_PATH=...
#    ODISSEU_RECOVERY_IMPORT_TOKEN=...

# 4) Após !pk recuperar confirmar do jogador:
curl -sS -X POST "$JVM/v1/recovery/import" \
  -H "Authorization: Bearer $ODISSEU_BRIDGE_TOKEN" \
  -H "X-Recovery-Import-Token: $ODISSEU_RECOVERY_IMPORT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"requestId":"..."}'
```

## Alias de identidade (dump)

```edn
{:meta {:authorized-offline-copy true
        :identity-aliases
        {"1203...@g.us|999...@lid"
         {:chat-id "1203...@g.us" :player-id "555...@c.us"}}}
 :historical_snapshots [...]}
```

Sem alias, só igualdade exata chat+player.

## Testes

```bash
clojure -M:test -e \
"(require 'clojure.test 'pokemon.tests.recovery-a64-test)
 (let [r (clojure.test/run-tests 'pokemon.tests.recovery-a64-test)]
   (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))"

node --test pokemon-service/test/export-historical-saves.test.cjs
```

## Implantação (sem executar aqui)

- Imagens AMD64 a partir deste branch; Compose com volumes `odisseu-core-a56-*`.
- **Não** `docker compose down -v`.
- Preservar `.wwebjs_auth_core`.
- Migration 025 já no A6.2; validar em cópia descartável do Datomic antes do Core.
- Rollback: remover dump/token de recovery; slots já importados permanecem (não apagar).
