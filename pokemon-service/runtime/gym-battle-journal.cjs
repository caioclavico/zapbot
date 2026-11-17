'use strict';

// Durable coordinator for a gym-battle result spanning Cassandra partitions.
// Target adapters MUST persist each effect and its operation receipt atomically
// (for example, with an LWT on the target record). This journal never treats a
// sequence of writes as a distributed transaction.
const {hash} = require('./restore-protocol.cjs');
const MAX_EFFECTS = 32;
const MAX_PLAN_BYTES = 512 * 1024;
const BUCKETS = 16;
const EFFECT_TYPES = new Set(['trainer-account', 'gym-occupancy', 'gym-statistics']);
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const stableJson = value => JSON.stringify(value, (_key, item) => {
  if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
  return Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]));
});
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const fail = code => Object.assign(new Error(code), {code});
const validId = value => typeof value === 'string' && value.length > 0 && value.length <= 200 &&
  !/[\u0000-\u001f\u007f]/.test(value);
const validOperation = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{8,100}$/.test(value);

class GymBattleJournal {
  constructor(execute, keyspace, {now = () => new Date(), writerId = 'pokemon-service'} = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_battle_journal_config');
    this.execute = execute;
    this.keyspace = keyspace;
    this.now = now;
    this.writerId = writerId;
    this.journals = `${keyspace}.pokemon_gym_battle_journal`;
    this.index = `${keyspace}.pokemon_gym_battle_index`;
  }

  bucket(chat) { return Number.parseInt(hash(chat).slice(0, 8), 16) % BUCKETS; }

  normalizePlan({chat, player, battleId, effects}) {
    if (!validId(chat) || !validId(player) || !validOperation(battleId) ||
        !Array.isArray(effects) || effects.length === 0 || effects.length > MAX_EFFECTS) {
      throw fail('invalid_battle_journal_plan');
    }
    const ids = new Set();
    const normalized = effects.map(effect => {
      if (!object(effect) || !validOperation(effect.id) || ids.has(effect.id) ||
          !EFFECT_TYPES.has(effect.type) || !object(effect.payload)) throw fail('invalid_battle_journal_effect');
      ids.add(effect.id);
      return {id: effect.id, type: effect.type, payload: effect.payload, payloadHash: hash(effect.payload), status: 'pending'};
    });
    const plan = {schemaVersion: 1, chat, player, battleId, effects: normalized};
    const planJson = stableJson(plan);
    if (Buffer.byteLength(planJson) > MAX_PLAN_BYTES) throw fail('battle_journal_plan_too_large');
    return {plan, planJson, planHash: hash(plan)};
  }

  validateJournal(value, chat, battleId) {
    if (!object(value) || value.schemaVersion !== 1 || value.chat !== chat || value.battleId !== battleId ||
        !validId(value.player) || !['prepared', 'applying', 'blocked', 'completed'].includes(value.state) ||
        !Array.isArray(value.effects) || value.effects.length === 0 || value.effects.length > MAX_EFFECTS ||
        !Number.isSafeInteger(value.generation) || value.generation < 1 ||
        value.planHash !== hash({schemaVersion: 1, chat, player: value.player, battleId, effects: value.effects.map(({id, type, payload, payloadHash}) => ({id, type, payload, payloadHash, status: 'pending'}))}) ||
        value.stateHash !== hash({...value, stateHash: undefined})) {
      throw fail('battle_journal_corrupt');
    }
    for (const effect of value.effects) {
      if (!object(effect) || !validOperation(effect.id) || !EFFECT_TYPES.has(effect.type) ||
          !object(effect.payload) || effect.payloadHash !== hash(effect.payload) ||
          !['pending', 'applied'].includes(effect.status)) throw fail('battle_journal_corrupt');
    }
    return value;
  }

  journalValue(raw, chat, battleId) {
    if (!raw) return null;
    let value;
    try { value = JSON.parse(raw); } catch { throw fail('battle_journal_corrupt'); }
    return this.validateJournal(value, chat, battleId);
  }

  async read(chat, battleId) {
    const found = row(await this.execute(
      `SELECT generation, state_hash, state_json FROM ${this.journals} WHERE chat_id = ? AND battle_id = ?`,
      [chat, battleId], {consistency: 'SERIAL'}));
    if (!found) return null;
    const value = this.journalValue(found.state_json, chat, battleId);
    if (Number(found.generation) !== value.generation || found.state_hash !== value.stateHash) throw fail('battle_journal_hash_mismatch');
    return value;
  }

