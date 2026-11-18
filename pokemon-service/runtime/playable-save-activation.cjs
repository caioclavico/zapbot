'use strict';

// Internal-only MVP for switching a canonical trainer between independently
// persisted playable slots.  It intentionally has no command/HTTP export.
// The caller must prove writer coverage before this class can be constructed.
const {RestoreError, receiptRecord, hash} = require('./restore-protocol.cjs');

const MODULES = new Set([
  'pokemon-playable-save-state',
  'pokemon-playable-save-index',
  'pokemon-active-save-pointer',
  'pokemon-playable-save-operations',
  'pokemon-playable-save-operation-index'
]);
const MAX_BYTES = 8 * 1024 * 1024;
const MAX_RETRIES = 12;
const MAX_SLOTS = 8;
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const json = value => JSON.stringify(value);
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const fail = code => { throw new RestoreError(code); };
const validPart = value => typeof value === 'string' && value.length > 0 && value.length <= 200 && !/[\u0000-\u001f\u007f]/.test(value);
const validSlot = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const validOperation = value => typeof value === 'string' && /^[A-Za-z0-9_-]{8,80}$/.test(value);

function withoutGlobalReceipts(account) {
  if (!object(account)) fail('playable_save_invalid_account');
  const copy = structuredClone(account);
  delete copy['adventure-entregas'];
  if (Buffer.byteLength(json(copy)) > MAX_BYTES) fail('playable_save_too_large');
  return copy;
}

function withGlobalReceipts(account, receipts) {
  const copy = withoutGlobalReceipts(account);
  return {...copy, 'adventure-entregas': structuredClone(receipts)};
}

// The ledger belongs to the player globally, while a canonical active save
// only records the receipts that were present when that save was last active.
// Hydration must therefore prove coverage, not equality: every receipt in the
// active state must be in the monotonic ledger with the same delivered entity.
// Extra ledger entries may belong to another inactive save and must never be
// copied into, or removed from, the active state during hydration.
function receiptsCoveredByLedger(value, ledger) {
  const current = receiptRecord(value);
  if (!current.ok || !object(ledger)) return false;
  return Object.entries(current.receipts).every(([eventId, deliveredId]) =>
    Object.hasOwn(ledger, eventId) && ledger[eventId] === deliveredId);
}

