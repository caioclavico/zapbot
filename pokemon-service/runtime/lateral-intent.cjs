'use strict';

// Durable lateral-write intents live on the canonical trainer row (same LWT
// partition as fence/save). Writers are not wired yet — this module encodes
// status, identity, evidence gates and blocking rules for reserveTransition.
const {hash} = require('./restore-protocol.cjs');

const SCHEMA_VERSION = 1;
const MAX_LATERAL_INTENTS = 32;
const MAX_LATERAL_TOMBSTONES = 32;
const STATUSES = Object.freeze({
  active: 'active',
  confirmed: 'confirmed',
  uncertain: 'uncertain',
  reconciled: 'reconciled'
});
const BLOCKING = new Set([STATUSES.active, STATUSES.confirmed, STATUSES.uncertain]);
const EVIDENCE_OUTCOMES = Object.freeze({
  persisted: 'persisted',
  absent: 'absent'
});

const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const validOperationId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{8,100}$/.test(value);
const validType = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const validHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const validSave = value => typeof value === 'string' && /^[a-z][a-z0-9_-]{0,63}$/.test(value);
const validReceipt = value => typeof value === 'string' && /^[A-Za-z0-9_-]{8,200}$/.test(value);

function fail(code, message = code) {
  return Object.assign(new Error(message), {code});
}

function intentFingerprint({operationId, type, originSaveId, payloadHash, fenceGeneration}) {
  return hash({operationId, type, originSaveId, payloadHash, fenceGeneration});
}

function evidenceBindingHash({operationId, payloadHash, intentHash, outcome, receipt}) {
  return hash({operationId, payloadHash, intentHash, outcome, receipt});
}

function buildEffectEvidence(intent, {outcome, receipt}) {
  if (!object(intent) || !validHash(intent.intentHash)) throw fail('invalid_lateral_evidence');
  if (![EVIDENCE_OUTCOMES.persisted, EVIDENCE_OUTCOMES.absent].includes(outcome)) {
    throw fail('invalid_lateral_evidence');
  }
  if (!validReceipt(receipt)) throw fail('invalid_lateral_evidence');
  return Object.freeze({
    operationId: intent.operationId,
    payloadHash: intent.payloadHash,
    outcome,
    receipt,
    bindingHash: evidenceBindingHash({
      operationId: intent.operationId,
      payloadHash: intent.payloadHash,
      intentHash: intent.intentHash,
      outcome,
      receipt
    })
  });
}

function assertEffectEvidence(intent, evidence, allowedOutcomes) {
  if (!object(evidence)) throw fail('canonical_trainer_lateral_evidence_required');
  if (evidence.operationId !== intent.operationId) {
    throw fail('canonical_trainer_lateral_evidence_conflict');
  }
  if (evidence.payloadHash !== intent.payloadHash) {
    throw fail('canonical_trainer_lateral_evidence_conflict');
  }
  if (!allowedOutcomes.includes(evidence.outcome)) {
    throw fail('canonical_trainer_lateral_evidence_conflict');
  }
  if (!validReceipt(evidence.receipt)) {
    throw fail('canonical_trainer_lateral_evidence_required');
  }
  const expected = evidenceBindingHash({
    operationId: intent.operationId,
    payloadHash: intent.payloadHash,
    intentHash: intent.intentHash,
    outcome: evidence.outcome,
    receipt: evidence.receipt
  });
  if (evidence.bindingHash !== expected) {
    throw fail('canonical_trainer_lateral_evidence_conflict');
  }
}

function encodeIntent(intent) {
  return JSON.stringify(intent, (_key, item) => {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return item;
    return Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]));
  });
}

