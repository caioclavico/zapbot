'use strict';

// Explicit resume for Adventure hatch deliveries after A→B→A.
// The incubate-era save-fence stays immutable evidence of origin. A stale
// callback must not refresh it. Resume is a new durable operation that:
//   1) proves originSaveId + egg identity + pending state + no global receipt
//      + no transition reservation;
//   2) only then captures the *current* fence for the delivery LWT.
// Binding to the live generation without that proof is forbidden.
const {hash} = require('./restore-protocol.cjs');
const {normalizeSeal} = require('./save-bound-task.cjs');

const MODULE_PENDING = 'pokemon-adventure-hatch-pending';
const MODULE_RESUME = 'pokemon-adventure-hatch-resume';
const MAX_RETRIES = 12;
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const fail = (code, message = code) => Object.assign(new Error(message), {code});
const validPart = value => typeof value === 'string' && value.length > 0 && value.length <= 200 &&
  !/[\u0000-\u001f\u007f]/.test(value);
const validSave = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const validId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{8,100}$/.test(value);
const validEgg = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(value);
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];

function hatchEffectId(hatchId) {
  const id = `hatch_${String(hatchId).replace(/[^A-Za-z0-9_-]/g, '_')}`;
  if (!validId(id)) throw fail('adventure_hatch_invalid_hatch');
  return id;
}

function resumeOperationId(hatchId) {
  const id = `rsm_${String(hatchId).replace(/[^A-Za-z0-9_-]/g, '_')}`;
  if (!validId(id)) throw fail('adventure_hatch_invalid_operation');
  return id;
}

function normalizePending(record) {
  if (!object(record) || record.schemaVersion !== 1 || !validPart(record.chat) || !validPart(record.player) ||
      !validEgg(record.eggId) || !validId(record.hatchId) || !validPart(record.pokemonId) ||
      !validSave(record.originSaveId) || !Number.isSafeInteger(record.originFenceGeneration) ||
      record.originFenceGeneration < 1 || !['delivery_pending', 'hatched'].includes(record.eggState) ||
      !object(record.incubateSeal)) {
    throw fail('adventure_hatch_pending_corrupt');
  }
  const seal = normalizeSeal(record.incubateSeal);
  if (!seal || seal.chat !== record.chat || seal.player !== record.player) {
    throw fail('adventure_hatch_pending_corrupt');
  }
  const frozen = seal.contexts[record.player];
  if (!frozen || frozen.activeSaveId !== record.originSaveId ||
      frozen.fenceGeneration !== record.originFenceGeneration || frozen.transitionOwner) {
    throw fail('adventure_hatch_pending_corrupt');
  }
  const without = {...record};
  delete without.integrityHash;
  if (record.integrityHash !== hash(without)) throw fail('adventure_hatch_pending_corrupt');
  return record;
}

function assertResumeEligible({pending, account, globalReceipts}) {
  const claim = normalizePending(pending);
  if (claim.eggState !== 'delivery_pending') throw fail('adventure_hatch_not_pending');
  if (!account || account.transitionOwner) throw fail('canonical_trainer_transition_reserved');
  if (account.activeSaveId !== claim.originSaveId) {
    throw fail('adventure_hatch_resume_save_mismatch');
  }
  const receipts = object(globalReceipts) ? globalReceipts : (account.state?.['adventure-entregas'] || {});
  if (Object.hasOwn(receipts, claim.hatchId)) {
    if (receipts[claim.hatchId] !== claim.pokemonId) throw fail('adventure_hatch_receipt_conflict');
    throw fail('adventure_hatch_already_delivered');
  }
  return claim;
}

