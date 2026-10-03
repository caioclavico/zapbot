# zapbot-pokemon-service

Serviço HTTP independente para o jogo existente. Não instala WhatsApp, Puppeteer
ou Chromium. Contém somente os módulos Pokémon, imagens, tradução usada pela
Pokédex e infraestrutura de persistência/HTTP. Regras e IDs existentes são mantidos.

## Arquitetura

Antes: WhatsApp → ZapBot (regras + Sharp + Pokémon + outros jogos) → Cassandra.

Depois:

```text
VM ZapBot: WhatsApp/Chromium + comandos gerais + cliente HTTP
                           │ HTTP privado :8090
VM Pokémon: comandos + regras + Sharp + timers + eventos persistidos
                           │ Cassandra :9042
VM Cassandra: tabelas existentes
```

O placar compartilhado fica com o ZapBot. Pokémon entrega efeitos idempotentes de
pontuação por HTTP. O bot é o único processo que grava `rank`; o serviço é o único
que grava os módulos do jogo. Nunca executar a implementação antiga junto do
serviço sobre os mesmos dados.

## Executar localmente

Requisitos: Node 22, Java 17+ para compilar, npm e Cassandra com o schema atual.
Java não faz parte da imagem final. Não há servidor WhatsApp neste projeto.

```sh
npm ci
cp .env.example .env
# Configure os nós/datacenter/keyspace e um API_TOKEN aleatório.
npm run build
npm start
```

O servidor abre em `127.0.0.1:8090`. `/health` pode responder antes do Cassandra;
`/ready` só responde 200 após carregar a persistência. Não aceita comandos enquanto
não estiver pronto. A inicialização **não cria nem migra schema**. Um banco legado
que ainda precisa da migração para `estado_particionado` exige intervenção
explícita antes do corte; o serviço falha com segurança e não executa essa migração.

Não use credenciais nem dados de produção nos testes:

```sh
npm test
npm run test:domain
```

Os testes de contrato com o cliente do ZapBot ficam no repositório de migração;
após extrair este diretório, mantenha-os no CI de integração do ZapBot.

## Contrato HTTP v1

Todas as rotas de jogo exigem `Authorization: Bearer <API_TOKEN>` quando configurado.
Para HOST diferente de loopback, token com pelo menos 24 caracteres é obrigatório.
`/health` e `/ready` não expõem dados nem exigem token. Firewall ainda é necessário.

### POST /commands

```json
{
  "requestId": "id-estavel-da-mensagem",
  "chatId": "id-do-contexto-existente",
  "playerId": "id-existente-do-jogador",
  "playerName": "Nome para exibição",
  "command": "pk treinador",
  "context": {
    "mentionedIds": [],
    "quotedPlayerId": null,
    "isAdmin": false,
    "botVersion": "0.19.0"
  }
}
```

`command` preserva o comando, sem o prefixo `!`. Aceita também as rotas externas
`pokedex/dex/pdx`, `presente/presentes`, `missoes/missões`, `mochila` e `loja`.
`bugTarget`, quando necessário para relatórios, contém `playerId`, `text`,
`timestamp` (milissegundos) e `source` (`citada` ou `anterior`). Autorizações e
contexto vêm do adaptador confiável; não exponha esta API a jogadores diretamente.
Não normalizar sufixos de IDs nem criar jogadores substitutos.

```json
{
  "requestId": "id-estavel-da-mensagem",
  "messages": [
    {"type":"image", "text":"Legenda", "mentions":[],
     "mediaId":"sha256-de-64-caracteres", "mimeType":"image/png", "filename":"perfil.png"}
  ],
  "effects": [],
  "timings": {"request_total_ms":40,"command_processing_ms":32,"response_preparation_ms":8}
}
```

As mensagens têm ordem e tipos `text`, `image` ou `document`. O adaptador prepara
`MessageMedia` apenas na entrega. Resize, composição, PNG e downloads de sprites
ocorrem aqui. Não há Base64 de imagens no JSON HTTP. A conversão exigida pela
biblioteca WhatsApp continua somente na fronteira de envio do bot.

