'use strict';

// Opt-in integration for Adventure hatch fencing against disposable Cassandra.
// Proves that a save-fence stamped at incubate survives restart and gates
// trainer delivery via LWT. Does not enable production flags/certifications.
// A lock-then-INSERT is never treated as atomic fencing.
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');
const {hash} = require('../runtime/restore-protocol.cjs');
const {normalizeSeal, assertSealMatchesRecord} = require('../runtime/save-bound-task.cjs');
const {CanonicalTrainerStore, PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');
const {SaveSlotStore} = require('../runtime/save-slot-store.cjs');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');
const {AdventureHatchResume} = require('../runtime/adventure-hatch-resume.cjs');

const HOST = 'zapbot-adventure-fence-cassandra-test';
const KEYSPACE = 'adventure_fence_validation';
// Test-only override for the disposable harness. Does not certify production writers.
const TEST_WRITER_COVERAGE = {writerProtocolCoverageVerified: true};
const host = process.env.ADVENTURE_FENCE_CASSANDRA_HOST;
const phase = process.env.ADVENTURE_FENCE_CASSANDRA_PHASE || 'seed';

if (require.main === module && (process.env.ADVENTURE_FENCE_CASSANDRA_ISOLATED !== 'true' ||
    host !== HOST || !['seed', 'verify-restart'].includes(phase))) {
  throw new Error('Refusing Adventure fence Cassandra integration without the isolated test harness.');
}

const account = (id, extra = {}) => ({
  ativo: 0,
  equipe: [{
    'id-pokemon': id, nome: 'Synthetic', nivel: 1, hp: 10, 'hp-atual': 10,
    ataque: 5, defesa: 6, 'atq-esp': 7, 'def-esp': 8, veloc: 9,
    'xp-desde-nivel': 0, shiny: false, golpes: [], tipos: ['normal']
  }],
  pc: [], enfermaria: [], inventario: {pokebola: 1}, moedas: 10,
  'adventure-entregas': {},
  ...extra
});

function executor(client) {
  return (sql, args, options = {}) => client.execute(sql, args, {
    prepare: true,
    consistency: options.consistency === 'SERIAL'
      ? cassandra.types.consistencies.serial : cassandra.types.consistencies.quorum,
    serialConsistency: cassandra.types.consistencies.serial,
    socketOptions: {readTimeout: 10000},
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

async function activateCanonicalTrainerControl(client, keyspace, migrationId) {
  return client.execute(
    `INSERT INTO ${keyspace}.canonical_trainer_control (capability,protocol_version,state,migration_id,updated_at) VALUES (?,?,?,?,toTimestamp(now())) IF NOT EXISTS`,
    ['trainer-canonical', PROTOCOL_VERSION, 'active', migrationId],
    {prepare: true, consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial});
}

async function setup(client) {
  await client.execute(`CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_accounts (chat_id text, player_id text, generation bigint, schema_version int, protocol_version int, state_hash text, state_json text, operation_receipts map<text,text>, fence_generation bigint, active_save_id text, transition_owner text, updated_at timestamp, writer_id text, migration_id text, PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.adventure_fence_eggs (
      chat_id text, player_id text, egg_id text,
      egg_state text, hatch_id text, pokemon_id text,
      save_fence_json text, updated_at timestamp,
      PRIMARY KEY ((chat_id, player_id), egg_id))`
  ]) await client.execute(ddl);
  await activateCanonicalTrainerControl(client, KEYSPACE, 'adventure-fence');
}

function eggRow(result) {
  return typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
}

async function persistEgg(client, {chat, player, eggId, seal, hatchId, pokemonId, eggState = 'incubating'}) {
  const normalized = normalizeSeal(seal);
  assert.ok(normalized, 'incubate must persist a complete frozen fence');
  await client.execute(
    `INSERT INTO ${KEYSPACE}.adventure_fence_eggs (chat_id, player_id, egg_id, egg_state, hatch_id, pokemon_id, save_fence_json, updated_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, toTimestamp(now()))`,
    [chat, player, eggId, eggState, hatchId, pokemonId, JSON.stringify(seal)],
    {prepare: true, consistency: cassandra.types.consistencies.quorum});
  return loadEgg(client, chat, player, eggId);
}

async function loadEgg(client, chat, player, eggId) {
  const row = eggRow(await client.execute(
    `SELECT egg_id, egg_state, hatch_id, pokemon_id, save_fence_json FROM ${KEYSPACE}.adventure_fence_eggs
     WHERE chat_id = ? AND player_id = ? AND egg_id = ?`,
    [chat, player, eggId],
    {prepare: true, consistency: cassandra.types.consistencies.serial}));
  if (!row) return null;
  return {
    eggId: row.egg_id,
    eggState: row.egg_state,
    hatchId: row.hatch_id,
    pokemonId: row.pokemon_id,
    seal: JSON.parse(row.save_fence_json)
  };
}

async function setEggState(client, chat, player, eggId, eggState) {
  await client.execute(
    `UPDATE ${KEYSPACE}.adventure_fence_eggs SET egg_state = ?, updated_at = toTimestamp(now())
     WHERE chat_id = ? AND player_id = ? AND egg_id = ?`,
    [eggState, chat, player, eggId],
    {prepare: true, consistency: cassandra.types.consistencies.quorum});
}

function hatchEffectId(hatchId) {
  const id = `hatch_${String(hatchId).replace(/[^A-Za-z0-9_-]/g, '_')}`;
  assert.match(id, /^[A-Za-z0-9_-]{8,100}$/);
  return id;
}

async function deliverWithPersistedFence(store, {chat, player, egg, pokemon}) {
  // Mirrors Adventure: reinstall the incubate-era seal, then mutate the trainer
  // under that ALS. applyOnce enforces the inherited fence on the final LWT;
  // never refresh the seal from the live row.
  const seal = egg.seal;
  return store.runPersistedContext(chat, player, seal, async () => {
    const current = await store.readAccount(chat, player);
    const registro = {
      'id-pokemon': egg.pokemonId, nome: pokemon?.nome || 'Hatched', nivel: 1, hp: 10, 'hp-atual': 10,
      ataque: 1, defesa: 1, 'atq-esp': 1, 'def-esp': 1, veloc: 1,
      'xp-desde-nivel': 0, shiny: false, golpes: [], tipos: ['normal']
    };
    // writerContext must be the frozen incubate fence (ALS), not a recaptured
    // live generation. build it from the seal so assertContext cannot adopt B.
    const writerContext = {
      identityHash: seal.identityHash,
      fenceGeneration: seal.fenceGeneration,
      activeSaveId: seal.activeSaveId,
      transitionOwner: seal.transitionOwner || ''
    };
    return store.applyOnce(chat, player, {
      effectId: hatchEffectId(egg.hatchId),
      intentHash: hash({hatch: egg.hatchId, pokemon: egg.pokemonId}),
      expectedGeneration: current.generation,
      expectedStateHash: current.stateHash,
      writerContext,
      transform: state => {
        const receipts = {...(state['adventure-entregas'] || {})};
        if (receipts[egg.hatchId]) return state;
        return {
          ...state,
          equipe: [...(state.equipe || []), registro],
          'adventure-entregas': {...receipts, [egg.hatchId]: egg.pokemonId}
        };
      }
    });
  });
}

function activationService(execute, trainerStore) {
  const slotStore = new SaveSlotStore(execute, KEYSPACE, {now: () => '2026-10-16T20:00:00.000Z'});
  const coordinator = new SaveTransitionCoordinator(execute, KEYSPACE, {
    enabled: true, legacyJournalCoverageVerified: true, ...TEST_WRITER_COVERAGE,
    now: () => '2026-10-16T20:00:00.000Z'
  });
  return {
    slotStore,
    activation: new PlayableSaveActivation(execute, KEYSPACE, {
      slotStore, trainerStore, coordinator, enabled: true,
      now: () => '2026-10-16T20:00:00.000Z'
    })
  };
}

async function seed(client, cassandraVersion) {
  await setup(client);
  const execute = executor(client);
  const trainer = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-fence-seed'});
  const chat = 'adventure-fence-group';
  const player = 'player-a';
  const peer = 'player-b';
  const saveA = account('save-a', {'adventure-entregas': {}});
  const saveB = account('save-b', {moedas: 22, 'adventure-entregas': {}});
  saveB.equipe[0].shiny = true;
  const peerAccount = account('peer', {moedas: 55});

  await trainer.createAccount(chat, player, saveA, 'adventure-fence-a');
  await trainer.createAccount(chat, peer, peerAccount, 'adventure-fence-peer');

  // 1) Incubate on save A and stamp the frozen fence into durable egg state.
  let incubateSeal;
  await trainer.withWriterContext(chat, () => {
    incubateSeal = trainer.freezeSaveBoundTask(player);
  }, undefined, true, player);
  assert.equal(incubateSeal.activeSaveId, 'a');
  assert.equal(incubateSeal.fenceGeneration, 1);
  const deliveredEgg = await persistEgg(client, {
    chat, player, eggId: 'egg-deliver-once', seal: incubateSeal,
    hatchId: 'hatch-deliver-01', pokemonId: 'hatched-once'
  });
  assert.equal(deliveredEgg.seal.fenceGeneration, 1);

  // 2) Process restart simulation: new store instance reloads the persisted fence.
  const restartedTrainer = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-fence-restarted'});
  const reloaded = await loadEgg(client, chat, player, 'egg-deliver-once');
  assert.deepEqual(reloaded.seal, incubateSeal);
  assert.ok(normalizeSeal(reloaded.seal));

  // 3) Delivery succeeds exactly once while A remains active.
  await setEggState(client, chat, player, 'egg-deliver-once', 'delivery_pending');
  const first = await deliverWithPersistedFence(restartedTrainer, {chat, player, egg: reloaded});
  assert.equal(first.applied, true);
  assert.equal(first.state.equipe.filter(p => p['id-pokemon'] === 'hatched-once').length, 1);
  assert.equal(first.state['adventure-entregas']['hatch-deliver-01'], 'hatched-once');
  await setEggState(client, chat, player, 'egg-deliver-once', 'hatched');

  // 9) Global receipts do not duplicate rewards on retry of the same hatch.
  const replay = await deliverWithPersistedFence(restartedTrainer, {chat, player, egg: reloaded});
  assert.equal(replay.applied, false);
  assert.equal(replay.state.equipe.filter(p => p['id-pokemon'] === 'hatched-once').length, 1);
  assert.equal(Object.keys(replay.state['adventure-entregas']).filter(id => id === 'hatch-deliver-01').length, 1);

  // 4) Reservation before delivery rejects the trainer mutation.
  let reservedSeal;
  await trainer.withWriterContext(chat, () => {
    reservedSeal = trainer.freezeSaveBoundTask(player);
  }, undefined, true, player);
  const reservedEgg = await persistEgg(client, {
    chat, player, eggId: 'egg-reserved', seal: reservedSeal,
    hatchId: 'hatch-reserved-01', pokemonId: 'blocked-by-reserve', eggState: 'delivery_pending'
  });
  const beforeReserve = await trainer.readAccount(chat, player);
  const reserved = await trainer.reserveTransition(chat, player, beforeReserve, 'transition_hatch_01');
  await assert.rejects(
    deliverWithPersistedFence(trainer, {chat, player, egg: reservedEgg}),
    {code: 'canonical_trainer_fence_conflict'});
  const held = await trainer.readAccount(chat, player);
  assert.throws(() => assertSealMatchesRecord(reservedSeal, player, held),
    {code: 'canonical_trainer_transition_reserved'});
  assert.equal(held.state.equipe.filter(p => p['id-pokemon'] === 'blocked-by-reserve').length, 0);
  assert.equal((await loadEgg(client, chat, player, 'egg-reserved')).eggState, 'delivery_pending');

  // 8) Peer in the same group remains writable during the reservation.
  await trainer.withWriterContext(chat, async () => {
    const peerRow = await trainer.readAccount(chat, peer);
    await trainer.compareAndSet(chat, peer, peerRow, {...peerRow.state, moedas: 56});
  }, undefined, true, peer);
  assert.equal((await trainer.readAccount(chat, peer)).state.moedas, 56);
  await trainer.releaseTransition(chat, player, reserved, 'transition_hatch_01');

  // Prepare A→B with playable activation; incubate another A egg before the switch.
  const {slotStore, activation} = activationService(execute, trainer);
  await slotStore.importValidated({
    chat, player, slotId: 'historic-b',
    source: {kind: 'legacy_estado_particionado', version: '1'},
    account: saveB, currentAccount: (await trainer.readAccount(chat, player)).state
  });
  await activation.bootstrap(chat, player, 'a');
  await activation.importHistorical(chat, player, 'historic-b', 'b');

  const hatchResume = new AdventureHatchResume(execute, KEYSPACE, {trainerStore: trainer});
  let pendingASeal;
  await trainer.withWriterContext(chat, () => {
    pendingASeal = trainer.freezeSaveBoundTask(player);
  }, undefined, true, player);
  const pendingAEgg = await persistEgg(client, {
    chat, player, eggId: 'egg-pending-a', seal: pendingASeal,
    hatchId: 'hatch-pending-a', pokemonId: 'from-save-a', eggState: 'delivery_pending'
  });
  await hatchResume.registerPending({
    chat, player, eggId: 'egg-pending-a', hatchId: 'hatch-pending-a',
    pokemonId: 'from-save-a', incubateSeal: pendingASeal
  });
  assert.equal(pendingASeal.activeSaveId, 'a');

  // 5) Activate B; pending delivery from A must not mutate B.
  await activation.activate(chat, player, 'b', 'advFence-B-0001');
  const afterB = await trainer.readAccount(chat, player);
  assert.equal(afterB.activeSaveId, 'b');
  assert.equal(afterB.state.equipe[0]['id-pokemon'], 'save-b');
  const equipeBeforeRejectedDelivery = structuredClone(afterB.state.equipe);
  const receiptsBeforeRejectedDelivery = structuredClone(afterB.state['adventure-entregas']);
  await assert.rejects(
    deliverWithPersistedFence(trainer, {chat, player, egg: pendingAEgg}),
    {code: 'canonical_trainer_fence_conflict'});
  await assert.rejects(
    hatchResume.resume(chat, player, 'egg-pending-a', 'resumeOp-on-B'),
    {code: 'adventure_hatch_resume_save_mismatch'});
  const stillB = await trainer.readAccount(chat, player);
  assert.equal(stillB.activeSaveId, 'b');
  assert.deepEqual(stillB.state.equipe, equipeBeforeRejectedDelivery);
  assert.deepEqual(stillB.state['adventure-entregas'], receiptsBeforeRejectedDelivery);
  assert.equal((await loadEgg(client, chat, player, 'egg-pending-a')).eggState, 'delivery_pending');

  // 6) Return to A: incubate-era fence stays invalid for blind replay, but the
  // explicit resume proves origin and binds a new audited fence.
  await activation.activate(chat, player, 'a', 'advFence-A-0001');
  const backOnA = await trainer.readAccount(chat, player);
  assert.equal(backOnA.activeSaveId, 'a');
  assert.ok(backOnA.fenceGeneration > pendingASeal.fenceGeneration,
    'returning to A advances fence_generation; incubate seal cannot be reused blindly');
  assert.throws(() => assertSealMatchesRecord(pendingASeal, player, backOnA),
    {code: 'save_bound_fence_conflict'});
  await assert.rejects(
    deliverWithPersistedFence(trainer, {chat, player, egg: pendingAEgg}),
    {code: 'canonical_trainer_fence_conflict'});
  const resumed = await hatchResume.resume(chat, player, 'egg-pending-a', 'resumeOp-A0001');
  assert.equal(resumed.applied, true);
  assert.equal(resumed.account.state.equipe.filter(p => p['id-pokemon'] === 'from-save-a').length, 1);
  assert.equal(resumed.account.state['adventure-entregas']['hatch-pending-a'], 'from-save-a');
  assert.equal((await hatchResume.loadPending(chat, player, 'egg-pending-a')).eggState, 'hatched');
  await setEggState(client, chat, player, 'egg-pending-a', 'hatched');
  const resumedAgain = await hatchResume.resume(chat, player, 'egg-pending-a', 'resumeOp-A0001');
  assert.equal(resumedAgain.idempotent, true);

  // 7) Lost LWT acknowledgement + retry remains idempotent via operation receipts
  // and adventure-entregas (global receipt).
  let lostSeal;
  await trainer.withWriterContext(chat, () => {
    lostSeal = trainer.freezeSaveBoundTask(player);
  }, undefined, true, player);
  const lostEgg = await persistEgg(client, {
    chat, player, eggId: 'egg-lost-lwt', seal: lostSeal,
    hatchId: 'hatch-lost-lwt', pokemonId: 'lost-then-retry', eggState: 'delivery_pending'
  });
  await hatchResume.registerPending({
    chat, player, eggId: 'egg-lost-lwt', hatchId: 'hatch-lost-lwt',
    pokemonId: 'lost-then-retry', incubateSeal: lostSeal
  });
  let lose = true;
  const interrupted = new CanonicalTrainerStore(async (sql, args, opts) => {
    const result = await execute(sql, args, opts);
    if (lose && sql.startsWith('UPDATE') && sql.includes('operation_receipts = ?') &&
        sql.includes('canonical_trainer_accounts')) {
      lose = false;
      throw Object.assign(new Error('simulated lost hatch LWT response'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, KEYSPACE, {writerId: 'adventure-fence-lost'});
  const interruptedResume = new AdventureHatchResume(execute, KEYSPACE, {trainerStore: interrupted});
  const recovered = await interruptedResume.resume(chat, player, 'egg-lost-lwt', 'resumeOp-lost1');
  assert.equal(recovered.account.state['adventure-entregas']['hatch-lost-lwt'], 'lost-then-retry');
  assert.equal(recovered.account.state.equipe.filter(p => p['id-pokemon'] === 'lost-then-retry').length, 1);
  const retry = await hatchResume.resume(chat, player, 'egg-lost-lwt', 'resumeOp-lost1');
  assert.equal(retry.idempotent, true);
  assert.equal(retry.account.state.equipe.filter(p => p['id-pokemon'] === 'lost-then-retry').length, 1);
  await setEggState(client, chat, player, 'egg-lost-lwt', 'hatched');

  // Leave a durable mid-resume marker for verify-restart: trainer receipt exists,
  // pending egg mark may still be delivery_pending until recover runs.
  let restartSeal;
  await trainer.withWriterContext(chat, () => {
    restartSeal = trainer.freezeSaveBoundTask(player);
  }, undefined, true, player);
  await persistEgg(client, {
    chat, player, eggId: 'egg-resume-restart', seal: restartSeal,
    hatchId: 'hatch-resume-restart', pokemonId: 'restart-mon', eggState: 'delivery_pending'
  });
  await hatchResume.registerPending({
    chat, player, eggId: 'egg-resume-restart', hatchId: 'hatch-resume-restart',
    pokemonId: 'restart-mon', incubateSeal: restartSeal
  });
  let loseAfterDeliver = true;
  const flakyTrainer = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-fence-flaky'});
  const originalApply = flakyTrainer.applyOnce.bind(flakyTrainer);
  flakyTrainer.applyOnce = async (...args) => {
    const result = await originalApply(...args);
    if (loseAfterDeliver) {
      loseAfterDeliver = false;
      throw Object.assign(new Error('lost after hatch deliver'), {code: 'synthetic_lost_after_deliver'});
    }
    return result;
  };
  const flakyResume = new AdventureHatchResume(execute, KEYSPACE, {trainerStore: flakyTrainer});
  await assert.rejects(flakyResume.resume(chat, player, 'egg-resume-restart', 'resumeOp-boot1'),
    {code: 'synthetic_lost_after_deliver'});
  assert.equal((await trainer.readAccount(chat, player)).state['adventure-entregas']['hatch-resume-restart'],
    'restart-mon');
  assert.equal((await hatchResume.loadPending(chat, player, 'egg-resume-restart')).eggState,
    'delivery_pending');

  const final = await trainer.readAccount(chat, player);
  console.log(JSON.stringify({
    event: 'adventure_fence_cassandra_seed_passed',
    cassandra: cassandraVersion,
    deliveredOnce: true,
    reservationRejected: true,
    saveBUnchangedByPendingA: true,
    resumeAfterAtoBtoA: true,
    lostLwtIdempotent: true,
    peerIsolation: true,
    globalReceiptNoDuplicate: true,
    restartRecoveryPending: true,
    finalFenceGeneration: final.fenceGeneration,
    finalActiveSaveId: final.activeSaveId,
    receiptCount: Object.keys(final.state['adventure-entregas'] || {}).length
  }));
}

async function verifyRestart(client) {
  const execute = executor(client);
  const trainer = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-fence-verify'});
  const hatchResume = new AdventureHatchResume(execute, KEYSPACE, {trainerStore: trainer});
  const chat = 'adventure-fence-group';
  const player = 'player-a';
  const peer = 'player-b';

  const pendingClaim = await hatchResume.loadPending(chat, player, 'egg-resume-restart');
  assert.ok(pendingClaim, 'resume claim must survive Cassandra restart');
  assert.equal(pendingClaim.originSaveId, 'a');

  const current = await trainer.readAccount(chat, player);
  assert.equal(current.activeSaveId, 'a');
  assert.equal(current.state['adventure-entregas']['hatch-resume-restart'], 'restart-mon');
  assert.equal(current.state['adventure-entregas']['hatch-pending-a'], 'from-save-a');

  const recovered = await hatchResume.recover(chat, player, 'egg-resume-restart', 'resumeOp-boot1');
  assert.equal(recovered.idempotent, true);
  assert.equal((await hatchResume.loadPending(chat, player, 'egg-resume-restart')).eggState, 'hatched');
  assert.equal(recovered.account.state.equipe.filter(p => p['id-pokemon'] === 'restart-mon').length, 1);

  const delivered = await loadEgg(client, chat, player, 'egg-deliver-once');
  assert.equal(delivered.eggState, 'hatched');
  assert.equal(current.state['adventure-entregas']['hatch-deliver-01'], 'hatched-once');
  const replay = await deliverWithPersistedFence(trainer, {chat, player, egg: delivered});
  assert.equal(replay.applied, false);
  assert.equal(replay.state.equipe.filter(p => p['id-pokemon'] === 'hatched-once').length, 1);

  assert.equal((await trainer.readAccount(chat, peer)).state.moedas, 56);
  assert.equal((await hatchResume.loadPending(chat, player, 'egg-pending-a')).eggState, 'hatched');

  console.log(JSON.stringify({
    event: 'adventure_fence_cassandra_restart_passed',
    resumeRecoveredAfterRestart: true,
    receiptSurvivedRestart: true,
    peerIsolationSurvivedRestart: true,
    noDuplicateAfterRecover: true
  }));
}

async function main() {
  const client = new cassandra.Client({
    contactPoints: [host], localDataCenter: 'datacenter1',
    authProvider: new cassandra.auth.PlainTextAuthProvider('cassandra', 'cassandra'),
    socketOptions: {connectTimeout: 10000, readTimeout: 15000}
  });
  try {
    const cassandraVersion = await connectReady(client);
    if (phase === 'seed') await seed(client, cassandraVersion);
    else if (phase === 'verify-restart') await verifyRestart(client);
    else throw new Error(`unknown_adventure_fence_cassandra_phase:${phase}`);
  } finally {
    await client.shutdown();
  }
}

if (require.main === module) {
  main().catch(error => {
    console.error(JSON.stringify({
      event: 'adventure_fence_cassandra_failed', phase,
      error: error?.name || 'Error', code: error?.code || 'integration_failure',
      message: String(error?.message || '').replace(/[\r\n]+/g, ' ').slice(0, 220)
    }));
    process.exitCode = 1;
  });
}

module.exports = {
  HOST, KEYSPACE, normalizeSeal, persistEgg, loadEgg, deliverWithPersistedFence, hatchEffectId
};
