# ZapBot 🤖

Bot para WhatsApp escrito em **ClojureScript**, rodando sobre Node.js com a
biblioteca [whatsapp-web.js](https://github.com/pedroslopez/whatsapp-web.js)
(automação do WhatsApp Web via navegador, não oficial).

## Funcionalidades

| Comando                    | Descrição                                                     |
|-----------------------------|----------------------------------------------------------------|
| `!piada`                    | Conta uma piada aleatória                                      |
| `!curiosidade`              | Conta uma curiosidade aleatória                                |
| `!noticias`                 | Mostra as últimas manchetes (feed RSS configurável)             |
| `!cotacao [PAR ...]`        | Cotação de moedas, ex.: `!cotacao USD-BRL EUR-BRL BTC-BRL`      |
| `!previsao [cidade]`        | Previsão do tempo para os próximos dias                        |
| `!horoscopo <signo>`        | Horóscopo do dia (em português: aries, touro, gemeos, ...)     |
| `!ban`                      | Remove do grupo quem for citado/marcado (apenas admins)         |
| `!ajuda`                    | Lista os comandos disponíveis                                  |

O prefixo (`!`) e os padrões de cada comando podem ser alterados no arquivo `.env`.

> ⚠️ whatsapp-web.js é uma biblioteca **não oficial** que automatiza o WhatsApp
> Web via Puppeteer/Chromium. Use por sua conta e risco e evite enviar spam —
> contas podem ser bloqueadas pelo WhatsApp caso o uso seja abusivo.

## Requisitos

- [Node.js](https://nodejs.org/) 18 ou superior (necessário para `fetch` nativo)
- npm

## Instalação

```bash
npm install
cp .env.example .env
# edite o .env se quiser mudar prefixo, cidade padrão, moedas, admins, etc.
```

## Executando

**Para rodar o bot de verdade (conectar ao WhatsApp), use sempre:**

```bash
npm run build
npm start
```

Na primeira execução, um **QR code** aparecerá no terminal. Escaneie-o pelo
WhatsApp do celular em *Aparelhos conectados > Conectar aparelho*. A sessão
fica salva em `.wwebjs_auth/`, então não será necessário escanear novamente

> ⚠️ `npm run dev` (`shadow-cljs watch app`) **apenas compila e fica observando
> mudanças no código** — ele não inicia o bot sozinho. É normal ver só avisos
> do compilador (`WARNING ... :infer-warning`, `:fn-deprecated`) e nada mais
> acontecer; isso não é um erro. Use `npm run dev` apenas se estiver
> editando o código-fonte e quiser recompilar automaticamente; para
> efetivamente ligar o bot, rode `node target/main.js` (ou `npm start`) em um
> outro terminal.
nas próximas vezes (a menos que você apague essa pasta ou desconecte o
aparelho pelo celular).

## Configuração (`.env`)

| Variável                | Padrão                              | Descrição                                             |
|--------------------------|--------------------------------------|--------------------------------------------------------|
| `PREFIX`                 | `!`                                  | Prefixo dos comandos                                    |
| `ADMIN_NUMBERS`          | (vazio)                             | Números com permissão de `!ban` mesmo sem serem admins do grupo (`5511999999999@c.us`, separados por vírgula) |
| `WEATHER_DEFAULT_CITY`   | `Sao Paulo`                         | Cidade padrão para `!previsao`                          |
| `NEWS_FEED_URL`          | feed do G1                           | Feed RSS usado por `!noticias`                          |
| `CURRENCY_DEFAULT`       | `USD-BRL,EUR-BRL,BTC-BRL`           | Pares padrão para `!cotacao`                            |

## Como o `!ban` funciona

- Só funciona em grupos, e apenas se o **bot for administrador** do grupo.
- Quem chama o comando precisa ser admin do grupo (ou estar em `ADMIN_NUMBERS`).
- Para escolher quem remover: marque a pessoa (`!ban @5511999999999`) ou
  responda (reply) a uma mensagem dela com `!ban`.

## APIs usadas (gratuitas, sem necessidade de chave)

- **Notícias**: RSS (via `rss-parser`)
- **Cotações**: [AwesomeAPI](https://docs.awesomeapi.com.br/api-de-moedas)
- **Previsão do tempo**: [wttr.in](https://wttr.in)
- **Horóscopo**: [horoscope-app-api](https://horoscope-app-api.vercel.app)

## Estrutura do projeto

```
src/zapbot/
├── core.cljs        ; conexão com o WhatsApp e ligação dos eventos
├── router.cljs       ; interpreta o texto das mensagens e escolhe o comando
├── config.cljs       ; leitura do .env
├── piadas.cljs        ; !piada
├── noticias.cljs      ; !noticias
├── cotacao.cljs       ; !cotacao
├── previsao.cljs      ; !previsao
├── horoscopo.cljs     ; !horoscopo
└── moderacao.cljs     ; !ban
```

## Adicionando novos comandos

1. Crie um novo namespace em `src/zapbot/` com uma função que retorne a
   resposta (string) ou uma promise que resolva para uma string.
2. Registre o comando em `zapbot.router/processar` (dentro do `case`).
3. Atualize `zapbot.router/texto-ajuda` com a descrição do novo comando.
