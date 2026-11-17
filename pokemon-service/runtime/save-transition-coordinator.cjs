'use strict';

// Durable coordination primitives for a future save transition. These do not
// activate a slot or mutate gyms by themselves. Every data mutation still
// requires a current fence and an LWT against the exact prior row value.
const {RestoreError, hash} = require('./restore-protocol.cjs');

const MODULES = new Set([
  'pokemon-save-transition-locks',
  'pokemon-save-transition-journal',
  'pokemon-save-transition-index'
]);
const DATA_MODULES = new Set([
  'treinador', 'ginasios', 'ginasios-estatisticas', 'loja', 'raids',
  // These names are the durable module identifiers registered by core.cljs.
  // Keeping this allow-list exact avoids a coordinator inspection silently
  // reading an obsolete module while the real activity remains active.
  'pokemon-batalhas-ativas', 'pokemon-cacadas-ativas', 'pokemon-propostas-troca',
  'pokemon-remocoes-pendentes'
]);
const PHASES = ['preparacao', 'reserva', 'backup', 'encerramento', 'ativacao', 'verificacao', 'conclusao'];
const MAX_OPERATIONS = 128;
const MAX_INDEXED_OPERATIONS = 2048;
const INDEX_PARTITION = 'active';
const MAX_RESOURCES = 32;
const MAX_CAS = 12;
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const json = value => JSON.stringify(value);
const resultRow = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const validPart = value => typeof value === 'string' && value.length > 0 && value.length <= 200 && !/[\u0000-\u001f\u007f]/.test(value);
const validOperation = value => typeof value === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(value);

function normalizeResource(resource) {
  if (!object(resource) || !['account', 'gym'].includes(resource.type) || !validPart(resource.id)) {
    throw new RestoreError('invalid_transition_resource');
  }
  return {type: resource.type, id: resource.id};
}

class SaveTransitionCoordinator {
  constructor(execute, keyspace, {now = () => new Date().toISOString(), enabled = false,
    legacyJournalCoverageVerified = false, writerProtocolCoverageVerified = false} = {}) {
    if (!/^[a-z][a-z0-9_]*$/.test(keyspace) || typeof execute !== 'function') throw new RestoreError('invalid_transition_config');
    this.execute = execute;
    this.table = `${keyspace}.estado_particionado`;
    this.now = now;
    this.enabled = enabled === true;
    this.legacyJournalCoverageVerified = legacyJournalCoverageVerified === true;
    // The current gym, battle and account mutation paths still contain legacy
    // snapshot writers. A lock checked separately from those writes is not a
    // fence, so transitions fail closed until every domain writer is certified.
    this.writerProtocolCoverageVerified = writerProtocolCoverageVerified === true;
    this.startupReconciled = false;
  }

  requireEnabled() {
    if (!this.enabled) throw new RestoreError('save_transition_disabled');
    if (!this.writerProtocolCoverageVerified) throw new RestoreError('save_transition_writer_coverage_unverified');
  }

  identity(chat, player) {
    if (!validPart(chat) || !validPart(player)) throw new RestoreError('invalid_identity');
    return hash([chat, player]);
  }

  resourcePartition(chat, resource) {
    return hash([chat, resource.type, resource.id]);
  }

