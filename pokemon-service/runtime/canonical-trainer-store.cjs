'use strict';

// Versioned per-player source of truth. This module intentionally never reads
// the legacy trainer partition; callers must fail closed when canonical mode
// is enabled but the row/capability is absent or incompatible.
const {hash} = require('./restore-protocol.cjs');
const {normalizeSeal, freezeFromScope, assertSealMatchesRecord} = require('./save-bound-task.cjs');
const {
  MAX_LATERAL_INTENTS, STATUSES, EVIDENCE_OUTCOMES, parseIntentMap, serializeIntentMap,
  buildActiveIntent, assertNoBlockingIntents, assertEffectEvidence, blockingIntents,
  pruneOldestTombstones
} = require('./lateral-intent.cjs');
const {AsyncLocalStorage} = require('node:async_hooks');
const PROTOCOL_VERSION = 2;
const GROUP_BUCKETS = 16;
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const stableJson = value => JSON.stringify(value, (_key, item) => {
  if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
  return Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]));
});
const fail = (code, message = code) => Object.assign(new Error(message), {code});
const validEffectId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{8,100}$/.test(value);
const validHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const receiptEntries = value => value instanceof Map ? [...value.entries()] :
  object(value) ? Object.entries(value) : [];
const receiptMap = value => Object.fromEntries(receiptEntries(value).filter(([id, intent]) =>
  validEffectId(id) && validHash(intent)));
const MAX_OPERATION_RECEIPTS = 1024;

class CanonicalTrainerStore {
  constructor(execute, keyspace, options = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_canonical_store');
    this.execute = execute;
    this.keyspace = keyspace;
    this.protocolVersion = options.protocolVersion ?? PROTOCOL_VERSION;
    if (this.protocolVersion !== PROTOCOL_VERSION) throw fail('canonical_trainer_writer_incompatible');
    this.writerContext = new AsyncLocalStorage();
    this.writerId = options.writerId || 'pokemon-service';
    this.now = options.now || (() => new Date());
    this.accounts = `${keyspace}.canonical_trainer_accounts`;
    this.groups = `${keyspace}.canonical_trainer_groups`;
    this.control = `${keyspace}.canonical_trainer_control`;
  }

  bucket(chat) { return Number.parseInt(hash(chat).slice(0, 8), 16) % GROUP_BUCKETS; }

