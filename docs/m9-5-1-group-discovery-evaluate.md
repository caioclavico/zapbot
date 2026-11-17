# M9.5.1 — ReferenceError em page.evaluate + HTTP discover

## Causa raiz

Puppeteer `page.evaluate(fn)` serializa **apenas** o corpo de `fn` e recria a função no contexto do WhatsApp Web. Helpers do módulo Node (`helpersDisponiveis`, `getChatModels`, …) **não** existem lá → `ReferenceError: helpersDisponiveis is not defined`.

O `curl (56) Recv failure: Connection reset by peer` vinha do handler HTTP disparar `page.evaluate` (quebra) no mesmo tick da resposta — ou do processo/socket em estado ruim após o erro — sem garantir JSON + `Connection: close` antes do trabalho assíncrono.

## Correções

1. `discoverGroupsInPage` agora declara **todos** os helpers como `function` aninhadas (autossuficiente).
2. `runDiscoverInIsolatedContext` nos testes simula evaluate sem escopo do módulo.
3. Endpoint `/bootstrap/discover-groups`: status síncrono (`ready`/`busy`/`not_ready`/`error`) → JSON imediatamente → `setTimeout(0)` agenda a descoberta.
4. Concorrência: `discovery-inflight?` + HTTP 409.
5. `groups.json`: tentativa vazia **não** apaga grupos anteriores sem `staleGroupsPreserved` / `incomplete` / `awaitingSync`.
6. Continua sem `getChatModel` / sem msgs privadas / sem inventar `DEV_GROUP_ID`.

`cutover-ready?=false`.

## Atualizar só o Node

```bash
# NÃO: down -v | rm -rf .wwebjs_auth_core
docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  --profile with-node up -d --build --no-deps odisseu-node

# Após READY:
curl -sS -X POST http://127.0.0.1:3001/bootstrap/discover-groups
# → 202 accepted | 409 busy | 503 not_ready
```

## Testes

```bash
node --test test/bootstrap-group-discovery.test.cjs test/bootstrap-discover-http.test.cjs
clojure -M:test-m9-5-group-discovery
clojure -M:test-m9-4-bootstrap-discovery
npx shadow-cljs compile app
```
