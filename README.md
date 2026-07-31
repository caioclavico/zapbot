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
| `!previsao [cidade]`        | Previsão do tempo para os próximos dias (também: `!tempo`, `!clima`)   |
| `!horoscopo <signo>`        | Horóscopo do dia (em português: aries, touro, gemeos, ...); sem signo, sorteia um |
| `!filme [nome]`             | Sinopse e nota IMDb de um filme (use o título original/inglês); sem nome, sugere um aleatório |
| `!traduza <frase>`          | Traduz uma frase qualquer para português                        |
| `!resuma`                   | Resume as últimas mensagens do chat (últimas 300 desde que o bot foi ligado, exceto comandos) |
| `!pergunta <texto>`         | Faz uma pergunta livre para o bot responder com IA (Gemini)     |
| `!bola8 [pergunta]`         | Bola 8 mágica: manda uma imagem e uma resposta aleatória        |
| `!sorteio`                  | Sorteia uma pessoa conhecida do chat/grupo (marca com @)        |
| `!velha [1-9\|sair]`        | Jogo da velha entre duas pessoas do chat                        |
| `!adedonha [parar]`         | Sorteia letra e categorias pro grupo jogar STOP (também: `!stop`) |
| `!musica [genero]`          | Indica uma música com link do Spotify; sem gênero, sorteia um       |
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

## Como o `!sorteio` funciona

- Sorteia entre as pessoas que já mandaram alguma mensagem no chat desde que
  o bot foi ligado (não busca a lista completa de participantes do grupo,
  pelo mesmo motivo do `!resuma` - ver `historico.cljs`).

## Como o `!velha` funciona

- `!velha` sem argumento: abre uma partida (você joga de ❌) ou, se já houver
  uma partida esperando adversário, você entra de ⭕ e o jogo começa.
- `!velha <1-9>`: joga na casa correspondente (1 = canto superior esquerdo,
  9 = canto inferior direito), só quando for a sua vez.
- `!velha sair`: cancela a partida em andamento naquele chat.
- O estado da partida fica em memória por chat e não sobrevive a um reinício
  do bot.

## Como o `!adedonha` funciona

- `!adedonha` sorteia uma letra e mostra 8 categorias (Nome, Sobrenome, Cor,
  Animal, Objeto, Fruta, País, Profissão); a galera manda as respostas no
  grupo mesmo (o bot não valida nem pontua automaticamente).
- Depois de 60 segundos o bot avisa que o tempo acabou.
- `!adedonha parar` (ou `!stop`) encerra a rodada antes da hora.
- Só uma rodada por vez em cada chat; o estado também fica só em memória.

## APIs usadas

- **Notícias**: RSS (via `rss-parser`) - grátis, sem chave
- **Cotações**: [AwesomeAPI](https://docs.awesomeapi.com.br/api-de-moedas) - grátis, sem chave
- **Previsão do tempo**: [wttr.in](https://wttr.in) - grátis, sem chave
- **Horóscopo**: [freehoroscopeapi.com](https://freehoroscopeapi.com) - grátis, sem chave
  (texto traduzido para PT via endpoint não-oficial do Google Translate)
- **Filmes**: [TMDB (The Movie Database)](https://www.themoviedb.org) - grátis, requer chave (ver `.env.example`)
- **Resumo de conversas e perguntas livres**: [Gemini API (Google AI Studio)](https://aistudio.google.com/apikey) - grátis, requer chave (ver `.env.example`)
- **Músicas**: [Spotify Web API](https://developer.spotify.com/dashboard) (Client Credentials) - grátis, requer credenciais (ver `.env.example`)

## Deploy grátis na Oracle Cloud Free Tier

A Oracle Cloud oferece uma VM ARM64 (Ampere A1) gratuita para sempre, com
recursos suficientes para rodar o bot 24/7. Puppeteer não tem Chromium
pré-compilado para ARM64, então usamos o `Dockerfile` deste projeto, que
instala o Chromium do sistema via `apt`.

### 1. Criar a instância

1. Crie uma conta na [Oracle Cloud](https://www.oracle.com/cloud/free/) (cartão
   é pedido só para verificação, o Always Free não cobra nada).
2. Crie uma instância **Ampere (ARM), VM.Standard.A1.Flex** (ex.: 1 OCPU / 6GB
   RAM, dentro da cota grátis), imagem **Ubuntu** ou **Oracle Linux**.
3. Na criação, abra a porta de saída (padrão) e garanta acesso SSH (porta 22)
   no *Security List* da VCN.

### 2. Instalar Docker na VM

```bash
ssh ubuntu@<ip-da-vm>
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
# saia e conecte de novo pra aplicar o grupo docker
```

### 3. Enviar o projeto e configurar

```bash
git clone <url-do-seu-repo> zapbot
cd zapbot
cp .env.example .env
nano .env   # ajuste PREFIX, ADMIN_NUMBERS etc. (PUPPETEER_EXECUTABLE_PATH já
            # vem certo pela imagem Docker, não precisa mexer)
```

### 4. Build e primeira execução (escanear o QR code)

```bash
docker build -t zapbot .
docker run -it --name zapbot \
  --env-file .env \
  -v "$(pwd)/.wwebjs_auth:/app/.wwebjs_auth" \
  zapbot
```

Escaneie o QR code que aparece no terminal. Depois de conectado, pressione
`Ctrl+C` para parar (a sessão já ficou salva em `.wwebjs_auth/` no host).

### 5. Rodar em segundo plano, permanente

```bash
docker rm zapbot   # remove o container do passo anterior (a sessão continua salva no volume)
docker run -d --name zapbot \
  --restart unless-stopped \
  --env-file .env \
  -v "$(pwd)/.wwebjs_auth:/app/.wwebjs_auth" \
  zapbot
```

`--restart unless-stopped` garante que o bot volte a rodar sozinho se a VM
reiniciar. Para ver os logs: `docker logs -f zapbot`.

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
├── filme.cljs         ; !filme
├── traduza.cljs       ; !traduza
├── resumo.cljs        ; !resuma
├── gemini.cljs        ; wrapper da API Gemini (usado por !resuma e !pergunta)
├── pergunta.cljs      ; !pergunta
├── bola8.cljs        ; !bola8
├── historico.cljs     ; mensagens/participantes conhecidos (usado por !resuma e !sorteio)
├── sorteio.cljs       ; !sorteio
├── velha.cljs         ; !velha
├── adedonha.cljs      ; !adedonha
├── spotify.cljs       ; wrapper da API do Spotify (usado por !musica)
├── musica.cljs        ; !musica
└── moderacao.cljs     ; !ban
```

## Adicionando novos comandos

1. Crie um novo namespace em `src/zapbot/` com uma função que retorne a
   resposta (string) ou uma promise que resolva para uma string.
2. Registre o comando em `zapbot.router/processar` (dentro do `case`).
3. Atualize `zapbot.router/texto-ajuda` com a descrição do novo comando.
