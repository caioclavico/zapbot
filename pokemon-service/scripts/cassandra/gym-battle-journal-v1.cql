-- Additive journal and discoverability index for recoverable gym-battle effects.
-- The journal is intentionally not enabled by this DDL alone. Every target
-- adapter must atomically write its effect receipt with its target mutation.
CREATE TABLE IF NOT EXISTS zapbot.pokemon_gym_battle_journal (
  chat_id text,
  battle_id text,
  generation bigint,
  state_hash text,
  state_json text,
  updated_at timestamp,
  writer_id text,
  PRIMARY KEY ((chat_id), battle_id)
);

CREATE TABLE IF NOT EXISTS zapbot.pokemon_gym_battle_index (
  bucket int,
  chat_id text,
  battle_id text,
  plan_hash text,
  state text,
  updated_at timestamp,
  PRIMARY KEY ((bucket), chat_id, battle_id)
);

-- These grants are for a future dedicated runtime role, after writer coverage
-- has been certified. Do not grant them to legacy instances.
-- GRANT SELECT, MODIFY ON TABLE zapbot.pokemon_gym_battle_journal TO pokemon_battle_runtime;
-- GRANT SELECT, MODIFY ON TABLE zapbot.pokemon_gym_battle_index TO pokemon_battle_runtime;
