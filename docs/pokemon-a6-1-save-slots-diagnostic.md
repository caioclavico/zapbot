# A6.1 — Diagnóstico de recuperação V1 e múltiplos slots

Data: 2026-10-23
Branch de trabalho: `a6-save-slots`
Base: `2cab639` (descendente de `382638a`)

Este documento é somente a auditoria A6.1. Nenhum Cassandra, volume de produção,
VM ou sessão WhatsApp foi acessado. A implementação pública de slots e a
recuperação de dados reais ficam para A6.2/A6.3.

## Estado atual do Core

O Core já possui um modelo Datomic de jogador com ponteiro para o save ativo:

| Entidade/atributo | Evidência | Situação |
| --- | --- | --- |
| Identidade do treinador | `migrations/pokemon/001-trainer-saves.edn`, `:player/key`, `:player/chat-id`, `:player/player-id` | Implementado; chave canônica é `chat-id|player-id`. |
| Ponteiro ativo | `:player/active-save-id` e `:player/fence-generation` | Implementado; troca incrementa o fence por transação CAS. |
| Save por slot | `:save/key`, `:save/slot-id`, `:save/player` | Implementado; chave canônica é `chat-id|player-id|slot-id`. |
| Estado por save | `:save/*`, `:pokemon/*`, `:inventory-item/*`, `:pokedex-entry/*` | Implementado; leituras e escritores recebem o slot. |
| Criação de save | `pokemon.application.trainer/create-save!` (linhas 27–34) | Implementado na aplicação JVM para qualquer slot válido. |
| Alternância | `trainer/switch-save!` e `bridge-tx/activate-save!` (linhas 50–56 e 138–159) | Implementado; valida existência, fence esperado e retorna novo fence. |
| Listagem | `bridge-tx/list-saves` (linhas 126–136), `/v1/saves` | Parcial; a lista é limitada a `model/default-slots` (`a`, `b`). |
| Cliente/HTTP | `scripts/lib/pokemon-jvm-client.cjs`, `/v1/saves`, `/v1/saves/activate` | Implementado para listar/ativar os slots existentes. |
| WhatsApp | `src/zapbot/pokemon_http.cljs` | Parcial; há comandos `save`/`saves`, mas a seleção aceita apenas `a`/`b`. |

`create-trainer!` cria os slots `a` e `b` e ativa `a` (`trainer.clj:15–25`). A
função `create-save!` aceita identificadores arbitrários validados pelo domínio,
mas não existe hoje um endpoint/cliente/comando público para chamá-la. O modelo
já impede que uma operação de escrita use implicitamente outro save: os
writers exigem `save-id` e `fence-generation` explícitos.

Os testes JVM em
`modules/pokemon/tests/pokemon/tests/trainer_saves_test.clj` cobrem criação de
A/B, dados independentes, sequência A→B→A, fence/retry, concorrência,
reabertura do Datomic e isolamento entre jogadores/chats. Eles não cobrem
criação/renomeação pública de slots nem importação V1.

## Estado legado Cassandra

O código histórico está presente, mas não está ligado ao gameplay atual.
`pokemon-service/runtime/save-slot-store.cjs` declara explicitamente que é um
store de snapshots imutáveis, sem integração com writers, HTTP ou comando de
ativação. Ele usa somente os módulos Cassandra:

- `pokemon-save-slots`;
- `pokemon-save-slot-index`;
- `pokemon-save-global-receipts`.

O registro está em `estado_particionado`, particionado por
`hash([chat, player]):slot-id`. `importValidated` aceita apenas
`legacy_estado_particionado`/versão `1` ou `legacy_estado`/versão `2`, valida
conteúdo e tamanho, faz `INSERT ... IF NOT EXISTS`, confirma por leitura
serializada, mantém recibos globais monotônicos e publica o índice por CAS.
Conflitos de conteúdo, recibo ou índice são erros; não há sobrescrita silenciosa.

`pokemon-service/src/pokemon_service/save_slots.cljs` seleciona a origem por
`cid`/`pid` autenticados e versão, lê o grupo atual e chama `importValidated`.
As funções `listar`/`metadados` são internas; `ativar-save!` delega a uma
ativação jogável protegida por flag e não há rota HTTP nem comando WhatsApp
habilitado por padrão. O próprio módulo documenta que os snapshots não alteram
o treinador ativo.

## Inventário de estado e decisão de escopo

O inventário em `docs/pokemon-state-inventory.md` e
`docs/m5-1-recovery-prep.md` classifica as fontes Cassandra assim:

| Fonte legada | Conteúdo | Destino de slot | Decisão A6 |
| --- | --- | --- | --- |
| `treinador` / `estado` | Pokémon, PC, time, ativo, XP, liga, ginásio, Professor e perfil | Slot do jogador; requer mapeamento explícito | Importar somente após dump offline e validação de versão. |
| `estado_particionado` | Snapshots V1/V2 por jogador | Slot histórico novo | Fonte primária da recuperação; nunca escolher automaticamente entre versões. |
| `loja` | moedas, inventário, armazenamento, quests/eventos | Majoritariamente slot; revisar campos globais | Migrar apenas campos comprovadamente por save. |
| `pokemon-descobertas` | Descobertas/Pokédex compartilhadas | Possivelmente jogador/chat, não necessariamente slot | Separar dados globais de progresso por slot antes de importar. |
| `pokedex-cache` | Cache de consulta | Derivável/cache | Não usar como fonte canônica sem validação. |
| `ginasios` | Estado de ginásios e participantes | Parte por save, parte global | Mapear referências; ranking compartilhado não deve ser copiado para slot. |
| `ginasios-estatisticas` | Histórico/ranking | Jogador/chat e histórico global conforme legado | Exige regra de associação; não assumir isolamento por slot. |
| `raids` / `raides-agendas` | Agendas, participantes e recompensas | Agenda/chat e operações de jogador | Batalhas pendentes precisam ser bloqueadas ou resolvidas antes da troca. |
| `pokemon-batalhas-ativas` | Estado transitório de batalha | Transitório por jogador | Não importar como progresso; bloquear troca durante batalha. |
| `pokemon-cacadas-ativas` | Caçada em andamento | Transitório | Não importar; encerrar/invalidar explicitamente. |
| propostas/recibos de troca | Negociações e efeitos | Associados a saves | Não copiar propostas pendentes sem contrato de compatibilidade. |
| `rank`, `bugs`, admins, lembretes, enquetes etc. | Estado do bot/chat | Compartilhado, fora do save | Permanecem sob o dono do ZapBot; não entram no slot. |