### GET /media/{mediaId}

Retorna bytes e `Content-Type`, com autenticação. Não aceita URLs arbitrárias.
Tamanho máximo de uma mídia: 10 MiB. Arquivos ficam no `DATA_DIR/media`, próprio
desta VM, com limpeza após 24 horas; mídias de eventos sem ack são protegidas.
Persista `/app/data` no Docker para recuperar notificações após recriar o container.
Resposta 410 significa mídia expirada: não execute novamente a jogada para refazê-la.

### GET /events/pending e POST /events/{id}/ack

```json
{"events":[{"id":"uuid","chatId":"contexto","createdAt":0,"messages":[],"effects":[]}]}
```

Retorna até 20 eventos em ordem de criação. O bot consulta a cada 15s (configurável),
aplica efeitos, entrega as mensagens e confirma com POST. Ack repetido é seguro.
Filas são registros Cassandra locais ao serviço, sem broker ou infraestrutura extra.
A entrega WhatsApp é **pelo menos uma vez**: se cair entre enviar a mensagem e
persistir a confirmação, pode haver repetição visual. Não há garantia de entrega
exatamente uma vez pelo WhatsApp. Pontuação tem marcador durável e não duplica.

### Erros e prazos

- 400: pedido inválido; 401: token inválido; 404: rota desconhecida.
- 409: requestId reutilizado com outro conteúdo ou resultado ainda incerto.
- 413: JSON acima de 64 KiB; 429: limite de 8 comandos em andamento.
- 500: execução incerta; 503: Cassandra/serviço indisponível; 504: resposta excedeu prazo.
- Cliente: conexão 5s, chamada 45s por padrão; servidor encerra resposta aos 60s.
- HTTP externo (PokéAPI/sprites/tradução) possui prazo finito de 8s.

Não há retry automático de comandos. Uma reserva `IF NOT EXISTS` é persistida antes
de entrar no domínio. O mesmo requestId devolve o resultado salvo quando concluído.
Após queda com status `processing/uncertain`, a jogada **não é reexecutada**.
É uma proteção contra duplicação, não uma transação global do jogo: operações
antigas que alteram vários módulos ainda podem terminar parcialmente numa queda.
Nesses casos, investigar os dados antes de qualquer correção ou reenvio com ID novo.
Os recibos são mantidos para preservar dedupe; planejar retenção explícita quando
houver volume, sem apagar pedidos incertos automaticamente.

## Cassandra e propriedade dos dados

Mesmos `CASSANDRA_CONTACT_POINTS`, `CASSANDRA_DATACENTER`, `CASSANDRA_KEYSPACE`.
Não há reset, renomeação de jogador, troca de banco ou alteração de tabelas.
Usa `estado_particionado(modulo,particao,valor)` e verifica o legado `estado`.
Cada registro continua com o JSON e os IDs antigos. Novos módulos na mesma tabela
armazenam recibos HTTP, eventos e pendências temporizadas.

Leituras são limitadas aos módulos registrados. Como a chave de partição existente
é composta por módulo e partição, a hidratação por módulo usa `ALLOW FILTERING`,
com paginação, apenas no startup. Não é uma consulta ideal para bancos grandes;
a extração evita alterar schema, e o custo deve ser medido antes de ampliar escala.
Executar **uma réplica** do serviço: os atoms e snapshots existentes não suportam
escritores de jogo simultâneos. LWT protege requestId, não toda conta do jogador.

## Nurse Joy, raids e relógios

Joy já usa `pronto-em` persistido. O recolhimento continua na próxima consulta,
como antes; não adicionamos uma notificação de cura que não existia.
Raids automáticas e prazos de batalha rodam no serviço. Seus avisos viram eventos
HTTP persistidos. Propostas de troca e remoções de golpe passam a guardar pendências
sem objetos de transporte nem handles de timer. Após restart, os relógios são
rearmados com os prazos existentes.

## Logs e performance

