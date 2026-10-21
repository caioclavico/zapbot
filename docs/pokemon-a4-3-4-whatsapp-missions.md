# A4.3.4 — missões no WhatsApp

Restaura a superfície de missões da `master` sobre os contratos canônicos da bridge JVM. O Node identifica a mensagem e apresenta DTOs; não calcula progresso, sorteia recompensas, movimenta inventário ou escreve no Datomic.

## Comandos preservados

| Comando | Aliases | Ação |
| --- | --- | --- |
| `!missoes` | `!missões`, `!pk mis` | diárias e semanais, com período, objetivo, progresso, estado e recompensa |
| `!missoes diarias` | `!pk missoes diarias` | somente diárias |
| `!missoes semanais` | `!pk missoes semanais` | somente semanais |
| `!missoes resgatar` | `!missoes diarias resgatar` | resgata diárias prontas |
| `!missoes semanais resgatar` | — | resgata semanais prontas |
| `!presente @amigo` | `!presentes`, `!pk pre` | consome cartão e envia o presente canônico |
| `!mochila` | `!pk mochila` | mostra inventário e recompensas pendentes |
| `!mochila resgatar` | — | recolhe pendências que couberem |

O texto preserva os objetivos e instruções da master. O histórico de Novo Recomeço aparece como encerrado e não oferece resgate. Não havia cartão visual de missão reutilizável no adaptador JVM; a apresentação textual segue o mesmo formato dos demais DTOs da bridge e não cria mídia sintética.

## Chamadas ao Core

Consultas usam `GET /v1/missions` (e o cliente também expõe `GET /v1/missions/rewards`). Escritas usam `POST /v1/missions/claim/daily`, `POST /v1/missions/claim/weekly`, `POST /v1/gifts/send` e `POST /v1/missions/pending/collect`. A recuperação consulta `GET /v1/operations/:operationId`.

O `operationId` é derivado do ID estável da mensagem WhatsApp, mais a ação e o período/destinatário. Em timeout, o cliente consulta o recibo com o mesmo ID; se o recibo não existir, reenvia o mesmo corpo. A renderização vem depois da mutação e não repete a escrita se o envio da mensagem falhar. Chat, jogador, save ativo e fence são sempre obtidos do contexto confiável e da JVM.

## Auditoria da migration 019

`pokemon.migration.receipt-a433` é uma verificação somente-leitura:

```sh
clojure -M -m pokemon.migration.receipt-a433 <storage-dir> <system> <db-name>
```

Ela abre apenas um banco já existente, lista `operation-receipt/id`, informa a unicidade atual, a presença da migration e IDs/EIDs duplicados. Não chama `ensure-db!`, `schema/install!` ou `transact`.

Em uma base regular que já aplicou a migration 001, `:db.unique/identity` já impede IDs duplicados. A 019 troca apenas a restrição para `:db.unique/value`; não altera recibos. A transação de schema é atômica: se a auditoria encontrar duplicações de uma base não convencional, a 019 não deve ser aplicada; a tentativa falharia sem marcar a migration e sem apagar dados. Os IDs e EIDs do relatório devem ser reconciliados em procedimento separado antes de nova tentativa. Esta entrega não acessa VMs nem executa a migration fora dos bancos temporários de teste.

## Verificação

- Cliente e apresentação Node: contratos, aliases, estados, pendências e recuperação de timeout com o mesmo recibo.
- Core A4.3.3: 21 testes e 208 asserções, sem falhas; inclui auditoria somente-leitura da migration 019.
- Compilação ClojureScript do alvo de testes concluída.

As seis falhas históricas de regressão permanecem separadas: três no ginásio JVM e três na apresentação Node (Praia Azul, coleção/time e expectativa antiga de `batalhar`).