class PlayableSaveActivation {
  constructor(execute, keyspace, {slotStore, trainerStore, coordinator, eligibility = null, now = () => new Date().toISOString(), enabled = false} = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace) || !slotStore || !trainerStore || !coordinator) {
      fail('playable_save_invalid_config');
    }
    if (enabled !== true) fail('playable_save_activation_disabled');
    this.execute = execute;
    this.table = `${keyspace}.estado_particionado`;
    this.slotStore = slotStore;
    this.trainerStore = trainerStore;
    this.coordinator = coordinator;
    this.eligibility = eligibility;
    this.now = now;
  }

  async assertActivityEligible(chat, player, operationId = null) {
    if (!this.eligibility) return null;
    const report = await this.eligibility.check({chat, player, ignoreOperationId:operationId});
    if (!report?.eligible) throw new RestoreError('save_transition_activity_blocked', {blocks: report?.blocks || []});
    if (typeof this.eligibility.assertActivityMayStart === 'function') {
      await this.eligibility.assertActivityMayStart({chat, player, coordinator:this.coordinator,
        ignoreOperationId:operationId});
    }
    return report;
  }

  identity(chat, player) {
    if (!validPart(chat) || !validPart(player)) fail('playable_save_invalid_identity');
    return hash([chat, player]);
  }
  statePartition(identity, slotId) { return `${identity}:${slotId}`; }

  async read(module, partition) {
    if (!MODULES.has(module)) fail('playable_save_invalid_module');
    const result = await this.execute(`SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,
      [module, partition], {prepare:true, consistency:'SERIAL'});
    const value = row(result)?.valor;
    if (value == null) return null;
    try { return {raw:value, value:JSON.parse(value)}; }
    catch { fail('playable_save_storage_corrupt'); }
  }

  async cas(module, partition, expectedRaw, next) {
    const serialized = json(next);
    try {
      if (expectedRaw == null) await this.execute(
        `INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
        [module, partition, serialized], {prepare:true, consistency:'QUORUM', serialConsistency:'SERIAL'});
      else await this.execute(
        `UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,
        [serialized, module, partition, expectedRaw], {prepare:true, consistency:'QUORUM', serialConsistency:'SERIAL'});
    } catch { /* Resolve an acknowledgement loss through the SERIAL read. */ }
    const observed = await this.read(module, partition);
    return observed && hash(observed.value) === hash(next);
  }

  async update(module, partition, change, validate) {
    for (let n = 0; n < MAX_RETRIES; n++) {
      const prior = await this.read(module, partition);
      const next = change(prior?.value ?? null);
      validate(next);
      if (await this.cas(module, partition, prior?.raw ?? null, next)) return next;
    }
    fail('playable_save_cas_contention');
  }

  validateRecord(record, identity, slotId) {
    if (!object(record) || record.schemaVersion !== 1 || record.identityHash !== identity || record.slotId !== slotId ||
        !Number.isSafeInteger(record.generation) || record.generation < 1 || !object(record.account) ||
        Object.hasOwn(record.account, 'adventure-entregas') || !/^[a-f0-9]{64}$/.test(record.contentHash || '') ||
        !Number.isFinite(Date.parse(record.createdAt)) || !Number.isFinite(Date.parse(record.updatedAt))) fail('playable_save_corrupt');
    const check = {...record}; delete check.integrityHash;
    if (record.contentHash !== hash(record.account) || record.integrityHash !== hash(check)) fail('playable_save_corrupt');
    return record;
  }

  makeRecord(identity, slotId, account, source, generation = 1, createdAt = this.now()) {
    const clean = withoutGlobalReceipts(account);
    const record = {schemaVersion:1, identityHash:identity, slotId, generation, source,
      account:clean, contentHash:hash(clean), createdAt, updatedAt:this.now()};
    return {...record, integrityHash:hash(record)};
  }

  async readPlayable(chat, player, slotId) {
    if (!validSlot(slotId)) fail('playable_save_invalid_slot');
    const identity = this.identity(chat, player);
    const stored = await this.read('pokemon-playable-save-state', this.statePartition(identity, slotId));
    return stored ? this.validateRecord(stored.value, identity, slotId) : null;
  }

  async addSlotToIndex(identity, slotId, record) {
    return this.update('pokemon-playable-save-index', identity, old => {
      const index = old ?? {schemaVersion:1, slots:{}};
      if (!object(index) || index.schemaVersion !== 1 || !object(index.slots) || Object.keys(index.slots).length > MAX_SLOTS) fail('playable_save_index_corrupt');
      const prior = index.slots[slotId];
      const entry = {contentHash:record.contentHash, generation:record.generation, createdAt:record.createdAt};
      // Unlike imported history, a playable slot is a versioned working copy.
      // Its index advances only with its own monotonic state generation.
      if (prior && (!Number.isSafeInteger(prior.generation) || prior.generation > entry.generation ||
          prior.createdAt !== entry.createdAt)) fail('playable_save_slot_conflict');
      return {schemaVersion:1, slots:{...index.slots,[slotId]:entry}};
    }, value => {
      if (!object(value) || value.schemaVersion !== 1 || !object(value.slots) || Object.keys(value.slots).length > MAX_SLOTS) fail('playable_save_index_corrupt');
    });
  }

  async createPlayable(chat, player, slotId, account, source) {
    if (!validSlot(slotId) || !['active-bootstrap','historical-import'].includes(source)) fail('playable_save_invalid_slot');
    const identity = this.identity(chat, player), partition = this.statePartition(identity,slotId);
    const proposed = this.makeRecord(identity,slotId,account,source);
    const existing = await this.read('pokemon-playable-save-state',partition);
    let record;
    if (existing) {
      record = this.validateRecord(existing.value,identity,slotId);
      if (hash(record.account) !== hash(proposed.account) || record.source !== source) fail('playable_save_slot_conflict');
    } else {
      if (!await this.cas('pokemon-playable-save-state',partition,null,proposed)) {
        const raced = await this.read('pokemon-playable-save-state',partition);
        if (!raced) fail('playable_save_durability_unconfirmed');
        record = this.validateRecord(raced.value,identity,slotId);
        if (hash(record.account) !== hash(proposed.account) || record.source !== source) fail('playable_save_slot_conflict');
      } else record = proposed;
    }
    await this.addSlotToIndex(identity,slotId,record);
    return record;
  }

  async importHistorical(chat, player, historicalSlotId, playableSlotId) {
    if (!validSlot(historicalSlotId) || !validSlot(playableSlotId)) fail('playable_save_invalid_slot');
    const identity = this.identity(chat,player);
    const stored = await this.slotStore.read('pokemon-save-slots', this.slotStore.slotPartition(identity,historicalSlotId));
    if (!stored) fail('playable_save_historical_slot_missing');
    const historical = this.slotStore.assertRecord(stored.value,identity,historicalSlotId);
    await this.slotStore.verifyGlobalReceiptReferences(historical);
    return this.createPlayable(chat,player,playableSlotId,historical.account,'historical-import');
  }

  validatePointer(pointer, identity) {
    if (!object(pointer) || pointer.schemaVersion !== 1 || pointer.identityHash !== identity || !validSlot(pointer.activeSlotId) ||
        !Number.isSafeInteger(pointer.generation) || pointer.generation < 1 || !/^[a-f0-9]{64}$/.test(pointer.activeContentHash || '') ||
        !Number.isSafeInteger(pointer.canonicalGeneration) || pointer.canonicalGeneration < 1 ||
        !/^[a-f0-9]{64}$/.test(pointer.canonicalStateHash || '')) fail('playable_save_pointer_corrupt');
    return pointer;
  }

  async bootstrap(chat, player, slotId = 'a') {
    const identity = this.identity(chat,player);
    const current = await this.trainerStore.readAccount(chat,player);
    if (!current) fail('playable_save_trainer_missing');
    if (current.activeSaveId !== slotId || current.transitionOwner !== '') fail('playable_save_atomic_fence_required');
    const slot = await this.createPlayable(chat,player,slotId,current.state,'active-bootstrap');
    const pointer = {schemaVersion:1,identityHash:identity,activeSlotId:slotId,activeContentHash:slot.contentHash,
      canonicalGeneration:current.generation,canonicalStateHash:current.stateHash,generation:1,updatedAt:this.now()};
    const stored = await this.read('pokemon-active-save-pointer',identity);
    if (stored) {
      const prior = this.validatePointer(stored.value,identity);
      if (prior.activeSlotId !== slotId || prior.activeContentHash !== slot.contentHash) fail('playable_save_pointer_conflict');
      return prior;
    }
    if (!await this.cas('pokemon-active-save-pointer',identity,null,pointer)) return this.bootstrap(chat,player,slotId);
    return pointer;
  }

  async globalReceipts(identity) {
    const row = await this.slotStore.read('pokemon-save-global-receipts',identity);
    if (!row) return {};
    this.slotStore.validateLedger(row.value);
    return row.value.receipts;
  }

  // The canonical account is the mutable representation of the active save.
  // A playable slot is updated when it becomes inactive; it is deliberately
  // not rewritten after every normal game command.  Startup therefore must
  // never replace the canonical account with the older slot snapshot.  It
  // instead proves that the confirmed pointer still names the same slot and
  // then hydrates the already hash-verified canonical state.
  async hydrateActive(chat, player) {
    const identity = this.identity(chat, player);
    const pointerRow = await this.read('pokemon-active-save-pointer', identity);
    if (!pointerRow) fail('playable_save_pointer_missing');
    const pointer = this.validatePointer(pointerRow.value, identity);
    const current = await this.trainerStore.readAccount(chat, player);
    if (!current || current.identityHash !== identity ||
        !Number.isSafeInteger(current.generation) || current.generation < pointer.canonicalGeneration ||
        typeof current.stateHash !== 'string' || !/^[a-f0-9]{64}$/.test(current.stateHash) ||
        current.activeSaveId !== pointer.activeSlotId) {
      fail('playable_save_hydration_conflict');
    }
    // A pointer created by an activation normally precedes the final release
    // LWT, and later normal gameplay advances the canonical generation.  A
    // lower pointer generation is therefore valid.  Equality, however, must
    // describe the exact same canonical snapshot; otherwise the pointer was
    // tampered with or published inconsistently.
    if (current.generation === pointer.canonicalGeneration && current.stateHash !== pointer.canonicalStateHash) {
      fail('playable_save_hydration_conflict');
    }
    if (current.transitionOwner !== '') fail('playable_save_transition_pending');
    const slot = await this.readPlayable(chat, player, pointer.activeSlotId);
    if (!slot || slot.contentHash !== pointer.activeContentHash) fail('playable_save_slot_pointer_conflict');
    const receipts = await this.globalReceipts(identity);
    if (!receiptsCoveredByLedger(current.state['adventure-entregas'], receipts)) {
      fail('playable_save_global_receipts_changed');
    }
    return {account: structuredClone(current.state), generation: current.generation,
      stateHash: current.stateHash, fenceGeneration: current.fenceGeneration,
      activeSlotId: pointer.activeSlotId, pointer: structuredClone(pointer)};
  }

  async persistOperation(identity, operationId, plan) {
    const partition = `${identity}:${operationId}`;
    return this.update('pokemon-playable-save-operations',partition, old => {
      if (old && hash(old.plan) !== hash(plan)) fail('playable_save_operation_conflict');
      return old ?? {schemaVersion:1, identityHash:identity, operationId, plan, phase:'prepared', createdAt:this.now(), updatedAt:this.now()};
    }, value => {
      if (!object(value) || value.schemaVersion !== 1 || value.identityHash !== identity || value.operationId !== operationId ||
          !object(value.plan) || !['prepared','source-saved','canonical-applied','pointer-applied','completed','blocked'].includes(value.phase)) fail('playable_save_operation_corrupt');
    });
  }

  async setOperationPhase(identity, operationId, phase) {
    const partition = `${identity}:${operationId}`;
    return this.update('pokemon-playable-save-operations',partition, old => {
      if (!old || old.identityHash !== identity || old.operationId !== operationId) fail('playable_save_operation_missing');
      return {...old,phase,updatedAt:this.now()};
    }, value => { if (!object(value) || value.phase !== phase) fail('playable_save_operation_corrupt'); });
  }

  async updatePointer(identity, expected, next) {
    const pointer = {...next, generation:expected.generation + 1, updatedAt:this.now()};
    const current = await this.read('pokemon-active-save-pointer',identity);
    if (!current) fail('playable_save_pointer_missing');
    const observed = this.validatePointer(current.value,identity);
    if (observed.activeSlotId === next.activeSlotId && observed.activeContentHash === next.activeContentHash &&
        observed.canonicalStateHash === next.canonicalStateHash) return observed;
    if (observed.generation !== expected.generation || hash(observed) !== hash(expected)) fail('playable_save_pointer_conflict');
    if (!await this.cas('pokemon-active-save-pointer',identity,current.raw,pointer)) return this.updatePointer(identity,expected,next);
    return pointer;
  }

  async saveSourceSlot(chat, player, pointer, current) {
    const identity = this.identity(chat,player), partition = this.statePartition(identity,pointer.activeSlotId);
    const prior = await this.read('pokemon-playable-save-state',partition);
    if (!prior) fail('playable_save_active_slot_missing');
    const record = this.validateRecord(prior.value,identity,pointer.activeSlotId);
    const next = this.makeRecord(identity,pointer.activeSlotId,current.state,record.source,record.generation + 1,record.createdAt);
    if (!await this.cas('pokemon-playable-save-state',partition,prior.raw,next)) return this.saveSourceSlot(chat,player,pointer,current);
    await this.addSlotToIndex(identity,pointer.activeSlotId,next);
    return next;
  }

  // Unwind a fenced activation that must not publish a new save.  `completed`
  // is reserved for a confirmed no-op (pointer already names the target).
  // Failures after reserve use `blocked` so recover() cannot claim the target
  // was activated when the active pointer/source account were preserved.
  async finishWithoutActivation(chat, player, identity, operationId, {terminalPhase = 'completed'} = {}) {
    if (!['completed', 'blocked'].includes(terminalPhase)) fail('playable_save_operation_corrupt');
    const phases = ['backup','encerramento','ativacao','verificacao','conclusao'];
    let inspection = await this.coordinator.inspect(chat,player,operationId);
    for (const phase of phases) {
      const from = ['preparacao','reserva','backup','encerramento','ativacao','verificacao','conclusao'].indexOf(inspection.phase);
      const to = ['preparacao','reserva','backup','encerramento','ativacao','verificacao','conclusao'].indexOf(phase);
      if (to === from + 1) inspection = await this.coordinator.advance(chat,player,operationId,phase);
    }
    const account = await this.trainerStore.readAccount(chat,player);
    if (account.transitionOwner === operationId) await this.trainerStore.releaseTransition(chat,player,account,operationId);
    await this.setOperationPhase(identity,operationId,terminalPhase);
    await this.coordinator.release(chat,player,operationId);
  }

  async activate(chat, player, targetSlotId, operationId) {
    if (!validSlot(targetSlotId) || !validOperation(operationId)) fail('playable_save_invalid_activation');
    const identity = this.identity(chat,player);
    // This initial read is diagnostic; it is deliberately repeated after the
    // durable reservation below so an activity which races the request cannot
    // be approved from a stale preflight.
    await this.assertActivityEligible(chat, player);
    const pointerRow = await this.read('pokemon-active-save-pointer',identity);
    if (!pointerRow) fail('playable_save_pointer_missing');
    const pointer = this.validatePointer(pointerRow.value,identity);
    const current = await this.trainerStore.readAccount(chat,player);
    // Gameplay may legitimately advance the currently active canonical save
    // after the pointer was last published.  The current canonical generation
    // becomes this operation's exact source snapshot and is persisted into the
    // departing slot under the transition fence.
    if (!current) fail('playable_save_trainer_missing');
    if (typeof this.trainerStore.reserveTransition !== 'function' || typeof this.trainerStore.releaseTransition !== 'function' ||
        !Number.isSafeInteger(current.fenceGeneration) || current.fenceGeneration < 1 ||
        !validSlot(current.activeSaveId) || typeof current.transitionOwner !== 'string' ||
        (current.transitionOwner !== '' && !validOperation(current.transitionOwner))) {
      fail('playable_save_atomic_fence_required');
    }
    // A valid owned row proves an in-flight reservation, not a missing fencing
    // capability. It can already hold the target save while the pointer still
    // names the source. Reject the contender before creating another journal.
    if (current.transitionOwner !== '') fail('transition_resource_locked');
    if (current.activeSaveId !== pointer.activeSlotId) {
      // The winner may have completed between our pointer and account reads.
      // Confirm only a read-only duplicate; never use the refreshed pointer as
      // a new base for an automatic second switch.
      const freshRow = await this.read('pokemon-active-save-pointer',identity);
      const fresh = freshRow && this.validatePointer(freshRow.value,identity);
      if (fresh?.activeSlotId === targetSlotId && current.activeSaveId === targetSlotId &&
          fresh.canonicalStateHash === current.stateHash) return {activeSlotId:targetSlotId,idempotent:true};
      fail('playable_save_pointer_conflict');
    }
    if (pointer.activeSlotId === targetSlotId) return {activeSlotId:targetSlotId, idempotent:true};
    const target = await this.readPlayable(chat,player,targetSlotId);
    if (!target) fail('playable_save_target_missing');
    const receipts = await this.globalReceipts(identity);
    const targetState = withGlobalReceipts(target.account,receipts);
    const reservationExpected = {identityHash:identity,generation:current.generation,stateHash:current.stateHash,
      fenceGeneration:current.fenceGeneration,activeSaveId:current.activeSaveId,transitionOwner:''};
    const plan = {fromSlot:pointer.activeSlotId,toSlot:targetSlotId,pointer:structuredClone(pointer),pointerHash:hash(pointer),
      reservationExpected, expectedCanonicalGeneration:current.generation + 1,expectedCanonicalHash:current.stateHash,
      targetContentHash:target.contentHash,targetStateHash:hash(targetState),intentHash:hash([identity,operationId,pointer,target.contentHash])};
    await this.persistOperation(identity,operationId,plan);
    await this.coordinator.begin({chat,player,operationId,resources:[{type:'account',id:player}]});
    await this.coordinator.reserve(chat,player,operationId,{trainerStore:this.trainerStore,expectedAccount:reservationExpected});
    try {
      // Re-check after the durable reservation so a race that started a battle,
      // hunt, Adventure delivery, etc. cannot publish a different save.
      await this.assertActivityEligible(chat, player, operationId);
    } catch (error) {
      // Reservation is ours and still pre-canonical: safe to unwind without
      // publishing the target. Preserve the active pointer/source account.
      await this.finishWithoutActivation(chat, player, identity, operationId,
        {terminalPhase: 'blocked'});
      throw error;
    }

    // A concurrent activation can complete while this operation waits to
    // reserve the account.  Do not use the stale pre-reservation snapshot:
    // that would save the wrong source slot and apply the target a second time.
    const fencedPointerRow = await this.read('pokemon-active-save-pointer',identity);
    if (!fencedPointerRow) fail('playable_save_pointer_missing');
    const fencedPointer = this.validatePointer(fencedPointerRow.value,identity);
    if (fencedPointer.activeSlotId === targetSlotId) {
      // Durable no-op: target already active. Terminal phase is completed.
      await this.finishWithoutActivation(chat,player,identity,operationId);
      return {activeSlotId:targetSlotId,idempotent:true};
    }
    if (hash(fencedPointer) !== hash(pointer)) {
      await this.finishWithoutActivation(chat,player,identity,operationId,
        {terminalPhase: 'blocked'});
      fail('playable_save_pointer_conflict');
    }
    const fencedCurrent = await this.trainerStore.readAccount(chat,player);
    if (!fencedCurrent || fencedCurrent.generation !== plan.expectedCanonicalGeneration || fencedCurrent.stateHash !== current.stateHash ||
        fencedCurrent.fenceGeneration !== current.fenceGeneration + 1 || fencedCurrent.transitionOwner !== operationId) {
      await this.finishWithoutActivation(chat,player,identity,operationId,
        {terminalPhase: 'blocked'});
      fail('playable_save_generation_conflict');
    }
    await this.coordinator.advance(chat,player,operationId,'backup');
    const saved = await this.saveSourceSlot(chat,player,fencedPointer,fencedCurrent);
    await this.setOperationPhase(identity,operationId,'source-saved');
    await this.coordinator.advance(chat,player,operationId,'encerramento');
    await this.coordinator.advance(chat,player,operationId,'ativacao');
    const fence = (await this.coordinator.inspect(chat,player,operationId)).resources[0].fence;
    await this.coordinator.assertFence(chat,operationId,fence);
    const effectId = `saveact_${operationId}`;
    let applied;
    try {
      applied = await this.trainerStore.applyOnce(chat,player,{effectId,intentHash:plan.intentHash,
        expectedGeneration:fencedCurrent.generation,expectedStateHash:fencedCurrent.stateHash,
        writerContext:fencedCurrent,targetSaveId:targetSlotId,transform:() => targetState});
    } catch (error) {
      if (['canonical_trainer_stale_generation','canonical_trainer_cas_conflict'].includes(error?.code)) {
        await this.setOperationPhase(identity,operationId,'blocked');
        fail('playable_save_generation_conflict');
      }
      throw error;
    }
    if (applied.stateHash !== plan.targetStateHash || applied.operationReceipts?.[effectId] !== plan.intentHash) fail('playable_save_canonical_unconfirmed');
    await this.setOperationPhase(identity,operationId,'canonical-applied');
    await this.coordinator.advance(chat,player,operationId,'verificacao');
    const nextPointer = {schemaVersion:1,identityHash:identity,activeSlotId:targetSlotId,activeContentHash:target.contentHash,
      canonicalGeneration:applied.generation,canonicalStateHash:applied.stateHash};
    const confirmedPointer = await this.updatePointer(identity,fencedPointer,nextPointer);
    await this.setOperationPhase(identity,operationId,'pointer-applied');
    await this.trainerStore.releaseTransition(chat,player,applied,operationId);
    await this.coordinator.advance(chat,player,operationId,'conclusao');
    await this.setOperationPhase(identity,operationId,'completed');
    await this.coordinator.release(chat,player,operationId);
    return {activeSlotId:targetSlotId, pointer:confirmedPointer, sourceGeneration:saved.generation, idempotent:false};
  }

  // Replays only an already-persisted immutable plan.  It never accepts a
  // replacement target from a caller, which prevents a retry from switching to
  // a different save after an acknowledgement was lost.
  async recover(chat, player, operationId) {
    if (!validOperation(operationId)) fail('playable_save_invalid_activation');
    const identity = this.identity(chat,player);
    const stored = await this.read('pokemon-playable-save-operations',`${identity}:${operationId}`);
    if (!stored || !object(stored.value?.plan)) fail('playable_save_operation_missing');
    const operation = stored.value, plan = operation.plan;
    if (operation.phase === 'completed') return {activeSlotId:plan.toSlot,recovered:true,completed:true};
    if (operation.phase === 'blocked') fail('playable_save_activation_blocked');
    const pointerRow = await this.read('pokemon-active-save-pointer',identity);
    const pointer = pointerRow && this.validatePointer(pointerRow.value,identity);
    if (!pointer || hash(plan.pointer) !== plan.pointerHash) fail('playable_save_operation_corrupt');
    const target = await this.readPlayable(chat,player,plan.toSlot);
    if (!target || target.contentHash !== plan.targetContentHash) fail('playable_save_target_changed');
    const receipts = await this.globalReceipts(identity);
    const targetState = withGlobalReceipts(target.account,receipts);
    if (hash(targetState) !== plan.targetStateHash) fail('playable_save_global_receipts_changed');
    const effectId = `saveact_${operationId}`;
    let current = await this.trainerStore.readAccount(chat,player);
    const applied = current?.operationReceipts?.[effectId] === plan.intentHash && current.stateHash === plan.targetStateHash;
    if (!applied) {
      if (!current || current.generation !== plan.expectedCanonicalGeneration || current.stateHash !== plan.expectedCanonicalHash) {
        await this.setOperationPhase(identity,operationId,'blocked');
        fail('playable_save_activation_unresolved');
      }
      const inspection = await this.coordinator.inspect(chat,player,operationId);
      const fence = inspection?.resources?.find(item => item.resource.type === 'account' && item.resource.id === player)?.fence;
      if (!fence) fail('playable_save_fence_unresolved');
      await this.coordinator.assertFence(chat,operationId,fence);
      await this.assertActivityEligible(chat,player,operationId);
      if (current.transitionOwner !== operationId || current.fenceGeneration !== plan.reservationExpected?.fenceGeneration + 1 ||
          current.activeSaveId !== plan.fromSlot) fail('playable_save_fence_unresolved');
      if (operation.phase === 'prepared') {
        await this.saveSourceSlot(chat,player,plan.pointer,current);
        await this.setOperationPhase(identity,operationId,'source-saved');
      }
      current = await this.trainerStore.applyOnce(chat,player,{effectId,intentHash:plan.intentHash,
        expectedGeneration:plan.expectedCanonicalGeneration,expectedStateHash:plan.expectedCanonicalHash,
        writerContext:current,targetSaveId:plan.toSlot,transform:() => targetState});
      if (current.stateHash !== plan.targetStateHash || current.operationReceipts?.[effectId] !== plan.intentHash) fail('playable_save_canonical_unconfirmed');
    }
    const updated = await this.updatePointer(identity,plan.pointer,{schemaVersion:1,identityHash:identity,
      activeSlotId:plan.toSlot,activeContentHash:target.contentHash,canonicalGeneration:current.generation,
      canonicalStateHash:current.stateHash});
    await this.trainerStore.releaseTransition(chat,player,current,operationId);
    const phases = ['backup','encerramento','ativacao','verificacao','conclusao'];
    let inspection = await this.coordinator.inspect(chat,player,operationId);
    for (const phase of phases) {
      if (inspection.phase === phase) continue;
      const from = ['preparacao','reserva','backup','encerramento','ativacao','verificacao','conclusao'].indexOf(inspection.phase);
      const to = ['preparacao','reserva','backup','encerramento','ativacao','verificacao','conclusao'].indexOf(phase);
      if (to === from + 1) inspection = await this.coordinator.advance(chat,player,operationId,phase);
    }
    await this.setOperationPhase(identity,operationId,'completed');
    await this.coordinator.release(chat,player,operationId);
    return {activeSlotId:plan.toSlot,pointer:updated,recovered:true,completed:true};
  }
}

module.exports = {PlayableSaveActivation, MODULES, withoutGlobalReceipts, withGlobalReceipts};
