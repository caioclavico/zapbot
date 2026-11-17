'use strict';

// Deterministic coverage for durable lateral-intent infrastructure on the
// canonical trainer row. Raids/loja are NOT wired — only begin/confirm/
// uncertain/reconcile + reserveTransition gating + evidence gates.
const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {
  STATUSES, EVIDENCE_OUTCOMES, blockingIntents, assertNoBlockingIntents, buildEffectEvidence
} = require('../runtime/lateral-intent.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

function mock() {
  const accounts = new Map(), groups = new Set(), backups = new Map();
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
    if (sql.includes('canonical_trainer_backups')) {
      const id = key(args[0], args[1]) + `\0${args[2]}`;
      if (sql.startsWith('INSERT')) {
        if (!backups.has(id)) backups.set(id, {source_hash: args[3], source_json: args[4]});
        result.applied = true;
      } else result.rows = backups.has(id) ? [backups.get(id)] : [];
      return result;
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute, accounts};
}

const OP = 'lateral_op_aaaaaaaa';
const OP2 = 'lateral_op_bbbbbbbb';
const payload = (n = 1) => hash({kind: 'synthetic', n});
const evidenceFor = (intent, outcome = EVIDENCE_OUTCOMES.persisted, receipt = 'durable_rx_001') =>
  buildEffectEvidence(intent, {outcome, receipt});

async function provision(store, chat = 'g', player = 'ash') {
  await store.createAccount(chat, player, {slot: 'a', xp: 1}, 'migration-lateral');
  return store.readAccount(chat, player);
}

async function beginAndRead(store, chat, player, op, type, payloadHash, ctx) {
  await store.beginLateralIntent(chat, player, {
    operationId: op, type, payloadHash, writerContext: ctx
  });
  return store.readAccount(chat, player);
}

test('helpers: blocking statuses e tombstone não bloqueia', () => {
  const active = {status: STATUSES.active, operationId: OP};
  const confirmed = {status: STATUSES.confirmed, operationId: OP};
  const uncertain = {status: STATUSES.uncertain, operationId: OP};
  const tomb = {status: STATUSES.reconciled, operationId: OP2};
  assert.equal(blockingIntents({a: active, b: confirmed, c: uncertain, d: tomb}).length, 3);
  assert.throws(() => assertNoBlockingIntents({a: active}), {code: 'canonical_trainer_lateral_intent_pending'});
  assertNoBlockingIntents({d: tomb});
});

test('1 intent de A antes da reserva: begin LWT sob fence/save ativos', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const result = await store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(), writerContext: before
  });
  assert.equal(result.applied, true);
  assert.equal(result.intent.status, STATUSES.active);
  assert.equal(result.intent.originSaveId, 'a');
  assert.equal(result.intent.fenceGeneration, before.fenceGeneration);
  assert.equal(typeof result.intent.intentHash, 'string');
  assert.equal(result.intent.intentHash.length, 64);
  const after = await store.readAccount('g', 'ash');
  assert.equal(after.lateralIntents[OP].status, STATUSES.active);
  assert.equal(after.generation, before.generation + 1);
  assert.equal(after.transitionOwner, '');
});

test('2 reserva enquanto A possui intent: reserveTransition recusa', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const withIntent = await beginAndRead(store, 'g', 'ash', OP, 'loja_buy', payload(), before);
  await assert.rejects(store.reserveTransition('g', 'ash', withIntent, 'op_switch_xx'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  assert.equal((await store.readAccount('g', 'ash')).transitionOwner, '');

  const evidence = evidenceFor(withIntent.lateralIntents[OP]);
  await store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: withIntent, effectEvidence: evidence
  });
  const confirmed = await store.readAccount('g', 'ash');
  await assert.rejects(store.reserveTransition('g', 'ash', confirmed, 'op_switch_yy'),
    {code: 'canonical_trainer_lateral_intent_pending'});
});

test('3 tentativa de intent durante reserva: begin recusa', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  await store.reserveTransition('g', 'ash', before, 'op_switch_zz');
  const reserved = await store.readAccount('g', 'ash');
  assert.ok(reserved.transitionOwner);
  await assert.rejects(store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(), writerContext: reserved
  }), {code: 'canonical_trainer_transition_reserved'});
});

