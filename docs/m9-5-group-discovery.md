# M9.5 — Descoberta leve de grupos no WhatsApp Web

## Diagnóstico (causa sustentada pelo código)

Na odisseu-core, após READY:

1. `Client.getChats` → `pupPage.evaluate(WWebJS.getChats)` → para **cada** chat chama `WWebJS.getChatModel`.
2. `getChatModel` (Utils.js) só marca `isGroup = true` se `chat.groupMetadata` existir, e ainda faz `await groupMetadata.update(chatWid)` (participantes). Isso explica o erro opaco `r: r` no Puppeteer.
3. O fallback M9.4 chamava o **mesmo** `WWebJS.getChats()` quando o helper existia — ou filtrava `Store.Chat` por `m.isGroup` (campo que **não** existe nos models crus). Resultado: `groups: []` sem exceção, mesmo com o grupo Pokémon visível no app.

Caminho correto (biblioteca atual): `window.require('WAWebCollections').Chat.getModelsArray()` e detectar grupo por `id.server === 'g.us'` / `id.isGroup()` / sufixo `@g.us` / presença de `groupMetadata` — **sem** `getChatModel`.

## Correção

| Item | Comportamento |
|------|----------------|
| Descoberta principal | `scripts/lib/bootstrap-group-discovery.cjs` via `page.evaluate` |
| getChats | Opcional/diagnóstico; falha não aborta |
| Diagnóstico | `chatCount`, `groupCount`, `idServerCounts`, helpers — sem telefones/msgs |
| Re-descoberta | `POST|GET http://127.0.0.1:3001/bootstrap/discover-groups` (só bootstrap) |
| Auto-retry | Timers após esgotar tentativas (15s/45s/90s/180s), sem reiniciar Chromium |
| DEV_GROUP_ID | Nunca inventado |
| STAGING_GROUP_CONFIRMED | Permanece false no JSON; operador confirma manualmente |
| Sessão | LocalAuth intocada |

`cutover-ready?=false`.

## Atualizar só o Node (sessão preservada)

```bash
# Na VM odisseu-core — NÃO use down -v / rm sessão
git pull   # ou rsync do commit M9.5

grep -n 'WWEBJS_DATA_PATH\|wwebjs_auth_core' docker-compose.odisseu-core.yml

docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  --profile with-node up -d --build --no-deps odisseu-node

# Após READY:
docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  logs -f --tail=100 odisseu-node
# Procure: [bootstrap] diagnóstico … groupCount≥1

# Re-disparar sem reiniciar Chromium:
curl -sS -X POST http://127.0.0.1:3001/bootstrap/discover-groups
```

Com grupo listado: copie o `id` (`…@g.us`) para `DEV_GROUP_ID`, defina `STAGING_GROUP_CONFIRMED=1`, remova `STAGING_BOOTSTRAP_GROUP`, reinicie só o Node.

## Testes locais

```bash
node --test test/bootstrap-group-discovery.test.cjs
clojure -M:test-m9-5-group-discovery
clojure -M:test-m9-4-bootstrap-discovery
```
