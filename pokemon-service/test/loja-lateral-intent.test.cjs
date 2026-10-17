'use strict';

// Loja ↔ LateralIntent integration (no full Cassandra). Proves reserve is
// blocked while a loja write lease is open, delayed INSERT after reconcile is
// refused, and peers stay free. Cross-partition atomicity is NOT claimed.
const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {withLojaLateralIntent, operationIdFor, payloadHashFor} =
  require('../runtime/loja-lateral-write.cjs');
const {STATUSES, blockingIntents} = require('../runtime/lateral-intent.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

function mock() {
  const accounts = new Map(), groups = new Set();
  let state = 'active';
  const key = (chat, player) => `${chat}\0${player}`;
  const execute = async (sql, args) => {
    const result = {rows: [], first() { return this.rows[0]; }, wasApplied() { return this.applied; }};
    if (sql.includes('canonical_trainer_control')) {
      result.rows = [{protocol_version: 2, state}]; return result;
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
  return {execute, accounts};
}

async function provision(store, chat, player, state = {xp: 1}) {
  await store.createAccount(chat, player, state, 'loja-lateral');
  return store.readAccount(chat, player);
}

function ctx(record) {
  return {
    identityHash: record.identityHash,
    fenceGeneration: record.fenceGeneration,
    activeSaveId: record.activeSaveId,
    transitionOwner: record.transitionOwner || ''
  };
}

function lojaState() {
  return {
    g: {
      ash: {
        'schema-version-loja': 'save-bound-v1',
        saves: {
          a: {moedas: 100, inventario: {}},
          b: {moedas: 0, inventario: {}}
        }
      },
      misty: {
        'schema-version-loja': 'save-bound-v1',
        saves: {a: {moedas: 80, inventario: {}}}
      }
    }
  };
}

test('1 compra de A iniciada antes da reserva: intent bloqueia reserveTransition', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store, 'g', 'ash');
  const loja = lojaState();
  let inserted = false;
  const run = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', item: 'fruta', before: 100, after: 90},
    writerContext: ctx(before),
    write: async ({operationId}) => {
      const mid = await store.readAccount('g', 'ash');
      assert.equal(mid.lateralIntents[operationId].status, STATUSES.active);
      await assert.rejects(store.reserveTransition('g', 'ash', mid, 'op_switch_early'),
        {code: 'canonical_trainer_lateral_intent_pending'});
      loja.g.ash.saves.a.moedas = 90;
      inserted = true;
      return {confirmedFingerprint: hash(loja.g.ash.saves.a)};
    }
  });
  await run;
  assert.equal(inserted, true);
  const after = await store.readAccount('g', 'ash');
  assert.equal(after.lateralIntents[Object.keys(after.lateralIntents)[0]]?.status, STATUSES.reconciled);
  await store.reserveTransition('g', 'ash', after, 'op_switch_ok');
  assert.ok((await store.readAccount('g', 'ash')).transitionOwner);
});

test('2 reserva antes da compra: begin recusa transition_reserved', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store, 'g', 'ash');
  await store.reserveTransition('g', 'ash', before, 'op_first');
  const reserved = await store.readAccount('g', 'ash');
  await assert.rejects(withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', item: 'fruta'},
    writerContext: ctx(reserved),
    write: async () => ({confirmedFingerprint: hash({x: 1})})
  }), {code: 'canonical_trainer_transition_reserved'});
});

test('3 escrita pausada entre intent e INSERT: reserve continua bloqueada', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store, 'g', 'ash');
  let release;
  const gate = new Promise(r => { release = r; });
  const pending = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', pause: true},
    writerContext: ctx(before),
    write: async () => {
      await gate;
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  });
  // Allow begin + assertActive to run until write waits on gate.
  await new Promise(r => setTimeout(r, 20));
  const mid = await store.readAccount('g', 'ash');
  assert.equal(blockingIntents(mid.lateralIntents).length, 1);
  await assert.rejects(store.reserveTransition('g', 'ash', mid, 'op_while_paused'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  release();
  await pending;
});

test('4 reinício com intent pendente: lease permanece e bloqueia', async () => {
  const db = mock();
  const first = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(first, 'g', 'ash');
  const payload = {saveId: 'a', orphan: true};
  const payloadHash = payloadHashFor(payload);
  const operationId = operationIdFor({chat: 'g', player: 'ash', type: 'loja_mut', payloadHash});
  await first.beginLateralIntent('g', 'ash', {
    operationId, type: 'loja_mut', payloadHash, writerContext: ctx(before)
  });
  const restarted = new CanonicalTrainerStore(db.execute, 'zapbot');
  const seen = await restarted.readAccount('g', 'ash');
  assert.equal(seen.lateralIntents[operationId].status, STATUSES.active);
  await assert.rejects(restarted.reserveTransition('g', 'ash', seen, 'op_restart'),
    {code: 'canonical_trainer_lateral_intent_pending'});
});