class AdventureHatchResume {
  constructor(execute, keyspace, {
    trainerStore,
    now = () => new Date().toISOString(),
    readGlobalReceipts = null
  } = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace) || !trainerStore) {
      throw fail('adventure_hatch_resume_invalid_config');
    }
    this.execute = execute;
    this.table = `${keyspace}.estado_particionado`;
    this.trainerStore = trainerStore;
    this.now = now;
    this.readGlobalReceipts = readGlobalReceipts;
  }

  identity(chat, player) {
    if (!validPart(chat) || !validPart(player)) throw fail('adventure_hatch_invalid_identity');
    return hash([chat, player]);
  }

  pendingPartition(chat, player, eggId) {
    return `${this.identity(chat, player)}:${eggId}`;
  }

  resumePartition(identity, operationId) {
    return `${identity}:${operationId}`;
  }

  async read(module, partition) {
    const result = await this.execute(
      `SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,
      [module, partition], {prepare: true, consistency: 'SERIAL'});
    const value = row(result)?.valor;
    if (value == null) return null;
    try { return {raw: value, value: JSON.parse(value)}; }
    catch { throw fail('adventure_hatch_storage_corrupt'); }
  }

  async cas(module, partition, expectedRaw, next) {
    const serialized = JSON.stringify(next);
    try {
      if (expectedRaw == null) {
        await this.execute(
          `INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
          [module, partition, serialized],
          {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      } else {
        await this.execute(
          `UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,
          [serialized, module, partition, expectedRaw],
          {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      }
    } catch { /* reconcile via SERIAL read */ }
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
    throw fail('adventure_hatch_cas_contention');
  }

  // Registers an immutable origin claim when the egg becomes ready to deliver.
  // Refuses silent fence refreshes from old callbacks.
  async registerPending({chat, player, eggId, hatchId, pokemonId, incubateSeal}) {
    if (!validEgg(eggId) || !validId(hatchId) || !validPart(pokemonId)) {
      throw fail('adventure_hatch_invalid_pending');
    }
    const seal = normalizeSeal(incubateSeal);
    if (!seal || seal.chat !== chat || seal.player !== player) throw fail('save_bound_context_required');
    const frozen = seal.contexts[player];
    if (!frozen || frozen.transitionOwner) throw fail('canonical_trainer_transition_reserved');
    const partition = this.pendingPartition(chat, player, eggId);
    const proposed = {
      schemaVersion: 1, chat, player, eggId, hatchId, pokemonId,
      originSaveId: frozen.activeSaveId,
      originFenceGeneration: frozen.fenceGeneration,
      incubateSeal: {
        chat, player, identityHash: frozen.identityHash,
        fenceGeneration: frozen.fenceGeneration, activeSaveId: frozen.activeSaveId,
        transitionOwner: ''
      },
      eggState: 'delivery_pending',
      createdAt: this.now(), updatedAt: this.now()
    };
    proposed.integrityHash = hash((() => { const c = {...proposed}; delete c.integrityHash; return c; })());

    return this.update(MODULE_PENDING, partition, old => {
      if (!old) return proposed;
      const prior = normalizePending(old);
      // Origin binding is immutable. An old callback cannot rewrite the fence
      // or reassign the hatch to another save/pokemon.
      if (prior.originSaveId !== proposed.originSaveId ||
          prior.originFenceGeneration !== proposed.originFenceGeneration ||
          prior.hatchId !== proposed.hatchId ||
          prior.pokemonId !== proposed.pokemonId ||
          hash(prior.incubateSeal) !== hash(proposed.incubateSeal)) {
        throw fail('adventure_hatch_pending_immutable');
      }
      if (prior.eggState === 'hatched') return prior;
      return {...prior, updatedAt: this.now(),
        integrityHash: hash((() => {
          const c = {...prior, updatedAt: this.now()};
          delete c.integrityHash;
          return c;
        })())};
    }, value => normalizePending(value));
  }

  async loadPending(chat, player, eggId) {
    const stored = await this.read(MODULE_PENDING, this.pendingPartition(chat, player, eggId));
    return stored ? normalizePending(stored.value) : null;
  }

  async markHatched(chat, player, eggId) {
    const partition = this.pendingPartition(chat, player, eggId);
    return this.update(MODULE_PENDING, partition, old => {
      if (!old) throw fail('adventure_hatch_pending_missing');
      const prior = normalizePending(old);
      if (prior.eggState === 'hatched') return prior;
      const next = {...prior, eggState: 'hatched', updatedAt: this.now()};
      delete next.integrityHash;
      next.integrityHash = hash(next);
      return next;
    }, value => normalizePending(value));
  }

  validateResumeOp(op, identity, operationId) {
    if (!object(op) || op.schemaVersion !== 1 || op.identityHash !== identity ||
        op.operationId !== operationId || !validEgg(op.eggId) || !validId(op.hatchId) ||
        !validSave(op.originSaveId) || !validId(op.effectId) || !/^[a-f0-9]{64}$/.test(op.intentHash || '') ||
        !/^[a-f0-9]{64}$/.test(op.originProofHash || '') ||
        !['prepared', 'fenced', 'delivered', 'completed', 'blocked'].includes(op.phase)) {
      throw fail('adventure_hatch_resume_corrupt');
    }
    if (op.resumeFence != null) {
      const fence = op.resumeFence;
      if (!object(fence) || fence.identityHash !== hash([op.chat, op.player]) ||
          !Number.isSafeInteger(fence.fenceGeneration) || fence.fenceGeneration < 1 ||
          fence.activeSaveId !== op.originSaveId || fence.transitionOwner !== '') {
        throw fail('adventure_hatch_resume_corrupt');
      }
    }
    return op;
  }

  async globalReceiptsFor(chat, player, account) {
    if (typeof this.readGlobalReceipts === 'function') {
      return this.readGlobalReceipts(chat, player);
    }
    return account.state?.['adventure-entregas'] || {};
  }

  // Durable, auditable resume. Captures the current fence only after origin proof.
  // Optional `deliver` runs inside the new writer context and must create the
  // adventure-entregas receipt itself (real game path). Without it, applyOnce
  // performs a synthetic team append for isolated runtime tests.
  async resume(chat, player, eggId, operationId, {pokemon = null, deliver = null} = {}) {
    if (!validId(operationId)) throw fail('adventure_hatch_invalid_operation');
    const identity = this.identity(chat, player);
    const pending = await this.loadPending(chat, player, eggId);
    if (!pending) throw fail('adventure_hatch_pending_missing');
    const account = await this.trainerStore.readAccount(chat, player);
    if (!account) throw fail('canonical_trainer_missing');
    const receipts = await this.globalReceiptsFor(chat, player, account);
    const effectId = hatchEffectId(pending.hatchId);
    const intentHash = hash({hatch: pending.hatchId, pokemon: pending.pokemonId, origin: pending.originSaveId});

    // Idempotent completion via trainer receipt even if egg mark lagged.
    if (account.operationReceipts?.[effectId] === intentHash ||
        Object.hasOwn(account.state['adventure-entregas'] || {}, pending.hatchId)) {
      if ((account.state['adventure-entregas'] || {})[pending.hatchId] &&
          account.state['adventure-entregas'][pending.hatchId] !== pending.pokemonId) {
        throw fail('adventure_hatch_receipt_conflict');
      }
      await this.markHatched(chat, player, eggId);
      await this.update(MODULE_RESUME, this.resumePartition(identity, operationId), old => {
        if (old) return {...this.validateResumeOp(old, identity, operationId), phase: 'completed', updatedAt: this.now()};
        return {
          schemaVersion: 1, identityHash: identity, operationId, chat, player,
          eggId, hatchId: pending.hatchId, pokemonId: pending.pokemonId,
          originSaveId: pending.originSaveId, originProofHash: hash(pending),
          effectId, intentHash, resumeFence: null, phase: 'completed',
          createdAt: this.now(), updatedAt: this.now()
        };
      }, value => this.validateResumeOp(value, identity, operationId));
      return {applied: false, idempotent: true, account: await this.trainerStore.readAccount(chat, player)};
    }

    let claim;
    try {
      claim = assertResumeEligible({pending, account, globalReceipts: receipts});
    } catch (error) {
      if (error.code === 'adventure_hatch_already_delivered') {
        await this.markHatched(chat, player, eggId);
        return {applied: false, idempotent: true, account};
      }
      throw error;
    }

    const originProofHash = hash(claim);
    const plan = {
      schemaVersion: 1, identityHash: identity, operationId, chat, player,
      eggId, hatchId: claim.hatchId, pokemonId: claim.pokemonId,
      originSaveId: claim.originSaveId, originProofHash, effectId, intentHash,
      resumeFence: null, phase: 'prepared', createdAt: this.now(), updatedAt: this.now()
    };

    let operation = await this.update(MODULE_RESUME, this.resumePartition(identity, operationId), old => {
      if (!old) return plan;
      const prior = this.validateResumeOp(old, identity, operationId);
      if (prior.originProofHash !== originProofHash || prior.effectId !== effectId ||
          prior.intentHash !== intentHash || prior.originSaveId !== claim.originSaveId ||
          prior.eggId !== eggId) {
        throw fail('adventure_hatch_resume_conflict');
      }
      if (prior.phase === 'completed') return prior;
      return prior;
    }, value => this.validateResumeOp(value, identity, operationId));

    if (operation.phase === 'completed') {
      return {applied: false, idempotent: true, account: await this.trainerStore.readAccount(chat, player)};
    }

    // Re-check eligibility immediately before capturing the current fence so a
    // concurrent A→B cannot be papered over by a stale proof.
    const live = await this.trainerStore.readAccount(chat, player);
    assertResumeEligible({
      pending: claim,
      account: live,
      globalReceipts: await this.globalReceiptsFor(chat, player, live)
    });

    const delivered = await this.trainerStore.withWriterContext(chat, async () => {
      const scopeSeal = this.trainerStore.freezeSaveBoundTask(player);
      if (scopeSeal.activeSaveId !== claim.originSaveId) {
        throw fail('adventure_hatch_resume_save_mismatch');
      }
      // Persist the resume fence for audit/restart. Never mutate incubateSeal.
      operation = await this.update(MODULE_RESUME, this.resumePartition(identity, operationId), old => {
        const prior = this.validateResumeOp(old, identity, operationId);
        if (prior.phase === 'completed' || prior.phase === 'delivered') return prior;
        if (prior.resumeFence && hash(prior.resumeFence) !== hash({
          identityHash: scopeSeal.identityHash,
          fenceGeneration: scopeSeal.fenceGeneration,
          activeSaveId: scopeSeal.activeSaveId,
          transitionOwner: ''
        })) {
          // A concurrent resume already fenced a different generation; fail closed.
          if (prior.phase === 'fenced' || prior.phase === 'delivered') return prior;
          throw fail('adventure_hatch_resume_fence_conflict');
        }
        return {
          ...prior,
          phase: 'fenced',
          resumeFence: {
            identityHash: scopeSeal.identityHash,
            fenceGeneration: scopeSeal.fenceGeneration,
            activeSaveId: scopeSeal.activeSaveId,
            transitionOwner: ''
          },
          updatedAt: this.now()
        };
      }, value => this.validateResumeOp(value, identity, operationId));

      if (operation.phase === 'completed') {
        return {applied: false, state: (await this.trainerStore.readAccount(chat, player)).state};
      }

      const current = await this.trainerStore.readAccount(chat, player);
      assertResumeEligible({
        pending: claim,
        account: current,
        globalReceipts: await this.globalReceiptsFor(chat, player, current)
      });
      if (current.fenceGeneration !== operation.resumeFence.fenceGeneration ||
          current.activeSaveId !== operation.resumeFence.activeSaveId ||
          current.transitionOwner) {
        throw fail('canonical_trainer_fence_conflict');
      }

      let result;
      if (typeof deliver === 'function') {
        result = await deliver({
          chat, player, claim, account: current, effectId, intentHash,
          resumeFence: operation.resumeFence
        });
        const after = await this.trainerStore.readAccount(chat, player);
        const receipt = after?.state?.['adventure-entregas']?.[claim.hatchId];
        if (receipt && receipt !== claim.pokemonId) throw fail('adventure_hatch_receipt_conflict');
        if (!receipt) throw fail('adventure_hatch_deliver_unconfirmed');
        const duplicated = result && (result.duplicado === true || result['duplicado?'] === true);
        result = {
          applied: result?.applied !== false && !duplicated,
          state: after.state,
          operationReceipts: after.operationReceipts
        };
      } else {
        const registro = {
          'id-pokemon': claim.pokemonId,
          nome: pokemon?.nome || 'Hatched',
          nivel: 1, hp: 10, 'hp-atual': 10,
          ataque: 1, defesa: 1, 'atq-esp': 1, 'def-esp': 1, veloc: 1,
          'xp-desde-nivel': 0, shiny: false, golpes: [], tipos: ['normal']
        };
        result = await this.trainerStore.applyOnce(chat, player, {
          effectId, intentHash,
          expectedGeneration: current.generation,
          expectedStateHash: current.stateHash,
          writerContext: current,
          transform: state => {
            const adventureReceipts = {...(state['adventure-entregas'] || {})};
            if (adventureReceipts[claim.hatchId]) {
              if (adventureReceipts[claim.hatchId] !== claim.pokemonId) {
                throw fail('adventure_hatch_receipt_conflict');
              }
              return state;
            }
            return {
              ...state,
              equipe: [...(state.equipe || []), registro],
              'adventure-entregas': {...adventureReceipts, [claim.hatchId]: claim.pokemonId}
            };
          }
        });
      }

      await this.update(MODULE_RESUME, this.resumePartition(identity, operationId), old => {
        const prior = this.validateResumeOp(old, identity, operationId);
        return {...prior, phase: 'delivered', updatedAt: this.now()};
      }, value => this.validateResumeOp(value, identity, operationId));

      return result;
    }, undefined, true, player);

    await this.markHatched(chat, player, eggId);
    await this.update(MODULE_RESUME, this.resumePartition(identity, operationId), old => {
      const prior = this.validateResumeOp(old, identity, operationId);
      return {...prior, phase: 'completed', updatedAt: this.now()};
    }, value => this.validateResumeOp(value, identity, operationId));

    return {
      applied: delivered.applied !== false,
      idempotent: delivered.applied === false,
      account: await this.trainerStore.readAccount(chat, player),
      operationId
    };
  }

  // After restart: if the trainer receipt exists, finish egg/op markers; else
  // retry resume with the same operation id (idempotent plan).
  async recover(chat, player, eggId, operationId, options = {}) {
    const identity = this.identity(chat, player);
    const opRow = await this.read(MODULE_RESUME, this.resumePartition(identity, operationId));
    if (opRow) this.validateResumeOp(opRow.value, identity, operationId);
    return this.resume(chat, player, eggId, operationId, options);
  }
}

module.exports = {
  AdventureHatchResume,
  assertResumeEligible,
  normalizePending,
  hatchEffectId,
  resumeOperationId,
  MODULE_PENDING,
  MODULE_RESUME
};