Logs JSON separam processamento, Cassandra, PokeAPI, sprite, PNG e preparação da
resposta. O cliente mede HTTP, transferência binária e envio WhatsApp separadamente.
Etapas podem se sobrepor: não somar todas para inferir tempo total.
Os testes locais medem transporte e processamento com fixtures; não equivalem à
latência de produção nem garantem que o Chromium passou a receber mensagens.

## Docker e VM Pokémon

```sh
docker build -t zapbot-pokemon-service:VERSION .
# API_TOKEN e Cassandra em arquivo privado, fora do Git.
docker run -d --name pokemon-service --restart unless-stopped \
  --env-file /caminho/privado/pokemon.env \
  -p IP_PRIVADO_DA_VM:8090:8090 \
  -v pokemon-data:/app/data zapbot-pokemon-service:VERSION
```

Configure `HOST=0.0.0.0` dentro do container. No firewall/NSG, liberar TCP 8090
**somente da VM ZapBot para a VM Pokémon**. Cassandra TCP 9042 permanece privado,
permitido às VMs que possuem módulos no banco. Não abrir Cassandra na Internet.
A VM Pokémon também precisa HTTPS de saída para PokeAPI, sprites e tradução.

A base Node Debian e Sharp têm suporte amd64/arm64. Não há plataforma fixada no
Dockerfile. Para imagens de ambas arquiteturas:

```sh
docker buildx build --platform linux/amd64,linux/arm64 -t REGISTRY/pokemon:VERSION --push .
```

Isso publica uma imagem e exige um registry configurado; não foi executado como
parte da migração local. Validações de arquitetura realizadas estão no relatório.

## Corte para HTTP e rollback

1. Fazer backup dos dados Cassandra pelos procedimentos existentes e guardar a
   imagem anterior do bot. Não copiar `.wwebjs_auth` para o serviço Pokémon.
2. Parar o bot antigo (ele contém timers e writes Pokémon).
3. Iniciar uma única instância do serviço com as mesmas chaves de persistência.
4. Aguardar `GET /ready` retornar 200.
5. Configurar no ZapBot `POKEMON_SERVICE_URL=http://IP_PRIVADO:8090` e
   `POKEMON_SERVICE_TOKEN` igual a `API_TOKEN`.
6. Instalar o build HTTP do ZapBot, mantendo `.env`, dados e sessão.
7. Confirmar saúde, executar um comando de consulta e validar eventos/ack.
   Testes de captura, troca e batalha devem usar um grupo/contas de homologação.

O build novo usa HTTP exclusivamente. Não há fallback silencioso ao jogo local:
ele criaria dois escritores e recolocaria o consumo de imagem no bot.
Rollback: parar o bot novo **e o serviço**, preservar pendências/recibos, iniciar a
imagem antiga do bot com os mesmos volumes e confirmar saúde. Não executar as duas
implementações juntas. Eventos sem ack e pendências novas precisam ser reconciliados
antes do rollback: a imagem antiga não sabe consumir esses novos módulos.

## Extrair repositório

Copiar somente este diretório para `zapbot-pokemon-service`, mantendo `package-lock`,
fontes, runtime, assets e testes de domínio/API. Excluir `node_modules`, `target`,
`.shadow-cljs`, `.env` e `data`. O teste de contrato que importa o cliente do ZapBot
é executado pelo CI do ZapBot; não é dependência de runtime do serviço.

Inventários completos e relatório de validação ficam em `../docs/pokemon-*.md` no
repositório de migração. As fontes antigas do ZapBot ficam preservadas para testes
e referência até validar o corte; não são importadas pelo novo entrypoint.

### Benchmark local reproduzível

No repositório de migração, compile `shadow-cljs release benchmark` na raiz e
neste diretório. Depois execute, na raiz, `node scripts/benchmark-pokemon.cjs`.
O script compara o PNG real do treinador nas duas versões, usando sprite SVG
local, e mede o transporte HTTP do novo serviço com persistência em memória.
Não inicia WhatsApp, acessa Cassandra ou chama PokeAPI. Os resultados não medem
latência entre VMs nem custo do Cassandra. O benchmark antigo pertence ao
repositório de migração; o build de produção não o carrega.