test('5 resposta Cassandra perdida no begin: observe recupera; compra não duplica', async () => {
  const db = mock();
  await provision(new CanonicalTrainerStore(db.execute, 'zapbot'), 'g', 'ash');
  const before = await new CanonicalTrainerStore(db.execute, 'zapbot').readAccount('g', 'ash');
  let lose = true;
  let writes = 0;
  const flaky = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await db.execute(sql, args, options);
    if (lose && sql.startsWith('UPDATE') && sql.includes('lateral_intents')) {
      lose = false;
      throw Object.assign(new Error('lost'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, 'zapbot');
  const payload = {saveId: 'a', item: 'fruta', n: 1};
  await withLojaLateralIntent(flaky, {
    chat: 'g', player: 'ash', type: 'loja_mut', payload,
    writerContext: ctx(before),
    write: async () => {
      writes += 1;
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  });
  assert.equal(writes, 1);
  // Mesmo payload após reconcile: tombstone impede segundo INSERT.
  await assert.rejects(withLojaLateralIntent(flaky, {
    chat: 'g', player: 'ash', type: 'loja_mut', payload,
    writerContext: ctx(await flaky.readAccount('g', 'ash')),
    write: async () => {
      writes += 1;
      return {confirmedFingerprint: hash({moedas: 90})};
    }
  }), {code: 'canonical_trainer_lateral_intent_conflict'});
  assert.equal(writes, 1);
});

test('5b repetição da mesma compra após reconcile: operationId tombstone impede reuso', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store, 'g', 'ash');
  const payload = {saveId: 'a', item: 'fruta', eco: 100};
  await withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut', payload,
    writerContext: ctx(before),
    write: async () => ({confirmedFingerprint: hash({moedas: 90})})
  });
  const live = await store.readAccount('g', 'ash');
  await assert.rejects(withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut', payload,
    writerContext: ctx(live),
    write: async () => ({confirmedFingerprint: hash({moedas: 80})})
  }), {code: 'canonical_trainer_lateral_intent_conflict'});
});

test('6 escrita atrasada após reconcile: assertLateralIntentActive recusa', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store, 'g', 'ash');
  const payload = {saveId: 'a', delayed: true};
  const payloadHash = payloadHashFor(payload);
  const operationId = operationIdFor({chat: 'g', player: 'ash', type: 'loja_mut', payloadHash});
  await store.beginLateralIntent('g', 'ash', {
    operationId, type: 'loja_mut', payloadHash, writerContext: ctx(before)
  });
  let mid = await store.readAccount('g', 'ash');
  // Simulate confirm+reconcile without the delayed INSERT.
  const {buildEffectEvidence, EVIDENCE_OUTCOMES} = require('../runtime/lateral-intent.cjs');
  const evidence = buildEffectEvidence(mid.lateralIntents[operationId], {
    outcome: EVIDENCE_OUTCOMES.persisted, receipt: 'loja_rx_delayed_01'
  });
  await store.confirmLateralIntent('g', 'ash', {
    operationId, writerContext: ctx(mid), effectEvidence: evidence
  });
  mid = await store.readAccount('g', 'ash');
  await store.reconcileLateralIntent('g', 'ash', {operationId, writerContext: ctx(mid)});
  const after = await store.readAccount('g', 'ash');
  await assert.rejects(
    store.assertLateralIntentActive('g', 'ash', operationId, ctx(after)),
    {code: 'canonical_trainer_lateral_intent_stale'});
  // Activation can proceed; delayed INSERT must not.
  await store.reserveTransition('g', 'ash', after, 'op_after_reconcile');
});

test('7 A→B→A saldos independentes: intents usam originSaveId do fence vigente', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  let ash = await provision(store, 'g', 'ash', {slot: 'a'});
  const balances = {a: 100, b: 0};
  await withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', buy: 1, bal: balances.a},
    writerContext: ctx(ash),
    write: async () => {
      balances.a = 90;
      return {confirmedFingerprint: hash({save: 'a', moedas: balances.a})};
    }
  });
  ash = await store.readAccount('g', 'ash');
  await store.reserveTransition('g', 'ash', ash, 'op_switch_b');
  let reserved = await store.readAccount('g', 'ash');
  await store.applyOnce('g', 'ash', {
    effectId: 'loja_switch_b', intentHash: hash({to: 'b'}),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'b',
    transform: s => ({...s, slot: 'b'})
  });
  ash = await store.readAccount('g', 'ash');
  await store.releaseTransition('g', 'ash', ash, 'op_switch_b');
  ash = await store.readAccount('g', 'ash');
  assert.equal(ash.activeSaveId, 'b');
  await withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'b', credit: 50, bal: balances.b},
    writerContext: ctx(ash),
    write: async ({intent}) => {
      assert.equal(intent.originSaveId, 'b');
      balances.b = 50;
      return {confirmedFingerprint: hash({save: 'b', moedas: balances.b})};
    }
  });
  assert.equal(balances.a, 90);
  assert.equal(balances.b, 50);
});

test('8 outro jogador do grupo compra enquanto ash tem intent pendente', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const ash = await provision(store, 'g', 'ash');
  const misty = await provision(store, 'g', 'misty');
  let release;
  const gate = new Promise(r => { release = r; });
  const ashWrite = withLojaLateralIntent(store, {
    chat: 'g', player: 'ash', type: 'loja_mut',
    payload: {saveId: 'a', slow: true},
    writerContext: ctx(ash),
    write: async () => {
      await gate;
      return {confirmedFingerprint: hash({ash: 90})};
    }
  });
  await new Promise(r => setTimeout(r, 20));
  await withLojaLateralIntent(store, {
    chat: 'g', player: 'misty', type: 'loja_mut',
    payload: {saveId: 'a', item: 'fruta'},
    writerContext: ctx(misty),
    write: async () => ({confirmedFingerprint: hash({misty: 70})})
  });
  release();
  await ashWrite;
  assert.equal(blockingIntents((await store.readAccount('g', 'ash')).lateralIntents).length, 0);
  assert.equal(blockingIntents((await store.readAccount('g', 'misty')).lateralIntents).length, 0);
});
