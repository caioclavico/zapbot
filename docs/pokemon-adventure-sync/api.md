# API e vinculação propostas

Fase 1 — 10/10/2026. Rotas `/adventure/v1/*` são **propostas e inexistentes**.
Não há aplicativo autorizado a usar a API atual. Nenhum token foi gerado.
Ver [arquitetura](arquitetura.md), [dados](modelo-dados.md) e
[segurança](riscos-e-seguranca.md).

## Etapa A

A mecânica futura usa tempo real ou pontos de atividades, sem passos, GPS,
HealthKit, Health Connect ou Flutter. As funções de domínio ainda são internas
e não constituem uma API pública; persistência e integração de eventos ficam
para as etapas B e C.

## Estado após a Fase 2

Nenhuma rota deste contrato público foi implementada. Os exports internos do
`target/domain.cjs` são `eggsActivate`, `eggsCreate`, `eggsIncubate`, `eggsDistance`,
`eggsState`, `eggsRecover` e `eggsHistory`, todos sujeitos à flag e às barreiras
de persistência. Eles não estão no dispatcher de `/commands` nem no handler
HTTP. `eggsCreate` não é uma concessão aberta a usuário externo.

`eggsDistance(cid,pid,evento)` pertence ao protótipo anterior por distância e
não é usado pela nova mecânica. A futura interface por atividades receberá IDs
estáveis de eventos. O trecho legado recebia chaves `event-id`, `from-ms`, `to-ms` e
`distance-mm`. O produtor deve entregar incremento já validado/consolidado
e identidade estável para a caminhada. Repetir ID+conteúdo não credita de novo;
ID igual com conteúdo diferente falha. Não enviar payloads móveis diretamente
a esse export, nem sintetizar novas identidades para retry. A futura API ainda
precisa implementar autenticação, fontes/intervalos, rate limit, consolidação,
revogação e isolamento público.


## 1. API atual e fronteira nova

| Rota existente | Autorização/comportamento observado |
|---|---|
| `GET /health` | Sem Bearer; informa processo HTTP, não WhatsApp ou capacidade de nascimento |
| `GET /ready` | Sem Bearer; `ready` exige serviço aceitando e domínio conectado/hidratado |
| `POST /commands` | Bearer interno; requestId, chatId, playerId, command, context. Reserva LWT e fila de chat; não é API de usuário móvel |
| `GET /events/pending` | Bearer interno; eventos para Odisseu |
| `POST /events/{id}/ack` | Bearer interno; confirmação após envio |
| `GET /media/{id}` | Bearer interno; conteúdo temporário por hash |

O servidor atual usa HTTP Node, corpo máximo 64 KiB, deadline HTTP 60 s e
`POKEMON_READ_ONLY` bloqueando rotas além de health/ready. Timeout não cancela
necessariamente uma gravação já em andamento. Resultado incerto não é permissão
para repetir comando com outro requestId.

Preservar esse contrato. Um futuro dispatcher móvel separado dentro do mesmo
servidor terá validador, autenticação e limites próprios. Não aceitar token de
dispositivo nas rotas internas nem `API_TOKEN` nas rotas de usuário.
Feature flag implementada `ADVENTURE_SYNC_ENABLED=false` inicialmente; read-only
continua bloqueando qualquer gravação nova, inclusive códigos/tokens.

Publicar somente `/adventure/v1/*` em HTTPS, com validação do certificado no app;
rotas internas permanecem privadas. Não usar acesso público ao IP/porta HTTP
atual como solução. Domínio/TLS são pré-requisitos futuros, não configurados aqui.

## 2. Vinculação sem usar número como credencial

Fluxo inspirado em autorização de dispositivo, com PKCE S256 para a troca.
Não declarar compatibilidade OAuth completa: implementar apenas após revisão
de segurança e preferir bibliotecas/protocolos padronizados onde aplicável.

