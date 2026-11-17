-- Additive schema for per-gym canonical occupancy and statistics records.
-- Apply only in the target keyspace with a schema administrator. This file
-- does not modify or remove legacy state.
CREATE TABLE IF NOT EXISTS zapbot.canonical_gym_records (
  chat_id text,
  record_id text,
  generation bigint,
  protocol_version int,
  state_hash text,
  state_json text,
  deleted boolean,
  updated_at timestamp,
  writer_id text,
  migration_id text,
  operation_receipts set<text>,
  PRIMARY KEY ((chat_id), record_id)
);

-- Additive upgrade for installations that created the first table revision.
ALTER TABLE zapbot.canonical_gym_records ADD IF NOT EXISTS operation_receipts set<text>;

CREATE TABLE IF NOT EXISTS zapbot.canonical_gym_groups (
  bucket int,
  chat_id text,
  PRIMARY KEY ((bucket), chat_id)
);

CREATE TABLE IF NOT EXISTS zapbot.canonical_gym_control (
  capability text PRIMARY KEY,
  protocol_version int,
  state text,
  migration_id text,
  updated_at timestamp
);

CREATE TABLE IF NOT EXISTS zapbot.canonical_gym_backups (
  chat_id text,
  module text,
  backup_id text,
  source_hash text,
  source_json text,
  created_at timestamp,
  PRIMARY KEY ((chat_id, module), backup_id)
);

-- Create distinct roles outside this file, then grant only these permissions:
-- GRANT SELECT ON TABLE zapbot.canonical_gym_control TO pokemon_gym_runtime;
-- GRANT SELECT ON TABLE zapbot.canonical_gym_records TO pokemon_gym_runtime;
-- GRANT MODIFY ON TABLE zapbot.canonical_gym_records TO pokemon_gym_runtime;
-- GRANT SELECT ON TABLE zapbot.canonical_gym_groups TO pokemon_gym_runtime;
-- GRANT MODIFY ON TABLE zapbot.canonical_gym_groups TO pokemon_gym_runtime;
-- GRANT SELECT ON TABLE zapbot.canonical_gym_backups TO pokemon_gym_runtime;
-- The migration role receives SELECT on estado_particionado, SELECT and MODIFY
-- on backups/control/records/directory, and no role grants to the old writer.
-- The legacy application role receives no permissions on canonical_gym_*.
