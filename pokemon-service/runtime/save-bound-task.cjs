'use strict';

// Save-specific delayed work (hatches, rebuilt timers) must carry the fence
// captured when the task was created. Reading the live account to refresh
// that fence would apply an old Adventure/timer effect onto a newly activated
// save. Global receipts stay on the trainer JSON / ledger; this helper only
// binds identity+generation for the trainer LWT.
const {hash} = require('./restore-protocol.cjs');

const fail = (code, message = code) => Object.assign(new Error(message), {code});
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const validSave = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);

function frozenContext(value) {
  if (!object(value) || typeof value.identityHash !== 'string' ||
      !Number.isSafeInteger(value.fenceGeneration) || value.fenceGeneration < 1 ||
      !validSave(value.activeSaveId) || typeof value.transitionOwner !== 'string') return null;
  return Object.freeze({identityHash:value.identityHash, fenceGeneration:value.fenceGeneration,
    activeSaveId:value.activeSaveId, transitionOwner:value.transitionOwner || ''});
}

function normalizeSeal(seal) {
  if (!object(seal) || typeof seal.chat !== 'string' || !seal.chat) return null;
  if (object(seal.contexts)) {
    const contexts = {};
    for (const [player, ctx] of Object.entries(seal.contexts)) {
      const frozen = frozenContext(ctx);
      if (!frozen || frozen.identityHash !== hash([seal.chat, player])) return null;
      contexts[player] = frozen;
    }
    if (!Object.keys(contexts).length) return null;
    return Object.freeze({chat:seal.chat, player:seal.player || null, contexts:Object.freeze(contexts)});
  }
  if (typeof seal.player !== 'string' || !seal.player) return null;
  const frozen = frozenContext(seal);
  if (!frozen || frozen.identityHash !== hash([seal.chat, seal.player])) return null;
  return Object.freeze({chat:seal.chat, player:seal.player,
    contexts:Object.freeze({[seal.player]: frozen})});
}

function freezeFromScope(scope, player) {
  if (!object(scope) || typeof scope.chat !== 'string' || !scope.contexts) {
    throw fail('save_bound_context_required');
  }
  if (player) {
    const ctx = scope.contexts[player];
    if (!ctx) throw fail('save_bound_context_required');
    if (ctx.transitionOwner) throw fail('canonical_trainer_transition_reserved');
    if (ctx.identityHash !== hash([scope.chat, player])) throw fail('save_bound_context_required');
    return Object.freeze({chat:scope.chat, player, identityHash:ctx.identityHash,
      fenceGeneration:ctx.fenceGeneration, activeSaveId:ctx.activeSaveId, transitionOwner:''});
  }
  const contexts = {};
  for (const [id, ctx] of Object.entries(scope.contexts)) {
    if (ctx.transitionOwner) continue;
    contexts[id] = Object.freeze({identityHash:ctx.identityHash, fenceGeneration:ctx.fenceGeneration,
      activeSaveId:ctx.activeSaveId, transitionOwner:''});
  }
  if (!Object.keys(contexts).length) throw fail('canonical_trainer_transition_reserved');
  return Object.freeze({chat:scope.chat, contexts:Object.freeze(contexts)});
}

function assertSealMatchesRecord(seal, player, record) {
  const normalized = normalizeSeal(seal);
  if (!normalized) throw fail('save_bound_context_required');
  const frozen = normalized.contexts[player];
  if (!frozen) throw fail('save_bound_context_required');
  if (!record) throw fail('save_bound_context_required');
  if (record.transitionOwner) throw fail('canonical_trainer_transition_reserved');
  if (frozen.identityHash !== hash([normalized.chat, player]) ||
      frozen.fenceGeneration !== record.fenceGeneration ||
      frozen.activeSaveId !== record.activeSaveId) {
    throw fail('save_bound_fence_conflict');
  }
  return normalized;
}

module.exports = {normalizeSeal, freezeFromScope, assertSealMatchesRecord};