1. App gera `codeVerifier` aleatório de pelo menos 32 bytes, guarda localmente e
   envia `codeChallenge=S256(verifier)` para `POST /pairings`. Servidor gera
   deviceCode aleatório de 32 bytes e userCode de 8 caracteres Crockford base32
   (40 bits), validade de 10 min e polling mínimo de 5 s.
2. App mostra userCode e instrução: no chat onde está a coleção, enviar
   `!pk sync vincular <userCode>`. deviceCode/verifier jamais vão ao WhatsApp.
3. Odisseu usa o autor real da mensagem e chat, pela API interna existente.
   Servidor resolve/cria accountId e vincula solicitação ao par exato. App
   não escolhe esses IDs. Outro jogador não pode sobrescrever o solicitante;
   códigos/claims concorrentes usam CAS e vencimento pelo relógio do servidor.
4. Exigir confirmação **privada** ao mesmo autor, mostrando app/plataforma,
   fingerprint curto da solicitação, grupo-alvo e aviso de troca do coletor.
   `!pk sync confirmar <nonce>` dura até 2 min, sempre dentro do prazo original.
   Uma mensagem em grupo não revela nonce/tokens. O comando de confirmação em
   DM deve resolver a solicitação original, sem mudar accountId para o chat DM.
   Se entrega privada ou identidade exata não puder ser validada, abortar sem
   vínculo; não resolver automaticamente LID por número/contato.
5. App consulta estado via `POST /pairings/status` com userCode, deviceCode e
   prova PKCE. Antes da confirmação só recebe estado genérico, sem jogador.
6. `POST /tokens` só após confirmação troca deviceCode+verifier por access token,
   refresh token, deviceId e accountId. Consumir código uma vez com CAS; repetir
   a mesma troca pode recuperar resposta cifrada por até 60 s, com mesma prova
   e idempotencyKey. Nunca criar um segundo vínculo/tokens novos num replay.
7. Se já existe coletor, só trocar após confirmação explícita no WhatsApp.
   Desativar epoch antiga e iniciar nova no próximo intervalo UTC de 5 min;
   negar qualquer novo upload pelo aparelho antigo. Avisar sobre backlog que
   deve ser sincronizado antes da troca e possíveis perdas, não importar
   automaticamente o histórico do novo celular sobre o antigo.

O número/ID do WhatsApp é somente referência de conta, não senha, código ou
Bearer. Controle do app não permite vincular conta alheia só conhecendo número
ou código. A segurança pressupõe a conta WhatsApp do jogador não comprometida;
confirmar voluntariamente o app de um invasor ainda pode autorizar o vínculo.

### Tokens, renovação e revogação

- Access token opaco: aleatório >=32 bytes, 15 min, escopos `sync:write`,
  `eggs:read`, `device:self`; refresh de até 30 dias com rotação por uso.
- Guardar hashes no servidor; response replay excepcional cifrado e de prazo
  curto, com chave fora do banco/código. No aparelho usar armazenamento seguro
  Keychain/Keystore, sem backup/sync de tokens como dado comum.
- `POST /tokens/refresh` exige rotationId persistido no aparelho. Mesmo token
  anterior+rotationId recupera resposta idêntica até 60 s; outro ID com token
  consumido caracteriza replay e revoga a família. Resultado incerto não dispara
  refresh simultâneos; app serializa e conserva ID até resolução.
- Tabela de lookup é índice; expiração explícita, geração do device e epoch
  autoritativa precisam ser conferidas. Token expirado/índice residual não
  reativa aparelho revogado. CAS de sync valida epoch ainda vigente no commit.
- `!pk sync dispositivos` lista IDs curtos sem segredos; `!pk sync revogar <id>`
  ou `todos` revoga imediatamente autorização de sync. `DELETE /devices/self`
  permite auto-revogação. Se celular foi perdido, WhatsApp permite revogar e
  refazer vínculo; nunca solicitar chave ou token antigo.
- Não modificar autenticação/sessão whatsapp-web.js para esse fluxo.

