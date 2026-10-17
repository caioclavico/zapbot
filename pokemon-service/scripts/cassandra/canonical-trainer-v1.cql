-- Additive schema only. Run with a schema administrator against the target
-- keyspace after creating the dedicated roles described in the migration runbook.
-- No legacy table is changed or removed by this file.
CREATE TABLE IF NOT EXISTS zapbot.canonical_trainer_accounts (
  chat_id text,
  player_id text,
  generation bigint,
  schema_version int,
  protocol_version int,
  state_hash text,
  state_json text,
  operation_receipts map<text, text>,
  fence_generation bigint,
  active_save_id text,
  transition_owner text,
  updated_at timestamp,
  writer_id text,
  migration_id text,
  PRIMARY KEY ((chat_id), player_id)
);

-- Safe on existing installations. Receipts bind a battle effect identity to
-- its immutable intent hash in the *same* LWT that advances account state.
ALTER TABLE zapbot.canonical_trainer_accounts
  ADD IF NOT EXISTS operation_receipts map<text, text>;

-- Protocol 2. Existing rows are deliberately NOT initialized automatically.
-- A supervised offline cutover must certify writers and set all three fields
-- before publishing protocol_version=2; incomplete records fail closed.
ALTER TABLE zapbot.canonical_trainer_accounts ADD IF NOT EXISTS fence_generation bigint;
ALTER TABLE zapbot.canonical_trainer_accounts ADD IF NOT EXISTS active_save_id text;
ALTER TABLE zapbot.canonical_trainer_accounts ADD IF NOT EXISTS transition_owner text;

-- Lateral-write intents (raids/loja/…) share this row's LWT with fence/save.
-- Values are JSON; only non-reconciled statuses are retained. Writers are not
-- certified by the presence of this column alone.
ALTER TABLE zapbot.canonical_trainer_accounts
  ADD IF NOT EXISTS lateral_intents map<text, text>;

CREATE TABLE IF NOT EXISTS zapbot.canonical_trainer_groups (
  bucket int,
  chat_id text,
  PRIMARY KEY ((bucket), chat_id)
);

CREATE TABLE IF NOT EXISTS zapbot.canonical_trainer_control (
  capability text PRIMARY KEY,
  protocol_version int,
  state text,
  migration_id text,
  updated_at timestamp
);

CREATE TABLE IF NOT EXISTS zapbot.canonical_trainer_backups (
  chat_id text,
  player_id text,
  backup_id text,
  source_hash text,
  source_json text,
  created_at timestamp,
  PRIMARY KEY ((chat_id, player_id), backup_id)
);

-- Example permissions (replace role names as part of controlled provisioning):
-- GRANT SELECT ON TABLE zapbot.canonical_trainer_control TO pokemon_runtime;
-- GRANT SELECT, MODIFY ON TABLE zapbot.canonical_trainer_accounts TO pokemon_runtime;
-- GRANT SELECT, MODIFY ON TABLE zapbot.canonical_trainer_groups TO pokemon_runtime;
-- GRANT SELECT ON TABLE zapbot.canonical_trainer_backups TO pokemon_runtime;
-- GRANT MODIFY ON TABLE zapbot.canonical_trainer_backups TO pokemon_migrator;
-- GRANT MODIFY ON TABLE zapbot.canonical_trainer_control TO pokemon_migrator;
-- GRANT SELECT ON TABLE zapbot.estado_particionado TO pokemon_migrator;
-- The legacy application role MUST NOT receive any permission on canonical tables.
-- The legacy role's MODIFY permission on estado_particionado must be revoked
-- before publishing the active capability row and starting canonical writers.
