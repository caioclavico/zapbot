# Isolamento do Datomic A6

Este texto descreve o que o isolamento por container cobre e o que continua fora dele. Ele não autoriza deploy.

## O que impede

- Um processo sem o volume `odisseu-a6-cutover-datomic` montado não escreve em `/data/datomic-local`.
- A política recusa mais de um container com o volume gravável, container privilegiado, `CapAdd` com `SYS_ADMIN` e `CapEff` com o bit 21.
- O entrypoint da JVM recusa subir sem o mount, sem conseguir ler `/proc/self/mountinfo`, ou com `CAP_SYS_ADMIN`.
- Os 13 clientes externos passam por `assert-external!` antes de `d/client`. Identidade `:live` sem prova revalidada, ou `:unknown`, não abre o cliente.
- `IOException`, mountinfo ausente ou malformado no caminho `/data/datomic-local` é `:unknown`, não autorização.
- Um mapa com `mountsObserved` ou `quiescenceProven` não prova quiescência. A prova exige uma coleta feita por `docker inspect` e leitura dos marcadores e do `ps` no momento.
- A prova em arquivo só é consultada no caminho fixo `/run/odisseu-exclusive/proof` e só vale se o boot id, o id do container e o mountinfo relidos agora coincidem. Hash de arquivo não autentica.

## O que não impede

- Administrador do host, quem tem `sudo docker` e quem acessa o socket do Docker podem montar `/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data` ou entrar no container com `docker exec`.
- Esses atores ficam fora da garantia técnica do container. Por isso a autorização operacional de produção continua bloqueada.
- A janela de manutenção só impede operações concorrentes autorizadas depois que a coleta observa a JVM quiescente, o Node drenando e nenhum dos 13 clientes no `ps`. Uma lista declarada pelo chamador não conta.

## Quem alcança o volume

Na VM, o usuário de operação usa `sudo -n docker`. O socket não é concedido diretamente a esse usuário. Qualquer outro administrador com privilégio de host continua capaz de montar o volume. Não há controle neste repositório que retire esse acesso.

## Ferramentas que chamam `d/transact`

Manutenção: backup frio, drain, bag flags e quiescência. Migração: import, schema 035, schema 028, ensaios 032, 033 e fase 7. Recuperação: gym HP e flat9. Desenvolvimento: `datomic_local`. Todas passam pelo cliente guardado, exceto a ponte, que usa `runtime-client` e só sobe depois do entrypoint.