function decodeIntent(raw) {
  if (typeof raw !== 'string' || !raw) return null;
  let value;
  try { value = JSON.parse(raw); } catch { return null; }
  if (!object(value) || value.schemaVersion !== SCHEMA_VERSION) return null;
  if (!Object.values(STATUSES).includes(value.status)) return null;
  if (!validOperationId(value.operationId) || !validType(value.type)) return null;
  if (!validSave(value.originSaveId) || !validHash(value.payloadHash)) return null;
  if (!Number.isSafeInteger(value.fenceGeneration) || value.fenceGeneration < 1) return null;
  if (!Number.isSafeInteger(value.accountGeneration) || value.accountGeneration < 1) return null;
  if (!validHash(value.intentHash)) return null;
  if (typeof value.writerId !== 'string' || !value.writerId) return null;
  if (value.effectReceipt !== undefined && !validReceipt(value.effectReceipt)) return null;
  if (value.effectOutcome !== undefined &&
      ![EVIDENCE_OUTCOMES.persisted, EVIDENCE_OUTCOMES.absent].includes(value.effectOutcome)) {
    return null;
  }
  return value;
}

function parseIntentMap(raw) {
  const entries = raw instanceof Map ? [...raw.entries()] :
    object(raw) ? Object.entries(raw) : [];
  const out = {};
  for (const [id, encoded] of entries) {
    if (!validOperationId(id)) continue;
    const intent = decodeIntent(encoded);
    if (!intent || intent.operationId !== id) continue;
    out[id] = intent;
  }
  return out;
}

function serializeIntentMap(intents) {
  return Object.fromEntries(Object.entries(intents)
    .filter(([, intent]) => intent)
    .map(([id, intent]) => [id, encodeIntent(intent)]));
}

function blockingIntents(intents) {
  return Object.values(intents || {}).filter(intent => BLOCKING.has(intent.status));
}

function tombstoneIntents(intents) {
  return Object.values(intents || {}).filter(intent => intent.status === STATUSES.reconciled);
}

function assertNoBlockingIntents(intents) {
  const blocked = blockingIntents(intents);
  if (blocked.length) {
    throw fail('canonical_trainer_lateral_intent_pending',
      `Unreconciled lateral intent(s): ${blocked.map(i => i.operationId).join(',')}`);
  }
}

function pruneOldestTombstones(intents, maxTombstones = MAX_LATERAL_TOMBSTONES) {
  const next = {...intents};
  let tombs = tombstoneIntents(next)
    .sort((a, b) => String(a.updatedAt).localeCompare(String(b.updatedAt)));
  while (tombs.length > maxTombstones) {
    const oldest = tombs.shift();
    delete next[oldest.operationId];
  }
  return next;
}

function buildActiveIntent({operationId, type, payloadHash, originSaveId, fenceGeneration,
  accountGeneration, writerId, now}) {
  if (!validOperationId(operationId)) throw fail('invalid_lateral_intent_id');
  if (!validType(type)) throw fail('invalid_lateral_intent_type');
  if (!validHash(payloadHash)) throw fail('invalid_lateral_payload_hash');
  if (!validSave(originSaveId)) throw fail('invalid_lateral_origin_save');
  if (!Number.isSafeInteger(fenceGeneration) || fenceGeneration < 1) throw fail('invalid_lateral_fence');
  if (!Number.isSafeInteger(accountGeneration) || accountGeneration < 1) throw fail('invalid_lateral_generation');
  const intentHash = intentFingerprint({operationId, type, originSaveId, payloadHash, fenceGeneration});
  const stamp = now instanceof Date ? now.toISOString() : String(now);
  return Object.freeze({
    schemaVersion: SCHEMA_VERSION,
    status: STATUSES.active,
    operationId,
    type,
    originSaveId,
    fenceGeneration,
    accountGeneration,
    payloadHash,
    intentHash,
    writerId: String(writerId || 'pokemon-service'),
    createdAt: stamp,
    updatedAt: stamp
  });
}

module.exports = {
  SCHEMA_VERSION,
  MAX_LATERAL_INTENTS,
  MAX_LATERAL_TOMBSTONES,
  STATUSES,
  EVIDENCE_OUTCOMES,
  intentFingerprint,
  encodeIntent,
  decodeIntent,
  parseIntentMap,
  serializeIntentMap,
  blockingIntents,
  tombstoneIntents,
  assertNoBlockingIntents,
  assertEffectEvidence,
  buildEffectEvidence,
  buildActiveIntent,
  pruneOldestTombstones,
  validOperationId,
  validType,
  validHash,
  validReceipt
};
