'use strict';

// Canonical per-gym records. Each occupancy/statistics row owns its monotonic
// generation; mutations use LWT on that same row. Legacy module snapshots are
// never read by this store after capability activation.
const {hash} = require('./restore-protocol.cjs');
const PROTOCOL_VERSION = 1;
const BUCKETS = 16;
const MODULES = new Set(['ginasios', 'ginasios-estatisticas']);
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const stableJson = value => JSON.stringify(value, (_key, item) => {
  if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
  return Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]));
});
const fail = code => Object.assign(new Error(code), {code});
const validId = value => typeof value === 'string' && value.length > 0 && value.length <= 200 &&
  !/[\u0000-\u001f\u007f]/.test(value);
const recordId = (module, key) => `${module}:${key}`;
const validOperationId = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{8,100}$/.test(value);

function receiptSet(value) {
  if (value instanceof Set) return value;
  if (Array.isArray(value)) return new Set(value);
  if (value && typeof value[Symbol.iterator] === 'function') return new Set(value);
  return new Set();
}

class CanonicalGymStore {
  constructor(execute, keyspace, {writerId = 'pokemon-service', now = () => new Date()} = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_canonical_gym_store');
    this.execute = execute;
    this.keyspace = keyspace;
    this.writerId = writerId;
    this.now = now;
    this.records = `${keyspace}.canonical_gym_records`;
    this.groups = `${keyspace}.canonical_gym_groups`;
    this.control = `${keyspace}.canonical_gym_control`;
  }

  bucket(chat) { return Number.parseInt(hash(chat).slice(0, 8), 16) % BUCKETS; }

  async ensureGroup(chat) {
    await this.execute(`INSERT INTO ${this.groups} (bucket, chat_id) VALUES (?, ?) IF NOT EXISTS`,
      [this.bucket(chat), chat], {consistency:'QUORUM', serialConsistency:'SERIAL'});
  }

  async capability(expected = 'active') {
    const record = row(await this.execute(`SELECT protocol_version, state FROM ${this.control} WHERE capability = ?`,
      ['gym-canonical'], {consistency:'SERIAL'}));
    if (!record) throw fail('canonical_gym_capability_missing');
    const version = Number(record.protocol_version);
    if (version !== PROTOCOL_VERSION) {
      throw Object.assign(fail('canonical_gym_protocol_incompatible'), {observedProtocolVersion:version});
    }
    if (record.state !== expected) {
      throw Object.assign(fail('canonical_gym_not_activated'), {observedState:record.state, expectedState:expected});
    }
  }

  async readRaw(chat, id) {
    const result = await this.execute(
      `SELECT generation, protocol_version, state_hash, state_json, deleted, operation_receipts FROM ${this.records} WHERE chat_id = ? AND record_id = ?`,
      [chat, id], {consistency:'SERIAL'});
    const found = row(result);
    if (!found) return null;
    const generation = Number(found.generation);
    if (!Number.isSafeInteger(generation) || generation < 1 || found.protocol_version !== PROTOCOL_VERSION ||
        typeof found.state_json !== 'string' || typeof found.deleted !== 'boolean') throw fail('canonical_gym_record_incompatible');
    let state;
    try { state = JSON.parse(found.state_json); } catch { throw fail('canonical_gym_record_corrupt'); }
    if (hash(state) !== found.state_hash || (found.deleted && state !== null) || (!found.deleted && !object(state))) {
      throw fail('canonical_gym_hash_mismatch');
    }
    return {generation, protocolVersion: found.protocol_version, stateHash: found.state_hash,
      state, deleted: found.deleted, operationReceipts: receiptSet(found.operation_receipts), raw: found};
  }

  async readRecord(chat, module, key, expectedCapability = 'active') {
    if (!validId(chat) || !MODULES.has(module) || !validId(key)) throw fail('invalid_canonical_gym_identity');
    await this.capability(expectedCapability);
    const found = await this.readRaw(chat, recordId(module, key));
    return found && !found.deleted ? found : null;
  }

  async readGroup(chat, expectedCapability = 'active') {
    if (!validId(chat)) throw fail('invalid_canonical_gym_identity');
    await this.capability(expectedCapability);
    const result = await this.execute(
      `SELECT record_id, generation, protocol_version, state_hash, state_json, deleted FROM ${this.records} WHERE chat_id = ?`,
      [chat], {consistency:'SERIAL'});
    const output = {'ginasios':{}, 'ginasios-estatisticas':{}};
    for (const record of result?.rows || []) {
      const separator = record.record_id.indexOf(':');
      const module = record.record_id.slice(0, separator), key = record.record_id.slice(separator + 1);
      if (!MODULES.has(module) || !validId(key)) throw fail('canonical_gym_record_incompatible');
      const checked = await this.readRaw(chat, record.record_id);
      if (!checked.deleted) output[module][key] = checked.state;
    }
    return output;
  }

