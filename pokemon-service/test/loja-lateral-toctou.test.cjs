'use strict';

// Deterministic proof that assertLateralIntentActive → INSERT is still a race
// under the current protocol, and that a same-row write_lease LWT closes it.
// An extra SELECT is intentionally NOT treated as a fix.
const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {withLojaLateralIntent} = require('../runtime/loja-lateral-write.cjs');
const {createMemoryLojaLeaseStore} = require('../runtime/loja-lateral-lease.cjs');
const {buildEffectEvidence, EVIDENCE_OUTCOMES, STATUSES, blockingIntents} =
  require('../runtime/lateral-intent.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

function mock() {
  const accounts = new Map(), groups = new Set();
  const key = (chat, player) => `${chat}\0${player}`;
  const execute = async (sql, args) => {
    const result = {rows: [], first() { return this.rows[0]; }, wasApplied() { return this.applied; }};
    if (sql.includes('canonical_trainer_control')) {
      result.rows = [{protocol_version: 2, state: 'active'}]; return result;
    }
    if (sql.includes('canonical_trainer_groups')) {
      if (sql.startsWith('INSERT')) { groups.add(args[1]); result.applied = true; }
      else result.rows = [...groups].filter(chat => Number.parseInt(hash(chat).slice(0, 8), 16) % 16 === args[0]).map(chat_id => ({chat_id}));
      return result;
    }
    if (sql.includes('canonical_trainer_accounts')) {
      const id = key(args[0], args[1]);
      if (sql.startsWith('SELECT') && sql.includes('player_id = ?')) {
        const value = accounts.get(id); result.rows = value ? [{...value}] : []; return result;
      }
      if (sql.startsWith('SELECT')) {
        result.rows = [...accounts].filter(([k]) => k.startsWith(`${args[0]}\0`)).map(([k, value]) => ({player_id: k.split('\0')[1], ...value}));
        return result;
      }
      if (sql.startsWith('INSERT')) {
        if (accounts.has(id)) result.applied = false;
        else {
          accounts.set(id, {generation: args[2], schema_version: args[3], protocol_version: args[4], state_hash: args[5], state_json: args[6],
            fence_generation: args[10], active_save_id: args[11], transition_owner: args[12],
            operation_receipts: {}, lateral_intents: {}});
          result.applied = true;
        }
        return result;
      }
      if (sql.startsWith('UPDATE')) {
        const setColumns = sql.split(' SET ')[1].split(' WHERE ')[0].split(', ').map(part => part.split(' = ')[0]);
        const offset = setColumns.length;
        const conditions = sql.split(' IF ')[1].split(' AND ').map(part => part.split(' = ')[0]);
        const value = accounts.get(key(args[offset], args[offset + 1]));
        result.applied = Boolean(value && conditions.every((column, index) => value[column] === args[offset + 2 + index]));
        if (result.applied) {
          accounts.set(key(args[offset], args[offset + 1]),
            {...value, ...Object.fromEntries(setColumns.map((column, index) => [column, args[index]]))});
        }
        return result;
      }
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute};
}

function ctx(record) {
  return {
    identityHash: record.identityHash,
    fenceGeneration: record.fenceGeneration,
    activeSaveId: record.activeSaveId,
    transitionOwner: record.transitionOwner || ''
  };
}

async function provision(store) {
  await store.createAccount('g', 'ash', {slot: 'a', xp: 1}, 'toctou');
  return store.readAccount('g', 'ash');
}

async function forceReconcile(store, operationId, writerContext) {
  let mid = await store.readAccount('g', 'ash');
  const intent = mid.lateralIntents[operationId];
  assert.equal(intent.status, STATUSES.active);
  const evidence = buildEffectEvidence(intent, {
    outcome: EVIDENCE_OUTCOMES.persisted, receipt: 'toctou_force_rx01'
  });
  await store.confirmLateralIntent('g', 'ash', {
    operationId, writerContext, effectEvidence: evidence
  });
  mid = await store.readAccount('g', 'ash');
  await store.reconcileLateralIntent('g', 'ash', {operationId, writerContext: ctx(mid)});
}

test('TOCTOU aberto: após assert, reconcile+A→B e INSERT incondicional ainda grava', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const loja = createMemoryLojaLeaseStore();
  await loja.writeUnconditional({moedas: 100, save: 'a'});

  let releaseInsert;
  const insertGate = new Promise(r => { releaseInsert = r; });
  let insertLanded = false;

  const purchase = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', item: 'fruta', toctou: true},
    writerContext: ctx(before),
    afterAssert: async ({operationId, writerContext}) => {
      // Concurrent reconciler frees the intent while INSERT is paused.
      await forceReconcile(store, operationId, writerContext);
      const cleared = await store.readAccount('g', 'ash');
      assert.equal(cleared.lateralIntents[operationId].status, STATUSES.reconciled);
      assert.equal(blockingIntents(cleared.lateralIntents).length, 0);
      // A→B can publish once the barrier is gone.
      await store.reserveTransition('g', 'ash', cleared, 'op_toctou_b');
      const reserved = await store.readAccount('g', 'ash');
      await store.applyOnce('g', 'ash', {
        effectId: 'toctou_switch_b', intentHash: hash({to: 'b'}),
        expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
        writerContext: reserved, targetSaveId: 'b',
        transform: s => ({...s, slot: 'b'})
      });
      const live = await store.readAccount('g', 'ash');
      await store.releaseTransition('g', 'ash', live, 'op_toctou_b');
      assert.equal((await store.readAccount('g', 'ash')).activeSaveId, 'b');
    },
    write: async () => {
      await insertGate;
      // Today's path: no lease IF on the loja partition.
      await loja.writeUnconditional({moedas: 90, save: 'a', stale: true});
      insertLanded = true;
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  });

  // Let afterAssert finish (reconcile + B) before releasing INSERT.
  await new Promise(r => setTimeout(r, 30));
  releaseInsert();
  // Purchase pipeline fails post-INSERT (stale intent and/or fence after B),
  // but the unconditional loja write already landed — that is the residual race.
  await assert.rejects(purchase, err => {
    assert.ok(['canonical_trainer_lateral_intent_stale', 'canonical_trainer_fence_conflict']
      .includes(err.code), err.code);
    return true;
  });
  assert.equal(insertLanded, true);
  assert.equal(loja.snapshot().valor.stale, true);
  assert.equal(loja.snapshot().valor.save, 'a');
  assert.equal((await store.readAccount('g', 'ash')).activeSaveId, 'b');
});

