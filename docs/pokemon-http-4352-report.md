# Diagnóstico HTTP Pokémon — 2026-10-03

## Resultado e limite

Corrigida a observabilidade e a resposta de erro da reserva durável. `pk treinador`
funciona por HTTP com o domínio real, PNG real e Cassandra 5.0.9 descartável.
**Não está comprovada a resolução do timeout no Cassandra de produção.** Nenhum
deploy, restart, comando de jogo ou write de teste foi executado em produção
durante a investigação inicial. O deploy read-only autorizado posteriormente
está registrado ao final deste relatório.

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
distintos. O log histórico `INVALID_RESPONSE` não é explicado apenas pelo 500
informado; a imagem/log do Odisseu daquele pedido ainda precisa ser inspecionada.

## Inspeção de produção somente leitura

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
- O container atual **não possui volumes montados**. Uma atualização deve
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

Nenhuma regra, schema, consistência, timeout Cassandra ou cliente Odisseu mudou.

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

Odisseu não precisa de nova imagem ou restart **para esta alteração do serviço**.
A divergência histórica `INVALID_RESPONSE` permanece pendente até verificar sua
imagem real e logs correlacionados.

## Pendências

1. Obter acesso/logs Cassandra para identificar a causa operacional do writeTimeout.
2. Inspecionar imagem/logs Odisseu para o `INVALID_RESPONSE` histórico.
3. Aprovar a validação com writes somente depois do diagnóstico Cassandra.
4. Não remover LWT, não trocar por INSERT incondicional e não aumentar timeouts
   por suposição: isso esconderia a falha ou enfraqueceria dedupe.

## Arquivos do projeto alterados

- `pokemon-service/runtime/service.cjs`
- `pokemon-service/runtime/errors.cjs` (novo)
- `pokemon-service/test/api.test.cjs`
- `pokemon-service/test/pokemon_service/command_http_test.cljs` (novo)
- `pokemon-service/scripts/test-cassandra-local.cjs` (novo)
- `test/pokemon-http-client.test.cjs`
- `docs/pokemon-http-4352-report.md` (novo)

`pokemon-service/target/domain.cjs` também foi regenerado (artefato ignorado pelo Git).

## Deploy autorizado e concluído — 2026-10-03

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

**Comandos e timers Pokémon ficam suspensos nesta fase read-only.** O timeout
4352 no Cassandra de produção permanece pendente de diagnóstico. Não houve
cutover ou reabilitação de writes.

Rollback desta publicação, executado na VM caso autorizado:

```sh
docker stop zapbot-pokemon
docker rename zapbot-pokemon zapbot-pokemon-readonly-76abd80
docker rename zapbot-pokemon-before-76abd80 zapbot-pokemon
docker start zapbot-pokemon
```