test('4 intent antigo após ativação de B: callback de A perde o fence', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const staleContext = {...before};
  await store.reserveTransition('g', 'ash', before, 'op_switch_b1');
  const reserved = await store.readAccount('g', 'ash');
  await store.applyOnce('g', 'ash', {
    effectId: 'lateral_switch_to_b',
    intentHash: hash({to: 'b'}),
    expectedGeneration: reserved.generation,
    expectedStateHash: reserved.stateHash,
    writerContext: reserved,
    targetSaveId: 'b',
    transform: state => ({...state, slot: 'b'})
  });
  const fenced = await store.readAccount('g', 'ash');
  await store.releaseTransition('g', 'ash', fenced, 'op_switch_b1');
  const live = await store.readAccount('g', 'ash');
  assert.equal(live.activeSaveId, 'b');

  await assert.rejects(store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(), writerContext: staleContext
  }), {code: 'canonical_trainer_fence_conflict'});

  const begun = await store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(), writerContext: live
  });
  assert.equal(begun.intent.originSaveId, 'b');
  await assert.rejects(store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: staleContext,
    effectEvidence: evidenceFor(begun.intent)
  }), {code: 'canonical_trainer_fence_conflict'});
});

test('5 LWT perdido no begin: observe reconcilia e retry é idempotente', async () => {
  const db = mock();
  const setup = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(setup);
  let lose = true;
  const interrupted = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await db.execute(sql, args, options);
    if (lose && sql.startsWith('UPDATE') && sql.includes('lateral_intents = ?')) {
      lose = false;
      throw Object.assign(new Error('synthetic_lost_lwt_response'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, 'zapbot');

  const recovered = await interrupted.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(9), writerContext: before
  });
  assert.equal(recovered.intent.status, STATUSES.active);
  assert.equal(recovered.lateralIntents[OP].status, STATUSES.active);

  const again = await interrupted.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(9),
    writerContext: await interrupted.readAccount('g', 'ash')
  });
  assert.equal(again.applied, false);
  assert.equal(again.intent.intentHash, recovered.intent.intentHash);
});

test('6 reinício com operação incerta: intent permanece e bloqueia reserva', async () => {
  const db = mock();
  const first = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(first);
  const mid = await beginAndRead(first, 'g', 'ash', OP, 'loja_buy', payload(), before);
  await first.markLateralIntentUncertain('g', 'ash', {operationId: OP, writerContext: mid});

  const restarted = new CanonicalTrainerStore(db.execute, 'zapbot');
  const seen = await restarted.readAccount('g', 'ash');
  assert.equal(seen.lateralIntents[OP].status, STATUSES.uncertain);
  await assert.rejects(restarted.reserveTransition('g', 'ash', seen, 'op_switch_rs'),
    {code: 'canonical_trainer_lateral_intent_pending'});

  // Bare reconcile of uncertain is refused — requires durable evidence.
  await assert.rejects(restarted.reconcileLateralIntent('g', 'ash', {
    operationId: OP, writerContext: seen
  }), {code: 'canonical_trainer_lateral_evidence_required'});

  const evidence = evidenceFor(seen.lateralIntents[OP], EVIDENCE_OUTCOMES.absent, 'absent_obs_01');
  await restarted.reconcileLateralIntent('g', 'ash', {
    operationId: OP, writerContext: seen, effectEvidence: evidence
  });
  const clear = await restarted.readAccount('g', 'ash');
  assert.equal(clear.lateralIntents[OP].status, STATUSES.reconciled);
  await restarted.reserveTransition('g', 'ash', clear, 'op_switch_rs');
  assert.ok((await restarted.readAccount('g', 'ash')).transitionOwner);
});

test('7 dois processos concorrentes: um LWT vence; o outro CAS-conflita ou observa', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const results = await Promise.allSettled([
    store.beginLateralIntent('g', 'ash', {
      operationId: OP, type: 'raid_join', payloadHash: payload(1), writerContext: before
    }),
    store.beginLateralIntent('g', 'ash', {
      operationId: OP2, type: 'loja_buy', payloadHash: payload(2), writerContext: before
    })
  ]);
  const ok = results.filter(r => r.status === 'fulfilled');
  const fail = results.filter(r => r.status === 'rejected');
  assert.equal(ok.length, 1);
  assert.equal(fail.length, 1);
  assert.equal(fail[0].reason.code, 'canonical_trainer_cas_conflict');

  const live = await store.readAccount('g', 'ash');
  assert.equal(blockingIntents(live.lateralIntents).length, 1);

  const lostOp = ok[0].value.intent.operationId === OP ? OP2 : OP;
  const lostType = lostOp === OP ? 'raid_join' : 'loja_buy';
  const lostPayload = lostOp === OP ? payload(1) : payload(2);
  await store.beginLateralIntent('g', 'ash', {
    operationId: lostOp, type: lostType, payloadHash: lostPayload, writerContext: live
  });
  assert.equal(blockingIntents((await store.readAccount('g', 'ash')).lateralIntents).length, 2);
});