## 3. Rotas propostas

| Método/rota | Credencial | Resultado/efeito |
|---|---|---|
| `POST /adventure/v1/pairings` | Sem Bearer, rate limit | Solicitação temporária + códigos; só dados do app/plataforma/desafio |
| `POST /adventure/v1/pairings/status` | deviceCode + PKCE | Estado genérico, sem token/conta antes da aprovação |
| `POST /adventure/v1/tokens` | deviceCode + PKCE + aprovação | Consumo idempotente da solicitação |
| `POST /adventure/v1/tokens/refresh` | refresh token | Rotação limitada e verificável |
| `GET /adventure/v1/me` | Access token | Conta opaca, dispositivo, epoch, mode, serverTime, limites/consentVersion |
| `GET /adventure/v1/eggs` | Access token | Ovos/incubadoras, progresso confirmado/provisório e pendências |
| `POST /adventure/v1/sync` | Access token | Intervalos substituíveis, recibo e cálculo do servidor |
| `GET /adventure/v1/sync/{dayUtc}/{batchId}` | Access token | Consultar resultado próprio após timeout |
| `GET /adventure/v1/hatches?month=&cursor=` | Access token | Histórico próprio paginado, sem intervalos de saúde |
| `DELETE /adventure/v1/devices/self` | Access token | Revogar aparelho e interromper coleta |

Incubação/resgate no MVP ocorrem por WhatsApp. Não criar endpoint móvel para
atacar, capturar, comprar ou chamar `command`. Associação de conta, concessão
do primeiro ovo e incubação passam por operações tipadas do domínio, nunca por
texto arbitrário sintetizado pelo app.

## 4. Contrato incremental

Exemplo fictício de lote, sem dados pessoais; horários precisam satisfazer a
janela relativa ao serverTime real. `collectionEpoch` é emitida pelo servidor.
O campo `windows` não representa quilômetros calculados pelo cliente.

```json
{
  "schemaVersion": 1,
  "batchId": "11111111-1111-4111-8111-111111111111",
  "dayUtc": "2026-10-10",
  "collectionEpoch": "22222222-2222-4222-8222-222222222222",
  "policyVersion": "steps-v1",
  "mode": "steps",
  "windows": [
    {
      "start": "2026-10-10T10:00:00Z",
      "end": "2026-10-10T10:05:00Z",
      "revision": 1,
      "steps": 420,
      "recordingMethod": "automatic",
      "sourceClass": "os_aggregate",
      "evidenceDigest": "hash-opaco-opcional"
    }
  ]
}
```

Modo `walking_distance` usa `distanceMm`, inteiro, e não envia steps para
crédito. Modo fixo por epoch. Agregado não é prova criptográfica do sensor;
sourceClass/recordingMethod continuam alegações não confiáveis do app.

Validação proposta:

- Corpo <=64 KiB; <=100 intervalos, todos do mesmo dia UTC e epoch.
- Intervalos exatos de 300 s, alinhados, únicos, completos, sem sobreposição,
  UTC explícito e não futuros. Números inteiros seguros, >=0; rejeitar NaN,
  overflow, unidade desconhecida ou propriedades não permitidas.
- Revision inteira positiva crescente por intervalo. Releitura preserva a
  revisão se conteúdo não mudou; reinstalação não permite reset arbitrário.
- Fingerprint canônico vincula batchId ao corpo validado; mesma solicitação
  retorna recibo. Reutilizar batchId com outro conteúdo é 409.
- Quem envia não pode sobrescrever `accountId`, `deviceId`, alvo, catalogVersion,
  seed, goal, eggId ou progresso: derivar do token/estado.
- Sincronizar por páginas, partindo dos intervalos mais antigos, conservando
  lote e ID no armazenamento local até confirmação durável. Advance de âncora
  nativa só após gravar página no cache/outbox local; não precisa esperar HTTPS.
