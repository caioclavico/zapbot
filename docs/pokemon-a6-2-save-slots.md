# A6.2 — Slots dinâmicos e contrato de recuperação V1

Esta etapa amplia o modelo Datomic existente sem alterar o significado dos
slots `a` e `b`. Os novos slots usam o mesmo `:save/key` e continuam sujeitos
ao ponteiro ativo e ao `:player/fence-generation`.

## Contrato implementado

- `GET /v1/saves?chatId=&playerId=` lista todos os saves em ordem determinística
  por `save-id`.
- `POST /v1/saves/create` cria um save vazio sem mudar o ativo. Repetir uma
  criação já persistida retorna o save existente, sem duplicar entidade.
- `POST /v1/saves/rename` altera o nome apresentado, exigindo fence esperado e
  `operationId` no bridge.
- `POST /v1/saves/activate` mantém a operação existente e aceita qualquer
  identificador de slot válido, preservando `!pk ativar a|b`.

O bridge anuncia `save-create` e `save-rename` no `/health`. O cliente Node
reutiliza o mesmo Bearer e o mesmo mecanismo de retry (`mutateOnce`), sem gerar
novo `operationId`.

## Comandos

- `!pk saves`
- `!pk save <número ou id>`
- `!pk novo save`
- `!pk renomear save <número ou id> <nome>`

O número é resolvido contra uma listagem ordenada na mesma operação. A resposta
sempre exibe também o identificador persistente. `!pk save` sem argumento
continua mostrando o save ativo, e `!pk ativar a|b` permanece compatível.

## Metadados de recuperação

A migration `025-save-slot-metadata` adiciona atributos opcionais de nome,
origem, jogador de origem, versão legada, versão do importador, identificador
de importação, estado, instante e IDs de registros originais. Os saves A/B
existentes permanecem sem esses atributos.

O importador offline `pokemon.migration.import` passa a registrar esses dados
quando recebe uma IR de recuperação. A origem ainda precisa ser um dump offline
validado; nenhuma conexão Cassandra foi adicionada.

## Validação local

- `pokemon.tests.trainer-saves-test`: 9 testes, 54 asserções, zero falhas.
- `pokemon.tests.migration-m51-test`: 9 testes, 45 asserções, zero falhas.
- `npx shadow-cljs compile test`: build concluído.
- `node --test test/pokemon-jvm-present.test.cjs`: 11 testes, zero falhas.
- `node --test` dos testes que abrem loopback ficou bloqueado pelo sandbox com
  `listen EPERM`; esses testes devem ser executados no Docker/Mac.

Não houve acesso a Cassandra, VM, volumes reais, deploy ou migration em
produção. A importação V1 real e os testes Node→HTTP com sockets permanecem
pendentes da A6.3/ambiente externo.
