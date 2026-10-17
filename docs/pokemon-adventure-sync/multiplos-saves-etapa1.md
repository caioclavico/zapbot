# Múltiplos saves — Etapa 1: armazenamento inativo

## Estado entregue

Esta etapa acrescenta importação imutável de snapshots históricos para slots inativos e consulta de metadados. Não há comando WhatsApp, rota HTTP, ativação, alternância, restauração ou escrita no módulo `treinador`. O gate de confirmação existente permanece fechado.

A importação é uma função interna da biblioteca de domínio (`saveSlotsImport`); ela recebe grupo, jogador, slot e versão, busca a origem nas tabelas legadas usando a identidade exata e retorna somente metadados. `saveSlotsList` e `saveSlotMetadata` também são APIs internas de leitura. Nenhuma delas foi ligada ao dispatcher HTTP.

## Schema Cassandra

Não é necessária DDL. O serviço reutiliza o schema particionado já existente:

```sql
CREATE TABLE <keyspace>.estado_particionado (
  modulo text,
  particao text,
  valor text,
  PRIMARY KEY ((modulo, particao))
);
```

Os registros novos ficam em módulos aditivos da mesma tabela:

| `modulo` | `particao` | Conteúdo |
| --- | --- | --- |
| `pokemon-save-slots` | `SHA256([grupo,jogador]):<slot-id>` | Envelope imutável do snapshot e metadados de origem/integridade. O grupo e jogador não ficam em texto claro na chave. |
| `pokemon-save-slot-index` | `SHA256([grupo,jogador])` | Índice CAS de slot ID, hash e data, para listagem sem varredura ou `ALLOW FILTERING`. |
| `pokemon-save-global-receipts` | `SHA256([grupo,jogador])` | União monotônica dos recibos globais de entrega e hash de integridade. |

Todos os acessos são por chave completa preparada. Leituras de confirmação usam `SERIAL`; inserções imutáveis usam `IF NOT EXISTS` com `QUORUM/SERIAL`; índice e ledger usam CAS com `QUORUM/SERIAL`. O executor ClojureScript aceita somente SELECT pontual, INSERT condicional e UPDATE condicional nesses três módulos. Não há DDL, DELETE, TRUNCATE, batch ou escrita no legado.

## Identidade, integridade e recuperação de falhas

- Chaves são nomes curtos (`[a-z0-9_-]`, até 64 caracteres) e não podem ser sobrescritas com conteúdo ou origem diferentes.
- Importações repetidas com o mesmo grupo, jogador, ID, origem e conteúdo são idempotentes; preservam o `createdAt` original.
- O snapshot é validado com as regras atuais de treinador e com a projeção de normalização de XP em uma cópia. O valor persistido permanece o JSON original. Estados operacionais pendentes, incompatíveis ou desconhecidos continuam bloqueando a importação.
- O envelope guarda `source`, `sourceVersion`, `sourceHash`, `contentHash`, data ISO-8601, formato `json-account-v1`, total de Pokémon e referências hash aos recibos globais. Cada leitura de metadados verifica o hash do envelope, o índice e os recibos referenciados.
- Ordem de gravação: mesclar recibos monotônicos por CAS, inserir snapshot por `IF NOT EXISTS`, verificar o ledger por leitura SERIAL e publicar no índice por CAS. Uma falha antes do índice pode deixar snapshot órfão, nunca listado; repetir a mesma importação confirma o snapshot existente e termina o índice. Um timeout não é considerado sucesso sem releitura SERIAL.
- Se a atualização monotônica do ledger confirmar e a inserção do slot falhar, os IDs de recibo permanecem conservadoramente no ledger. Isso não concede recompensa; pode apenas impedir uma reentrega futura. A falha é segura para repetição e não altera o treinador ativo.
- Falhas de leitura, inconsistência de hash, conflito de recibos e índices incompletos falham fechados; os metadados não são apresentados como válidos.

## Propriedade dos dados

### Estado específico do slot

O snapshot preserva integralmente o objeto histórico de treinador, com exceção do campo global `adventure-entregas`, tratado abaixo. Isso inclui equipe, PC, enfermaria, shiny, XP original, atributos, golpes, itens, moedas, nível, Pokédex, referências e demais campos. A validação pode projetar conversões em memória, mas não as grava nem muda o JSON arquivado.

Ocupações e estado efetivo de ginásio não são clonados nem modificados por esta etapa. Configurações por índices que já estejam dentro do registro continuam no snapshot, sujeitas às validações atuais. Uma futura ativação precisa coordenar referências externas de ginásios antes de trocar qualquer save.

