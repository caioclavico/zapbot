# Diagnóstico e migração supervisionada do Adventure Sync

O runtime valida quatro tabelas antes de aceitar `ADVENTURE_SYNC_ENABLED=true`:
`adventure_account_binding`, `adventure_account_state`,
`adventure_sync_receipts` e `adventure_hatches_by_month`. A existência e a
compatibilidade no Cassandra de produção ainda não foram confirmadas.

## Diagnóstico somente leitura

Na VM que alcança `144.22.248.79:9042`, execute sem imprimir credenciais:

```sh
cqlsh 144.22.248.79 9042 -k zapbot \
  -f /caminho/adventure-schema-diagnostic.cql
```

Registre a saída das colunas, tipos e chaves primárias. Não execute `DESCRIBE`
com saída de credenciais nem qualquer comando de escrita.

## Migração aprovada

Depois de revisar o diagnóstico, faça um backup lógico das quatro tabelas (se
existirem) e confirme espaço disponível. Em um keyspace de teste descartável,
substitua `{{KEYSPACE}}` por um keyspace isolado e execute
`scripts/cassandra/adventure-sync-v1.cql`. O script contém apenas
`CREATE TABLE IF NOT EXISTS`, sem `DROP`, `TRUNCATE` ou alteração de tabelas
existentes.

Em produção, a execução deve ser supervisionada e repetida somente após
confirmar o resultado de cada instrução. O runtime não migra schema
automaticamente. Rollback do container não desfaz DDL; em caso de erro,
mantenha as tabelas e desative a flag até revisão do schema.