test('TOCTOU fechado com write_lease LWT na partição loja', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const loja = createMemoryLojaLeaseStore();
  await loja.writeUnconditional({moedas: 100, save: 'a'});

  let releaseInsert;
  const insertGate = new Promise(r => { releaseInsert = r; });
  let leaseRejectCode = null;

  const purchase = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', item: 'fruta', leased: true},
    writerContext: ctx(before),
    afterAssert: async ({operationId, writerContext}) => {
      await loja.acquire(operationId);
      // Reconciler frees trainer intent AND releases the loja lease (as the
      // abort/reconcile path must). Stale INSERT then fails IF write_lease.
      await forceReconcile(store, operationId, writerContext);
      await loja.release(operationId);
      const cleared = await store.readAccount('g', 'ash');
      await store.reserveTransition('g', 'ash', cleared, 'op_lease_b1');
      const reserved = await store.readAccount('g', 'ash');
      await store.applyOnce('g', 'ash', {
        effectId: 'lease_switch_b', intentHash: hash({to: 'b'}),
        expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
        writerContext: reserved, targetSaveId: 'b',
        transform: s => ({...s, slot: 'b'})
      });
      const live = await store.readAccount('g', 'ash');
      await store.releaseTransition('g', 'ash', live, 'op_lease_b1');
    },
    write: async ({operationId}) => {
      await insertGate;
      try {
        await loja.writeIfLease(operationId, {moedas: 90, save: 'a', stale: true});
      } catch (error) {
        leaseRejectCode = error.code;
        throw error;
      }
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  });

  await new Promise(r => setTimeout(r, 30));
  releaseInsert();
  await assert.rejects(purchase, {code: 'loja_write_lease_mismatch'});
  assert.equal(leaseRejectCode, 'loja_write_lease_mismatch');
  assert.equal(loja.snapshot().valor.stale, undefined);
  assert.equal(loja.snapshot().valor.moedas, 100);
  assert.equal(loja.snapshot().writeLease, '');
  assert.equal((await store.readAccount('g', 'ash')).activeSaveId, 'b');
});

test('tentativa de liberar intent com escrita ainda em execução: reserve bloqueada até reconcile', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  let release;
  const gate = new Promise(r => { release = r; });
  const purchase = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', slow: true},
    writerContext: ctx(before),
    write: async () => {
      await gate;
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  });
  await new Promise(r => setTimeout(r, 20));
  const mid = await store.readAccount('g', 'ash');
  assert.equal(blockingIntents(mid.lateralIntents).length, 1);
  await assert.rejects(store.reserveTransition('g', 'ash', mid, 'op_busy_rsv'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  // Forged reconcile while write runs would free the barrier — production must
  // not do that without durable evidence; here we only show reserve stays closed.
  release();
  await purchase;
});
