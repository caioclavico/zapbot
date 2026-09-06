# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## O que é

Bot de WhatsApp escrito em **ClojureScript** (compilado com shadow-cljs para Node.js), usando `whatsapp-web.js` (automação não oficial via Puppeteer/Chromium). Comandos, mensagens de log e docs são em português (pt-BR) — mantenha esse idioma em qualquer texto voltado ao usuário/commits/comentários.

## Comandos

```bash
npm run dev     # shadow-cljs watch: só compila e observa mudanças, NÃO inicia o bot
npm run build   # shadow-cljs release: compila para target/main.js
npm start        # node target/main.js: efetivamente conecta ao WhatsApp
```

Fluxo normal de desenvolvimento: `npm run dev` num terminal (recompila a cada salvamento) e `node target/main.js` em outro para rodar o bot de verdade. Não há suíte de testes automatizados neste projeto nem linter configurado.

Primeira execução gera um QR code no terminal para escanear com o WhatsApp (Aparelhos conectados); a sessão persiste em `.wwebjs_auth/`.

Scripts standalone adicionais (definidos em `shadow-cljs.edn`, cada um com seu próprio build/entrypoint):
- `zapbot.migrar-estado/-main` → `target/migrar-estado.js` (migração de chaves legadas no Cassandra)
- `zapbot.resetar-pokemon/-main` → `target/resetar-pokemon.js` (reset do roster de `!pokemon`)

Compile-os manualmente via shadow-cljs se precisar rodá-los (não fazem parte do `npm run build` padrão, que só compila o build `:app`).

### Docker

```bash
docker compose up -d cassandra    # só o banco, pra rodar o bot fora do container em dev
docker compose up -d --build      # bot + Cassandra juntos (produção/deploy)
```

O `Dockerfile` é pensado para ARM64 (ex.: Oracle Cloud Free Tier) e usa o Chromium do sistema via `apt` em vez do download automático do Puppeteer (`PUPPETEER_EXECUTABLE_PATH`).

## Arquitetura

### Fluxo de mensagem

`zapbot.core/main` conecta o `Client` do whatsapp-web.js e liga os eventos (`qr`, `ready`, `message_create`, `group_admin_changed`, etc). Toda mensagem recebida passa por `on-message`:

1. `zapbot.historico/registrar!` — guarda no histórico em memória (usado por `!resuma`, `!sorteio`)
2. `zapbot.adedonha/capturar-resposta!` — captura respostas de rodadas de adedonha em andamento
3. `zapbot.router/processar` — se a mensagem começa com o prefixo (`!`), tokeniza, resolve aliases (ex.: `tempo`/`clima` → `previsao`), checa bloqueio (`zapbot.bloqueio`) e despacha (`case` em `despachar`) para o namespace do comando. Retorna uma promise (via `promesa.core`) que resolve para `nil` (ignorar), uma string (resposta simples) ou `{:texto :mentions}` (resposta que precisa marcar alguém com @, ex. `!pokemon`).

`zapbot.core/main` espera `zapbot.armazenamento/iniciar!` completar (carregar estado do Cassandra) antes de chamar `.initialize` no client — isso é assíncrono mas nunca bloqueia o boot: se o Cassandra não conectar, o bot sobe sem persistência.

### Cada comando é seu próprio namespace

`src/zapbot/*.cljs` — a lista completa de comandos com descrições vive em `zapbot.router/comandos` (também usada para gerar `!ajuda`). Ao adicionar um comando novo: criar o namespace, adicionar ao `require` e `case` de `zapbot.router`, e adicionar entrada em `comandos`.

Namespaces que merecem atenção por não serem óbvios pelo nome:
- `zapbot.bloqueio` — bloqueio de comandos/jogos/bot por chat (admin), checado antes de todo despacho
- `zapbot.admins` — cadastro de admins conhecidos por grupo, alimentado passivamente pelo evento `group_admin_changed` (mais confiável que `getChatModel` ao vivo, que falha persistentemente em alguns grupos)
- `zapbot.historico` — só guarda mensagens vistas desde que o bot foi ligado (não busca histórico completo do grupo) — por isso `!resuma` e `!sorteio` só têm efeito sobre atividade recente
- `zapbot.pokemon`/`zapbot.pokedex`/`zapbot.treinador`/`zapbot.loja` — sistema de captura/batalha/evolução de Pokémon, com nível de treinador desacoplado do rank e economia de moedas via `zapbot.loja`
- `zapbot.rank` — pontuação persistida por chat (vitórias em `!velha`, `!naval`, `!pokemon`, `!quiz`)
- `zapbot.velha`, `!naval`, `!adedonha`, `!quiz` — estado de jogo em memória por chat (não sobrevive a restart, exceto placar via `zapbot.rank`)

### Persistência (Cassandra)

`zapbot.armazenamento` é a única camada de persistência: uma tabela `<keyspace>.estado (chave text PRIMARY KEY, valor text)`, onde `valor` é um JSON por namespace/chave lógica (`rank`, `loja`, `admins-conhecidos`, `participantes`, `quiz-historico`, `bloqueio`, etc). Não há ORM nem múltiplas tabelas — cada namespace serializa sua própria estrutura para/de JSON.

Padrão obrigatório para qualquer namespace com estado persistido (ver docstring de `zapbot.armazenamento` para detalhes completos):
1. `defonce` do atom local com valor padrão (ex.: `{}`), criado no carregamento do namespace
2. `armazenamento/registrar!` logo em seguida, para receber `reset!` automático quando `iniciar!` terminar de carregar do Cassandra (ou na hora, se `iniciar!` já tiver terminado)
3. `armazenamento/obter` para leituras síncronas (pode retornar `nil` antes de `iniciar!` completar — normal apenas na primeiríssima inicialização)
4. `armazenamento/salvar!` para escritas: atualiza o cache local na hora, grava no Cassandra em segundo plano (não bloqueia, só loga erro)

Convenção: chaves e estrutura salva usam sempre strings (nunca keywords), para round-trip exato com `clj->js`/`js->clj`. Sets viram vetores ao salvar e voltam a ser sets ao carregar — cada namespace é responsável por essa conversão.

`iniciar!` tenta reconectar algumas vezes se o Cassandra ainda não subiu (5 tentativas, 3s de intervalo); se falhar de vez, o bot sobe sem persistência nessa execução em vez de travar.

### Configuração

`zapbot.config` lê tudo de variáveis de ambiente (via `dotenv`, arquivo `.env`) com defaults sensatos — nunca acessar `process.env` diretamente fora desse namespace. Ver `.env.example` para a lista completa de variáveis (chaves de API externas: TMDB, Gemini, Spotify — todas opcionais, cada comando degrada/avisa se a chave faltar).

### Compilação (shadow-cljs)

Build `:app` usa `:optimizations :simple` (não `:advanced`) deliberadamente — `:advanced` faria o Closure Compiler renomear propriedades, quebrando a interop via `.-prop`/`(.metodo obj)` com objetos npm (itens do `rss-parser`, `Chat`/`Contact`/`Message` do whatsapp-web.js, etc).