  async readAll(module) {
    if (!MODULES.has(module)) throw fail('invalid_canonical_gym_module');
    return this.readAllInState(module, 'active');
  }

  async readAllInState(module, state = 'active') {
    if (!MODULES.has(module)) throw fail('invalid_canonical_gym_module');
    await this.capability(state);
    const chats = new Set();
    for (let bucket = 0; bucket < BUCKETS; bucket++) {
      const result = await this.execute(`SELECT chat_id FROM ${this.groups} WHERE bucket = ?`, [bucket], {consistency:'QUORUM'});
      for (const item of result?.rows || []) {
        if (!validId(item.chat_id) || this.bucket(item.chat_id) !== bucket) throw fail('canonical_gym_directory_corrupt');
        chats.add(item.chat_id);
      }
    }
    const output = {};
    for (const chat of chats) {
      const group = await this.readGroup(chat, state);
      if (Object.keys(group[module]).length) output[chat] = group[module];
    }
    return output;
  }

  async writeRecord(chat, module, key, expected, desired, migrationId = null, operationId = null) {
    if (!validId(chat) || !MODULES.has(module) || !validId(key) ||
        (desired !== null && !object(desired)) || (operationId !== null && !validOperationId(operationId))) {
      throw fail('invalid_canonical_gym_write');
    }
    await this.capability(migrationId ? 'migrating' : 'active');
    await this.ensureGroup(chat);
    const id = recordId(module, key);
    const current = await this.readRaw(chat, id);
    if (operationId && current?.operationReceipts.has(operationId)) return current;
    const priorState = !current || current.deleted ? null : current.state;
    if (stableJson(priorState) === stableJson(desired) && !operationId) {
      return current || {generation:0, state:null, stateHash:hash(null)};
    }
    // A deleted row retains its generation as a tombstone. An absent-state
    // snapshot must never resurrect it; legitimate recreation needs an
    // explicit, version-aware domain operation (not a stale whole-module save).
    if (current?.deleted && expected === null && desired !== null) throw fail('canonical_gym_tombstone_conflict');
    if (stableJson(priorState) !== stableJson(expected)) throw fail('canonical_gym_cas_conflict');
    const generation = (current?.generation || 0) + 1;
    const stateJson = stableJson(desired), stateHash = hash(desired), deleted = desired === null;
    // cassandra-driver encodes CQL set<text> from a JS Array; a JS Set is not
    // accepted by its default encoder and would turn a confirmed write into
    // an uncertain result before the LWT reaches Cassandra.
    const receiptSetForWrite = operationId
      ? [...new Set([...(current?.operationReceipts || []), operationId])] : null;
    const result = current
      ? await this.execute(
        `UPDATE ${this.records} SET generation = ?, protocol_version = ?, state_hash = ?, state_json = ?, deleted = ?, updated_at = ?, writer_id = ?, migration_id = ?${operationId ? ', operation_receipts = ?' : ''} WHERE chat_id = ? AND record_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ?`,
        [generation, PROTOCOL_VERSION, stateHash, stateJson, deleted, this.now(), migrationId ? 'pokemon-gym-migrator' : this.writerId,
          migrationId, ...(operationId ? [receiptSetForWrite] : []),
          chat, id, current.generation, current.stateHash, PROTOCOL_VERSION],
        {consistency:'QUORUM',serialConsistency:'SERIAL'})
      : await this.execute(
        `INSERT INTO ${this.records} (chat_id, record_id, generation, protocol_version, state_hash, state_json, deleted, updated_at, writer_id, migration_id${operationId ? ', operation_receipts' : ''}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?${operationId ? ', ?' : ''}) IF NOT EXISTS`,
        [chat, id, generation, PROTOCOL_VERSION, stateHash, stateJson, deleted, this.now(),
          migrationId ? 'pokemon-gym-migrator' : this.writerId, migrationId,
          ...(operationId ? [receiptSetForWrite] : [])],
        {consistency:'QUORUM',serialConsistency:'SERIAL'});
    if (result?.wasApplied?.()) return {generation, protocolVersion:PROTOCOL_VERSION, stateHash,
      state:desired, deleted, operationReceipts:new Set([...(current?.operationReceipts || []), ...(operationId ? [operationId] : [])])};
    throw fail('canonical_gym_cas_conflict');
  }

