'use strict';

// Opt-in: disposable Cassandra 5.0.9 validates LateralIntent LWT on the
// canonical trainer row. Does NOT enable production flags or wire loja/raids.
// Harness-only lateral_effect_receipts table simulates durable effect proof.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cassandra = require('cassandra-driver');
const {CanonicalTrainerStore, PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');
const {
  STATUSES, EVIDENCE_OUTCOMES, MAX_LATERAL_INTENTS, buildEffectEvidence, blockingIntents
} = require('../runtime/lateral-intent.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

const HOST = 'zapbot-lateral-intent-cassandra-test';
const KEYSPACE = 'lateral_intent_validation';
const host = process.env.LATERAL_INTENT_CASSANDRA_HOST;
const phase = process.env.LATERAL_INTENT_CASSANDRA_PHASE || 'seed';

if (require.main === module && (process.env.LATERAL_INTENT_CASSANDRA_ISOLATED !== 'true' ||
    host !== HOST || !['seed', 'verify-restart'].includes(phase))) {
  throw new Error('Refusing lateral-intent Cassandra integration without the isolated test harness.');
}

const CHAT = 'lateral-intent-group';
const ASH = 'ash';
const MISTY = 'misty';
const PENDING_ACTIVE = 'pend_active_op01';
const PENDING_UNCERTAIN = 'pend_uncertain_01';

function executor(client) {
  return (sql, args, options = {}) => client.execute(sql, args, {
    prepare: true,
    consistency: options.consistency === 'SERIAL'
      ? cassandra.types.consistencies.serial : cassandra.types.consistencies.quorum,
    serialConsistency: cassandra.types.consistencies.serial,
    socketOptions: {readTimeout: 15000},
    ...(options.fetchSize ? {fetchSize: options.fetchSize} : {}),
    ...(options.pageState ? {pageState: options.pageState} : {})
  });
}

async function connectReady(client) {
  const deadline = Date.now() + 180000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      await client.connect();
      const row = (await client.execute('SELECT release_version FROM system.local')).first();
      if (row?.release_version !== '5.0.9') {
        throw new Error('Disposable Cassandra must be exactly version 5.0.9.');
      }
      return row.release_version;
    } catch (error) {
      lastError = error;
      try { await client.shutdown(); } catch {}
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  throw new Error(`Cassandra CQL readiness timeout: ${lastError?.name || 'unavailable'}`);
}

async function columnExists(client, column) {
  const row = (await client.execute(
    `SELECT column_name FROM system_schema.columns
     WHERE keyspace_name = ? AND table_name = ? AND column_name = ?`,
    [KEYSPACE, 'canonical_trainer_accounts', column],
    {prepare: true, consistency: cassandra.types.consistencies.quorum})).first();
  return Boolean(row);
}

async function setupBaseSchema(client) {
  await client.execute(
    `CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  // Intentionally WITHOUT lateral_intents — ALTER is applied in a controlled step.
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_accounts (
      chat_id text, player_id text, generation bigint, schema_version int, protocol_version int,
      state_hash text, state_json text, operation_receipts map<text, text>,
      fence_generation bigint, active_save_id text, transition_owner text,
      updated_at timestamp, writer_id text, migration_id text,
      PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_groups (
      bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_control (
      capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_backups (
      chat_id text, player_id text, backup_id text, source_hash text, source_json text, created_at timestamp,
      PRIMARY KEY ((chat_id, player_id), backup_id))`,
    // Harness-only durable effect ledger — not production schema.
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.lateral_effect_receipts (
      chat_id text, player_id text, operation_id text, payload_hash text, receipt_id text, body text,
      PRIMARY KEY ((chat_id, player_id), operation_id))`
  ]) await client.execute(ddl);

  assert.equal(await columnExists(client, 'lateral_intents'), false,
    'base schema must not include lateral_intents before controlled ALTER');

  // Controlled ALTER from the checked-in CQL fragment (keyspace rewritten).
  const cqlPath = path.join(__dirname, '../scripts/cassandra/canonical-trainer-v1.cql');
  const source = fs.readFileSync(cqlPath, 'utf8');
  assert.match(source, /ADD IF NOT EXISTS lateral_intents map<text, text>/);
  await client.execute(
    `ALTER TABLE ${KEYSPACE}.canonical_trainer_accounts ADD IF NOT EXISTS lateral_intents map<text, text>`);
  assert.equal(await columnExists(client, 'lateral_intents'), true,
    'controlled ALTER must publish lateral_intents on the disposable keyspace');

  await client.execute(
    `INSERT INTO ${KEYSPACE}.canonical_trainer_control
     (capability, protocol_version, state, migration_id, updated_at)
     VALUES (?, ?, ?, ?, toTimestamp(now())) IF NOT EXISTS`,
    ['trainer-canonical', PROTOCOL_VERSION, 'active', 'lateral-intent-harness'],
    {prepare: true, consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial});
}

async function writeEffectReceipt(client, {chat, player, operationId, payloadHash, receiptId, body}) {
  const result = await client.execute(
    `INSERT INTO ${KEYSPACE}.lateral_effect_receipts
     (chat_id, player_id, operation_id, payload_hash, receipt_id, body)
     VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
    [chat, player, operationId, payloadHash, receiptId, body],
    {prepare: true, consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial});
  assert.equal(result.wasApplied(), true, 'harness effect receipt must apply');
  return receiptId;
}

async function readEffectReceipt(client, chat, player, operationId) {
  const row = (await client.execute(
    `SELECT receipt_id, payload_hash, body FROM ${KEYSPACE}.lateral_effect_receipts
     WHERE chat_id = ? AND player_id = ? AND operation_id = ?`,
    [chat, player, operationId],
    {prepare: true, consistency: cassandra.types.consistencies.serial})).first();
  return row || null;
}

function evidenceFromReceipt(intent, receiptId, outcome = EVIDENCE_OUTCOMES.persisted) {
  return buildEffectEvidence(intent, {outcome, receipt: receiptId});
}

async function seed(client) {
  await setupBaseSchema(client);
  const execute = executor(client);
  const store = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'lateral-intent-seed'});

  await store.createAccount(CHAT, ASH, {slot: 'a', xp: 1}, 'lateral-ash');
  await store.createAccount(CHAT, MISTY, {slot: 'a', xp: 2}, 'lateral-misty');
  let ash = await store.readAccount(CHAT, ASH);
  const misty = await store.readAccount(CHAT, MISTY);

  // --- 1+2: begin then reserve blocked; concurrent begin/reserve serialization ---
  const opBegin = 'lat_begin_op_0001';
  const payloadBegin = hash({op: opBegin, n: 1});
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: opBegin, type: 'raid_join', payloadHash: payloadBegin, writerContext: ash
  });
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[opBegin].status, STATUSES.active);
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_switch_blocked'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  console.log('PASS: begin blocks reserveTransition on the same row LWT.');

  // Confirm with durable harness receipt, reconcile → tombstone, then reserve briefly.
  const receiptBegin = await writeEffectReceipt(client, {
    chat: CHAT, player: ASH, operationId: opBegin, payloadHash: payloadBegin,
    receiptId: 'rx_begin_0001', body: JSON.stringify({ok: true})
  });
  const observed = await readEffectReceipt(client, CHAT, ASH, opBegin);
  assert.equal(observed.receipt_id, receiptBegin);
  await store.confirmLateralIntent(CHAT, ASH, {
    operationId: opBegin, writerContext: ash,
    effectEvidence: evidenceFromReceipt(ash.lateralIntents[opBegin], receiptBegin)
  });
  ash = await store.readAccount(CHAT, ASH);
  await store.reconcileLateralIntent(CHAT, ASH, {operationId: opBegin, writerContext: ash});
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[opBegin].status, STATUSES.reconciled);

  // --- 3: intent during reservation ---
  await store.reserveTransition(CHAT, ASH, ash, 'op_switch_temp');
  ash = await store.readAccount(CHAT, ASH);
  await assert.rejects(store.beginLateralIntent(CHAT, ASH, {
    operationId: 'lat_during_resv01', type: 'loja_buy', payloadHash: hash({n: 3}),
    writerContext: ash
  }), {code: 'canonical_trainer_transition_reserved'});
  await store.releaseTransition(CHAT, ASH, ash, 'op_switch_temp');
  ash = await store.readAccount(CHAT, ASH);
  console.log('PASS: begin refused while transitionOwner is set.');

  // --- 4: lost LWT response on begin ---
  const opLost = 'lat_lost_begin_01';
  const payloadLost = hash({op: opLost});
  let loseBegin = true;
  const lostStore = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await execute(sql, args, options);
    if (loseBegin && sql.startsWith('UPDATE') && sql.includes('lateral_intents')) {
      loseBegin = false;
      throw Object.assign(new Error('synthetic_lost_lwt_response'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, KEYSPACE, {writerId: 'lateral-intent-lost'});
  const recovered = await lostStore.beginLateralIntent(CHAT, ASH, {
    operationId: opLost, type: 'raid_join', payloadHash: payloadLost, writerContext: ash
  });
  assert.equal(recovered.lateralIntents[opLost].status, STATUSES.active);
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[opLost].status, STATUSES.active);
  // Clean via confirm+reconcile for subsequent scenarios
  const rxLost = await writeEffectReceipt(client, {
    chat: CHAT, player: ASH, operationId: opLost, payloadHash: payloadLost,
    receiptId: 'rx_lost_begin01', body: '{}'
  });
  await store.confirmLateralIntent(CHAT, ASH, {
    operationId: opLost, writerContext: ash,
    effectEvidence: evidenceFromReceipt(ash.lateralIntents[opLost], rxLost)
  });
  ash = await store.readAccount(CHAT, ASH);
  await store.reconcileLateralIntent(CHAT, ASH, {operationId: opLost, writerContext: ash});
  ash = await store.readAccount(CHAT, ASH);
  console.log('PASS: lost begin LWT response recovered via SERIAL observe.');

  // --- 5: process interrupted after begin, before lateral write (leave active) ---
  // Covered by PENDING_ACTIVE left for verify-restart.
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: PENDING_ACTIVE, type: 'raid_join',
    payloadHash: hash({pending: 'active'}), writerContext: ash
  });
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[PENDING_ACTIVE].status, STATUSES.active);
  console.log('PASS: interrupt-after-begin leaves durable active intent.');

  // --- 6: lateral write done, confirm response lost ---
  const opConfirmLost = 'lat_confirm_lost1';
  const payloadConfirm = hash({op: opConfirmLost});
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: opConfirmLost, type: 'loja_buy', payloadHash: payloadConfirm, writerContext: ash
  });
  ash = await store.readAccount(CHAT, ASH);
  const rxConfirm = await writeEffectReceipt(client, {
    chat: CHAT, player: ASH, operationId: opConfirmLost, payloadHash: payloadConfirm,
    receiptId: 'rx_confirm_lost', body: JSON.stringify({coins: 1})
  });
  let loseConfirm = true;
  const confirmLostStore = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await execute(sql, args, options);
    if (loseConfirm && sql.startsWith('UPDATE') && sql.includes('lateral_intents')) {
      // Only lose the confirm transition (second intents write after begin already done).
      const mapArg = args.find(a => a && typeof a === 'object' && !Array.isArray(a) &&
        Object.values(a).some(v => typeof v === 'string' && v.includes('"confirmed"')));
      if (mapArg) {
        loseConfirm = false;
        throw Object.assign(new Error('synthetic_lost_confirm_lwt'), {code: 'synthetic_lost_confirm_lwt'});
      }
    }
    return result;
  }, KEYSPACE, {writerId: 'lateral-intent-confirm-lost'});
  const confirmed = await confirmLostStore.confirmLateralIntent(CHAT, ASH, {
    operationId: opConfirmLost, writerContext: ash,
    effectEvidence: evidenceFromReceipt(ash.lateralIntents[opConfirmLost], rxConfirm)
  });
  assert.equal(confirmed.intent.status, STATUSES.confirmed);
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[opConfirmLost].status, STATUSES.confirmed);
  await store.reconcileLateralIntent(CHAT, ASH, {operationId: opConfirmLost, writerContext: ash});
  ash = await store.readAccount(CHAT, ASH);
  console.log('PASS: lost confirm LWT recovered; effect receipt already durable.');

  // --- 8: peer isolation ---
  await store.beginLateralIntent(CHAT, MISTY, {
    operationId: 'lat_misty_peer_01', type: 'loja_buy', payloadHash: hash({misty: 1}),
    writerContext: misty
  });
  let mistyLive = await store.readAccount(CHAT, MISTY);
  const mistyRx = await writeEffectReceipt(client, {
    chat: CHAT, player: MISTY, operationId: 'lat_misty_peer_01',
    payloadHash: hash({misty: 1}), receiptId: 'rx_misty_peer', body: '{}'
  });
  await store.confirmLateralIntent(CHAT, MISTY, {
    operationId: 'lat_misty_peer_01', writerContext: mistyLive,
    effectEvidence: evidenceFromReceipt(mistyLive.lateralIntents['lat_misty_peer_01'], mistyRx)
  });
  mistyLive = await store.readAccount(CHAT, MISTY);
  await store.reconcileLateralIntent(CHAT, MISTY, {
    operationId: 'lat_misty_peer_01', writerContext: mistyLive
  });
  mistyLive = await store.readAccount(CHAT, MISTY);
  await store.reserveTransition(CHAT, MISTY, mistyLive, 'op_misty_ok');
  assert.equal((await store.readAccount(CHAT, MISTY)).transitionOwner, 'op_misty_ok');
  // Ash still blocked by PENDING_ACTIVE
  ash = await store.readAccount(CHAT, ASH);
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_ash_still'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  await store.releaseTransition(CHAT, MISTY, await store.readAccount(CHAT, MISTY), 'op_misty_ok');
  console.log('PASS: misty reserves while ash is blocked by pending intent.');

  // --- 9: concurrency between two processes ---
  // Without a barrier, real Cassandra often lets process B re-read after A's
  // commit — both begins succeed with distinct operationIds (valid). To prove
  // same-generation LWT serialization we hold both UPDATEs until both have
  // observed the pre-write row.
  ash = await store.readAccount(CHAT, ASH);
  const mistyClear = await store.readAccount(CHAT, MISTY);
  let updateArrivals = 0;
  let releaseUpdates;
  const updatesReady = new Promise(resolve => { releaseUpdates = resolve; });
  const gated = new CanonicalTrainerStore(async (sql, args, options) => {
    if (sql.startsWith('UPDATE') && sql.includes('lateral_intents')) {
      updateArrivals += 1;
      if (updateArrivals >= 2) releaseUpdates();
      await Promise.race([
        updatesReady,
        new Promise((_, reject) => setTimeout(
          () => reject(Object.assign(new Error('concurrency barrier timeout'), {code: 'test_barrier_timeout'})),
          15000))
      ]);
    }
    return execute(sql, args, options);
  }, KEYSPACE, {writerId: 'lateral-intent-race'});

  const results = await Promise.allSettled([
    gated.beginLateralIntent(CHAT, MISTY, {
      operationId: 'lat_conc_aaaaaaa1', type: 'raid_join', payloadHash: hash({c: 1}),
      writerContext: mistyClear
    }),
    gated.beginLateralIntent(CHAT, MISTY, {
      operationId: 'lat_conc_bbbbbbb2', type: 'loja_buy', payloadHash: hash({c: 2}),
      writerContext: mistyClear
    })
  ]);
  const fulfilled = results.filter(r => r.status === 'fulfilled');
  const rejected = results.filter(r => r.status === 'rejected');
  assert.equal(fulfilled.length, 1, 'exactly one same-generation begin must apply');
  assert.equal(rejected.length, 1, 'the losing LWT must surface cas_conflict');
  assert.equal(rejected[0].reason.code, 'canonical_trainer_cas_conflict');
  mistyLive = await store.readAccount(CHAT, MISTY);
  assert.equal(blockingIntents(mistyLive.lateralIntents).length, 1,
    'loser must not leave a partial/corrupt intent map');
  assert.ok(
    mistyLive.lateralIntents['lat_conc_aaaaaaa1'] || mistyLive.lateralIntents['lat_conc_bbbbbbb2'],
    'winner operation must be durable');

  // Retry of the loser under the live generation must succeed (no lost update).
  const winnerOp = mistyLive.lateralIntents['lat_conc_aaaaaaa1']
    ? 'lat_conc_aaaaaaa1' : 'lat_conc_bbbbbbb2';
  const loserOp = winnerOp === 'lat_conc_aaaaaaa1' ? 'lat_conc_bbbbbbb2' : 'lat_conc_aaaaaaa1';
  const loserType = loserOp === 'lat_conc_aaaaaaa1' ? 'raid_join' : 'loja_buy';
  const loserPayload = loserOp === 'lat_conc_aaaaaaa1' ? hash({c: 1}) : hash({c: 2});
  await store.beginLateralIntent(CHAT, MISTY, {
    operationId: loserOp, type: loserType, payloadHash: loserPayload, writerContext: mistyLive
  });
  mistyLive = await store.readAccount(CHAT, MISTY);
  assert.equal(blockingIntents(mistyLive.lateralIntents).length, 2);

  // Clean misty so verify-restart focuses on ash pending
  for (const intent of blockingIntents(mistyLive.lateralIntents)) {
    const winnerRx = await writeEffectReceipt(client, {
      chat: CHAT, player: MISTY, operationId: intent.operationId,
      payloadHash: intent.payloadHash,
      receiptId: `rx_${intent.operationId}`.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 40),
      body: '{}'
    });
    await store.confirmLateralIntent(CHAT, MISTY, {
      operationId: intent.operationId, writerContext: mistyLive,
      effectEvidence: evidenceFromReceipt(intent, winnerRx)
    });
    mistyLive = await store.readAccount(CHAT, MISTY);
    await store.reconcileLateralIntent(CHAT, MISTY, {
      operationId: intent.operationId, writerContext: mistyLive
    });
    mistyLive = await store.readAccount(CHAT, MISTY);
  }
  console.log('PASS: concurrent begins serialize on generation LWT.');

  // --- intent limit (blocking cap) ---
  mistyLive = await store.readAccount(CHAT, MISTY);
  for (let i = 0; i < MAX_LATERAL_INTENTS; i++) {
    const id = `lat_limit_${String(i).padStart(4, '0')}`;
    await store.beginLateralIntent(CHAT, MISTY, {
      operationId: id, type: 'raid_join', payloadHash: hash({limit: i}),
      writerContext: mistyLive
    });
    mistyLive = await store.readAccount(CHAT, MISTY);
  }
  await assert.rejects(store.beginLateralIntent(CHAT, MISTY, {
    operationId: 'lat_limit_overflow', type: 'raid_join', payloadHash: hash({limit: 99}),
    writerContext: mistyLive
  }), {code: 'canonical_trainer_lateral_intent_limit'});
  // Leave misty at cap — still isolated from ash. Soft-clean: mark first as uncertain for restart variety? 
  // Prefer reconcile all misty blocking so verify-restart is about ash only.
  for (const intent of blockingIntents(mistyLive.lateralIntents)) {
    const rx = await writeEffectReceipt(client, {
      chat: CHAT, player: MISTY, operationId: intent.operationId,
      payloadHash: intent.payloadHash,
      receiptId: `rx_lim_${intent.operationId}`.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 40),
      body: '{}'
    });
    await store.confirmLateralIntent(CHAT, MISTY, {
      operationId: intent.operationId, writerContext: mistyLive,
      effectEvidence: evidenceFromReceipt(intent, rx)
    });
    mistyLive = await store.readAccount(CHAT, MISTY);
    await store.reconcileLateralIntent(CHAT, MISTY, {
      operationId: intent.operationId, writerContext: mistyLive
    });
    mistyLive = await store.readAccount(CHAT, MISTY);
  }
  assert.equal(blockingIntents(mistyLive.lateralIntents).length, 0);
  console.log('PASS: blocking intent limit enforced; peer cleaned.');

  // --- 7 prep: uncertain pending survives restart ---
  ash = await store.readAccount(CHAT, ASH);
  // Still have PENDING_ACTIVE. Add a separate uncertain after confirming we can begin another.
  // Cap isn't hit on ash (only 1 active). Begin uncertain path:
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: PENDING_UNCERTAIN, type: 'loja_buy',
    payloadHash: hash({pending: 'uncertain'}), writerContext: ash
  });
  ash = await store.readAccount(CHAT, ASH);
  await store.markLateralIntentUncertain(CHAT, ASH, {
    operationId: PENDING_UNCERTAIN, writerContext: ash
  });
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[PENDING_ACTIVE].status, STATUSES.active);
  assert.equal(ash.lateralIntents[PENDING_UNCERTAIN].status, STATUSES.uncertain);
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_pre_restart'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  // Bare reconcile of uncertain must fail even with real Cassandra
  await assert.rejects(store.reconcileLateralIntent(CHAT, ASH, {
    operationId: PENDING_UNCERTAIN, writerContext: ash
  }), {code: 'canonical_trainer_lateral_evidence_required'});
  console.log('PASS seed: pending active+uncertain durable; ALTER applied; peer isolated.');
}