test('8 outro jogador do grupo: intent de ash não isola misty', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const ash = await provision(store, 'g', 'ash');
  const misty = await provision(store, 'g', 'misty');
  await store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(), writerContext: ash
  });
  await assert.rejects(store.reserveTransition('g', 'ash', await store.readAccount('g', 'ash'), 'op_ash_x'),
    {code: 'canonical_trainer_lateral_intent_pending'});

  const mistyMid = await beginAndRead(store, 'g', 'misty', OP2, 'loja_buy', payload(3), misty);
  await store.confirmLateralIntent('g', 'misty', {
    operationId: OP2, writerContext: mistyMid,
    effectEvidence: evidenceFor(mistyMid.lateralIntents[OP2])
  });
  const mistyConf = await store.readAccount('g', 'misty');
  await store.reconcileLateralIntent('g', 'misty', {operationId: OP2, writerContext: mistyConf});
  const mistyClear = await store.readAccount('g', 'misty');
  await store.reserveTransition('g', 'misty', mistyClear, 'op_misty_x');
  assert.equal((await store.readAccount('g', 'misty')).transitionOwner, 'op_misty_x');
  assert.equal((await store.readAccount('g', 'ash')).transitionOwner, '');
  assert.equal((await store.readAccount('g', 'ash')).lateralIntents[OP].status, STATUSES.active);
});

test('ciclo confirm → reconcile remove bloqueio; active não reconcilia', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const active = await beginAndRead(store, 'g', 'ash', OP, 'raid_join', payload(), before);
  await assert.rejects(store.reconcileLateralIntent('g', 'ash', {operationId: OP, writerContext: active}),
    {code: 'canonical_trainer_lateral_intent_active'});
  await store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: active,
    effectEvidence: evidenceFor(active.lateralIntents[OP])
  });
  const conf = await store.readAccount('g', 'ash');
  assert.equal(conf.lateralIntents[OP].status, STATUSES.confirmed);
  assert.equal(conf.lateralIntents[OP].effectReceipt, 'durable_rx_001');
  await store.reconcileLateralIntent('g', 'ash', {operationId: OP, writerContext: conf});
  const clear = await store.readAccount('g', 'ash');
  assert.equal(clear.lateralIntents[OP].status, STATUSES.reconciled);
  await store.reserveTransition('g', 'ash', clear, 'op_after_reconcile');
  assert.ok((await store.readAccount('g', 'ash')).transitionOwner);
});

test('segurança: confirm sem evidência durável é recusado', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const active = await beginAndRead(store, 'g', 'ash', OP, 'raid_join', payload(), before);
  await assert.rejects(store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: active
  }), {code: 'canonical_trainer_lateral_evidence_required'});
  await assert.rejects(store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: active,
    effectEvidence: {
      operationId: OP, payloadHash: active.lateralIntents[OP].payloadHash,
      outcome: 'persisted', receipt: 'forged_receipt_01',
      bindingHash: '0'.repeat(64)
    }
  }), {code: 'canonical_trainer_lateral_evidence_conflict'});
  assert.equal((await store.readAccount('g', 'ash')).lateralIntents[OP].status, STATUSES.active);
});

test('segurança: operationId não reutiliza payload diferente nem tombstone', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  const before = await provision(store);
  const active = await beginAndRead(store, 'g', 'ash', OP, 'raid_join', payload(1), before);
  await assert.rejects(store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(2), writerContext: active
  }), {code: 'canonical_trainer_lateral_intent_conflict'});

  await store.confirmLateralIntent('g', 'ash', {
    operationId: OP, writerContext: active,
    effectEvidence: evidenceFor(active.lateralIntents[OP])
  });
  const conf = await store.readAccount('g', 'ash');
  await store.reconcileLateralIntent('g', 'ash', {operationId: OP, writerContext: conf});
  const tomb = await store.readAccount('g', 'ash');
  assert.equal(tomb.lateralIntents[OP].status, STATUSES.reconciled);
  await assert.rejects(store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(1), writerContext: tomb
  }), {code: 'canonical_trainer_lateral_intent_conflict'});
  await assert.rejects(store.beginLateralIntent('g', 'ash', {
    operationId: OP, type: 'raid_join', payloadHash: payload(99), writerContext: tomb
  }), {code: 'canonical_trainer_lateral_intent_conflict'});
});
