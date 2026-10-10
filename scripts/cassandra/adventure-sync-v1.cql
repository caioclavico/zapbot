-- REVISÃO/INSTALAÇÃO MANUAL FUTURA. Não executado pelo runtime ou deploy.
-- Substitua {{KEYSPACE}} por um keyspace EXISTENTE validado antes da execução.
-- Fase 2: apenas domínio. Projeções ovos/incubadoras são opcionais no modelo;
-- permanecem no state. Tabelas de autenticação/outbox ficam para fases futuras.
CREATE TABLE IF NOT EXISTS {{KEYSPACE}}.adventure_account_binding (
  chat_id text, player_id text, account_id uuid, created_at timestamp,
  PRIMARY KEY ((chat_id), player_id)
);
CREATE TABLE IF NOT EXISTS {{KEYSPACE}}.adventure_account_state (
  account_id uuid PRIMARY KEY,
  version bigint, schema_version int, updated_at timestamp, payload text
);
CREATE TABLE IF NOT EXISTS {{KEYSPACE}}.adventure_sync_receipts (
  account_id uuid, day_utc date, batch_id uuid,
  fingerprint text, state_version bigint, payload text,
  PRIMARY KEY ((account_id, day_utc), batch_id)
);
CREATE TABLE IF NOT EXISTS {{KEYSPACE}}.adventure_hatches_by_month (
  account_id uuid, month_utc text, seq bigint,
  hatch_id uuid, egg_id uuid, pokemon_id uuid, payload text,
  PRIMARY KEY ((account_id, month_utc), seq)
) WITH CLUSTERING ORDER BY (seq DESC);
