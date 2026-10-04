# Diagnóstico HTTP Pokémon — 2026-10-03

## Resultado e limite

Corrigidas a observabilidade da reserva durável no Pokémon Service e a leitura
do ID atual do WhatsApp no Odisseu. As duas correções foram publicadas com
autorização; o serviço está em modo normal e o Odisseu voltou a `READY`.
`pk treinador` funciona por HTTP com domínio real, PNG real e Cassandra 5.0.9
descartável. **Não está comprovada a resolução do timeout 4352 no Cassandra de
produção.** A única escrita diagnóstica autorizada não reproduziu o erro.
Nenhum comando real do jogo foi executado automaticamente nesta investigação;
o teste pelo usuário via WhatsApp continua pendente.

4352 decimal = 0x1100 = `writeTimeout`, confirmado no driver instalado e no
[protocolo Cassandra](https://cassandra.apache.org/doc/latest/cassandra/reference/native-protocol.html).
O código sozinho não identifica falta de disco, memória, contenção ou defeito
operacional: esses motivos exigem o erro completo e os logs do servidor.

## Caminho identificado

`scripts/lib/pokemon-http-client.cjs` envia JSON e Bearer a `/commands`.
`src/zapbot/pokemon_http.cljs` cria um DTO com IDs existentes e valida o retorno.
O handler lê JSON; `execute` verifica readiness, input e dedupe; `executeReserved`
chama `State.reserve`; `entry/reserve` chama `armazenamento/reservar!`.
Esta função executa `INSERT INTO zapbot.estado_particionado ... IF NOT EXISTS`
antes de entrar na fila do chat e no catch do processamento do jogo.

Essa é a escrita que pode escapar com o código numérico 4352 antes do domínio:
as demais writes do comando ficam dentro do catch que devolvia `result_unknown`.
Assim, a resposta fornecida no pedido identifica a falha de reserva, não o
renderizador do treinador. A falha foi reproduzida por injeção de um
`cassandra-driver.errors.ResponseError` real nessa fronteira, sem writes reais.

Depois da reserva, `entry/command` converte o DTO em contexto neutro, despacha
`pk treinador` ao domínio, espera confirmação da persistência e retorna texto e
bytes PNG. O serviço persiste mídia e recibo e devolve o contrato HTTP. Não há
dependência de WhatsApp Message/Contact/Chat nesse processamento.

Um HTTP 500 nesse cliente resulta em `code=HTTP,status=500`; `INVALID_RESPONSE`
significa JSON inválido em 2xx ou falha na validação ClojureScript. São caminhos
distintos. O log `INVALID_RESPONSE` foi posteriormente diagnosticado no
adaptador Odisseu: o ID atual usa `$1`, ausente no formato que o adaptador
aceitava. A causa e a correção estão registradas adiante.

## Inspeção inicial de produção somente leitura

- Google: imagem `zapbot-pokemon:latest`, container `zapbot-pokemon`, usuário `node`,
  WORKDIR `/app`, restart `unless-stopped`, publicação 8080.
- Arquivo de configuração localizado: `/home/caiohclavico/pokemon-service/.env`.
  Seu conteúdo não foi impresso.
- Logs mostram hidratação concluída, readOnly false e timers ativos. Não alterados.
- SELECTs de metadados confirmam Cassandra 5.0.9, DC `datacenter1`, uma entrada
  local, nenhum peer e `zapbot` com SimpleStrategy/RF=1.
- Chave da tabela: `PRIMARY KEY ((modulo, particao))`; coluna `valor` regular.
- O domínio compilado da imagem reconstruída tem o mesmo SHA-256 do domínio
  no container Google: `b692f2abab76629711891e464ac5aca88c15c6f6b15f2e9bedcee42ce9b42a0a`.
  O `service.cjs` remoto possui duas edições manuais de console.error no catch
  interno e uma no externo. Essas edições não substituem uma imagem reconstruída.
- O container dessa etapa **não possuía volumes montados**. Uma atualização deve
  preservar `/app/data`, que contém mídias de eventos pendentes.

O SSH de Cassandra foi recusado para `ubuntu` com `~/.ssh/oracle_vm` e com a chave
encontrada `~/Downloads/ssh-key-2026-10-03 (1).key`. O caminho informado sem espaço
`~/Downloads/ssh-key-2026-10-03(1).key` não existe neste ambiente. A permissão da
chave com espaço foi restringida a 600 conforme autorização; seu conteúdo não foi
lido, copiado ou exposto. Odisseu também recusou `ubuntu`/`~/.ssh/oracle_vm`.

## Correção mínima

- `runtime/errors.cjs`: log estruturado de campos explícitos; remove valores de
  variáveis de secrets e Bearer. Não serializa headers, payload ou objeto do driver.
- `runtime/service.cjs`: registra `command_failed` também na reserva, com comando,
  requestId, estágio e causa original; não repete LWT nem entra no domínio após
  timeout. Devolve HTTP 500 `result_unknown` com orientação de preservar requestId.
- Catch do domínio registra a causa antes de tentar recuperar effects. Falha na
  recuperação tem log próprio e não encobre a primeira causa.
- Catch HTTP registra método, pathname, status e erro. Erros desconhecidos
  devolvem `internal_error`/`Falha interna.`. Stack e código do driver ficam só
  nos logs. Idempotência e bloqueio de pedidos uncertain foram preservados.

Essa correção inicial não mudou regras, schema, consistência, timeout Cassandra
ou cliente Odisseu. A alteração posterior do adaptador está descrita adiante.

## Testes e build

Todos executados localmente em Docker com Node 22 e Java, sem credenciais reais:

- ZapBot `npm test`: 174 testes, 1127 assertions, zero falhas/erros.
- ZapBot `npm run test:http`: 25 testes aprovados.
- Pokémon `npm test`: 17 testes aprovados.
- Pokémon `npm run test:domain`: 150 testes, 1036 assertions, zero falhas/erros.
- Pokémon `npm run build`: concluído; dois warnings de depreciação de Promesa.
- Contrato cliente/domínio compilado: 1 teste aprovado.
- Cassandra 5.0.9 isolado: schema em keyspace aleatório, hidratação de 15 módulos,
  30 SELECTs e 3 writes de teste; POST treinador HTTP 200, PNG válido, replay igual.
  Timers nunca foram iniciados nesse teste. O container descartável foi removido.
- Imagem final Linux AMD64: mesmo teste aprovado como usuário `node`, com seus
  próprios assets, dependências, bundle e runtime. SHA-256 dos arquivos
  `runtime/service.cjs` e `runtime/errors.cjs` coincide com a fonte local.
- `git diff --check`: aprovado.

Os testes cobrem health, ready, eventos vazios, DTO válido, treinador real,
messages/effects/timings e requestId, mídia PNG, replay, restart, uncertain,
timeout 4352 antes do domínio, logs originais, sanitização e remoção de secrets.

## Contrato preservado

Pedido:

```json
{"requestId":"id-unico","chatId":"grupo-existente","playerId":"jogador-existente@lid","playerName":"Nome","command":"pk treinador","context":{"mentionedIds":[],"quotedPlayerId":null,"isAdmin":false,"botVersion":"0.19.0","bugTarget":null}}
```

Resposta HTTP 200:

```json
{"requestId":"id-unico","messages":[{"type":"image","text":"...","mentions":[],"mediaId":"sha256","mimeType":"image/png","filename":"treinador-pokemon.png"}],"effects":[],"timings":{"command_processing_ms":105,"response_preparation_ms":3,"request_total_ms":116}}
```

Timings podem incluir métricas adicionais. IDs não são normalizados. Não repetir
um pedido incerto com outro requestId. `teste-pk-treinador-001` não foi reutilizado.

## Rebuild e transferência — não executam deploy

Na raiz do repositório:

```sh
docker buildx build --platform linux/amd64 --load \
  -t zapbot-pokemon:http-errors-20261003 pokemon-service
docker save zapbot-pokemon:http-errors-20261003 -o /tmp/pokemon-http-errors-20261003.tar
scp -i ~/.ssh/google_pokemon /tmp/pokemon-http-errors-20261003.tar \
  caiohclavico@34.68.189.66:/home/caiohclavico/pokemon-service/
```

A imagem foi construída localmente. Transferência e atualização ainda não haviam
sido executadas na entrega inicial; a execução posterior está registrada abaixo.

## Atualização Google após autorização

Estes comandos preservam a instância anterior e a mídia existente e iniciam a
nova imagem **somente leitura** para validação. Isso suspende comandos/timers
Pokémon durante a validação; não corrige nem testa writes de produção.
Executar na VM, em janela aprovada:

```sh
cd /home/caiohclavico/pokemon-service
docker load -i pokemon-http-errors-20261003.tar
docker stop zapbot-pokemon
docker rename zapbot-pokemon zapbot-pokemon-before-http-errors
docker volume create pokemon-data-http-errors
docker create --name zapbot-pokemon --restart unless-stopped \
  --env-file /home/caiohclavico/pokemon-service/.env \
  -e HOST=0.0.0.0 -e PORT=8080 -e DATA_DIR=/app/data \
  -e POKEMON_READ_ONLY=true \
  -p 8080:8080 -v pokemon-data-http-errors:/app/data \
  zapbot-pokemon:http-errors-20261003
media_copy=$(mktemp -d /home/caiohclavico/pokemon-service/media-copy.XXXXXX)
docker cp zapbot-pokemon-before-http-errors:/app/data/. "$media_copy"/
docker cp "$media_copy"/. zapbot-pokemon:/app/data/
rm -rf -- "$media_copy"
docker run --rm --user root --entrypoint chown \
  -v pokemon-data-http-errors:/app/data \
  zapbot-pokemon:http-errors-20261003 -R node:node /app/data
docker start zapbot-pokemon
curl --fail http://127.0.0.1:8080/health
curl --fail http://127.0.0.1:8080/ready
docker logs --tail 100 zapbot-pokemon
```

Não executar o novo e o anterior simultaneamente. Não reabilitar writes antes de
diagnosticar o Cassandra e aprovar a validação seguinte. Se algum comando falhar,
parar e avaliar o estado; não prosseguir cegamente. Rollback nessa fase read-only:

```sh
docker stop zapbot-pokemon
docker rename zapbot-pokemon zapbot-pokemon-http-errors-readonly
docker rename zapbot-pokemon-before-http-errors zapbot-pokemon
docker start zapbot-pokemon
```

## Deploy autorizado do adaptador Odisseu — 2026-10-04 UTC

- Commit: `a44db4cae829764d60b2a8a852ecc30b1af91603`.
- Imagem Linux AMD64: `zapbot:a44db4cae829764d60b2a8a852ecc30b1af91603`,
  construída e testada localmente, transferida via SSH e carregada na VM.
- Container ativo `zapbot`: `healthy`, zero reinícios, `unless-stopped`.
- Cassandra conectado e estado particionado carregado. WhatsApp autenticado e
  `READY` em `2026-10-04T03:54:49.477Z`; healthcheck interno confirmou
  `{"status":"ok","whatsapp":"READY","chromium":true}`.
- Hostname `78155fc81773`, arquivo privado `.env` e montagens de
  `/home/ubuntu/zapbot/.wwebjs_auth` e `/home/ubuntu/zapbot/data` preservados.
  Nenhuma nova porta foi publicada. O override Compose com hostname antigo
  não foi usado.
- Container anterior parado para rollback: `zapbot-before-a44db4c`, imagem
  `zapbot:f4d08d066e3bb11323b763e952ae2e73745e7d6c`. Ele encerrou com código 0
  antes do novo container iniciar; os dois não compartilharam a sessão em execução.
- Cinco verificações da função extraída do bundle implantado passaram: ID `$1`,
  prioridade de `_serialized`, ID direto, ausência de ID e ID em branco.
  O bundle continua sem `zapbot.pokemon.core`.
- Odisseu → Pokémon Service: GETs `/health` e `/ready` retornaram HTTP 200.
  O serviço Google permanece `healthy`, imagem `zapbot-pokemon:76abd80`, com
  `POKEMON_READ_ONLY=false`, conforme habilitação normal já autorizada.
- Nenhum comando real do jogo foi executado automaticamente. Não houve mudança
  de configuração, firewall ou dados Cassandra por esta publicação.
- Sem push Git. A revisão implantada foi registrada em
  `/home/ubuntu/zapbot/deployed-revision`.

## Atualização do diagnóstico Cassandra após recuperação do SSH

O acesso a `ubuntu@144.22.248.79` funcionou com
`~/Downloads/ssh-key-2026-10-04.key`. Foram executadas somente leituras de logs,
configuração e métricas; nenhuma alteração no Cassandra ou write de teste.

- Container: `zapbot-cassandra`, imagem `cassandra:5`; Cassandra não é um serviço
  systemd nessa VM. Os logs relevantes ficam em `/var/log/cassandra` no container.
- `nodetool status`: único nó `10.0.0.234`, `UN` (Up/Normal), DC `datacenter1`.
- Memória da VM: 954 MiB totais, 773 MiB usados, 180 MiB disponíveis;
  swap: 511 MiB usados de 4 GiB. Container: 388 MiB no instante observado.
- Heap: 368,84 MiB de 512 MiB. Disco: 7,9 GiB usados de 45 GiB, 37 GiB livres.
- `nodetool info`: zero exceptions; `tpstats`: sem tarefas pendentes/bloqueadas
  e sem mensagens descartadas no instante observado.
- Configuração existente: write timeout 2000 ms, CAS contention timeout 1000 ms,
  read timeout 5000 ms. Nenhum desses valores foi alterado.
- Logs atual e arquivado de 2026-10-03: pausas GC registradas entre 218 e 403 ms
  e algumas consultas SELECT lentas de aproximadamente 508–546 ms. Não foi
  encontrado erro explícito que explique o timeout 4352 informado.
- Sem registros OOM/I/O nos logs do kernel consultados. A pressão de memória e
  as pausas observadas, isoladamente, **não demonstram a causa do timeout**.

A causa operacional do LWT continuava não comprovada nessa fase. A reprodução precisa
capturar `writeType`, `consistency`, `received` e `blockFor` do erro do driver.
Uma reprodução LWT no cluster real exige autorização explícita para uma escrita
diagnóstica isolada, pois a investigação autorizada restringiu produção a leituras.
Nessa etapa, o serviço Pokémon continuava em read-only, sem reabilitação de
timers ou comandos. A autorização posterior está descrita a seguir.

## LWT diagnóstica e habilitação posterior autorizadas

A única LWT diagnóstica autorizada foi executada da VM Google contra o Cassandra,
com módulo `pokemon-http-diagnostics`, partição
`bc82d8db-25af-44c7-a7be-07ed095feadf` e TTL 600 segundos. Resultado:
`applied=true`, 2502 ms incluindo preparação/comunicação. Um SELECT confirmou TTL
restante de 549 segundos. Não houve retries nem execução do domínio. O erro 4352
não foi reproduzido e sua causa histórica permanece não identificada.

Após autorização para habilitar comandos, o Odisseu foi inspecionado via
`ubuntu@129.148.52.187`, chave `~/Downloads/ssh-key-2026-09-27.key`. Seu único
container ativo do bot nessa etapa usava `zapbot:f4d08d066e3bb11323b763e952ae2e73745e7d6c`:
bundle contém o cliente HTTP e não contém `zapbot.pokemon.core`; apenas o processo
atual Node do bot foi encontrado. URL e token já estavam configurados para Google.

O serviço Google foi recriado, sem rebuild, com `POKEMON_READ_ONLY=false`, mesma
imagem `zapbot-pokemon:76abd80`, mesmo token e volume `pokemon-data-76abd80`.
O container read-only foi preservado parado como `zapbot-pokemon-readonly-76abd80`.
O backup anterior `zapbot-pokemon-before-76abd80` também permanece parado.

- Modo efetivo: `readOnly=false`; timers: `gameTimersEnabled=true`.
- Startup: 15 módulos/11 partições hidratados, 30 SELECTs, zero writes no startup.
- Container: `healthy`, restart `unless-stopped`, porta 8080.
- Odisseu → Google: `/health`, `/ready` e `/events/pending` autenticado retornaram
  HTTP 200; eventos pendentes vazios no instante consultado.
- Odisseu não foi recriado nem reiniciado nessa etapa. Nenhum comando real do jogo foi
  executado automaticamente para validação. Os comandos estão liberados para
  teste pelo usuário; sucesso de `pk treinador` em produção ainda não foi medido.

**Estado atual: modo normal ativo.** A observabilidade corrigida está disponível
para diagnosticar qualquer nova ocorrência do 4352.

## Causa confirmada do INVALID_RESPONSE no Odisseu

O pedido `pk treinador` de `2026-10-04T03:23:12.996Z` falhou no Odisseu sem log
correspondente no serviço Google e sem etapa `pokemon_http`/contexto WhatsApp.
A inspeção somente leitura do Chromium do Odisseu confirmou, em 20 mensagens,
que o ID atual tem `$1` (string), mas não `_serialized`. O valor `$1` coincide
com `MsgKey.toString()` nas 20 amostras, incluindo mensagens com e sem participante.
Somente nomes/tipos de campos e resultados booleanos foram exibidos; nenhum ID
ou conteúdo de mensagem foi exposto.

O adaptador `src/zapbot/pokemon_http.cljs` aceitava somente strings ou
`id._serialized`; portanto obtinha nil e lançava `INVALID_RESPONSE` com
"Mensagem sem identificador estável; comando não encaminhado." antes do HTTP.
Essa falha é distinta do timeout Cassandra 4352.

Correção: aceitar o ID canônico em `$1` quando `_serialized` não está presente,
preservando prioridade do formato legado e rejeitando valores vazios/não string.
O log de `INVALID_RESPONSE` agora inclui o motivo textual seguro do contrato.
Não há geração de ID aleatório, alteração de identidade ou de regras do jogo.

Validação local: 177 testes/1144 assertions, zero falhas/erros; 25 testes HTTP
aprovados; `npm run build` concluído. Os testes novos cobrem formato atual,
compatibilidade legado, repetibilidade do ID, rejeição de IDs inválidos e o fluxo
completo de montagem do pedido, cliente fake e entrega fake, sem writes reais.

Esta correção exigiu imagem nova e recriação do **Odisseu**. A publicação
autorizada foi concluída no commit `a44db4c`, conforme registro ao final.
Nenhuma mensagem real foi reenviada automaticamente.

Imagem local preparada: `zapbot:whatsapp-id-fix-20261003`, Linux AMD64. A função
de serialização extraída do bundle dessa imagem foi executada isoladamente:
aceita `$1`, mantém `_serialized`, rejeita ausência de ID, e o bundle continua
sem `zapbot.pokemon.core`. Nenhum bot/timer foi iniciado nessa verificação.

## Pendências

1. Identificar a causa histórica do writeTimeout 4352 caso ocorra novamente,
   usando os campos completos de erro e logs correlacionados. A LWT diagnóstica
   passou e não comprovou a causa desse timeout.
2. Confirmar o resultado de um comando real via WhatsApp executado pelo usuário
   após o deploy do adaptador.

LWT, consistência e timeouts permanecem preservados. Não há retries automáticos
da reserva incerta nem troca por INSERT incondicional.

## Arquivos do projeto alterados

- `pokemon-service/runtime/service.cjs`
- `pokemon-service/runtime/errors.cjs` (novo)
- `pokemon-service/test/api.test.cjs`
- `pokemon-service/test/pokemon_service/command_http_test.cljs` (novo)
- `pokemon-service/scripts/test-cassandra-local.cjs` (novo)
- `test/pokemon-http-client.test.cjs`
- `src/zapbot/pokemon_http.cljs`
- `test/zapbot/pokemon_http_test.cljs`
- `docs/pokemon-http-4352-report.md` (novo)

`pokemon-service/target/domain.cjs` também foi regenerado (artefato ignorado pelo Git).

## Primeiro deploy autorizado em read-only — 2026-10-03

- Commit da correção: `76abd80`; imagem `zapbot-pokemon:76abd80`, Linux AMD64,
  construída fora da VM e transferida via SSH.
- Container ativo: `zapbot-pokemon`; `unless-stopped`; porta 8080; configuração
  privada existente preservada, com override `POKEMON_READ_ONLY=true`.
- Volume novo: `pokemon-data-76abd80`; `/app/data` anterior copiado antes do startup.
- Container de rollback: `zapbot-pokemon-before-76abd80`, parado (Exited 0).
- Primeira tentativa: Docker rejeitou cópia direta entre containers; rollback
  restaurou automaticamente o serviço anterior. A cópia foi corrigida para usar
  diretório privado intermediário; a segunda tentativa concluiu. O container
  nunca iniciado e volume vazio da primeira tentativa foram removidos.
- `/health`: HTTP 200, `{"status":"ok"}`.
- `/ready`: HTTP 200, `{"ready":true}`.
- Startup: `readOnly=true`, `gameTimersEnabled=false`, 30 SELECTs, 0 writes,
  15 módulos e 11 partições hidratados; Cassandra conectado.
- POST autenticado `/commands`: HTTP 403 `read_only`, confirmando a barreira.
  Não foi executado comando de jogo para testar writes de produção.
- RAM observada: 56,78 MiB; CPU 0,19%; disco da VM: 5,8 GB usados de 8,7 GB.
- API_TOKEN existente validado (>=24 caracteres), sem exibir seu valor.
- Não houve mudança de firewall nem deploy/restart do Odisseu. Teste externo a
  partir do Odisseu não foi repetido porque seu acesso SSH segue indisponível.
- O arquivo de imagem transferido foi removido após sucesso; script reproduzível
  ficou em `/home/caiohclavico/pokemon-service/deploy-pokemon-76abd80.sh`.

**Comandos e timers Pokémon ficaram suspensos nessa fase read-only.** O timeout
4352 permanecia pendente de diagnóstico. Não houve cutover ou reabilitação de
writes nessa publicação; a habilitação normal posterior foi autorizada e está
registrada acima.

Rollback desta publicação, executado na VM caso autorizado:

```sh
docker stop zapbot-pokemon
docker rename zapbot-pokemon zapbot-pokemon-readonly-76abd80
docker rename zapbot-pokemon-before-76abd80 zapbot-pokemon
docker start zapbot-pokemon
```
