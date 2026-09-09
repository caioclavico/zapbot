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
| `!resuma [30m\|8h\|hoje\|ontem]` | Resume as mensagens do período (sem período: últimas 1.000 desde que o bot foi ligado, exceto comandos) |
| `!lembrete <tempo> <texto>` | Agenda um lembrete e marca quem o criou, ex.: `!lembrete 10s caçar` ou `!lembrete 30m reunião`; use `!lembretes` para listar e `!cancelarlembrete <ID>` para cancelar |
| `!enquete Pergunta \| Opção 1 \| Opção 2` | Cria uma enquete; vote com `!votar <número>` e encerre com `!enquete fechar` |
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
- [Docker](https://www.docker.com/) + plugin `docker compose` (pra rodar o
  Cassandra usado na persistência - ver "Persistência (Cassandra)" abaixo).
  Sem Docker, dá pra apontar `CASSANDRA_CONTACT_POINTS` no `.env` pra um
  Cassandra já rodando em outro lugar (local nativo, VM própria, serviço
  gerenciado).

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
| `CASSANDRA_CONTACT_POINTS` | `cassandra`                       | Host(s) do Cassandra (separados por vírgula); `cassandra` já funciona direto com o `docker-compose.yml` deste projeto |
| `CASSANDRA_DATACENTER`   | `datacenter1`                        | Data center do cluster (o padrão do próprio Cassandra pra um nó único) |
| `CASSANDRA_KEYSPACE`     | `zapbot`                             | Keyspace usado pra persistência (criado automaticamente se não existir) |
| `APP_ENV`                | `production`                        | Com `development`, o bot só responde no chat `DEV_GROUP_ID` (evita respostas duplicadas nos grupos reais rodando uma instância local de teste junto com a de produção) |
| `DEV_GROUP_ID`           | (vazio)                             | Chat de teste usado quando `APP_ENV=development` |

## Persistência (Cassandra)

Rank, loja (moedas/curas do `!pokemon`), admins conhecidos, participantes do
chat, histórico anti-repetição do `!quiz`, lembretes e enquetes são persistidos no Cassandra (ver
`zapbot.armazenamento`), numa única tabela `<keyspace>.estado (chave text
PRIMARY KEY, valor text)` - `valor` guarda um JSON por chave, um por
namespace (`rank`, `loja`, `admins-conhecidos`, `participantes`,
`quiz-historico`, `bloqueio`, `lembretes`, `enquetes`). O conteúdo usado por
`!resuma` continua apenas em memória.

Pra rodar localmente com Docker Compose (recomendado - já vem configurado):

```bash
docker compose up -d cassandra   # só o banco, pra rodar o bot fora do container em dev
# ou
docker compose up -d --build     # bot + Cassandra juntos
```

Se o Cassandra ainda não tiver terminado de subir quando o bot tentar
conectar, `zapbot.armazenamento/iniciar!` tenta de novo algumas vezes antes
de desistir (e, se mesmo assim não conseguir, o bot sobe do mesmo jeito, só
que sem persistência nessa execução - nada trava por causa disso).

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
mkdir -p data
docker compose up --build
```

Escaneie o QR code que aparece no terminal (do serviço `bot` - o Cassandra
sobe primeiro e o bot espera ele ficar saudável antes de conectar). Depois de
conectado, pressione `Ctrl+C` para parar (a sessão já ficou salva em
`.wwebjs_auth/` no host).

### 5. Rodar em segundo plano, permanente

```bash
docker compose up -d --build
```

O `restart: unless-stopped` de cada serviço (ver `docker-compose.yml`) garante
que tudo volte a rodar sozinho se a VM reiniciar. Para ver os logs do bot:
`docker compose logs -f bot` (ou `docker logs -f zapbot`).

### 6. CI/CD automático (GitHub Actions)

Depois do setup manual acima (passos 1-5, incluindo o QR code inicial), os
próximos deploys são automáticos: o workflow [`.github/workflows/deploy.yml`](.github/workflows/deploy.yml)
builda o projeto a cada push/PR e, a cada push direto na `master` (com o
build passando), conecta na VM via SSH e roda `git pull` + `docker compose up
-d --build` - preservando `.env` e a sessão em `.wwebjs_auth/` (nenhum dos
dois é tocado pelo pipeline), além dos dados do Cassandra (volume nomeado
`cassandra-data`, também não tocado).

Configure estes *secrets* no repositório GitHub (`Settings > Secrets and
variables > Actions`):

| Secret              | Valor                                                              |
|----------------------|---------------------------------------------------------------------|
| `ORACLE_HOST`        | IP público da VM                                                    |
| `ORACLE_USER`        | usuário SSH (ex.: `ubuntu`)                                          |
| `ORACLE_APP_DIR`     | caminho absoluto do repo na VM (ex.: `/home/ubuntu/zapbot`)          |
| `ORACLE_SSH_KEY`     | chave privada SSH (par autorizado em `~/.ssh/authorized_keys` na VM) |
| `ORACLE_SSH_PORT`    | porta SSH, opcional (padrão `22`)                                    |

> 🔒 Gere um par de chaves **dedicado só para o deploy** (não reaproveite sua
> chave pessoal), ex.: `ssh-keygen -t ed25519 -f deploy_key -C "gh-actions"`,
> adicione `deploy_key.pub` ao `authorized_keys` da VM e cole o conteúdo de
> `deploy_key` (privada) no secret `ORACLE_SSH_KEY`.

### 7. Versionamento

Cada release que vai pra VM ganha uma tag anotada `vMAJOR.MINOR.PATCH`, além
do commit normal - assim dá pra saber exatamente qual versão está rodando
(`git describe --tags` na VM) e voltar pra uma anterior se precisar
(`git checkout vX.Y.Z && docker compose up -d --build`). Pushar só a tag
(sem mudar `master`) não dispara o deploy - o workflow só reage a push/PR na
branch `master`.

Ao preparar um release:

```bash
# 1. bump no campo "version" do package.json, depois:
git add package.json
git commit -m "chore: bump version to X.Y.Z"
git push origin master        # dispara o deploy de verdade