async function verifyRestart(client) {
  const execute = executor(client);
  const store = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'lateral-intent-restart'});
  assert.equal(await columnExists(client, 'lateral_intents'), true);

  const ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[PENDING_ACTIVE].status, STATUSES.active,
    'active intent must survive Cassandra restart without auto-clear');
  assert.equal(ash.lateralIntents[PENDING_UNCERTAIN].status, STATUSES.uncertain,
    'uncertain intent must survive Cassandra restart without auto-clear');
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_post_restart'),
    {code: 'canonical_trainer_lateral_intent_pending'});

  const misty = await store.readAccount(CHAT, MISTY);
  assert.equal(blockingIntents(misty.lateralIntents).length, 0);
  await store.reserveTransition(CHAT, MISTY, misty, 'op_misty_post');
  assert.equal((await store.readAccount(CHAT, MISTY)).transitionOwner, 'op_misty_post');
  console.log('PASS verify-restart: pending intents intact; misty still unblocked.');
}

async function main() {
  const client = new cassandra.Client({
    contactPoints: [host],
    localDataCenter: 'datacenter1',
    protocolOptions: {port: 9042},
    credentials: {username: 'cassandra', password: 'cassandra'},
    socketOptions: {connectTimeout: 10000, readTimeout: 20000}
  });
  try {
    await connectReady(client);
    if (phase === 'seed') await seed(client);
    else await verifyRestart(client);
  } finally {
    await client.shutdown();
  }
}

if (require.main === module) {
  main().catch(err => {
    console.error(err);
    process.exit(1);
  });
}

module.exports = {HOST, KEYSPACE, PENDING_ACTIVE, PENDING_UNCERTAIN};
