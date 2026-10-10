# Modelo de dados proposto

Atualizado na Fase 2 — 10/10/2026. O desenho completo e os schemas futuros
continuam como referência; o subconjunto implementado está descrito abaixo.
CQL foi validado somente em banco descartável local, nunca em produção.
Referências: [arquitetura](arquitetura.md), [API](api.md).

## Etapa A — mudança de mecânica

A sincronização por distância/quilômetros foi abandonada como mecânica de
produto. O novo domínio puro em `ovos.cljs` modela categorias `:comum`, `:raro`
e `:epico`, com metas configuráveis de 2/6/12 horas ou 100/250/500 pontos,
respectivamente. A transição é persistida na Etapa B por
`transicionar-legado!`, usando o CAS existente, preservando recibos e sem DDL.

Política de conversão aprovada para a Etapa B: distância 2/5/10 km vira
comum/raro/épico; `points = floor(oldProgress / oldGoal * newPointsGoal)`;
`incubated-at`, identidade, pool, seed, recibos e estado são preservados.
Ovos `ready_to_hatch` continuam prontos e não são entregues durante a
conversão. O marcador `transition-receipt` impede repetição; nenhum prazo é
reiniciado e nenhuma recompensa é criada. A escrita é protegida pelo CAS
existente e só ocorre depois de confirmar o schema legado. O bootstrap não a
executa enquanto a flag estiver desligada.

## Implementação delimitada da Fase 2

Foram implementadas quatro tabelas aditivas do modelo: `adventure_account_binding`,
`adventure_account_state`, `adventure_sync_receipts` e `adventure_hatches_by_month`.
O schema para revisão está em `scripts/cassandra/adventure-sync-v1.cql`, com
placeholder `{{KEYSPACE}}`; não é executado pelo runtime, CI ou deploy. O runtime
confere tipos e chaves requeridos em `system_schema.columns`, sem DDL.

Ovos/incubadoras são lidos do state canônico, como a opção MVP já prevista;
projeções opcionais ficam adiadas. Tabelas de devices/tokens/link/outbox não
foram implementadas porque autenticação e notificações não fazem parte desta
fase. O histórico usa o registro atual de Pokémon, **sem IVs**.

Cada operação grava recibo e resultado junto ao state por CAS versionado.
Distância é inteira em mm, recebida de emissor interno confiável, com ID único
e intervalo não futuro dentro de 7 dias. Não há cálculo de passos, revisão de
amostras, modo/epoch móvel ou espera adicional de 24 h nesta interface: o
incremento já deve estar consolidado. Receipts técnicos são projetados com
TTL 14 dias; somente depois dessa confirmação os recibos de distância velhos
podem sair do state. Incrementos antigos sem recibo são recusados, não reaplicados.
Recibos de concessão/incubação não são esquecidos automaticamente.

Entrega e `adventure-entregas[hatchId]` são gravados por CAS na mesma linha
legada do chat. Mudança não confirmada bloqueia esse chat até leitura SERIAL
e reconciliação. Gerações de snapshot preservam a partição recém-confirmada
contra snapshots capturados por outros chats. Não há transação ACID entre
tabelas. Histórico e limpeza do inventário são projeções reparáveis do state.

Na inicialização habilitada, páginas de até 32 contas do novo state são lidas
sem cache global; pendências são reconciliadas na fila existente do chat.
Nenhum histórico inteiro ou módulo de saúde é hidratado. Mantém-se o contrato
de um único writer Pokémon contra os módulos legados.


## 1. Compatibilidade com o armazenamento existente

O serviço usa `CASSANDRA_KEYSPACE` e estas tabelas existentes:

```text
estado: chave text PRIMARY KEY, valor text
estado_particionado: modulo text, particao text, valor text,
                    PRIMARY KEY ((modulo, particao))
```

O módulo `treinador` armazena uma linha por chat, com todos os jogadores daquele
chat no JSON; `loja` segue o mesmo padrão. Outros módulos incluem `raids`,
`ginasios`, `pokemon-descobertas`, combates/caçadas e recibos/eventos HTTP.
`salvar!` serializa por módulo, mas não implementa CAS de versão nem transação
entre módulos. `reservar!` é LWT para criar uma linha, não uma transação de jogo.

Adicionar um repositório dedicado, usando o mesmo driver/conexão e barreira
read-only, sem hidratar todo o histórico no cache. O driver hoje é privado ao
namespace: expor interface restrita de consultas preparadas, não acesso livre do
app a CQL. Sem `ALLOW FILTERING`, JOIN, contador Cassandra ou alterações ao
schema legado. DDL futuro separado e aprovado; runtime apenas valida schema.