  async write(chat, battleId, before, next) {
    const generation = (before?.generation || 0) + 1;
    const value = {...next, generation, updatedAt: this.now().toISOString(), writerId: this.writerId};
    value.stateHash = hash({...value, stateHash: undefined});
    const serialized = stableJson(value);
    let result;
    try {
      result = before
        ? await this.execute(
          `UPDATE ${this.journals} SET generation = ?, state_hash = ?, state_json = ?, updated_at = ?, writer_id = ? WHERE chat_id = ? AND battle_id = ? IF generation = ? AND state_hash = ?`,
          [generation, value.stateHash, serialized, this.now(), this.writerId, chat, battleId, before.generation, before.stateHash],
          {consistency: 'QUORUM', serialConsistency: 'SERIAL'})
        : await this.execute(
          `INSERT INTO ${this.journals} (chat_id, battle_id, generation, state_hash, state_json, updated_at, writer_id) VALUES (?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
          [chat, battleId, generation, value.stateHash, serialized, this.now(), this.writerId],
          {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
    } catch (error) {
      const observed = await this.read(chat, battleId);
      if (observed?.stateHash === value.stateHash) return observed;
      throw Object.assign(fail('battle_journal_result_unknown'), {cause: error});
    }
    if (result?.wasApplied?.()) return value;
    const observed = await this.read(chat, battleId);
    if (observed?.stateHash === value.stateHash) return observed;
    throw fail('battle_journal_cas_conflict');
  }

  async indexRow(chat, battleId) {
    return row(await this.execute(
      `SELECT plan_hash, state FROM ${this.index} WHERE bucket = ? AND chat_id = ? AND battle_id = ?`,
      [this.bucket(chat), chat, battleId], {consistency: 'SERIAL'}));
  }

  async setIndex(chat, battleId, planHash, state) {
    const before = await this.indexRow(chat, battleId);
    if (!before) {
      const result = await this.execute(
        `INSERT INTO ${this.index} (bucket, chat_id, battle_id, plan_hash, state, updated_at) VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
        [this.bucket(chat), chat, battleId, planHash, state, this.now()],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (result?.wasApplied?.()) return;
      const observed = await this.indexRow(chat, battleId);
      if (observed?.plan_hash === planHash && observed.state === state) return;
      throw fail('battle_journal_index_conflict');
    }
    if (before.plan_hash !== planHash) throw fail('battle_journal_index_conflict');
    if (before.state === state) return;
    if (before.state === 'completed' && state !== 'completed') throw fail('battle_journal_completed_immutable');
    const result = await this.execute(
      `UPDATE ${this.index} SET state = ?, updated_at = ? WHERE bucket = ? AND chat_id = ? AND battle_id = ? IF plan_hash = ? AND state = ?`,
      [state, this.now(), this.bucket(chat), chat, battleId, planHash, before.state],
      {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
    if (!result?.wasApplied?.()) {
      const observed = await this.indexRow(chat, battleId);
      if (observed?.plan_hash === planHash && observed.state === state) return;
      throw fail('battle_journal_index_conflict');
    }
  }

  async begin(input) {
    const {plan, planHash} = this.normalizePlan(input);
    // Publish discovery first. If the journal write is interrupted, startup sees
    // the orphan index entry and fails closed instead of overlooking the battle.
    const priorIndex = await this.indexRow(input.chat, input.battleId);
    if (priorIndex) {
      if (priorIndex.plan_hash !== planHash) throw fail('battle_journal_index_conflict');
      const priorJournal = await this.read(input.chat, input.battleId);
      if (!priorJournal) throw fail('battle_journal_orphan_index');
      if (priorIndex.state === 'completed' && priorJournal.state !== 'completed') throw fail('battle_journal_index_state_conflict');
      return priorJournal;
    }
    await this.setIndex(input.chat, input.battleId, planHash, 'pending');
    const existing = await this.read(input.chat, input.battleId);
    if (existing) {
      if (existing.planHash !== planHash || stableJson(existing.effects.map(({id, type, payload}) => ({id, type, payload}))) !==
          stableJson(plan.effects.map(({id, type, payload}) => ({id, type, payload})))) throw fail('battle_journal_plan_conflict');
      return existing;
    }
    const createdAt = this.now().toISOString();
    const value = {schemaVersion: 1, chat: input.chat, player: input.player, battleId: input.battleId,
      planHash, effects: plan.effects, state: 'prepared', generation: 1, createdAt,
      updatedAt: createdAt, writerId: this.writerId};
    value.stateHash = hash({...value, stateHash: undefined});
    const stored = await this.write(input.chat, input.battleId, null, value);
    await this.setIndex(input.chat, input.battleId, planHash, stored.state === 'completed' ? 'completed' : 'pending');
    return stored;
  }

  async update(chat, battleId, transform) {
    for (let attempt = 0; attempt < 12; attempt++) {
      const before = await this.read(chat, battleId);
      if (!before) throw fail('battle_journal_missing');
      const next = transform(before);
      if (next === before) return before;
      try { return await this.write(chat, battleId, before, next); }
      catch (error) { if (error?.code !== 'battle_journal_cas_conflict') throw error; }
    }
    throw fail('battle_journal_contention');
  }

  async apply(chat, battleId, adapters) {
    let journal = await this.read(chat, battleId);
    if (!journal) throw fail('battle_journal_missing');
    if (journal.state === 'completed') return journal;
    if (journal.state === 'prepared' || journal.state === 'blocked') {
      journal = await this.update(chat, battleId, old => ({...old, state: 'applying'}));
    }
    for (const effect of journal.effects) {
      if (effect.status === 'applied') continue;
      const adapter = adapters?.[effect.type];
      if (!adapter || typeof adapter.applyOnce !== 'function' || typeof adapter.hasReceipt !== 'function') {
        throw fail('battle_effect_adapter_missing');
      }
      try {
        await adapter.applyOnce({chat, player: journal.player, battleId, effectId: effect.id, payload: effect.payload});
        // Receipt lookup is target-specific. Keep the immutable payload and
        // payload hash attached to both apply and verification; adapters such
        // as gym statistics use payload.targetId to read their own row.
        if (!await adapter.hasReceipt({chat, player: journal.player, battleId,
          effectId: effect.id, payload: effect.payload, payloadHash: effect.payloadHash,
          effectType: effect.type})) {
          throw fail('battle_effect_receipt_unconfirmed');
        }
      } catch (error) {
        // Preserve the pending intent. A retry/restart may only proceed through
        // the same idempotent target adapter; no effect is inferred from memory.
        try { await this.update(chat, battleId, old => ({...old, state: 'blocked'})); } catch { /* durable pending index remains */ }
        throw Object.assign(fail('battle_effect_unresolved'), {
          effectId: effect.id,
          effectType: effect.type,
          payloadHash: effect.payloadHash,
          causeCode: error?.code || undefined,
          cause: error
        });
      }
      journal = await this.update(chat, battleId, old => ({...old, effects: old.effects.map(item =>
        item.id === effect.id ? {...item, status: 'applied'} : item)}));
    }
    journal = await this.update(chat, battleId, old => ({...old, state: 'completed'}));
    await this.setIndex(chat, battleId, journal.planHash, 'completed');
    return journal;
  }

  async reconcileStartup(adapters, {chat = null, battleId = null} = {}) {
    const pending = [];
    const unresolved = [];
    const inspect = item => {
      if (!validId(item.chat_id) || !validOperation(item.battle_id) ||
          this.bucket(item.chat_id) !== item.bucket || !/^[a-f0-9]{64}$/.test(item.plan_hash || '') ||
          !['pending', 'completed'].includes(item.state)) {
        unresolved.push({chatHash: hash(String(item.chat_id)), battleHash: hash(String(item.battle_id)), code: 'battle_journal_index_corrupt'});
        return;
      }
      if (item.state !== 'completed') pending.push(item);
    };
    if ((chat == null) !== (battleId == null) || (chat != null && (!validId(chat) || !validOperation(battleId)))) {
      throw fail('invalid_battle_journal_scope');
    }
    if (chat != null) {
      const item = await this.indexRow(chat, battleId);
      if (!item) throw fail('battle_journal_missing');
      inspect({...item, chat_id: chat, battle_id: battleId, bucket: this.bucket(chat)});
    } else for (let bucket = 0; bucket < BUCKETS; bucket++) {
      const result = await this.execute(
        `SELECT chat_id, battle_id, plan_hash, state FROM ${this.index} WHERE bucket = ?`,
        [bucket], {consistency: 'QUORUM'});
      for (const item of result?.rows || []) {
        inspect({...item, bucket});
      }
    }
    for (const item of pending) {
      try {
        const journal = await this.read(item.chat_id, item.battle_id);
        if (!journal || journal.planHash !== item.plan_hash) throw fail('battle_journal_orphan_or_mismatch');
        if (journal.state === 'completed') {
          await this.setIndex(item.chat_id, item.battle_id, journal.planHash, 'completed');
        } else {
          await this.apply(item.chat_id, item.battle_id, adapters);
        }
      } catch (error) {
        const causeMessage = error?.cause?.message;
        const diagnosticCode = error?.causeCode || error?.cause?.code ||
          (typeof causeMessage === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(causeMessage) ? causeMessage : null) ||
          error?.code || 'battle_reconciliation_failed';
        unresolved.push({chatHash: hash(item.chat_id), battleHash: hash(item.battle_id), code: diagnosticCode,
          causeCode: error?.causeCode || error?.cause?.code || undefined,
          causeName: error?.cause?.name || undefined});
      }
    }
    if (unresolved.length) throw Object.assign(fail('battle_startup_reconciliation_blocked'), {unresolved});
    return {ready: true, reconciled: pending.length};
  }
}

module.exports = {GymBattleJournal, EFFECT_TYPES, stableJson};
