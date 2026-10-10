-- Somente leitura. Execute contra Cassandra com cqlsh; não contém credenciais.
SELECT keyspace_name, table_name, column_name, kind, position, type
FROM system_schema.columns
WHERE keyspace_name = 'zapbot'
  AND table_name IN ('adventure_account_binding',
                     'adventure_account_state',
                     'adventure_sync_receipts',
                     'adventure_hatches_by_month');

SELECT keyspace_name, table_name, clustering_order, column_name, type
FROM system_schema.columns
WHERE keyspace_name = 'zapbot'
  AND table_name IN ('adventure_account_binding',
                     'adventure_account_state',
                     'adventure_sync_receipts',
                     'adventure_hatches_by_month')
  AND kind IN ('partition_key', 'clustering')
ORDER BY table_name, kind, position;