Consistência proposta: `LOCAL_QUORUM` para novas leituras/escritas e
`LOCAL_SERIAL` para CAS, após verificar RF/DC/versão reais na fase autorizada.
Com RF=1 isso não fornece redundância. Não se conhece o schema/replicação da
produção por observação de VMs nesta fase. Validar sintaxe/recuperação no
Cassandra de testes da mesma versão antes de instalar. Isolamento de batch
é limitado à partição; não usá-lo como transação entre coleção e histórico.
[Semântica CQL oficial](https://cassandra.apache.org/doc/latest/cassandra/developing/cql/dml.html).

## 2. Autoridade, estado e consultas

Usar UUID `account_id` para cada par exato `(chat_id, player_id)`. Não derivar
autorização de número, nome ou ID enviado pelo app. IDs de ovos, incubadoras e
dispositivos são UUIDs do servidor. IDs de Pokémon nascidos são fixados no
resultado e permanecem iguais em qualquer recuperação.

| Tabela nova proposta | Chave primária | Consulta/retenção |
|---|---|---|
| `adventure_account_binding` | `((chat_id), player_id)` | Resolver conta a partir do contexto WhatsApp; `IF NOT EXISTS` resolve criação concorrente; sem TTL |
| `adventure_account_state` | `account_id` | Estado autoritativo por versão CAS; ovos, incubadoras, progresso, epochs, revisões e operações pendentes; sem TTL |
| `adventure_link_codes` | `code_hash` | HMAC do código → solicitação, desafio PKCE, deviceCode hash, prazo e confirmação; TTL 600 s, prazo explícito |
| `adventure_devices` | `((account_id), device_id)` | Listar/revogar dispositivos e consultar credencial; hashes e geração de refresh; sem token bruto |
| `adventure_token_lookup` | `token_hash` | Token → account/device/scopes/generation; TTL restante de acesso/refresh; estado/device é autoridade final |
| `adventure_eggs_by_account` | `((account_id), egg_id)` | Projeção consultável do inventário, máximo 9; autoridade continua no state |
| `adventure_incubators_by_account` | `((account_id), incubator_id)` | Projeção de slots/usos; estado canônico decide disponibilidade |
| `adventure_sync_receipts` | `((account_id, day_utc), batch_id)` | Recibo técnico de lote, hash, seq e resultado, TTL máximo 14 dias |
| `adventure_hatches_by_month` | `((account_id, month_utc), seq)` | Histórico paginado de nascimentos e UUID/registro atual; sem TTL por padrão, sujeito a exclusão solicitada |
| `adventure_outbox_by_bucket` | `((day_utc, shard), event_id)` | Trabalho pendente de histórico/notificação, shards fixos 0–15, consulta de buckets conhecidos, retry idempotente |

Eventos de sync e progresso são campos autoritativos do state e recibos
consultáveis; não é necessário armazenar toda amostra individual do sensor.
As tabelas de ovos/incubadoras são projeções opcionais: podem ser adiadas no
MVP e lidas diretamente do state. Todas as demais têm finalidade específica,
sem implantar mensageria paralela. O bucket de outbox é índice recuperável,
não verdade única da entrega.

### CQL ilustrativo mínimo

```sql
CREATE TABLE <keyspace>.adventure_account_binding (
  chat_id text, player_id text, account_id uuid, created_at timestamp,
  PRIMARY KEY ((chat_id), player_id)
);
CREATE TABLE <keyspace>.adventure_account_state (
  account_id uuid PRIMARY KEY,
  version bigint, schema_version int, updated_at timestamp, payload text
);
CREATE TABLE <keyspace>.adventure_link_codes (
  code_hash text PRIMARY KEY,
  payload text, state text, expires_at timestamp
);
CREATE TABLE <keyspace>.adventure_devices (
  account_id uuid, device_id uuid, version bigint, payload text,
  PRIMARY KEY ((account_id), device_id)
);
CREATE TABLE <keyspace>.adventure_token_lookup (
  token_hash text PRIMARY KEY,
  account_id uuid, device_id uuid, generation bigint, kind text,
  expires_at timestamp
);
CREATE TABLE <keyspace>.adventure_eggs_by_account (
  account_id uuid, egg_id uuid, state_version bigint, payload text,
  PRIMARY KEY ((account_id), egg_id)
);
CREATE TABLE <keyspace>.adventure_incubators_by_account (
  account_id uuid, incubator_id uuid, state_version bigint, payload text,
  PRIMARY KEY ((account_id), incubator_id)
);
CREATE TABLE <keyspace>.adventure_sync_receipts (
  account_id uuid, day_utc date, batch_id uuid,
  fingerprint text, state_version bigint, payload text,
  PRIMARY KEY ((account_id, day_utc), batch_id)
);
CREATE TABLE <keyspace>.adventure_hatches_by_month (
  account_id uuid, month_utc text, seq bigint,
  hatch_id uuid, egg_id uuid, pokemon_id uuid, payload text,
  PRIMARY KEY ((account_id, month_utc), seq)
) WITH CLUSTERING ORDER BY (seq DESC);
CREATE TABLE <keyspace>.adventure_outbox_by_bucket (
  day_utc date, shard int, event_id uuid,
  account_id uuid, state_version bigint, payload text,
  PRIMARY KEY ((day_utc, shard), event_id)
);
```

`<keyspace>` é placeholder validado, não um nome literal executável. `payload`
é JSON versionado e com tamanho limitado, evitando UDTs/mapas crescentes sem
controle. Campos consultados pertencem às chaves; não criar índices secundários.

### Consultas previstas, sempre por chave

Exemplos preparados para o repositório futuro, sem execução nesta fase:

```sql
SELECT account_id FROM <keyspace>.adventure_account_binding
WHERE chat_id = ? AND player_id = ?;
SELECT version, schema_version, payload FROM <keyspace>.adventure_account_state
WHERE account_id = ?;
SELECT egg_id, state_version, payload FROM <keyspace>.adventure_eggs_by_account
WHERE account_id = ?;
SELECT incubator_id, state_version, payload FROM <keyspace>.adventure_incubators_by_account
WHERE account_id = ?;
SELECT device_id, version, payload FROM <keyspace>.adventure_devices
WHERE account_id = ?;
SELECT fingerprint, state_version, payload FROM <keyspace>.adventure_sync_receipts
WHERE account_id = ? AND day_utc = ? AND batch_id = ?;
SELECT seq, hatch_id, pokemon_id, payload FROM <keyspace>.adventure_hatches_by_month
WHERE account_id = ? AND month_utc = ? AND seq < ? LIMIT 20;
SELECT event_id, account_id, payload FROM <keyspace>.adventure_outbox_by_bucket
WHERE day_utc = ? AND shard = ? LIMIT 64;
```

Histórico inicial omite `seq < ?`; páginas seguintes usam último seq e mês,
com cursor vinculado à conta. Listas usam paginação do driver; o limite da
outbox não pode deixar trabalho posterior inacessível. Não atualizar projeção
com stateVersion mais antiga que a já gravada: serializar projeções e usar CAS
de versão quando necessário, recuperando de intents em vez de confiar no índice.

## 3. Conteúdo do estado canônico

```text
schemaVersion, version, chatId, playerId
devices: até 5 registros; no máximo 1 coletor ativo, authEpoch/revokedAt
collectionEpochs: modo, origem/política, início e fim UTC não sobrepostos
eggInventory: eggId, category, goalMm, state, catalogVersion, seed privada
incubators: incubatorId, reusable, usesRemaining, activeEggId
activations: eggId/incubatorId, start/end alinhados, versão de regra
progress: totalCreditedMm, debtMm, finalizedBefore, lastSyncAt
windows: epoch/start → total original, revisão, digest, decisão, crédito final
pendingOperations: operationId, kind, resultado fixo e checkpoints
projectionCheckpoint, nextHatchSeq
```

Não transportar esse JSON bruto ao cliente: ele contém identificadores
WhatsApp, seed e controle de autorização. Expor somente DTOs permitidos.
Unidade interna: milímetros inteiros (`bigint`/inteiro seguro validado), sem
acumular floats de km; converter km apenas para apresentação. 0,70 m/passo =
700 mm. `goalMm` é 2.000.000, 5.000.000 ou 10.000.000.

Estados do ovo:

```text
inventory -> incubating -> ready_to_hatch -> delivery_pending -> hatched
```

Desativação opcional mantém progresso e fecha activation; reativação só no
próximo intervalo completo, sem reaproveitar distância já contabilizada.
Usos consumíveis decrementam uma única vez na confirmação do nascimento.
O MVP permanente não perde uso por falha de rede ou estoque cheio.

Manter no máximo 7 dias de intervalos detalhados (2.016 intervalos por epoch
contínuo), poucas epochs e ativações dentro dessa janela. Limite inicial do
state: 512 KiB serializado, com backpressure no código; validar volume e pior
caso em homologação antes de liberar a API móvel. Esse não é um limite do
Cassandra. Paginar lotes e compor estruturas compactas. Recibo de distância não
fica indefinidamente no state; concessão/incubação mantêm recibos permanentes
na Fase 2. Manter marcadores suficientes para reparar
a projeção. Histórico de ovos consumidos sai do inventário após confirmação.

Cadastro também precisa de recuperação: se a linha binding foi criada e o
processo caiu antes do state, reusar o mesmo accountId e inicializar state com
`IF NOT EXISTS`. Não conceder outro ovo de boas-vindas. Aprovação de dispositivo
fica pendente até state/device/lookup concordarem; um índice isolado não autoriza.
Reparação de projeções e recibos deve terminar antes de compactar seu diário;
se faltar espaço, aplicar backpressure e preservar operações, não apagá-las.

## 4. Commit idempotente de sincronização

1. Autenticar dispositivo, verificar revogação/mode/epoch e ler state atual.
2. Consultar recibo por `batchId` e dia UTC canônico do lote; mesmo ID+hash
   retorna resultado, outro hash gera conflito. Um ID novo não evita dedupe
   por intervalo/revisão. Todos os intervalos de um lote pertencem ao mesmo dia.
3. Calcular substituições, limites diários, distância elegível e transições de
   ovos, mantendo ordem cronológica da caminhada. Não incrementar um counter.
4. Preparar operação com hash, resultado, novos windows e efeitos no mesmo
   payload. CAS único persiste revisão e crédito juntos:

```sql
UPDATE <keyspace>.adventure_account_state
SET version = ?, payload = ?, updated_at = ?
WHERE account_id = ? IF version = ?;
```

5. Só após confirmação atualizar cache e projetar recibo/histórico/outbox. CAS
   não aplicado exige releitura/recalcular, com tentativas limitadas. Timeout
   Cassandra é **resultado incerto**: ler state pela chave/ID da operação usando
   consistência apropriada; jamais reaplicar o incremento às cegas. Se não for
   possível confirmar, devolver `result_unknown` e impedir nova mutação até
   reconciliar aquela conta.
6. A operação persistida é a origem dos efeitos. Falha antes da projeção não
   perde progresso nem autorização. Ler/sincronizar/reiniciar pode reparar a
   projeção a partir do state; usar índice de contas com trabalho pendente e
   recuperação paginada por token range do **novo state**, não carregar todos
   os jogadores/legado. Não responder que histórico foi projetado antes de sê-lo.

Sem transação entre recibo e state: recibo é derivado. Ao repetir um lote cujo
recibo falhou, os windows/diário reconhecem o commit. Após expirar recibos, o
watermark impede ressuscitar crédito velho, mesmo com outro batchId.

### Correções e proteção contra duplicação

Antes de finalizar, revisão maior substitui o total; revisão menor é obsoleta;
mesma revisão com digest diferente é conflito. Reiniciar/reinstalar não reinicia
sequência sem nova epoch aprovada. Ausência de amostras não equivale a exclusão;
exclusão confirmada deve enviar total revisado explícito.

Para intervalo finalizado, manter `creditedMm`, `currentValidatedMm` e débito
aplicado. Se 700 m creditados virarem 560 m, criar dívida de 140 m; atualização
igual não repete dívida. Se voltar a 700 m, cancelar a dívida correspondente
(ou restituir saldo compensado), **sem novo nascimento/novo crédito líquido**.
O benefício líquido daquele intervalo jamais ultrapassa o crédito já fixado.
Correções após janela retida são divergências auditáveis sem ajuste automático:
limitação conhecida, não promessa de reconciliação eterna.

Avançar `finalizedBefore` quando a janela antiga sair da elegibilidade. Esse
watermark não tem TTL. Recusa de intervalos anteriores independe de hashes
expirados; TTL de 14 dias de recibos não reabre 7 dias antigos. Débito/progresso
e recibos de entrega de Pokémon não expiram automaticamente.

## 5. Nascimento e ponte com coleção legada

O cálculo CAS fixa `hatchId`, UUID do Pokémon, espécie, nível, golpes,
catálogo e uma intenção `delivery_pending`. Histórico usa sequência fixa;
projeções idempotentes usam a mesma chave. Não gerar nova seed a cada retry.

Entrega proposta:

1. Entrar na fila de domínio do chat, serializada com captura, troca, doação,
   transferência e outros escritores. Respeitar guarda de operação pendente e
   evitar alteração de índices durante combate; por cautela, adiar entrega
   enquanto o jogador estiver em combate/transferência incompatível.
2. Revalidar capacidade atual, incluindo equipe, enfermaria e defensores de
   ginásio, usando a regra central já existente. Estoque cheio conserva intenção.
3. Criar operação **nova e específica** em `treinador.cljs`: no mesmo snapshot
   da conta, acrescentar registro compatível e recibo persistente
   `adventure-entregas[hatchId] = pokemonId`, atualizar descoberta de nascimento
   sem bônus de captura e aguardar gravação real. Não basta chamar
   `adicionar-pokemon!` e depois salvar recibo em outra tabela.
4. Falha/resultado incerto do snapshot legado bloqueia mutações conflitantes
   do chat até reconciliar último snapshot durável. Não usar memória otimista
   como prova de entrega. Essa guarda é uma adaptação futura necessária;
   precisa de testes com comandos existentes e não existe hoje.
5. Se receipt já existe, não acrescentar novamente, mesmo se o Pokémon foi
   depois doado, transferido ou removido da coleção. Procurar apenas UUID na
   equipe não basta. Manter recibo compacto durável enquanto a intenção puder
   ser reapresentada; não compactar sem provar que não há replay possível.
6. Após snapshot confirmado, CAS marca ovo `hatched`, consume uso uma vez e
   projeta histórico e notificação. Se crash entre passos 3 e 6, receipt permite
   completar apenas passo 6. Não adicionar Pokémon outra vez.

O contrato atual é de **um escritor Pokémon**. CAS do novo state não protege
snapshots legados contra dois serviços ativos. Replicação horizontal só depois
de redesign/fencing explícito; não assumir que a nova tabela resolve isso.

## 6. Notificação e recuperação

Persistir intenção com `eventId` estável derivado do `hatchId` e tipo. A outbox
nova projeta para `pokemon-http-events` por uma futura variante idempotente de
`enqueueEvent`, preservando o envelope e consumidor Odisseu existentes.
Os eventos atuais usam UUID aleatório e o ACK apaga o evento; adicionar recibo
durável de projeção/ACK para nascimento evita ressuscitar um evento já confirmado.

Falha de notificação não reverte coleção. Sem ACK, enviar novamente com mesmo
ID; Odisseu reaproveita `pokemon-http-deliveries`. Crash após envio WhatsApp e
antes de salvar entrega pode duplicar a mensagem, jamais o Pokémon.
Mensagem só informa nascimento/especie, não passos, horários ou dados de saúde.

Durante shutdown, suspender novas admissões, drenar sync/entrega/projeções e
filas existentes, e só então encerrar Cassandra. Registrar operações pendentes
na instrumentação de shutdown atual. Não descartar promises nem forçar sucesso.

## 7. Migração e retenção

- Plano futuro de schema **aditivo**, numerado, verificável e executado uma vez
  após autorização. Sem `DROP`, `TRUNCATE`, `ALTER` em tabelas atuais ou reescrita
  de jogadores. Novos registros só surgem quando alguém opta por vincular.
- Verificar schema/RF/DC e conta Cassandra apropriada antes de aprovar DDL;
  não conceder DDL à API móvel. Não expor queries de manutenção na API.
- Rollback do recurso: desativar admissão móvel/novos ovos; conservar schemas,
  receipts e intenções. Uma imagem antiga não deve regravar contas contendo
  receipts novos sem teste de compatibilidade. Rollback binário e de feature
  são decisões distintas.
- Retenção proposta: intervalos/cache saúde 7 dias; recibos técnicos até 14 dias;
  código 10 min; access token 15 min; refresh até 30 dias; histórico de jogo
  até exclusão solicitada. TTL é limpeza auxiliar, não mecanismo exclusivo de
  expiração/revogação. Backups/replicas/tombstones precisam de política explícita.
- Exclusão de dados de saúde/dispositivo não apaga Pokémon já recebidos nem
  dados de outros módulos. Eliminar identificação das projeções quando possível;
  manter só marcador mínimo de crédito/entrega para impedir fraude por replay,
  com finalidade e prazo transparentes.
