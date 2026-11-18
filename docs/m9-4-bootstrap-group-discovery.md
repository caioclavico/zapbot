# M9.4 — Descoberta de grupos após READY + path sessão core

## Causa provável

O evento `ready` do `whatsapp-web.js` dispara quando o Client considera a sessão autenticada, **antes** de `window.WWebJS.getChats` / `Store.Chat` estarem injetados na página do WhatsApp Web.

`Client.getChats` (Client.js ~1655) faz `pupPage.evaluate(() => WWebJS.getChats())`. Se o helper ainda não existe, o Puppeteer devolve erros opacos do tipo `r: r` — exatamente o visto na odisseu-core com `STAGING_BOOTSTRAP_GROUP=1`.

Causa secundária (bloqueava o boot em staging): o Compose M9.1 montava `WWEBJS_DATA_PATH=/app/.wwebjs_auth`, path que o gate de staging rejeita (é o path de produção). A VM já tinha correção local; o repositório agora alinha para `/app/.wwebjs_auth_core`.

## Correção

1. **Espera + retries com backoff** (6 tentativas) antes/depois de `getChats`, checando `WWebJS.getChats` via `page.evaluate`.
2. **Fallback seguro**: `page.evaluate` lista só `id` + `name` de chats de grupo (`WWebJS` ou `Store.Chat`) — sem ler mensagens privadas.
3. **Diagnóstico**: loga `name`/`message` truncados; **não** grava telefone (`connectedUser: "redacted"`).
4. **Exceções**: falhas viram `console.warn`/`error` controlados; sessão LocalAuth **não** é limpa.
5. **Bootstrap**: continua sem `message_create`, sem comandos, sem replies.
6. **Compose**: `WWEBJS_DATA_PATH=/app/.wwebjs_auth_core` e bind `./.wwebjs_auth_core:/app/.wwebjs_auth_core` (idem staging → `_staging`).

`cutover-ready?=false`. Produção odisseu-zapbot intocada.

## Atualizar o Node na odisseu-core **sem** limpar sessão

Na VM, no diretório do compose (com `.env.odisseu-core` e pasta `.wwebjs_auth_core` já existentes):

```bash
# 1) Atualizar código (git pull / rsync) — NÃO apagar .wwebjs_auth_core
# 2) Confirmar que o yml já tem /app/.wwebjs_auth_core (não /.wwebjs_auth)

grep -n 'WWEBJS_DATA_PATH\|wwebjs_auth' docker-compose.odisseu-core.yml

# 3) Recriar SÓ o serviço Node (JVM/Datomic e volumes ficam)
docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  --profile with-node up -d --build --no-deps odisseu-node

# 4) NÃO use:
#    docker compose down -v
#    rm -rf .wwebjs_auth_core
#    docker volume prune
```

Se a VM já tinha bind antigo `./.wwebjs_auth_core:/app/.wwebjs_auth` e a sessão está em `session-core/` dentro dessa pasta do host, o novo bind `:/app/.wwebjs_auth_core` continua apontando para a **mesma pasta do host** — o conteúdo da sessão não muda; só o path **dentro** do container.

Com `STAGING_BOOTSTRAP_GROUP=1`, após READY espere logs `[bootstrap]` com retries e, se necessário, `Fallback Store OK`. Depois: preencher `DEV_GROUP_ID`, `STAGING_GROUP_CONFIRMED=1`, tirar bootstrap e reiniciar o Node.

## Testes locais

```bash
clojure -M:test-m9-4-bootstrap-discovery
```

## Segurança

- Sem deploy remoto neste milestone (código + docs + testes).
- Sem limpar sessões, sem desconectar WhatsApp, sem alterar Datomic.
- Sem processar comandos no modo bootstrap.
