'use strict';

// Opt-in disposable Cassandra 5.0.9: real LateralIntent + loja partition writes.
// Does NOT enable production flags/certifications. Demonstrates purchase path,
// peer isolation, restart, lost LWT, duplicate operationId, and TOCTOU residual.
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');
const {CanonicalTrainerStore, PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');
const {withLojaLateralIntent, operationIdFor, payloadHashFor} =
  require('../runtime/loja-lateral-write.cjs');
const {createCassandraLojaLeaseStore} = require('../runtime/loja-lateral-lease.cjs');
const {
  STATUSES, EVIDENCE_OUTCOMES, buildEffectEvidence, blockingIntents
} = require('../runtime/lateral-intent.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

const HOST = 'zapbot-loja-intent-cassandra-test';
const KEYSPACE = 'loja_intent_validation';
const host = process.env.LOJA_INTENT_CASSANDRA_HOST;
const phase = process.env.LOJA_INTENT_CASSANDRA_PHASE || 'seed';

if (require.main === module && (process.env.LOJA_INTENT_CASSANDRA_ISOLATED !== 'true' ||
    host !== HOST || !['seed', 'verify-restart'].includes(phase))) {
  throw new Error('Refusing loja-intent Cassandra integration without the isolated test harness.');
}

const CHAT = 'loja-intent-group';
const ASH = 'ash';
const MISTY = 'misty';
const PENDING_OP_PAYLOAD = {saveId: 'a', pendingRestart: true, n: 1};

function executor(client) {
  return (sql, args, options = {}) => client.execute(sql, args, {
    prepare: true,
    consistency: options.consistency === 'SERIAL'
      ? cassandra.types.consistencies.serial : cassandra.types.consistencies.quorum,
    serialConsistency: cassandra.types.consistencies.serial,
    socketOptions: {readTimeout: 20000},
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

function ctx(record) {
  return {
    identityHash: record.identityHash,
    fenceGeneration: record.fenceGeneration,
    activeSaveId: record.activeSaveId,
    transitionOwner: record.transitionOwner || ''
  };
}

function envelope(ashA = 100, ashB = 0, mistyA = 80) {
  return {
    g: {
      [ASH]: {
        'schema-version-loja': 'save-bound-v1',
        'legacy-claim': {'save-id': 'a', status: 'confirmed', 'fence-generation': 1},
        saves: {
          a: {moedas: ashA, inventario: {}},
          b: {moedas: ashB, inventario: {}}
        }
      },
      [MISTY]: {
        'schema-version-loja': 'save-bound-v1',
        'legacy-claim': {'save-id': 'a', status: 'confirmed', 'fence-generation': 1},
        saves: {a: {moedas: mistyA, inventario: {}}}
      }
    }
  };
}

async function setup(client) {
  await client.execute(
    `CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_accounts (
      chat_id text, player_id text, generation bigint, schema_version int, protocol_version int,
      state_hash text, state_json text, operation_receipts map<text, text>,
      fence_generation bigint, active_save_id text, transition_owner text,
      lateral_intents map<text, text>,
      updated_at timestamp, writer_id text, migration_id text,
      PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_groups (
      bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_control (
      capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado_particionado (
      modulo text, particao text, valor text, PRIMARY KEY ((modulo, particao)))`
  ]) await client.execute(ddl);

  await client.execute(
    `INSERT INTO ${KEYSPACE}.canonical_trainer_control
     (capability, protocol_version, state, migration_id, updated_at)
     VALUES (?, ?, ?, ?, toTimestamp(now())) IF NOT EXISTS`,
    ['trainer-canonical', PROTOCOL_VERSION, 'active', 'loja-intent-harness'],
    {prepare: true, consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial});
}

async function writeLojaUnconditional(client, valor) {
  await client.execute(
    `INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor) VALUES (?, ?, ?)`,
    ['loja', '__valor__', JSON.stringify(valor)],
    {prepare: true, consistency: cassandra.types.consistencies.quorum});
}

async function readLoja(client) {
  const row = (await client.execute(
    `SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo = ? AND particao = ?`,
    ['loja', '__valor__'],
    {prepare: true, consistency: cassandra.types.consistencies.serial})).first();
  assert.ok(row?.valor, 'loja row must exist');
  return JSON.parse(row.valor);
}

async function buyWithIntent(store, client, {player, saveId, delta, writerContext, afterAssert, payloadExtra}) {
  const beforeEco = (await readLoja(client)).g[player].saves[saveId];
  const afterEco = {...beforeEco, moedas: beforeEco.moedas + delta};
  return withLojaLateralIntent(store, {
    chat: CHAT, player, type: 'loja_mut',
    payload: {saveId, delta, before: beforeEco.moedas, after: afterEco.moedas, ...payloadExtra},
    writerContext, afterAssert,
    write: async () => {
      const current = await readLoja(client);
      current.g[player].saves[saveId] = afterEco;
      await writeLojaUnconditional(client, current);
      const confirmed = await readLoja(client);
      return {confirmedFingerprint: hash(confirmed.g[player].saves[saveId])};
    }
  });
}

async function seed(client) {
  await setup(client);
  const execute = executor(client);
  const store = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'loja-intent-seed'});
  await store.createAccount(CHAT, ASH, {slot: 'a', xp: 1}, 'loja-ash');
  await store.createAccount(CHAT, MISTY, {slot: 'a', xp: 1}, 'loja-misty');
  await writeLojaUnconditional(client, envelope(100, 0, 80));

  let ash = await store.readAccount(CHAT, ASH);
  const misty = await store.readAccount(CHAT, MISTY);

  // 1) Compra A antes da reserva
  await buyWithIntent(store, client, {
    player: ASH, saveId: 'a', delta: -10, writerContext: ctx(ash)
  });
  ash = await store.readAccount(CHAT, ASH);
  assert.equal((await readLoja(client)).g[ASH].saves.a.moedas, 90);
  assert.equal(blockingIntents(ash.lateralIntents).length, 0);
  console.log('PASS: compra A antes da reserva (intent ciclo completo).');

  // 2) Reserva antes de compra A
  await store.reserveTransition(CHAT, ASH, ash, 'op_block_buy');
  ash = await store.readAccount(CHAT, ASH);
  await assert.rejects(buyWithIntent(store, client, {
    player: ASH, saveId: 'a', delta: -5, writerContext: ctx(ash)
  }), {code: 'canonical_trainer_transition_reserved'});
  await store.releaseTransition(CHAT, ASH, ash, 'op_block_buy');
  ash = await store.readAccount(CHAT, ASH);
  console.log('PASS: reserva antes da compra recusa begin.');

  // 3+4) Pausa após assert / tentativa de liberar com escrita em voo
  let release;
  const gate = new Promise(r => { release = r; });
  const slow = buyWithIntent(store, client, {
    player: ASH, saveId: 'a', delta: -5, writerContext: ctx(ash),
    payloadExtra: {slow: true},
    afterAssert: async () => {
      const mid = await store.readAccount(CHAT, ASH);
      assert.equal(blockingIntents(mid.lateralIntents).length, 1);
      await assert.rejects(store.reserveTransition(CHAT, ASH, mid, 'op_during_write'),
        {code: 'canonical_trainer_lateral_intent_pending'});
      await gate;
    }
  });
  await new Promise(r => setTimeout(r, 50));
  release();
  await slow;
  assert.equal((await readLoja(client)).g[ASH].saves.a.moedas, 85);
  console.log('PASS: pausa pós-assert mantém barreira; escrita conclui.');

  // 5) Processo interrompido antes da gravação: leave active intent
  ash = await store.readAccount(CHAT, ASH);
  const pendingHash = payloadHashFor(PENDING_OP_PAYLOAD);
  const pendingOp = operationIdFor({
    chat: CHAT, player: ASH, type: 'loja_mut', payloadHash: pendingHash
  });
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: pendingOp, type: 'loja_mut', payloadHash: pendingHash,
    writerContext: ctx(ash)
  });
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[pendingOp].status, STATUSES.active);
  console.log('PASS: interrupt antes da gravação deixa intent active.');

  // Clean pending for subsequent scenarios via evidenced absent reconcile later
  // after TOCTOU demo — keep it for restart phase. Reconcile a *different* path:

  // 6) LWT perdido no begin de uma compra nova (misty)
  let lose = true;
  const flaky = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await execute(sql, args, options);
    if (lose && sql.startsWith('UPDATE') && sql.includes('lateral_intents')) {
      lose = false;
      throw Object.assign(new Error('lost'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, KEYSPACE, {writerId: 'loja-intent-lost'});
  await buyWithIntent(flaky, client, {
    player: MISTY, saveId: 'a', delta: -10, writerContext: ctx(misty),
    payloadExtra: {lost: true}
  });
  assert.equal((await readLoja(client)).g[MISTY].saves.a.moedas, 70);
  console.log('PASS: LWT perdido no begin recuperado; misty debitada uma vez.');

  // 7) Compra duplicada mesmo operationId (tombstone) — payload idêntico ao step 6
  const mistyLive = await store.readAccount(CHAT, MISTY);
  const dupPayload = {saveId: 'a', delta: -10, before: 80, after: 70, lost: true};
  await assert.rejects(withLojaLateralIntent(store, {
    chat: CHAT, player: MISTY, type: 'loja_mut', payload: dupPayload,
    writerContext: ctx(mistyLive),
    write: async () => {
      throw new Error('duplicate must not reach write');
    }
  }), {code: 'canonical_trainer_lateral_intent_conflict'});
  assert.equal((await readLoja(client)).g[MISTY].saves.a.moedas, 70);
  console.log('PASS: operationId duplicado recusado (tombstone).');

  // 8) Dois jogadores: ash ainda tem pendingOp; misty already bought
  ash = await store.readAccount(CHAT, ASH);
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_ash_blocked'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  // misty can still reserve
  let misty2 = await store.readAccount(CHAT, MISTY);
  // clear misty blocking (only tombstones) — reserve should work
  await store.reserveTransition(CHAT, MISTY, misty2, 'op_misty_ok');
  misty2 = await store.readAccount(CHAT, MISTY);
  assert.equal(misty2.transitionOwner, 'op_misty_ok');
  await store.releaseTransition(CHAT, MISTY, misty2, 'op_misty_ok');
  console.log('PASS: peer misty opera com ash bloqueado por intent pendente.');

  // 9) A→B→A — first reconcile pending with absent evidence so we can switch
  ash = await store.readAccount(CHAT, ASH);
  await store.markLateralIntentUncertain(CHAT, ASH, {
    operationId: pendingOp, writerContext: ctx(ash)
  });
  ash = await store.readAccount(CHAT, ASH);
  const evidenceAbsent = buildEffectEvidence(ash.lateralIntents[pendingOp], {
    outcome: EVIDENCE_OUTCOMES.absent, receipt: 'absent_pending_rx1'
  });
  await store.reconcileLateralIntent(CHAT, ASH, {
    operationId: pendingOp, writerContext: ctx(ash), effectEvidence: evidenceAbsent
  });
  ash = await store.readAccount(CHAT, ASH);
  const balA = (await readLoja(client)).g[ASH].saves.a.moedas;
  await store.reserveTransition(CHAT, ASH, ash, 'op_switch_ab');
  let reserved = await store.readAccount(CHAT, ASH);
  await store.applyOnce(CHAT, ASH, {
    effectId: 'loja_intent_to_b', intentHash: hash({to: 'b'}),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'b',
    transform: s => ({...s, slot: 'b'})
  });
  ash = await store.readAccount(CHAT, ASH);
  await store.releaseTransition(CHAT, ASH, ash, 'op_switch_ab');
  ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.activeSaveId, 'b');
  await buyWithIntent(store, client, {
    player: ASH, saveId: 'b', delta: 40, writerContext: ctx(ash),
    payloadExtra: {onB: true}
  });
  assert.equal((await readLoja(client)).g[ASH].saves.a.moedas, balA);
  assert.equal((await readLoja(client)).g[ASH].saves.b.moedas, 40);
  // back to A
  ash = await store.readAccount(CHAT, ASH);
  await store.reserveTransition(CHAT, ASH, ash, 'op_switch_ba');
  reserved = await store.readAccount(CHAT, ASH);
  await store.applyOnce(CHAT, ASH, {
    effectId: 'loja_intent_to_a', intentHash: hash({to: 'a'}),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'a',
    transform: s => ({...s, slot: 'a'})
  });
  ash = await store.readAccount(CHAT, ASH);
  await store.releaseTransition(CHAT, ASH, ash, 'op_switch_ba');
  assert.equal((await store.readAccount(CHAT, ASH)).activeSaveId, 'a');
  assert.equal((await readLoja(client)).g[ASH].saves.a.moedas, balA);
  console.log('PASS: A→B→A preserva economias independentes.');

  // TOCTOU residual on real Cassandra (unconditional estado_particionado)
  ash = await store.readAccount(CHAT, ASH);
  let releaseToctou;
  const toctouGate = new Promise(r => { releaseToctou = r; });
  let staleLanded = false;
  const toctouBuy = withLojaLateralIntent(store, {
    chat: CHAT, player: ASH, type: 'loja_mut',
    payload: {saveId: 'a', toctouCassandra: true},
    writerContext: ctx(ash),
    afterAssert: async ({operationId, writerContext}) => {
      let mid = await store.readAccount(CHAT, ASH);
      const evidence = buildEffectEvidence(mid.lateralIntents[operationId], {
        outcome: EVIDENCE_OUTCOMES.persisted, receipt: 'toctou_cass_rx01'
      });
      await store.confirmLateralIntent(CHAT, ASH, {
        operationId, writerContext, effectEvidence: evidence
      });
      mid = await store.readAccount(CHAT, ASH);
      await store.reconcileLateralIntent(CHAT, ASH, {
        operationId, writerContext: ctx(mid)
      });
    },
    write: async () => {
      await toctouGate;
      const current = await readLoja(client);
      current.g[ASH].saves.a = {...current.g[ASH].saves.a, moedas: 1, staleToctou: true};
      await writeLojaUnconditional(client, current);
      staleLanded = true;
      return {confirmedFingerprint: hash(current.g[ASH].saves.a)};
    }
  });
  await new Promise(r => setTimeout(r, 50));
  releaseToctou();
  await assert.rejects(toctouBuy, err => {
    assert.ok(['canonical_trainer_lateral_intent_stale', 'canonical_trainer_fence_conflict']
      .includes(err.code), err.code);
    return true;
  });
  assert.equal(staleLanded, true);
  assert.equal((await readLoja(client)).g[ASH].saves.a.staleToctou, true);
  console.log('PASS(demo): TOCTOU assert→INSERT ainda ABERTA no caminho incondicional.');

  // Lease protocol closes the same race on harness table
  const lease = createCassandraLojaLeaseStore(execute, {keyspace: KEYSPACE});
  await lease.ensureSchema();
  await lease.writeUnconditional(envelope(50, 0, 70));
  ash = await store.readAccount(CHAT, ASH);
  // Repair loja main row after TOCTOU pollution for clarity
  await writeLojaUnconditional(client, envelope(balA, 40, 70));

  let releaseLease;
  const leaseGate = new Promise(r => { releaseLease = r; });
  const leasedBuy = withLojaLateralIntent(store, {
    chat: CHAT, player: ASH, type: 'loja_mut',
    payload: {saveId: 'a', leaseDemo: true},
    writerContext: ctx(ash),
    afterAssert: async ({operationId, writerContext}) => {
      await lease.acquire(operationId);
      let mid = await store.readAccount(CHAT, ASH);
      const evidence = buildEffectEvidence(mid.lateralIntents[operationId], {
        outcome: EVIDENCE_OUTCOMES.persisted, receipt: 'lease_cass_rx01'
      });
      await store.confirmLateralIntent(CHAT, ASH, {
        operationId, writerContext, effectEvidence: evidence
      });
      mid = await store.readAccount(CHAT, ASH);
      await store.reconcileLateralIntent(CHAT, ASH, {
        operationId, writerContext: ctx(mid)
      });
      await lease.release(operationId);
    },
    write: async ({operationId}) => {
      await leaseGate;
      await lease.writeIfLease(operationId, {moedas: 1, stale: true});
      return {confirmedFingerprint: hash({moedas: 1})};
    }
  });
  await new Promise(r => setTimeout(r, 50));
  releaseLease();
  await assert.rejects(leasedBuy, {code: 'loja_write_lease_mismatch'});
  const leased = await lease.readValor();
  assert.notEqual(leased.valor?.stale, true);
  console.log('PASS(demo): write_lease LWT na partição loja FECHA a corrida.');

  // Leave a fresh pending intent for verify-restart
  ash = await store.readAccount(CHAT, ASH);
  const restartPayload = {saveId: 'a', restartProbe: true};
  const restartHash = payloadHashFor(restartPayload);
  const restartOp = operationIdFor({
    chat: CHAT, player: ASH, type: 'loja_mut', payloadHash: restartHash
  });
  await store.beginLateralIntent(CHAT, ASH, {
    operationId: restartOp, type: 'loja_mut', payloadHash: restartHash,
    writerContext: ctx(ash)
  });
  // Persist marker for verify-restart
  await client.execute(
    `INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor) VALUES (?, ?, ?)`,
    ['loja-intent-meta', 'restart-op', restartOp],
    {prepare: true, consistency: cassandra.types.consistencies.quorum});
  console.log('PASS seed: loja-intent disposable Cassandra seed completo.');
}