  async writeRecordRecovering(chat, module, key, expected, desired, migrationId = null, operationId = null) {
    try { return await this.writeRecord(chat, module, key, expected, desired, migrationId, operationId); }
    catch (error) {
      const observed = await this.readRaw(chat, recordId(module, key));
      if (operationId && observed?.operationReceipts.has(operationId)) return observed;
      if (!operationId && observed && observed.stateHash === hash(desired) && observed.deleted === (desired === null)) return observed;
      if (['canonical_gym_cas_conflict','canonical_gym_tombstone_conflict'].includes(error?.code)) throw error;
      if ((!observed && expected === null) || (observed && stableJson(observed.deleted ? null : observed.state) === stableJson(expected))) {
        throw Object.assign(fail('canonical_gym_result_unknown'), {cause:error});
      }
      throw Object.assign(fail('canonical_gym_result_unknown'), {cause:error});
    }
  }

  // A deterministic event can be replayed safely after a timeout/restart.
  // The receipt and the record mutation share one Cassandra LWT on the same
  // row, so a duplicate event cannot apply the transform twice.
  async updateRecordOnce(chat, module, key, operationId, transform) {
    if (!validOperationId(operationId) || typeof transform !== 'function') throw fail('invalid_canonical_gym_operation');
    await this.capability('active');
    await this.ensureGroup(chat);
    const id = recordId(module, key);
    for (let attempt = 0; attempt < 8; attempt++) {
      const current = await this.readRaw(chat, id);
      if (current?.operationReceipts.has(operationId)) return current;
      const before = !current || current.deleted ? null : current.state;
      const after = transform(before);
      if (after !== null && !object(after)) throw fail('invalid_canonical_gym_write');
      try {
        return await this.writeRecordRecovering(chat, module, key, before, after, null, operationId);
      } catch (error) {
        if (error?.code !== 'canonical_gym_cas_conflict') throw error;
      }
    }
    throw fail('canonical_gym_cas_contention');
  }

  flatten(module, value) {
    if (!object(value)) throw fail('canonical_gym_state_invalid');
    const output = new Map();
    for (const [chat, entries] of Object.entries(value)) {
      if (!validId(chat) || !object(entries)) throw fail('canonical_gym_state_invalid');
      for (const [key, state] of Object.entries(entries)) {
        if (!validId(key) || !object(state)) throw fail('canonical_gym_state_invalid');
        output.set(`${chat}\u0000${key}`, {chat, key, state});
      }
    }
    return output;
  }

  async saveModule(module, before, after) {
    if (!MODULES.has(module)) throw fail('invalid_canonical_gym_module');
    const old = this.flatten(module, before || {}), next = this.flatten(module, after || {});
    const keys = new Set([...old.keys(), ...next.keys()]);
    const confirmed = JSON.parse(JSON.stringify(before || {}));
    for (const composite of keys) {
      const prior = old.get(composite), desired = next.get(composite);
      if (stableJson(prior?.state ?? null) === stableJson(desired?.state ?? null)) continue;
      const {chat, key} = desired || prior;
      await this.writeRecordRecovering(chat, module, key, prior?.state ?? null, desired?.state ?? null);
      if (desired) {
        if (!object(confirmed[chat])) confirmed[chat] = {};
        confirmed[chat][key] = desired.state;
      } else if (object(confirmed[chat])) {
        delete confirmed[chat][key];
        if (!Object.keys(confirmed[chat]).length) delete confirmed[chat];
      }
    }
    return confirmed;
  }

  async importModuleSnapshot(chat, module, snapshot, migrationId) {
    if (!validId(chat) || !MODULES.has(module) || !object(snapshot) || typeof migrationId !== 'string' || !migrationId) {
      throw fail('invalid_canonical_gym_migration');
    }
    await this.capability('migrating');
    for (const [key, state] of Object.entries(snapshot)) {
      const current = await this.readRaw(chat, recordId(module, key));
      if (current) {
        if (current.deleted || current.stateHash !== hash(state)) throw fail('canonical_gym_migration_conflict');
      } else {
        await this.writeRecordRecovering(chat, module, key, null, state, migrationId);
      }
    }
  }
}

module.exports = {CanonicalGymStore, PROTOCOL_VERSION, MODULES, stableJson};