  async read(module, partition) {
    if (!MODULES.has(module) && !DATA_MODULES.has(module)) throw new RestoreError('invalid_transition_module');
    const result = await this.execute(
      `SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,
      [module, partition], {prepare: true, consistency: 'SERIAL'}
    );
    const row = resultRow(result);
    if (!row?.valor) return null;
    try { return {raw: row.valor, value: JSON.parse(row.valor)}; }
    catch { throw new RestoreError('transition_state_corrupt'); }
  }

  async cas(module, partition, expected, next) {
    if (!MODULES.has(module)) throw new RestoreError('invalid_transition_module');
    const serialized = json(next);
    if (expected === null) {
      const result = await this.execute(
        `INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
        [module, partition, serialized], {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'}
      );
      return Boolean(result?.wasApplied?.());
    }
    const result = await this.execute(
      `UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,
      [serialized, module, partition, expected],
      {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'}
    );
    return Boolean(result?.wasApplied?.());
  }

  async change(module, partition, transform, validator = () => {}) {
    for (let retry = 0; retry < MAX_CAS; retry++) {
      const before = await this.read(module, partition);
      const next = transform(before?.value ?? null);
      validator(next);
      try {
        if (await this.cas(module, partition, before?.raw ?? null, next)) return next;
      } catch (error) {
        // A lost LWT response is resolved by a SERIAL read below. If the
        // expected state is not visible, retry only from the newly observed row.
        const observed = await this.read(module, partition);
        if (observed && hash(observed.value) === hash(next)) return observed.value;
        if (!observed && before === null) continue;
        if (error instanceof RestoreError) throw error;
      }
      const observed = await this.read(module, partition);
      if (observed && hash(observed.value) === hash(next)) return observed.value;
    }
    throw new RestoreError('transition_cas_contention');
  }

  validateIndex(index) {
    if (!object(index) || index.schemaVersion !== 1 || index.coverageVerified !== true || !object(index.operations) ||
        Object.keys(index.operations).length > MAX_INDEXED_OPERATIONS) {
      throw new RestoreError('transition_index_corrupt');
    }
    for (const [reference, item] of Object.entries(index.operations)) {
      if (reference !== hash([item?.identityHash, item?.operationId]) ||
          !/^[a-f0-9]{64}$/.test(item?.identityHash || '') || !validOperation(item?.operationId) ||
          !/^[a-f0-9]{64}$/.test(item?.chatHash || '') || !/^[a-f0-9]{64}$/.test(item?.playerHash || '') ||
          !Number.isFinite(Date.parse(item?.createdAt || ''))) {
        throw new RestoreError('transition_index_corrupt');
      }
    }
  }

  async registerIndexOperation(identity, chat, player, operationId, createdAt) {
    const reference = hash([identity, operationId]);
    await this.change('pokemon-save-transition-index', INDEX_PARTITION, old => {
      const index = old ?? {schemaVersion: 1, coverageVerified: true, operations: {}};
      this.validateIndex(index);
      const item = {identityHash: identity, operationId, chatHash: hash(chat),
        playerHash: hash(player), createdAt};
      const prior = index.operations[reference];
      if (prior && hash(prior) !== hash(item)) throw new RestoreError('transition_index_conflict');
      if (!prior && Object.keys(index.operations).length >= MAX_INDEXED_OPERATIONS) {
        throw new RestoreError('transition_index_full');
      }
      return prior ? index : {...index, operations: {...index.operations, [reference]: item}};
    }, value => this.validateIndex(value));
  }

  async removeIndexOperation(identity, operationId) {
    const reference = hash([identity, operationId]);
    await this.change('pokemon-save-transition-index', INDEX_PARTITION, old => {
      const index = old ?? {schemaVersion: 1, coverageVerified: true, operations: {}};
      this.validateIndex(index);
      if (!index.operations[reference]) return index;
      const operations = {...index.operations};
      delete operations[reference];
      return {...index, operations};
    }, value => this.validateIndex(value));
  }

  async reconcileStartup() {
    const indexRow = await this.read('pokemon-save-transition-index', INDEX_PARTITION);
    if (!indexRow) {
      if (!this.legacyJournalCoverageVerified) throw new RestoreError('transition_index_coverage_unverified');
      await this.change('pokemon-save-transition-index', INDEX_PARTITION,
        old => old ?? {schemaVersion: 1, coverageVerified: true, operations: {}},
        value => this.validateIndex(value));
    }
    const current = (await this.read('pokemon-save-transition-index', INDEX_PARTITION)).value;
    this.validateIndex(current);
    const blocked = [];
    for (const [reference, item] of Object.entries(current.operations)) {
      const journalRow = await this.read('pokemon-save-transition-journal', item.identityHash);
      const journal = journalRow?.value;
      if (!journal) { blocked.push('missing_journal'); continue; }
      this.validateJournal(journal);
      const operation = journal.operations[item.operationId];
      if (!operation || operation.chatHash !== item.chatHash || operation.playerHash !== item.playerHash) {
        blocked.push('journal_identity_mismatch'); continue;
      }
      if (operation.phase !== 'conclusao' || operation.effects.some(effect => effect.status !== 'applied')) {
        blocked.push('operation_incomplete'); continue;
      }
      let locksSafe = true;
      for (const fence of Object.values(operation.fences)) {
        if (!fence?.resourcePartition || !Number.isSafeInteger(fence.generation)) { locksSafe = false; break; }
        const lockRow = await this.read('pokemon-save-transition-locks', fence.resourcePartition);
        const lock = lockRow?.value;
        if (lock?.owner === item.operationId) {
          if (lock.generation !== fence.generation || lock.resourceHash !== hash(fence.resource)) {
            locksSafe = false; break;
          }
          const released = {...lock, owner: null, updatedAt: this.now()};
          if (!await this.cas('pokemon-save-transition-locks', fence.resourcePartition, lockRow.raw, released)) {
            const observed = await this.read('pokemon-save-transition-locks', fence.resourcePartition);
            if (observed?.value?.owner === item.operationId) { locksSafe = false; break; }
          }
        } else if (lock?.owner !== null && lock?.owner !== undefined &&
                   lock.generation <= fence.generation) {
          locksSafe = false; break;
        }
      }
      if (locksSafe) await this.removeIndexOperation(item.identityHash, item.operationId);
      else blocked.push('lock_unresolved');
    }
    if (blocked.length) throw new RestoreError('transition_startup_unresolved', {blocked: blocked.length});
    this.startupReconciled = true;
    return {ready: true, pending: 0};
  }

  validateJournal(journal) {
    if (!object(journal) || journal.schemaVersion !== 1 || !object(journal.operations) ||
        Object.keys(journal.operations).length > MAX_OPERATIONS) throw new RestoreError('transition_journal_corrupt');
    for (const [id, operation] of Object.entries(journal.operations)) {
      if (!validOperation(id) || !object(operation) || !PHASES.includes(operation.phase) ||
          !Array.isArray(operation.resources) || operation.resources.length > MAX_RESOURCES ||
          !object(operation.fences) || !Array.isArray(operation.effects) ||
          operation.effects.length > MAX_RESOURCES * 8 || !Number.isFinite(Date.parse(operation.createdAt))) {
        throw new RestoreError('transition_journal_corrupt');
      }
      if (operation.resources.some(resource => {
        try { normalizeResource(resource); return false; } catch { return true; }
      })) throw new RestoreError('transition_journal_corrupt');
      const effectIds = new Set();
      for (const effect of operation.effects) {
        if (!object(effect) || !validOperation(effect.id) || effectIds.has(effect.id) ||
            !['treinador', 'ginasios', 'ginasios-estatisticas', 'loja', 'raids',
              'batalhas-ativas', 'cacadas-ativas', 'pokemon-propostas-troca',
              'pokemon-remocoes-pendentes'].includes(effect.module) ||
            typeof effect.partitionHash !== 'string' || !/^[a-f0-9]{64}$/.test(effect.partitionHash) ||
            ![effect.expectedHash, effect.nextHash].every(value => value === null ||
              (typeof value === 'string' && /^[a-f0-9]{64}$/.test(value))) ||
            !['pending', 'applied'].includes(effect.status) || !Number.isFinite(Date.parse(effect.updatedAt))) {
          throw new RestoreError('transition_journal_corrupt');
        }
        effectIds.add(effect.id);
      }
    }
  }

  async begin({chat, player, operationId, resources}) {
    this.requireEnabled();
    if (!this.startupReconciled) await this.reconcileStartup();
    const identity = this.identity(chat, player);
    if (!validOperation(operationId) || !Array.isArray(resources) || !resources.length || resources.length > MAX_RESOURCES) {
      throw new RestoreError('invalid_transition');
    }
    const normalized = resources.map(normalizeResource)
      .sort((a, b) => `${a.type}:${a.id}`.localeCompare(`${b.type}:${b.id}`));
    const keys = normalized.map(item => `${item.type}:${item.id}`);
    if (new Set(keys).size !== keys.length || !normalized.some(item => item.type === 'account' && item.id === player)) {
      throw new RestoreError('invalid_transition_resources');
    }
    const createdAt = this.now();
    // Publish discoverability first. If the per-identity journal write fails,
    // startup sees the orphan and stays blocked instead of overlooking it.
    try {
      await this.registerIndexOperation(identity, chat, player, operationId, createdAt);
      await this.change('pokemon-save-transition-journal', identity, old => {
        const journal = old ?? {schemaVersion: 1, operations: {}};
        this.validateJournal(journal);
        const prior = journal.operations[operationId];
        if (prior) {
          if (prior.chatHash !== hash(chat) || prior.playerHash !== hash(player) || hash(prior.resources) !== hash(normalized)) {
            throw new RestoreError('transition_id_conflict');
          }
          return journal;
        }
        if (Object.keys(journal.operations).length >= MAX_OPERATIONS) throw new RestoreError('transition_journal_full');
        return {schemaVersion: 1, operations: {...journal.operations, [operationId]: {
          chatHash: hash(chat), playerHash: hash(player), phase: 'preparacao',
          resources: normalized, fences: {}, createdAt, updatedAt: createdAt, effects: []
        }}};
      }, value => this.validateJournal(value));
    } catch (error) {
      // Keep this process from starting more protected work after an index/journal gap.
      this.startupReconciled = false;
      throw error;
    }
    return this.inspect(chat, player, operationId);
  }

  async journal(chat, player) {
    const identity = this.identity(chat, player);
    const row = await this.read('pokemon-save-transition-journal', identity);
    const value = row?.value ?? {schemaVersion: 1, operations: {}};
    this.validateJournal(value);
    return {identity, row, value};
  }

  async acquireResource(chat, identity, operationId, resource) {
    const partition = this.resourcePartition(chat, resource);
    const lock = await this.change('pokemon-save-transition-locks', partition, old => {
      const prior = old ?? {schemaVersion: 1, generation: 0, owner: null, resourceHash: hash(resource)};
      if (!object(prior) || prior.schemaVersion !== 1 || !Number.isSafeInteger(prior.generation) ||
          prior.generation < 0 || prior.resourceHash !== hash(resource) ||
          (prior.owner !== null && prior.owner !== operationId)) throw new RestoreError('transition_resource_locked');
      if (prior.owner === operationId) return prior;
      if (prior.generation >= Number.MAX_SAFE_INTEGER) throw new RestoreError('transition_generation_exhausted');
      return {...prior, generation: prior.generation + 1, owner: operationId, updatedAt: this.now()};
    }, value => {
      if (!object(value) || value.owner !== operationId || value.resourceHash !== hash(resource)) {
        throw new RestoreError('transition_lock_invalid');
      }
    });
    return {identityHash: identity, resource, resourcePartition: partition,
      generation: lock.generation, owner: operationId};
  }

  async setJournalFence(chat, player, operationId, fence) {
    const {identity} = await this.journal(chat, player);
    await this.change('pokemon-save-transition-journal', identity, old => {
      this.validateJournal(old);
      const operation = old.operations[operationId];
      if (!operation || operation.phase !== 'reserva') throw new RestoreError('transition_phase_invalid');
      const key = `${fence.resource.type}:${fence.resource.id}`;
      const prior = operation.fences[key];
      if (prior && hash(prior) !== hash(fence)) throw new RestoreError('transition_fence_conflict');
      return {...old, operations: {...old.operations, [operationId]: {
        ...operation, fences: {...operation.fences, [key]: fence}, updatedAt: this.now()
      }}};
    }, value => this.validateJournal(value));
  }

  async reserve(chat, player, operationId, {trainerStore, expectedAccount} = {}) {
    this.requireEnabled();
    const {identity} = await this.journal(chat, player);
    await this.change('pokemon-save-transition-journal', identity, old => {
      this.validateJournal(old);
      const operation = old.operations[operationId];
      if (!operation || !['preparacao', 'reserva'].includes(operation.phase)) throw new RestoreError('transition_phase_invalid');
      return {...old, operations: {...old.operations, [operationId]: {
        ...operation, phase: 'reserva', updatedAt: this.now()
      }}};
    }, value => this.validateJournal(value));
    const {value} = await this.journal(chat, player);
    const operation = value.operations[operationId];
    const acquired = [];
    // The resource ordering is deterministic, preventing lock-order deadlocks.
    try {
      for (const resource of operation.resources) {
        const fence = await this.acquireResource(chat, identity, operationId, resource);
        acquired.push(fence);
        await this.setJournalFence(chat, player, operationId, fence);
      }
      // Optional for generic resources; mandatory for playable activation.
      // Returning from this call is the account reservation barrier only when
      // the trainer row itself has advanced its fence by LWT.
      if (trainerStore) await trainerStore.reserveTransition(chat,player,expectedAccount,operationId);
    } catch (error) {
      // Do not silently release a partially acquired operation. The durable
      // journal and owner IDs remain for explicit reconciliation after restart.
      this.startupReconciled = false;
      throw error;
    }
    return this.inspect(chat, player, operationId);
  }

  async inspect(chat, player, operationId) {
    const {identity, value} = await this.journal(chat, player);
    const operation = value.operations[operationId];
    if (!operation || operation.chatHash !== hash(chat) || operation.playerHash !== hash(player)) return null;
    const resources = [];
    for (const resource of operation.resources) {
      const partition = this.resourcePartition(chat, resource);
      const lock = await this.read('pokemon-save-transition-locks', partition);
      const key = `${resource.type}:${resource.id}`;
      const recorded = operation.fences[key];
      const owned = lock?.value?.owner === operationId && lock.value.resourceHash === hash(resource);
      resources.push({resource, owned, generation: owned ? lock.value.generation : null,
        journalFenceMatches: Boolean(owned && recorded && recorded.generation === lock.value.generation),
        fence: owned && recorded && recorded.generation === lock.value.generation ? recorded : null});
    }
    return {operationId, identityHash: identity, phase: operation.phase, resources,
      effects: [...operation.effects], createdAt: operation.createdAt};
  }

  async advance(chat, player, operationId, nextPhase, {effect} = {}) {
    this.requireEnabled();
    const current = await this.inspect(chat, player, operationId);
    if (current?.phase === nextPhase) {
      if (current.effects.some(item => item.status !== 'applied')) throw new RestoreError('transition_effect_unresolved');
      return current;
    }
    const currentIndex = PHASES.indexOf(current?.phase);
    const nextIndex = PHASES.indexOf(nextPhase);
    if (nextIndex < 0 || nextIndex !== currentIndex + 1) throw new RestoreError('transition_phase_invalid');
    const snapshot = await this.inspect(chat, player, operationId);
    if (snapshot.resources.some(item => !item.owned || !item.journalFenceMatches)) {
      throw new RestoreError('transition_fence_unresolved');
    }
    if (snapshot.effects.some(item => item.status !== 'applied')) {
      throw new RestoreError('transition_effect_unresolved');
    }
    const {identity} = await this.journal(chat, player);
    await this.change('pokemon-save-transition-journal', identity, old => {
      this.validateJournal(old);
      const operation = old.operations[operationId];
      if (!operation || PHASES.indexOf(operation.phase) + 1 !== nextIndex) throw new RestoreError('transition_phase_conflict');
      const effects = effect ? [...operation.effects, effect] : operation.effects;
      return {...old, operations: {...old.operations, [operationId]: {
        ...operation, phase: nextPhase, effects, updatedAt: this.now()
      }}};
    }, value => this.validateJournal(value));
    return this.inspect(chat, player, operationId);
  }

  async assertFence(chat, operationId, fence) {
    const resource = normalizeResource(fence?.resource);
    const partition = this.resourcePartition(chat, resource);
    const row = await this.read('pokemon-save-transition-locks', partition);
    const lock = row?.value;
    if (!lock || lock.owner !== operationId || lock.generation !== fence.generation ||
        lock.resourceHash !== hash(resource) || fence.owner !== operationId || fence.resourcePartition !== partition ||
        typeof fence.identityHash !== 'string') {
      throw new RestoreError('stale_transition_fence');
    }
    const journalRow = await this.read('pokemon-save-transition-journal', fence.identityHash);
    const journal = journalRow?.value;
    this.validateJournal(journal);
    const operation = journal.operations[operationId];
    if (!operation || operation.chatHash !== hash(chat) || !['backup', 'encerramento', 'ativacao', 'verificacao'].includes(operation.phase) ||
        !operation.fences[`${resource.type}:${resource.id}`] ||
        operation.fences[`${resource.type}:${resource.id}`].generation !== fence.generation) {
      throw new RestoreError('transition_phase_invalid');
    }
    return true;
  }

  async writeFenced({chat, operationId, fence, effectId, module, partition, expectedValue, nextValue}) {
    this.requireEnabled();
    if (!['treinador', 'ginasios', 'ginasios-estatisticas', 'loja', 'raids',
      'batalhas-ativas', 'cacadas-ativas', 'pokemon-propostas-troca', 'pokemon-remocoes-pendentes'].includes(module) ||
      !validPart(partition) || !validOperation(effectId)) throw new RestoreError('invalid_fenced_write');
    await this.assertFence(chat, operationId, fence);
    const expectedHash = expectedValue === null ? null : hash(expectedValue);
    const effect = {id: effectId, module, partitionHash: hash(partition), expectedHash,
      nextHash: hash(nextValue), status: 'pending', updatedAt: this.now()};
    await this.change('pokemon-save-transition-journal', fence.identityHash, old => {
      this.validateJournal(old);
      const operation = old.operations[operationId];
      if (!operation) throw new RestoreError('transition_operation_missing');
      const prior = operation.effects.find(item => item.id === effectId);
      if (prior) {
        if (prior.module !== effect.module || prior.partitionHash !== effect.partitionHash ||
            prior.expectedHash !== effect.expectedHash || prior.nextHash !== effect.nextHash) {
          throw new RestoreError('transition_effect_conflict');
        }
        return old;
      }
      if (operation.effects.length >= MAX_RESOURCES * 8) throw new RestoreError('transition_effect_limit');
      return {...old, operations: {...old.operations, [operationId]: {
        ...operation, effects: [...operation.effects, effect], updatedAt: this.now()
      }}};
    }, value => this.validateJournal(value));
    return this.reconcileEffect({chat, operationId, fence, effectId, module, partition, expectedValue, nextValue});
  }

  async reconcileEffect({chat, operationId, fence, effectId, module, partition, expectedValue, nextValue}) {
    this.requireEnabled();
    await this.assertFence(chat, operationId, fence);
    const journalRow = await this.read('pokemon-save-transition-journal', fence.identityHash);
    const operation = journalRow?.value?.operations?.[operationId];
    const effect = operation?.effects?.find(item => item.id === effectId);
    if (!effect || effect.module !== module || effect.partitionHash !== hash(partition) ||
        effect.expectedHash !== (expectedValue === null ? null : hash(expectedValue)) || effect.nextHash !== hash(nextValue)) {
      throw new RestoreError('transition_effect_conflict');
    }
    if (effect.status === 'applied') return true;
    const before = await this.read(module, partition);
    const observed = before?.value ?? null;
    if (hash(observed) === hash(nextValue)) {
      await this.markEffectApplied(fence.identityHash, operationId, effectId);
      return true;
    }
    if (hash(observed) !== hash(expectedValue)) throw new RestoreError('transition_effect_uncertain');
    await this.assertFence(chat, operationId, fence);
    try {
      if (expectedValue === null) {
        await this.execute(`INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
          [module, partition, json(nextValue)], {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      } else {
        await this.execute(`UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,
          [json(nextValue), module, partition, before.raw], {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      }
    } catch {
      // Resolve the result through a SERIAL read; a timeout is never success.
    }
    const confirmed = await this.read(module, partition);
    if (hash(confirmed?.value ?? null) === hash(nextValue)) {
      await this.markEffectApplied(fence.identityHash, operationId, effectId);
      return true;
    }
    if (hash(confirmed?.value ?? null) === hash(expectedValue)) throw new RestoreError('transition_effect_not_applied');
    throw new RestoreError('transition_effect_uncertain');
  }

  async markEffectApplied(identity, operationId, effectId) {
    await this.change('pokemon-save-transition-journal', identity, old => {
      this.validateJournal(old);
      const operation = old.operations[operationId];
      if (!operation) throw new RestoreError('transition_operation_missing');
      const effects = operation.effects.map(effect => effect.id === effectId
        ? {...effect, status: 'applied', updatedAt: this.now()} : effect);
      return {...old, operations: {...old.operations, [operationId]: {...operation, effects, updatedAt: this.now()}}};
    }, value => this.validateJournal(value));
  }

  async release(chat, player, operationId) {
    this.requireEnabled();
    const {identity, value} = await this.journal(chat, player);
    const operation = value.operations[operationId];
    if (!operation) return false;
    if (operation.phase !== 'conclusao') throw new RestoreError('transition_not_complete');
    if (operation.effects.some(item => item.status !== 'applied')) throw new RestoreError('transition_effect_unresolved');
    const released = [];
    for (const resource of operation.resources) {
      const partition = this.resourcePartition(chat, resource);
      for (let attempt = 0; attempt < MAX_CAS; attempt++) {
        const before = await this.read('pokemon-save-transition-locks', partition);
        if (!before || before.value.owner !== operationId) break;
        const next = {...before.value, owner: null, updatedAt: this.now()};
        if (await this.cas('pokemon-save-transition-locks', partition, before.raw, next)) {
          released.push(resource);
          break;
        }
      }
      const observed = await this.read('pokemon-save-transition-locks', partition);
      if (observed?.value.owner === operationId) throw new RestoreError('transition_release_uncertain');
    }
    await this.change('pokemon-save-transition-journal', identity, old => {
      this.validateJournal(old);
      const current = old.operations[operationId];
      if (!current) return old;
      return {...old, operations: {...old.operations, [operationId]: {
        ...current, phase: current.phase === 'conclusao' ? 'conclusao' : 'reserva',
        fences: {}, updatedAt: this.now()
      }}};
    }, next => this.validateJournal(next));
    await this.removeIndexOperation(identity, operationId);
    return released.length;
  }
}

module.exports = {SaveTransitionCoordinator, MODULES, PHASES, normalizeResource};