Não há evidência para assumir que a conta V1 contém 119 Pokémon, nem para
resolver automaticamente campos desconhecidos. A importação deve preservar
IDs/atributos que tenham mapeamento e rejeitar registros incompatíveis.

## Lacunas comprovadas

1. **Slots dinâmicos incompletos.** O domínio permite IDs adicionais, mas a
   listagem e o comando atual só expõem `a`/`b`.
2. **Criação pública ausente.** Não há `!pk novo save` nem endpoint autenticado
   para criar um slot vazio.
3. **Renomeação ausente.** Não existe atributo/contrato de nome de slot ou
   operação de rename.
4. **Contexto de slot não é explícito na mensagem.** A maioria dos comandos usa
   o ponteiro ativo; a troca de slot precisa ser uma operação própria, com
   fence e bloqueio de operações incompatíveis (batalha, negociação, raid).
5. **Proveniência de recuperação ausente no schema Datomic.** O save atual não
   registra origem V1/V2, hash do snapshot, ID original, versão do importador
   ou recibo de importação. Esses dados são necessários para replay seguro.
6. **Importador V1 não grava Datomic.** O importer existente escreve os módulos
   Cassandra de snapshots; não há pipeline offline `Cassandra dump → Datomic`.
7. **Estado fora do save.** Ginásios, raids, histórico, missões, amizade,
   títulos e propostas possuem campos que precisam ser classificados como
   slot-scoped ou compartilhados antes de uma recuperação. A presença de
   `save-id` em algumas entidades não prova isolamento de todos os efeitos.
8. **Ativação jogável legada desabilitada.** O módulo `playable-save-activation`
   é interno e protegido por flags; não é uma alternativa ao contrato Datomic.

## Riscos de consistência

- Uma operação iniciada em A não pode continuar depois da troca para B. Todo
  request de escrita deve carregar `save-id` e fence capturados no início e o
  backend deve rejeitar fence/slot obsoletos.
- A→B concorrente com recompensa, batalha, item ou troca exige CAS no ponteiro e
  no save; retries precisam reutilizar o mesmo `operationId` e hash.
- Batalhas, raids e negociações pendentes podem manter referências a Pokémon e
  saves. A política precisa ser bloquear a troca ou finalizar o estado antes da
  ativação.
- Dados globais (recibos de aventura, rankings, agendas) não podem ser
  duplicados em cada slot. A importação deve manter sua autoridade original.
- IDs conflitantes, Pokémon duplicados, itens desconhecidos, campos EDN
  inválidos e tamanhos excessivos devem causar falha explícita, não descarte
  parcial.
- Cassandra possui linhas distribuídas; não há transação multi-tabela para
  formar um snapshot histórico. O dump deve ser offline, versionado e
  acompanhado de checksums.

## Plano dividido

### A6.2 — Implementação local, sem dados reais

1. Adicionar metadados de slot/proveniência no Datomic por migration versionada,
   sem alterar migrations 001–024.
2. Expor criar, listar, selecionar e (se comprovado no legado) renomear slots;
   remover a lista fixa A/B da camada de aplicação.
3. Propagar contexto explícito (`save-id`, fence, chat e jogador) pelo bridge,
   cliente Node e comandos ClojureScript.
4. Bloquear troca durante batalha/raid/trade incompatível e validar retries.
5. Cobrir A→B→A, três slots, concorrência, reabertura e isolamento de chat.
6. Implementar um importador offline de fixture EDN/JSON, com dry-run,
   validação de schema, provenance e recibo idempotente. Nenhum acesso ao
   Cassandra real nesta fase.

### A6.3 — Recuperação controlada

1. Obter autorização e janela de leitura; manter Cassandra somente leitura.
2. Gerar dump offline das partições V1, inventário e recibos, com SHA-256,
   versão dos formatos e identificação exata `chat|player`.
3. Executar o dry-run em área isolada, resolver ambiguidades manualmente e
   comparar contagens/IDs/atributos antes de qualquer escrita Datomic.
4. Importar para slots novos em Datomic descartável, verificando recibos e
   reexecução idempotente.
5. Somente após aprovação, planejar a introdução dos slots recuperados no
   ambiente operacional. Não há autorização para realizar essa etapa neste
   diagnóstico.

## Critérios de aceite da A6.1

- O Core atual é capaz de alternar A/B com fence e persistência após reinício.
- O inventário Cassandra e a separação slot/global/transitório estão
  documentados.
- As lacunas de criação/listagem/rename/importação estão explícitas.
- Nenhuma fonte real foi acessada e nenhuma alteração de produção foi feita.

A6.2 e A6.3 permanecem pendentes; não há declaração de recuperação V1 nem de
suporte a múltiplos slots públicos até que esses testes e a importação isolada
sejam implementados.