### Estado global da conta

- `adventure-entregas` é removido do payload do slot e unido no ledger `pokemon-save-global-receipts`. IDs e Pokémon são unidos por CAS; reutilizar um ID com outro Pokémon bloqueia a importação. O slot guarda somente hashes de referência, sem duplicar o recibo ou seus identificadores.
- O journal, locks, backups e recibos da própria restauração permanecem em `pokemon-restauracao-*`; não são copiados para um save.
- `adventure_sync_receipts` e o estado Adventure em suas tabelas próprias não são lidos, copiados ou alterados por esta API. Sua associação futura a slots ainda exige desenho e revisão; não se deve inferir que já alternam junto com o treinador.
- Estado externo de ginásios e quaisquer recompensas registradas fora do objeto treinador continuam globais e fora do slot. A ativação futura deve reconciliar esses registros sob protocolo coordenado.

O ledger novo ainda não é declarado autoritativo para todos os jogadores: ele contém a união dos recibos vistos nas importações. Antes de qualquer ativação futura, será necessário semear/validar nele todos os recibos do save ativo e comprovar que nenhum writer antigo bypassa a proteção.

## Integração posterior

Uma futura etapa de ativação precisa, no mínimo:

1. Adquirir exclusão durável por grupo/jogador e drenar filas/callbacks de treinador.
2. Participar de um protocolo coordenado com o writer de ginásios e demais estados externos; manter bloqueado se não houver CAS/journal/reconciliação que cubram todos.
3. Verificar hashes do slot, validar novamente referências e versão, sem recalcular uma versão diferente da prévia.
4. Criar e confirmar backup imutável do save ativo e dos estados externos afetados antes da primeira mudança.
5. Reconciliar recibos globais monotônicos antes de trocar a conta, sem restaurar os recibos antigos como estado regressivo.
6. Usar journal durável e estados intermediários recuperáveis para cada partição; nunca alegar atomicidade Cassandra entre linhas.
7. Manter restauração/alternância desabilitada até testes de falha, reinício, concorrência, CAS incerto e rollback cobrirem todos os writers.

## Testes e limites

Os testes unitários Node simulam LWT/CAS, respostas perdidas, concorrência, reinício (nova instância sobre as mesmas linhas), falhas parciais, isolamento, integridade e preservação de dados. Testes ClojureScript verificam seleção exata do save legado V1/V2 por grupo e jogador. Eles não substituem integração com Cassandra real; nenhum dado, schema ou serviço de produção foi acessado ou alterado.

### Validação Cassandra 5 descartável (Etapa 1.1)

O harness `npm run test:save-slots:cassandra` usa a imagem `cassandra:5.0.9` em uma rede Docker `--internal`, sem portas publicadas. Um runner separado conecta pelo DNS privado do container e exige que `system.local.release_version` seja exatamente `5.0.9`. O Cassandra recebe limite de 2 GiB e heap de 1 GiB. Só cria o keyspace descartável `save_slots_validation` e `estado_particionado`; dados e identidades são sintéticos. Um reinício real do container ocorre entre as fases seed e verify-restart. O `trap` remove o container com seu volume anônimo e a rede privada, inclusive em falha.

Execução local:

```sh
cd pokemon-service
npm ci
npm run test:save-slots:cassandra
```

Resultado validado nesta revisão: **Cassandra 5.0.9 real aprovado**. As fases reportaram `save_slot_cassandra_seed_passed` (slots V1/V2, concorrência LWT, interrupção antes do índice, preservação do treinador/ginásio, isolamento e ledger) e `save_slot_cassandra_restart_passed` (4 slots e 2 recibos íntegros após restart). A listagem devolveu metadados sem payload da conta. A primeira tentativa, com heap padrão de aproximadamente 4 GiB, foi interrompida pelo limite de memória do Docker Desktop; não chegou a executar CQL nem criar keyspace e seus recursos descartáveis foram removidos. A repetição com limites explícitos passou.

Além da integração, a validação local final executou `npm test` (**114/114**), `npm run test:domain` (**310 testes, 1.901 assertions, zero falhas/erros**), `npm run build` (release concluído) e `git diff --check` (aprovado).

Ainda não há recuperação de snapshots órfãos sem repetir a importação original, exclusão automática, ativação, comandos WhatsApp nem coordenação transacional com ginásios/Adventure. Esses itens permanecem bloqueadores explícitos para alternar saves.
