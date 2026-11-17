'use strict';

// Coordinates loja economy writes with LateralIntent on the trainer row.
// Intent LWT and loja INSERT are different partitions — this module does NOT
// claim cross-partition atomicity. It: begin → re-assert active → write →
// confirm with durable evidence → reconcile. Delayed INSERT after reconcile
// fails assertLateralIntentActive; residual TOCTOU between assert and INSERT
// still needs a journal to eliminate fully.
const {hash} = require('./restore-protocol.cjs');
const {buildEffectEvidence, EVIDENCE_OUTCOMES} = require('./lateral-intent.cjs');

const fail = (code, message = code) => Object.assign(new Error(message), {code});

function operationIdFor({chat, player, type, payloadHash}) {
  const digest = hash({chat, player, type, payloadHash}).slice(0, 32);
  const id = `loja_${digest}`;
  if (!/^[A-Za-z0-9_-]{8,100}$/.test(id)) throw fail('invalid_loja_operation_id');
  return id;
}

function payloadHashFor(parts) {
  return hash(parts);
}

function evidenceReceipt({chat, player, operationId, confirmedFingerprint}) {
  const raw = `loja_rx_${hash({chat, player, operationId, confirmedFingerprint}).slice(0, 24)}`;
  return raw.replace(/[^A-Za-z0-9_-]/g, '_');
}

/**
 * @param {object} store CanonicalTrainerStore
 * @param {object} args
 * @param {function} args.write async () => ({confirmedFingerprint: string})
 *   Must persist the loja effect and return a fingerprint observable in the
 *   durable confirmed snapshot (not merely the optimistic atom).
 * @param {function} [args.afterAssert] test hook — runs after assertActive and
 *   before write. A second SELECT here is NOT a fix; see loja-lateral-lease.cjs.
 */
async function withLojaLateralIntent(store, {
  chat, player, type, payload, writerContext, write, afterAssert
}) {
  if (!store || typeof store.beginLateralIntent !== 'function') {
    throw fail('canonical_trainer_not_ready');
  }
  if (typeof write !== 'function') throw fail('invalid_loja_lateral_write');
  const payloadHash = payloadHashFor(payload);
  const operationId = operationIdFor({chat, player, type, payloadHash});
  const begun = await store.beginLateralIntent(chat, player, {
    operationId, type, payloadHash, writerContext
  });
  const intent = begun.intent;
  try {
    // Closing the pause-before-INSERT window as far as same-row LWT allows.
    await store.assertLateralIntentActive(chat, player, operationId, writerContext);
    if (typeof afterAssert === 'function') {
      await afterAssert({operationId, intent, payloadHash, writerContext});
    }
    const result = await write({operationId, intent, payloadHash});
    const confirmedFingerprint = result?.confirmedFingerprint;
    if (typeof confirmedFingerprint !== 'string' || !confirmedFingerprint) {
      throw fail('loja_lateral_evidence_missing');
    }
    // If reconcile/activation won the race, refuse to confirm a stale lease.
    // This still does not stop an INSERT that already ran in `write`.
    await store.assertLateralIntentActive(chat, player, operationId, writerContext);
    const receipt = evidenceReceipt({chat, player, operationId, confirmedFingerprint});
    const effectEvidence = buildEffectEvidence(intent, {
      outcome: EVIDENCE_OUTCOMES.persisted,
      receipt
    });
    await store.confirmLateralIntent(chat, player, {
      operationId, writerContext, effectEvidence
    });
    const afterConfirm = await store.readAccount(chat, player);
    await store.reconcileLateralIntent(chat, player, {
      operationId,
      writerContext: {
        identityHash: writerContext.identityHash,
        fenceGeneration: afterConfirm.fenceGeneration,
        activeSaveId: afterConfirm.activeSaveId,
        transitionOwner: afterConfirm.transitionOwner || ''
      }
    });
    return {operationId, payloadHash, confirmedFingerprint, receipt, intent};
  } catch (error) {
    const code = error?.code || '';
    const unknown = code === 'canonical_trainer_result_unknown' ||
      code === 'result-unknown' || code === 'result_unknown' ||
      code === 'loja_lateral_result_unknown';
    if (unknown) {
      try {
        await store.markLateralIntentUncertain(chat, player, {operationId, writerContext});
      } catch {
        // Keep original failure; uncertain mark is best-effort after lost ack.
      }
    }
    throw error;
  }
}

module.exports = {
  operationIdFor,
  payloadHashFor,
  evidenceReceipt,
  withLojaLateralIntent
};