  async ensureGroup(chat) {
    await this.execute(`INSERT INTO ${this.groups} (bucket, chat_id) VALUES (?, ?) IF NOT EXISTS`,
      [this.bucket(chat), chat], {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
  }

  async assertCapability(expectedState = 'active') {
    const capability = row(await this.execute(
      `SELECT protocol_version, state FROM ${this.control} WHERE capability = ?`, ['trainer-canonical'], {consistency: 'SERIAL'}));
    if (!capability || capability.state !== expectedState || capability.protocol_version !== this.protocolVersion) {
      throw fail('canonical_trainer_not_activated', 'Canonical trainer protocol is not certified for this operation.');
    }
  }

  async assertActivated() { return this.assertCapability('active'); }

  context(chat, player, record) {
    return Object.freeze({identityHash:hash([chat,player]), fenceGeneration:record.fenceGeneration,
      activeSaveId:record.activeSaveId, transitionOwner:record.transitionOwner});
  }

  assertContext(chat, player, expected, current) {
    if (!expected || expected.identityHash !== hash([chat,player]) ||
        !Number.isSafeInteger(expected.fenceGeneration) || expected.fenceGeneration < 1 ||
        typeof expected.activeSaveId !== 'string' || !expected.activeSaveId ||
        typeof expected.transitionOwner !== 'string') throw fail('canonical_trainer_context_required');
    if (!current || expected.fenceGeneration !== current.fenceGeneration ||
        expected.activeSaveId !== current.activeSaveId || expected.transitionOwner !== current.transitionOwner) {
      throw fail('canonical_trainer_fence_conflict');
    }
  }

  assertInheritedContext(chat, player, current) {
    const inherited = this.writerContext.getStore();
    if (inherited) {
      if (inherited.chat !== chat) throw fail('canonical_trainer_context_required');
      this.assertContext(chat,player,inherited.contexts[player],current);
    }
  }

  currentWriterContext() {
    return this.writerContext.getStore() || null;
  }

  freezeSaveBoundTask(player) {
    return freezeFromScope(this.writerContext.getStore(), player || null);
  }

  // Installs a previously frozen fence. Never reads the live row to refresh it.
  async runPersistedContext(chat, player, seal, action) {
    const payload = object(seal) ? {...seal, chat: seal.chat || chat, player: player || seal.player} : null;
    const normalized = normalizeSeal(payload);
    if (!normalized || normalized.chat !== chat) throw fail('save_bound_context_required');
    const existing = this.writerContext.getStore();
    if (existing) {
      const id = player || normalized.player || Object.keys(normalized.contexts)[0];
      this.assertContext(chat, id, normalized.contexts[id], existing.contexts[id]);
      return action();
    }
    return this.writerContext.run(Object.freeze({
      chat, player: player || normalized.player || null,
      contexts: normalized.contexts, persisted: true
    }), action);
  }

  assertPersistedFence(chat, player, seal, record) {
    const payload = object(seal) ? {...seal, chat: seal.chat || chat, player: player || seal.player} : null;
    return assertSealMatchesRecord(payload, player, record);
  }

  // Captured once, before domain work. Node propagates the immutable context
  // to promises and timers. A nested/late callback must keep its original
  // fence, including when it rejoins a command queue after a transition.
  async withWriterContext(chat, action, expectedGroup, allowCapture = true, player) {
    if (this.writerContext.getStore()) return action();
    // Recovery/deferred work must not refresh the live fence. The action is
    // responsible for installing a persisted seal (runPersistedContext/resume)
    // or it will fail on the first write that requires context.
    if (allowCapture !== true) return action();
    const group = await this.readGroup(chat);
    if (expectedGroup !== undefined && stableJson(group) !== stableJson(expectedGroup)) {
      throw fail('canonical_trainer_snapshot_stale');
    }
    const contexts = {};
    for (const id of Object.keys(group)) {
      const record = await this.readAccount(chat,id);
      if (!record || record.stateHash !== hash(group[id])) {
        throw fail('canonical_trainer_context_unavailable');
      }
      contexts[id] = this.context(chat,id,record);
    }
    if (typeof player === 'string' && player) {
      if (!contexts[player]) throw fail('canonical_trainer_context_unavailable');
      if (contexts[player].transitionOwner) throw fail('canonical_trainer_transition_reserved');
    }
    return this.writerContext.run(Object.freeze({chat, player: player || null,
      contexts:Object.freeze(contexts)}),action);
  }

  // Shop, gym, battle and raid snapshots do not share the trainer LWT. While a
  // reservation owns the account they must not start or continue for that
  // player. After A→B the frozen writer fence must still match the live row —
  // otherwise a raid/loja callback from A can confirm once transitionOwner is
  // cleared. Other players in the captured group remain writable.
  //
  // This is still read-then-write, not atomic fencing: an admitted lateral can
  // pause before its INSERT while A→B publishes, then confirm on another
  // partition (see test/raid-save-concurrency.test.cjs TOCTOU cases). Closing
  // that window needs a durable cross-partition journal — not a second SELECT.
  async assertLateralAdmission(module) {
    const laterals = new Set(['loja', 'ginasios', 'ginasios-estatisticas', 'raids',
      'pokemon-batalhas-ativas', 'pokemon-cacadas-ativas', 'pokemon-propostas-troca',
      'pokemon-remocoes-pendentes', 'pokemon-descobertas']);
    if (!laterals.has(module)) return null;
    const scope = this.writerContext.getStore();
    if (!scope) throw fail('canonical_trainer_context_required');
    const players = scope.player ? [scope.player] : Object.keys(scope.contexts || {});
    for (const id of players) {
      const record = await this.readAccount(scope.chat, id);
      if (record?.transitionOwner) throw fail('canonical_trainer_transition_reserved');
      const ctx = scope.contexts?.[id];
      if (!ctx) throw fail('canonical_trainer_context_required');
      // Same predicate as trainer LWT: stale save/fence after activation loses.
      this.assertContext(scope.chat, id, ctx, record);
    }
    return true;
  }

  // Re-check immediately before a cross-partition lateral INSERT. A reconciled
  // or fence-moved intent must not allow a delayed write to land.
  async assertLateralIntentActive(chat, player, operationId, writerContext) {
    await this.assertActivated();
    const current = await this.readAccount(chat, player);
    if (!current) throw fail('canonical_trainer_missing');
    this.assertContext(chat, player, writerContext, current);
    this.assertInheritedContext(chat, player, current);
    const intent = current.lateralIntents[operationId];
    if (!intent || intent.status !== STATUSES.active) {
      throw fail('canonical_trainer_lateral_intent_stale');
    }
    if (intent.originSaveId !== current.activeSaveId ||
        intent.fenceGeneration !== current.fenceGeneration) {
      throw fail('canonical_trainer_fence_conflict');
    }
    return intent;
  }

  async readGroup(chat) {
    return this.readGroupInState(chat, 'active');
  }

  async readGroupInState(chat, state) {
    if (typeof chat !== 'string' || !chat) throw fail('invalid_canonical_identity');
    await this.assertCapability(state);
    const result = await this.execute(
      `SELECT player_id, generation, schema_version, protocol_version, state_hash, state_json, fence_generation, active_save_id, transition_owner FROM ${this.accounts} WHERE chat_id = ?`,
      [chat], {consistency: 'SERIAL'});
    const accounts = {};
    for (const record of (result?.rows || [])) {
      if (record.protocol_version !== this.protocolVersion || !Number.isSafeInteger(Number(record.generation)) ||
          Number(record.generation) < 1 || record.schema_version !== 1 || typeof record.player_id !== 'string') {
        throw fail('canonical_trainer_record_incompatible');
      }
      let value;
      try { value = JSON.parse(record.state_json); } catch { throw fail('canonical_trainer_corrupt'); }
      if (!object(value) || hash(value) !== record.state_hash) throw fail('canonical_trainer_hash_mismatch');
      this.validateFence(record);
      accounts[record.player_id] = value;
    }
    return accounts;
  }

  async readAllGroups() {
    return this.readAllGroupsInState('active');
  }

  async readAllGroupsInState(state) {
    await this.assertCapability(state);
    const groupIds = new Set();
    for (let bucket = 0; bucket < GROUP_BUCKETS; bucket++) {
      const result = await this.execute(`SELECT chat_id FROM ${this.groups} WHERE bucket = ?`,
        [bucket], {consistency: 'QUORUM'});
      for (const record of result?.rows || []) {
        if (this.bucket(record.chat_id) !== bucket) throw fail('canonical_trainer_directory_corrupt');
        groupIds.add(record.chat_id);
      }
    }
    const output = {};
    for (const chat of groupIds) output[chat] = await this.readGroupInState(chat,state);
    return output;
  }

  async readAccount(chat, player) {
    if (typeof chat !== 'string' || !chat || typeof player !== 'string' || !player) throw fail('invalid_canonical_identity');
    await this.assertActivated();
    const record = row(await this.execute(
      `SELECT generation, schema_version, protocol_version, state_hash, state_json, operation_receipts, fence_generation, active_save_id, transition_owner, lateral_intents FROM ${this.accounts} WHERE chat_id = ? AND player_id = ?`,
      [chat, player], {consistency: 'SERIAL'}));
    if (!record) return null;
    if (record.protocol_version !== this.protocolVersion || !Number.isSafeInteger(Number(record.generation)) ||
        Number(record.generation) < 1 || record.schema_version !== 1) throw fail('canonical_trainer_record_incompatible');
    let value;
    try { value = JSON.parse(record.state_json); } catch { throw fail('canonical_trainer_corrupt'); }
    if (!object(value) || hash(value) !== record.state_hash) throw fail('canonical_trainer_hash_mismatch');
    this.validateFence(record);
    return {generation: Number(record.generation), state: value, stateHash: record.state_hash,
      identityHash:hash([chat,player]), fenceGeneration:Number(record.fence_generation),
      activeSaveId:record.active_save_id, transitionOwner:record.transition_owner,
      operationReceipts: receiptMap(record.operation_receipts),
      lateralIntents: parseIntentMap(record.lateral_intents)};
  }

  validateFence(record) {
    if (!Number.isSafeInteger(Number(record.fence_generation)) || Number(record.fence_generation) < 1 ||
        typeof record.active_save_id !== 'string' || !/^[a-z][a-z0-9_-]{0,63}$/.test(record.active_save_id) ||
        typeof record.transition_owner !== 'string') throw fail('canonical_trainer_fence_incompatible');
  }

  async createAccount(chat, player, state, migrationId) {
    if (!object(state) || typeof migrationId !== 'string' || !migrationId) throw fail('invalid_canonical_migration');
    await this.assertActivated();
    await this.ensureGroup(chat);
    const stateJson = stableJson(state), stateHash = hash(state);
    const result = await this.execute(
      `INSERT INTO ${this.accounts} (chat_id, player_id, generation, schema_version, protocol_version, state_hash, state_json, updated_at, writer_id, migration_id, fence_generation, active_save_id, transition_owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
      [chat, player, 1, 1, this.protocolVersion, stateHash, stateJson, this.now(), this.writerId, migrationId, 1, 'a', ''],
      {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
    if (result?.wasApplied?.()) return {...await this.readAccount(chat,player), created: true};
    const existing = await this.readAccount(chat, player);
    if (existing && existing.stateHash === stateHash && stableJson(existing.state) === stateJson) return {...existing, created: false};
    throw fail('canonical_trainer_migration_conflict');
  }

  async importMigratedAccount(chat, player, state, migrationId) {
    if (!object(state) || typeof migrationId !== 'string' || !migrationId) throw fail('invalid_canonical_migration');
    await this.assertCapability('migrating');
    await this.ensureGroup(chat);
    const stateJson = stableJson(state), stateHash = hash(state);
    const result = await this.execute(
      `INSERT INTO ${this.accounts} (chat_id, player_id, generation, schema_version, protocol_version, state_hash, state_json, updated_at, writer_id, migration_id, fence_generation, active_save_id, transition_owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
      [chat, player, 1, 1, this.protocolVersion, stateHash, stateJson, this.now(), 'pokemon-migrator', migrationId, 1, 'a', ''],
      {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
    if (result?.wasApplied?.()) return {generation: 1, state, stateHash, created: true};
    const existing = row(await this.execute(
      `SELECT generation, schema_version, protocol_version, state_hash, state_json FROM ${this.accounts} WHERE chat_id = ? AND player_id = ?`,
      [chat, player], {consistency: 'SERIAL'}));
    if (existing && existing.protocol_version === this.protocolVersion && existing.schema_version === 1 &&
        existing.state_hash === stateHash && existing.state_json === stateJson) {
      return {generation: Number(existing.generation), state, stateHash, created: false};
    }
    throw fail('canonical_trainer_migration_conflict');
  }

  async compareAndSet(chat, player, expected, next) {
    if (!object(next)) throw fail('invalid_canonical_state');
    await this.assertActivated();
    await this.ensureGroup(chat);
    const current = await this.readAccount(chat, player);
    this.assertInheritedContext(chat,player,current);
    this.assertContext(chat,player,expected,current);
    if (expected.transitionOwner) throw fail('canonical_trainer_transition_reserved');
    if (!current || current.generation !== expected.generation || current.stateHash !== expected.stateHash) {
      throw fail('canonical_trainer_cas_conflict');
    }
    const stateJson = stableJson(next), stateHash = hash(next), generation = current.generation + 1;
    try {
      const result = await this.execute(
        `UPDATE ${this.accounts} SET generation = ?, state_hash = ?, state_json = ?, updated_at = ?, writer_id = ? WHERE chat_id = ? AND player_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ? AND fence_generation = ? AND active_save_id = ? AND transition_owner = ?`,
        [generation, stateHash, stateJson, this.now(), this.writerId, chat, player,
          current.generation, current.stateHash, this.protocolVersion, expected.fenceGeneration, expected.activeSaveId, ''],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (result?.wasApplied?.()) return {...current, generation, state: next, stateHash};
    } catch (error) {
      const observed = await this.readAccount(chat, player);
      if (observed?.generation === generation && observed.stateHash === stateHash &&
          observed.fenceGeneration === expected.fenceGeneration && observed.activeSaveId === expected.activeSaveId &&
          observed.transitionOwner === '') return observed;
      if (observed?.generation !== current.generation || observed?.stateHash !== current.stateHash) {
        throw fail('canonical_trainer_result_unknown');
      }
      throw error;
    }
    throw fail('canonical_trainer_cas_conflict');
  }

  // Applies one durable effect to one canonical account. The effect receipt and
  // the state mutation live in the same LWT; a lost response is reconciled by
  // reading that receipt, never by replaying the mutation from a stale snapshot.
  async applyOnce(chat, player, {effectId, intentHash, expectedGeneration, expectedStateHash, writerContext,
    targetSaveId, transform}) {
    if (!validEffectId(effectId) || !validHash(intentHash) ||
        !Number.isSafeInteger(expectedGeneration) || expectedGeneration < 1 ||
        !validHash(expectedStateHash) || typeof transform !== 'function') {
      throw fail('invalid_canonical_effect');
    }
    await this.assertActivated();
    await this.ensureGroup(chat);
    const current = await this.readAccount(chat, player);
    if (!current) throw fail('canonical_trainer_missing');
    const receipts = current.operationReceipts || {};
    if (Object.hasOwn(receipts, effectId)) {
      if (receipts[effectId] !== intentHash) throw fail('canonical_trainer_effect_conflict');
      return {...current, applied: false};
    }
    this.assertContext(chat,player,writerContext,current);
    this.assertInheritedContext(chat,player,current);
    if ((targetSaveId !== undefined && (!writerContext.transitionOwner ||
        !/^[a-z][a-z0-9_-]{0,63}$/.test(targetSaveId))) ||
        (writerContext.transitionOwner && targetSaveId === undefined)) throw fail('canonical_trainer_transition_reserved');
    if (current.generation !== expectedGeneration || current.stateHash !== expectedStateHash) {
      throw fail('canonical_trainer_stale_generation');
    }
    if (Object.keys(receipts).length >= MAX_OPERATION_RECEIPTS) throw fail('canonical_trainer_receipt_limit');
    const next = transform(structuredClone(current.state));
    if (!object(next)) throw fail('invalid_canonical_state');
    const generation = current.generation + 1;
    const stateJson = stableJson(next), stateHash = hash(next);
    const nextReceipts = {...receipts, [effectId]: intentHash};
    const observe = async () => {
      const observed = await this.readAccount(chat, player);
      if (observed?.operationReceipts?.[effectId] === intentHash) return {...observed, applied: false};
      if (observed?.operationReceipts?.[effectId]) throw fail('canonical_trainer_effect_conflict');
      return null;
    };
    try {
      const result = await this.execute(
        `UPDATE ${this.accounts} SET generation = ?, state_hash = ?, state_json = ?, operation_receipts = ?, updated_at = ?, writer_id = ?, active_save_id = ? WHERE chat_id = ? AND player_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ? AND fence_generation = ? AND active_save_id = ? AND transition_owner = ?`,
        [generation, stateHash, stateJson, nextReceipts, this.now(), this.writerId, targetSaveId ?? current.activeSaveId, chat, player,
          current.generation, current.stateHash, this.protocolVersion, writerContext.fenceGeneration,
          writerContext.activeSaveId, writerContext.transitionOwner],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (result?.wasApplied?.()) return {...current, generation, state: next, stateHash,
        activeSaveId:targetSaveId ?? current.activeSaveId, operationReceipts: nextReceipts, applied: true};
    } catch (error) {
      let observed;
      try { observed = await observe(); }
      catch (reconciliationError) {
        throw Object.assign(fail('canonical_trainer_result_unknown'), {cause: error, reconciliationError});
      }
      if (observed) return observed;
      throw Object.assign(fail('canonical_trainer_result_unknown'), {cause: error});
    }
    let observed;
    try { observed = await observe(); }
    catch (reconciliationError) {
      throw Object.assign(fail('canonical_trainer_result_unknown'), {cause: reconciliationError});
    }
    if (observed) return observed;
    throw fail('canonical_trainer_cas_conflict');
  }

  // The reservation linearizes here, on the SAME row as every account write.
  // A writer that passed a separate lock check earlier loses its final LWT.
  // Active/confirmed/uncertain lateral intents also block: B must not publish
  // while an admitted lateral of A may still confirm (see lateral-intent.cjs).
  async reserveTransition(chat, player, expected, operationId) {
    if (!validEffectId(operationId)) throw fail('invalid_canonical_transition');
    const effectId = `reserve_${operationId}`;
    const intent = hash({chat,player,operationId,context:expected});
    const current = await this.readAccount(chat,player);
    if (current?.operationReceipts?.[effectId] === intent) {
      if (current.transitionOwner !== operationId || current.fenceGeneration !== expected.fenceGeneration + 1) {
        throw fail('canonical_trainer_reservation_unresolved');
      }
      return current;
    }
    this.assertContext(chat,player,expected,current);
    if (current.transitionOwner || current.generation !== expected.generation || current.stateHash !== expected.stateHash) {
      throw fail('canonical_trainer_reservation_conflict');
    }
    assertNoBlockingIntents(current.lateralIntents);
    return this.changeFence(chat,player,current,{fenceGeneration:current.fenceGeneration + 1,
      transitionOwner:operationId},effectId,intent);
  }

  // --- Lateral intent protocol (infrastructure only; raids/loja not wired) ---

  async beginLateralIntent(chat, player, {operationId, type, payloadHash, writerContext}) {
    await this.assertActivated();
    const current = await this.readAccount(chat, player);
    if (!current) throw fail('canonical_trainer_missing');
    this.assertContext(chat, player, writerContext, current);
    this.assertInheritedContext(chat, player, current);
    if (current.transitionOwner) throw fail('canonical_trainer_transition_reserved');

    const existing = current.lateralIntents[operationId];
    const nextIntent = buildActiveIntent({
      operationId, type, payloadHash,
      originSaveId: current.activeSaveId,
      fenceGeneration: current.fenceGeneration,
      accountGeneration: current.generation,
      writerId: this.writerId,
      now: this.now()
    });
    if (existing) {
      // Tombstones and foreign payloads must never be overwritten in place.
      if (existing.intentHash === nextIntent.intentHash && existing.status === STATUSES.active) {
        return {...current, intent: existing, applied: false};
      }
      throw fail('canonical_trainer_lateral_intent_conflict');
    }
    if (blockingIntents(current.lateralIntents).length >= MAX_LATERAL_INTENTS) {
      throw fail('canonical_trainer_lateral_intent_limit');
    }
    const intents = {...current.lateralIntents, [operationId]: nextIntent};
    const updated = await this.writeLateralIntents(chat, player, current, writerContext, intents);
    return {...updated, intent: nextIntent, applied: true};
  }

  async confirmLateralIntent(chat, player, {operationId, writerContext, effectEvidence}) {
    // Confirm is not a claim: callers must bind a durable lateral receipt.
    return this.transitionLateralIntent(chat, player, {
      operationId, writerContext, from: STATUSES.active, to: STATUSES.confirmed,
      effectEvidence, allowedOutcomes: [EVIDENCE_OUTCOMES.persisted]
    });
  }

  async markLateralIntentUncertain(chat, player, {operationId, writerContext}) {
    // Lost effect ack: keep the row forever until explicit evidenced reconcile.
    return this.transitionLateralIntent(chat, player, {
      operationId, writerContext, from: STATUSES.active, to: STATUSES.uncertain
    });
  }

  async reconcileLateralIntent(chat, player, {operationId, writerContext, effectEvidence}) {
    // Explicit resolution only — never called from timers/restart hooks.
    // uncertain requires durable evidence; confirmed must already carry a receipt.
    await this.assertActivated();
    const current = await this.readAccount(chat, player);
    if (!current) throw fail('canonical_trainer_missing');
    this.assertContext(chat, player, writerContext, current);
    this.assertInheritedContext(chat, player, current);
    const existing = current.lateralIntents[operationId];
    if (!existing) return {...current, intent: null, applied: false};
    if (existing.status === STATUSES.active) {
      throw fail('canonical_trainer_lateral_intent_active');
    }
    if (existing.status === STATUSES.reconciled) {
      return {...current, intent: existing, applied: false};
    }
    if (![STATUSES.confirmed, STATUSES.uncertain].includes(existing.status)) {
      throw fail('canonical_trainer_lateral_intent_conflict');
    }
    let stamped = existing;
    if (existing.status === STATUSES.uncertain) {
      assertEffectEvidence(existing, effectEvidence,
        [EVIDENCE_OUTCOMES.persisted, EVIDENCE_OUTCOMES.absent]);
      stamped = {
        ...existing,
        effectReceipt: effectEvidence.receipt,
        effectOutcome: effectEvidence.outcome
      };
    } else if (!existing.effectReceipt || existing.effectOutcome !== EVIDENCE_OUTCOMES.persisted) {
      throw fail('canonical_trainer_lateral_evidence_required');
    }
    const stamp = this.now() instanceof Date ? this.now().toISOString() : String(this.now());
    const tombstone = Object.freeze({
      ...stamped,
      status: STATUSES.reconciled,
      updatedAt: stamp
    });
    const intents = pruneOldestTombstones({
      ...current.lateralIntents,
      [operationId]: tombstone
    });
    const updated = await this.writeLateralIntents(chat, player, current, writerContext, intents);
    return {...updated, intent: tombstone, applied: true, reconciled: tombstone};
  }

  async transitionLateralIntent(chat, player, {operationId, writerContext, from, to,
    effectEvidence, allowedOutcomes}) {
    await this.assertActivated();
    const current = await this.readAccount(chat, player);
    if (!current) throw fail('canonical_trainer_missing');
    this.assertContext(chat, player, writerContext, current);
    this.assertInheritedContext(chat, player, current);
    const existing = current.lateralIntents[operationId];
    if (!existing) throw fail('canonical_trainer_lateral_intent_missing');
    if (existing.status === to) return {...current, intent: existing, applied: false};
    if (existing.status !== from) throw fail('canonical_trainer_lateral_intent_conflict');
    if (existing.originSaveId !== current.activeSaveId ||
        existing.fenceGeneration !== current.fenceGeneration) {
      throw fail('canonical_trainer_fence_conflict');
    }
    const stamp = this.now() instanceof Date ? this.now().toISOString() : String(this.now());
    let nextIntent = {...existing, status: to, updatedAt: stamp};
    if (to === STATUSES.confirmed) {
      assertEffectEvidence(existing, effectEvidence, allowedOutcomes || [EVIDENCE_OUTCOMES.persisted]);
      nextIntent = {
        ...nextIntent,
        effectReceipt: effectEvidence.receipt,
        effectOutcome: effectEvidence.outcome
      };
    }
    const intents = {...current.lateralIntents, [operationId]: nextIntent};
    const updated = await this.writeLateralIntents(chat, player, current, writerContext, intents);
    return {...updated, intent: nextIntent, applied: true};
  }

  async writeLateralIntents(chat, player, current, writerContext, intents) {
    const serialized = serializeIntentMap(intents);
    const parsed = parseIntentMap(serialized);
    const generation = current.generation + 1;
    const owner = writerContext.transitionOwner || '';
    const observe = async () => {
      const observed = await this.readAccount(chat, player);
      if (!observed) throw fail('canonical_trainer_missing');
      if (observed.generation === generation &&
          observed.fenceGeneration === writerContext.fenceGeneration &&
          observed.activeSaveId === writerContext.activeSaveId &&
          observed.transitionOwner === owner &&
          stableJson(observed.lateralIntents) === stableJson(parsed)) {
        return observed;
      }
      return null;
    };
    try {
      const result = await this.execute(
        `UPDATE ${this.accounts} SET generation = ?, lateral_intents = ?, updated_at = ?, writer_id = ? WHERE chat_id = ? AND player_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ? AND fence_generation = ? AND active_save_id = ? AND transition_owner = ?`,
        [generation, serialized, this.now(), this.writerId, chat, player,
          current.generation, current.stateHash, this.protocolVersion,
          writerContext.fenceGeneration, writerContext.activeSaveId, owner],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (result?.wasApplied?.()) {
        return {...current, generation, lateralIntents: parsed};
      }
    } catch (error) {
      let observed;
      try { observed = await observe(); }
      catch (reconciliationError) {
        throw Object.assign(fail('canonical_trainer_result_unknown'), {cause: error, reconciliationError});
      }
      if (observed) return observed;
      throw Object.assign(fail('canonical_trainer_result_unknown'), {cause: error});
    }
    const observed = await observe();
    if (observed) return observed;
    throw fail('canonical_trainer_cas_conflict');
  }

  async releaseTransition(chat, player, expected, operationId) {
    if (!validEffectId(operationId)) throw fail('invalid_canonical_transition');
    const effectId = `release_${operationId}`;
    const intent = hash({chat,player,operationId,fence:expected.fenceGeneration,save:expected.activeSaveId});
    const current = await this.readAccount(chat,player);
    if (current?.operationReceipts?.[effectId] === intent) return current;
    this.assertContext(chat,player,expected,current);
    if (current.transitionOwner !== operationId) throw fail('canonical_trainer_reservation_conflict');
    return this.changeFence(chat,player,current,{fenceGeneration:current.fenceGeneration,
      transitionOwner:''},effectId,intent);
  }

  async changeFence(chat, player, current, next, effectId, intent) {
    if (Object.keys(current.operationReceipts).length >= MAX_OPERATION_RECEIPTS) throw fail('canonical_trainer_receipt_limit');
    const receipts = {...current.operationReceipts,[effectId]:intent};
    const observe = async () => {
      const record = await this.readAccount(chat,player);
      if (record?.operationReceipts?.[effectId] === intent && record.fenceGeneration === next.fenceGeneration &&
          record.transitionOwner === next.transitionOwner) return record;
      throw fail('canonical_trainer_reservation_unresolved');
    };
    try {
      const result = await this.execute(
        `UPDATE ${this.accounts} SET generation = ?, fence_generation = ?, transition_owner = ?, operation_receipts = ?, updated_at = ?, writer_id = ? WHERE chat_id = ? AND player_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ? AND fence_generation = ? AND active_save_id = ? AND transition_owner = ?`,
        [current.generation + 1,next.fenceGeneration,next.transitionOwner,receipts,this.now(),this.writerId,chat,player,
          current.generation,current.stateHash,this.protocolVersion,current.fenceGeneration,current.activeSaveId,current.transitionOwner],
        {consistency:'QUORUM',serialConsistency:'SERIAL'});
      if (result?.wasApplied?.()) return {...current,...next,generation:current.generation + 1,operationReceipts:receipts};
    } catch (cause) {
      try { return await observe(); }
      catch (reconciliationError) { throw Object.assign(fail('canonical_trainer_result_unknown'),{cause,reconciliationError}); }
    }
    return observe();
  }

  async saveGroup(chat, before, after) {
    if (!object(before) || !object(after)) throw fail('invalid_canonical_state');
    const ids = [...new Set([...Object.keys(before), ...Object.keys(after)])];
    const changed = ids.filter(player => stableJson(before[player]) !== stableJson(after[player]));
    if (!changed.length) return await this.readGroup(chat);
    const scope = this.writerContext.getStore();
    if (!scope || scope.chat !== chat) throw fail('canonical_trainer_context_required');
    const confirmed = await this.readGroup(chat);
    for (const player of changed) {
      const inherited = scope.contexts[player];
      if (!inherited) throw fail('canonical_trainer_context_required');
      if (inherited.transitionOwner) throw fail('canonical_trainer_transition_reserved');
    }
    let written = 0;
    try {
      for (const player of changed) {
        const expectedState = before[player];
        const currentState = confirmed[player];
        if (stableJson(currentState) !== stableJson(expectedState)) {
          // A stale process snapshot must not be used as an update base.
          throw fail('canonical_trainer_cas_conflict');
        }
        if (!Object.hasOwn(after, player)) throw fail('canonical_trainer_delete_forbidden');
        const record = await this.readAccount(chat, player);
        if (!record) {
          // Provisioning an account requires a separate certified entry point.
          // Never create a writer context by observing a newly active save.
          throw fail('canonical_trainer_context_required');
        } else {
          this.assertContext(chat,player,scope.contexts[player],record);
          if (record.stateHash !== hash(expectedState)) throw fail('canonical_trainer_cas_conflict');
          await this.compareAndSet(chat, player, {...record,...scope.contexts[player]}, after[player]);
        }
        written++;
      }
    } catch (error) {
      if (written > 0) throw fail('canonical_trainer_partial_write');
      throw error;
    }
    return await this.readGroup(chat);
  }
}

module.exports = {CanonicalTrainerStore, PROTOCOL_VERSION, stableJson};