# 2. marca esse commit como a versão:
git tag -a vX.Y.Z -m "vX.Y.Z"
git push origin vX.Y.Z
```

## Estrutura do projeto

```
src/zapbot/
├── core.cljs        ; conexão com o WhatsApp e ligação dos eventos
├── router.cljs       ; interpreta o texto das mensagens e escolhe o comando
├── config.cljs       ; leitura do .env
├── armazenamento.cljs ; persistência em Cassandra (rank/loja/admins/participantes/quiz-historico)
├── piadas.cljs        ; !piada
├── curiosidades.cljs  ; !curiosidade
├── noticias.cljs      ; !noticias
├── cotacao.cljs       ; !cotacao
├── previsao.cljs      ; !previsao
├── horoscopo.cljs     ; !horoscopo
├── filme.cljs         ; !filme
├── traduza.cljs       ; !traduza
├── resumo.cljs        ; !resuma
├── gemini.cljs        ; wrapper da API Gemini (usado por !resuma, !pergunta e !quiz)
├── pergunta.cljs      ; !pergunta
├── bola8.cljs        ; !bola8
├── historico.cljs     ; mensagens/participantes conhecidos (usado por !resuma e !sorteio)
├── sorteio.cljs       ; !sorteio
├── velha.cljs         ; !velha
├── naval.cljs         ; !naval
├── pokemon.cljs       ; !pokemon
├── quiz.cljs          ; !quiz
├── adedonha.cljs      ; !adedonha
├── spotify.cljs       ; wrapper da API do Spotify (usado por !musica)
├── musica.cljs        ; !musica
├── status.cljs        ; !status
├── bloqueio.cljs      ; !bloquear / !desbloquear
└── moderacao.cljs     ; !ban
```

## Adicionando novos comandos

1. Crie um novo namespace em `src/zapbot/` com uma função que retorne a
   resposta (string) ou uma promise que resolva para uma string.
2. Registre o comando em `zapbot.router/processar` (dentro do `case`).
3. Atualize `zapbot.router/texto-ajuda` com a descrição do novo comando.

### Perfil do treinador Pokémon

Use `!pokemon treinador` para ver o nível e XP do treinador, Pokémon ativo, sequência atual, recorde de capturas e insígnias conquistadas ou bloqueadas. O treinador recebe 1 XP por vitória mais XP por insígnias conquistadas e precisa de 5 XP para chegar ao nível 2, mais 7 para o nível 3, mais 9 para o nível 4 e assim por diante (+2 XP no custo de cada próximo nível); esse XP é separado do XP do Pokémon. As insígnias reconhecem 1, 10, 50 e 100 vitórias, além de sequências de 3, 5, 10 e 20 capturas.

O recorde é preservado quando uma sequência termina. Para contas antigas, parte da sequência atual salva: sequências anteriores à implementação não podem ser recuperadas.


### Ligas Pokémon (0.7.0)

O PvP usa times de três Pokémon da coleção, sem ajustar seus níveis reais:
Iniciante (1–10), Bronze (11–25), Prata (26–40), Ouro (41–60) e Diamante (61–100).

- `!pokemon liga`: consulta as faixas, a liga selecionada e sua escalação.
- `!pokemon liga iniciante`: seleciona a liga; a escolha fica salva por jogador e chat.
- `!pokemon liga time 1,3,5`: salva os três números da coleção (`!pokemon time`) na ordem de entrada.
- `!pokemon`: abre ou entra numa batalha usando o time salvo da liga selecionada.

É necessário ter três Pokémon diferentes da faixa e nenhum desmaiado. Após cada
nocaute, o próximo da escalação entra automaticamente e recebe a vez. A vitória,
rank e moedas só são concedidos quando os três adversários caem.

Desde a versão 0.7.2, o XP é calculado por Pokémon, pelo número de adversários que
ele derrotou na mesma partida: **0 nocautes = 1 XP, 1 = 3 XP, 2 = 5 XP e 3 = 7 XP**.
Todos que entraram em campo recebem, inclusive os derrotados; reservas que não
lutaram não recebem XP. Os nocautes são registrados antes de cada substituição,
e as recompensas são pagas apenas ao terminar a partida. Quedas por status ou
recuo contam para o Pokémon adversário que estava em campo; no nocaute simultâneo,
ambos recebem um nocaute. Empate, desistência e tempo esgotado continuam sem XP.
A contagem começa do zero a cada partida, sem sequência entre partidas.
O resumo final mostra os nocautes e XP de cada participante. Cada 9 XP dão um nível.

Ao subir além da faixa, ele sai automaticamente da escalação, mas continua na
coleção; a liga e as outras vagas permanecem salvas. Use o comando de escalação
para preencher as vagas antes da próxima batalha. Doação e enfermaria também
desocupam a vaga, preservando a identidade dos outros integrantes.

A batalha aberta aceita somente adversários da mesma liga. Comparando os dois
times ordenados por nível, cada par deve ter diferença de no máximo cinco níveis.
Sem adversário compatível, a batalha continua aguardando até o limite habitual de
30 minutos. A escalação fica bloqueada enquanto o jogador participa da batalha.
As caçadas continuam disponíveis para capturar Pokémon e formar novos times.



### XP por sequência de capturas (0.7.2)

O Pokémon usado na caçada recebe o XP da raridade mais um bônus pela sequência:
primeira captura **+0 XP**, segunda **+1 XP**, terceira e seguintes **+2 XP**.
O resultado mostra o total e a divisão entre raridade e sequência. Uma captura
comum rende 2, 3 e 4 XP, respectivamente; uma mítica rende 7, 8 e 9 XP.
O bônus pertence à sequência do treinador naquele chat, mesmo trocando o Pokémon.
Falha de captura, fuga ou desistência reiniciam a sequência; a próxima captura
bem-sucedida volta ao bônus zero. O recorde de capturas permanece salvo.


### XP do treinador por insígnias (0.7.2)

Cada insígnia contribui uma única vez para o XP total do treinador:

| Vitórias | Capturas seguidas | XP por insígnia |
|---|---|---:|
| Primeira vitória (1) | Capturador (3) | 3 |
| Batalhador (10) | Caçador (5) | 6 |
| Veterano (50) | Especialista (10) | 12 |
| Campeão (100) | Mestre da captura (20) | 24 |

As duas insígnias de cada linha são independentes e cada uma concede o valor
indicado. O perfil `!pokemon treinador` mostra os valores, as conquistas e o total
de XP por insígnias. As conquistas antigas também contam automaticamente.
Repetir uma sequência, consultar o perfil ou reiniciar o bot não duplica o bônus.
Quebrar a sequência não retira XP, pois vale o recorde permanente de capturas.
O bônus não conta como vitória para desbloquear outras insígnias e não concede XP
aos Pokémon. O novo nível do treinador também é usado na calibragem das caçadas.

O XP total existente é preservado ao aplicar a curva de níveis da versão 0.7.2.
O nível exibido é recalculado pela nova curva e pode diminuir, sem perda de XP
ou insígnias. O custo dos níveis dos Pokémon continua sendo 9 XP.

Pokémon novos começam com pelo menos um ataque ofensivo de um de seus tipos.
Quando ainda não aprenderiam um por nível, recebem um ataque básico do tipo
principal (por exemplo, Jato de Água para Squirtle). Coleções antigas recebem
a correção no próximo comando Pokémon fora de batalha; com quatro golpes, apenas
o último é substituído. Essa correção ocorre uma vez e não desfaz remoções futuras.