- Retry de rede usa mesmo corpo/batchId com backoff exponencial e jitter; só
  timeout/429/5xx recuperável. Consultar recibo em resultado incerto antes de
  novo lote. Não executar retry de nascimento/coleção pelo cliente.

Resposta ilustrativa após CAS confirmado, antes de consolidação de 24 h:

```json
{
  "batchId": "11111111-1111-4111-8111-111111111111",
  "status": "committed",
  "stateVersion": 12,
  "serverTime": "2026-10-10T10:06:00Z",
  "windows": [{"start":"2026-10-10T10:00:00Z","decision":"provisional"}],
  "provisionalMm": 294000,
  "creditedMm": 0,
  "pendingHatches": []
}
```

`creditedMm` é o crédito deste processamento, não nova permissão para o app
somar localmente em ovos. `GET /eggs` mostra o estado atual. Resultado pronto
para chocar exige distância consolidada, vaga e entrega confirmada. Não chamar
um retorno 200 de prova de mensagem WhatsApp entregue.

| Código | Situação | Tratamento |
|---|---|---|
| 400/413 | schema, intervalo, limite ou unidade inválida | Corrigir coleta; não retry cego |
| 401 | token expirado/inválido | Renovar uma vez, serializado |
| 403 | device revogado, epoch inválida ou read-only | Parar uploads; orientar vínculo/permissão |
| 409 `batch_conflict` | Mesmo ID com dados diferentes | Não substituir recibo |
| 409 `revision_conflict` | Revisão igual com outro digest | Reconciliar cache/solicitação |
| 410 `window_expired` | Fora dos 7 dias/código vencido | Não pode virar crédito com outro ID |
| 429 | Limite de requisições/lotes | Respeitar Retry-After/backoff |
| 503 `not_ready`/`feature_disabled` | Cassandra/schema/recurso indisponível | Conservar outbox local |
| 503/504 `result_unknown` | Commit não confirmado ou prazo HTTP | Consultar mesmo batch; não mudar ID |

Um lote atomicamente aceito pode conter decisões por intervalo (obsoleto,
provisório, rejeitado por política, já finalizado); corpo inválido não é commit
parcial. Resposta durável lista as decisões, inclusive valor rejeitado/razão
de forma privada. Erros não incluem tokens, payload saúde ou CQL.

## 5. Limites e observabilidade

Proposta inicial: uma operação de sync ativa por accountId, até 2 contas em
processamento móvel global, 6 lotes/min/dispositivo com burst 2, e orçamento
de catch-up ajustável para 7 dias sem bloquear o Odisseu. Pareamento: 5 criações
por 10 min/IP e limites adicionais por solicitação/conta; tentativas de código
limitadas e resposta genérica. Validar cargas reais antes de fixar valores.

Logs essenciais: auth/revogação falhou, sync rejeitado por integridade,
persistência incerta, projeção/nascimento pendente e falha de notificação.
IDs de correlação opacos; sem token, código, número, intervalo ou valor de saúde
em logs. Usar instrumentação existente condicionada a `PERFORMANCE_METRICS`;
preservar padrão false, sem timers de métricas paralelos.

Readiness do recurso exige schema novo validado e reconciliação das operações
incertas antes de mutações. Indisponibilidade Adventure Sync deve ser reportada
no módulo e negar suas rotas; não declarar falha de autenticação WhatsApp nem
desabilitar outros jogos por ausência de permissão móvel.
## Endpoints internos da Etapa C

Com `ADVENTURE_SYNC_ENABLED=true` e o mesmo `API_TOKEN` privado do serviço:

- `POST /eggs/state` com `{chatId, playerId}` consulta o estado isolado da conta.
- `POST /eggs/incubate` com `{chatId, playerId, eventId, eggId}` inicia a incubação
  usando o recibo idempotente existente.

Essas rotas exigem autenticação, não aceitam dados de passos e permanecem
indisponíveis quando a feature está desligada. `eventId` deve ser único por
operação; um resultado incerto não deve ser repetido com outro identificador.
