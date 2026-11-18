'use strict';

// CAS adapter for the current trainer-per-group row. It deliberately does not
// claim to fence binaries that still issue unconditional Cassandra upserts.
const {hash} = require('./restore-protocol.cjs');
const LOCK_MODULE = 'pokemon-save-transition-locks';
const MAX_CAS = 8;
const absent = Symbol('absent');
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const equal = (a, b) => a === absent || b === absent ? a === b : hash(a) === hash(b);
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];

async function read(execute, table, module, partition) {
  const result = await execute(`SELECT valor FROM ${table} WHERE modulo = ? AND particao = ?`,
    [module, partition], {consistency: 'SERIAL'});
  const value = row(result)?.valor;
  if (value === undefined || value === null) return {raw: null, value: module === 'treinador' ? {} : null};
  try { return {raw: value, value: JSON.parse(value)}; }
  catch { throw Object.assign(new Error('trainer_state_corrupt'), {code: 'trainer_state_corrupt'}); }
}

async function assertNoTransitionLocks(execute, table, chat, deltas) {
  for (const player of Object.keys(deltas)) {
    const partition = hash([chat, 'account', player]);
    const result = await execute(`SELECT valor FROM ${table} WHERE modulo = ? AND particao = ?`,
      [LOCK_MODULE, partition], {consistency: 'SERIAL'});
    const raw = row(result)?.valor;
    if (raw === undefined || raw === null) continue;
    let lock;
    try { lock = JSON.parse(raw); }
    catch { throw Object.assign(new Error('save_transition_lock_corrupt'), {code: 'save-transition-pending'}); }
    if (!object(lock) || lock.schemaVersion !== 1 || !Number.isSafeInteger(lock.generation) ||
        lock.generation < 0 || lock.resourceHash !== hash({type: 'account', id: player}) ||
        (lock.owner !== null && typeof lock.owner !== 'string')) {
      throw Object.assign(new Error('save_transition_lock_corrupt'), {code: 'save-transition-pending'});
    }
    if (lock.owner !== null) {
      throw Object.assign(new Error('save_transition_pending'), {code: 'save-transition-pending'});
    }
  }
}

function deltas(before, after) {
  const result = {};
  for (const player of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const oldValue = Object.hasOwn(before, player) ? before[player] : absent;
    const nextValue = Object.hasOwn(after, player) ? after[player] : absent;
    if (!equal(oldValue, nextValue)) result[player] = [oldValue, nextValue];
  }
  return result;
}

function merge(current, changes) {
  if (!object(current)) throw Object.assign(new Error('trainer_partition_corrupt'), {code: 'trainer_state_corrupt'});
  const next = {...current};
  for (const [player, [expected, desired]] of Object.entries(changes)) {
    const observed = Object.hasOwn(current, player) ? current[player] : absent;
    if (equal(observed, desired)) continue; // idempotent replay
    if (!equal(observed, expected)) {
      throw Object.assign(new Error('trainer_cas_conflict'), {code: 'trainer-cas-conflict'});
    }
    if (desired === absent) delete next[player];
    else next[player] = desired;
  }
  return next;
}

function targetsPresent(value, changes) {
  return Object.entries(changes).every(([player, [, desired]]) =>
    equal(Object.hasOwn(value, player) ? value[player] : absent, desired));
}

async function saveGroup(execute, table, chat, before, after) {
  if (typeof execute !== 'function' || typeof table !== 'string' ||
      !/^[A-Za-z][A-Za-z0-9_]*\.estado_particionado$/.test(table) ||
      typeof chat !== 'string' || !chat || !object(before) || !object(after)) {
    throw Object.assign(new Error('invalid_trainer_write'), {code: 'invalid_trainer_write'});
  }
  const changes = deltas(before, after);
  if (!Object.keys(changes).length) return before;

  for (let attempt = 0; attempt < MAX_CAS; attempt++) {
    await assertNoTransitionLocks(execute, table, chat, changes);
    const current = await read(execute, table, 'treinador', chat);
    if (targetsPresent(current.value, changes)) return current.value;
    const next = merge(current.value, changes);
    const serialized = JSON.stringify(next);
    const query = current.raw === null
      ? `INSERT INTO ${table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`
      : `UPDATE ${table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`;
    const params = current.raw === null ? ['treinador', chat, serialized]
      : [serialized, 'treinador', chat, current.raw];
    try {
      const result = await execute(query, params, {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (result?.wasApplied?.()) return next;
    } catch (error) {
      // A SERIAL read resolves a lost LWT response. Retry only if the prior
      // row is still current; never replay on top of an ambiguous value.
      const observed = await read(execute, table, 'treinador', chat);
      if (targetsPresent(observed.value, changes)) return observed.value;
      if (observed.raw === current.raw) continue;
      throw Object.assign(new Error('trainer_write_result_unknown'), {
        code: 'result-unknown', cause: error
      });
    }
  }
  throw Object.assign(new Error('trainer_cas_contention'), {code: 'result-unknown'});
}

module.exports = {saveGroup};