async function verifyRestart(client) {
  const execute = executor(client);
  const store = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'loja-intent-restart'});
  const meta = (await client.execute(
    `SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo = ? AND particao = ?`,
    ['loja-intent-meta', 'restart-op'],
    {prepare: true, consistency: cassandra.types.consistencies.serial})).first();
  assert.ok(meta?.valor);
  const ash = await store.readAccount(CHAT, ASH);
  assert.equal(ash.lateralIntents[meta.valor].status, STATUSES.active);
  await assert.rejects(store.reserveTransition(CHAT, ASH, ash, 'op_post_restart'),
    {code: 'canonical_trainer_lateral_intent_pending'});
  const misty = await store.readAccount(CHAT, MISTY);
  await store.reserveTransition(CHAT, MISTY, misty, 'op_misty_post');
  assert.equal((await store.readAccount(CHAT, MISTY)).transitionOwner, 'op_misty_post');
  const loja = await readLoja(client);
  assert.equal(loja.g[ASH].saves.b.moedas, 40);
  console.log('PASS verify-restart: intent pendente + economias A/B intactas; misty livre.');
}

async function main() {
  const client = new cassandra.Client({
    contactPoints: [host],
    localDataCenter: 'datacenter1',
    protocolOptions: {port: 9042},
    credentials: {username: 'cassandra', password: 'cassandra'},
    socketOptions: {connectTimeout: 10000, readTimeout: 25000}
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

module.exports = {HOST, KEYSPACE};
