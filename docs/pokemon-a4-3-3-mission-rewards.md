# A4.3.3 — presentes, recompensas e resgate

Esta entrega implementa a subetapa de recompensas do plano A4.3. O Core é a
fonte canônica: cada operação grava o resultado completo no recibo Datomic e
uma repetição devolve esse mesmo resultado, sem novo sorteio ou crédito.

## Regras preservadas da master

- Um presente consome um `cartao-presente`, impede autoenvio e credita ao save
  ativo do destinatário. O destinatário pode ainda não existir.
- O presente sorteia `ultra-bola` x1 em 0–9, `grande-bola` x2 em 10–34 e
  `pokebola` x3 nos demais valores. Somente o remetente progride as missões
  de presentes quando a transferência é confirmada.
- O resgate diário agrega todas as missões prontas, preserva multiplicador por
  nível congelado e aplica os sorteios originais de bolas, reviver e item de
  evolução. O resgate semanal agrega moedas, bolas, catalisadores e um item
  de evolução por operação não vazia.
- A lista de evolução da master tem onze itens, em ordem:
  `revestimento-metalico`, `escama-dragao`, `upgrade`, `protetor`,
  `pedra-rei`, `eletrizador`, `magmarizador`, `tecido-ceifador`,
  `escama-prisma`, `chicote-doce` e `sache-perfumado`.
- Mochila cheia direciona o excedente a recompensas pendentes; a coleta
  posterior só movimenta o que couber e preserva itens desconhecidos já
  pendentes.

O evento histórico Novo Recomeço permanece somente de leitura, encerrado, e
não participa de resgates.

## Persistência e concorrência

`operation-receipt/id` foi migrado de `:db.unique/identity` para
`:db.unique/value` em `019-operation-receipt-unique-value`. Assim, uma segunda
operação com o mesmo id não pode fazer upsert sobre o recibo de outro
treinador. O recibo guarda hash, proprietário, save e resultado canônico.

As transações validam revisão do save, fence do treinador e, em presentes,
fence/save ativo do destinatário. A criação técnica de destinatário ocorre em
duas fases seguras: primeiro garante identidades imutáveis; depois inicializa
campos ausentes apenas com CAS. Nenhum cartão ou prêmio é movimentado até a
transação econômica única. Uma interrupção nessa preparação pode deixar uma
conta vazia parcial, que a próxima tentativa de presente repara sem apagar
estado mutável; o onboarding aprovado não foi alterado.

## Contratos HTTP internos

Com autenticação bearer e identidade/fence explícitas:

- `POST /v1/missions/claim/daily`
- `POST /v1/missions/claim/weekly`
- `POST /v1/gifts/send`
- `POST /v1/missions/pending/collect`
- `GET /v1/missions` e `GET /v1/missions/rewards`
- `GET /v1/operations/:operationId` para reconciliação do resultado

As escritas aceitam somente os campos previstos e não aceitam recompensa,
sorteio, moeda ou horário fornecidos pelo cliente. Não há comando WhatsApp
nesta entrega; essa camada pertence à A4.3.4.

## Verificação

As suítes A4.3.1, A4.3.2 e A4.3.3 cobrem catálogo, histórico, isolamento por
treinador/save, reinício, colisões concorrentes, capacidade, pendências e HTTP.
A regressão JVM e a suíte Node foram comparadas com `afd2a90`: persistem apenas
as três falhas pré-existentes de ginásio e as três falhas pré-existentes de
apresentação Node.
