'use strict';

// Opt-in integration: real domain.cjs path
// adventure.cljs → armazenamento.cljs → AdventureHatchResume against disposable
// Cassandra. Does not enable production flags or certifications.
// Requires a prebuilt target/domain.cjs (npm run build in pokemon-service).
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cassandra = require('cassandra-driver');
const {CanonicalTrainerStore, PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');
const {SaveSlotStore} = require('../runtime/save-slot-store.cjs');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');
const {AdventureHatchResume} = require('../runtime/adventure-hatch-resume.cjs');

const HOST = 'zapbot-adventure-birth-cassandra-test';
const KEYSPACE = 'adventure_birth_validation';
const CANONICAL_USER = 'zapbot_birth_canonical';
const CANONICAL_PASS = 'birth-canonical-secret';
const TEST_WRITER_COVERAGE = {writerProtocolCoverageVerified: true};
const host = process.env.ADVENTURE_BIRTH_CASSANDRA_HOST;
const phase = process.env.ADVENTURE_BIRTH_CASSANDRA_PHASE || 'seed';

if (require.main === module && (process.env.ADVENTURE_BIRTH_CASSANDRA_ISOLATED !== 'true' ||
    host !== HOST || !['seed', 'verify-restart'].includes(phase))) {
  throw new Error('Refusing Adventure birth Cassandra integration without the isolated test harness.');
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

async function activateCanonicalControl(client, migrationId) {
  return client.execute(
    `INSERT INTO ${KEYSPACE}.canonical_trainer_control (capability,protocol_version,state,migration_id,updated_at) VALUES (?,?,?,?,toTimestamp(now())) IF NOT EXISTS`,
    ['trainer-canonical', PROTOCOL_VERSION, 'active', migrationId],
    {prepare: true, consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial});
}

async function provisionRoles(admin) {
  await admin.execute(
    `CREATE ROLE IF NOT EXISTS ${CANONICAL_USER} WITH PASSWORD='${CANONICAL_PASS}' AND LOGIN=true`);
  await admin.execute(`GRANT ALL PERMISSIONS ON KEYSPACE ${KEYSPACE} TO ${CANONICAL_USER}`);
  await admin.execute(`GRANT ALL PERMISSIONS ON KEYSPACE ${KEYSPACE} TO cassandra`);
}

async function setupSchema(client) {
  await client.execute(
    `CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado (chave text PRIMARY KEY, valor text)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_accounts (chat_id text, player_id text, generation bigint, schema_version int, protocol_version int, state_hash text, state_json text, operation_receipts map<text,text>, fence_generation bigint, active_save_id text, transition_owner text, updated_at timestamp, writer_id text, migration_id text, PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_backups (chat_id text, player_id text, backup_id text, source_hash text, source_json text, created_at timestamp, PRIMARY KEY ((chat_id, player_id), backup_id))`
  ]) await client.execute(ddl);
  const schema = fs.readFileSync(path.join(__dirname, '../../scripts/cassandra/adventure-sync-v1.cql'), 'utf8')
    .replaceAll('{{KEYSPACE}}', KEYSPACE).replace(/^--.*$/gm, '');
  for (const sql of schema.split(';').map(s => s.trim()).filter(Boolean)) await client.execute(sql);
  await activateCanonicalControl(client, 'adventure-birth');
  await provisionRoles(client);
}

function activationService(execute, trainer) {
  const slotStore = new SaveSlotStore(execute, KEYSPACE);
  const coordinator = new SaveTransitionCoordinator(execute, KEYSPACE, {
    enabled: true, legacyJournalCoverageVerified: true, ...TEST_WRITER_COVERAGE
  });
  const activation = new PlayableSaveActivation(execute, KEYSPACE, {
    slotStore, trainerStore: trainer, coordinator, enabled: true
  });
  return {slotStore, activation};
}

function applyDomainEnv() {
  process.env.CASSANDRA_CONTACT_POINTS = host;
  process.env.CASSANDRA_DATACENTER = 'datacenter1';
  process.env.CASSANDRA_KEYSPACE = KEYSPACE;
  process.env.CASSANDRA_USERNAME = 'cassandra';
  process.env.CASSANDRA_PASSWORD = 'cassandra';
  process.env.ADVENTURE_SYNC_ENABLED = 'true';
  process.env.CANONICAL_TRAINER_ENABLED = 'true';
  process.env.CANONICAL_TRAINER_USERNAME = CANONICAL_USER;
  process.env.CANONICAL_TRAINER_PASSWORD = CANONICAL_PASS;
  process.env.SAVE_TRANSITIONS_ENABLED = 'false';
  process.env.SAVE_TRANSITIONS_LEGACY_COVERAGE_VERIFIED = 'false';
  process.env.POKEMON_READ_ONLY = 'false';
  process.env.PERFORMANCE_METRICS = 'false';
  process.env.EGG_CAPTURE_COMMON_PERCENT = '100';
  process.env.EGG_PVP_EPIC_PERCENT = '100';
  process.env.EGG_PVP_RARE_PERCENT = '100';
}

function requireDomain() {
  const domainPath = path.join(__dirname, '../target/domain.cjs');
  if (!fs.existsSync(domainPath)) {
    throw new Error('target/domain.cjs missing; run npm run build in pokemon-service first.');
  }
  // Config is read at require-time; env must already be set.
  delete require.cache[require.resolve(domainPath)];
  return require(domainPath);
}

function eggIdOf(activity) {
  const egg = (activity.eggs || []).find(e => e.state !== 'hatched');
  assert.ok(egg, 'expected a non-hatched activity egg');
  return egg['egg-id'] || egg.eggId || egg['egg_id'];
}

function eggById(activity, eggId) {
  return (activity.eggs || []).find(e => (e['egg-id'] || e.eggId || e['egg_id']) === eggId);
}

function sealOf(egg) {
  return egg['save-fence'] || egg.saveFence || egg['save_fence'];
}

function trainerOf(domain, chat, player) {
  return domain.load('treinador')[chat][player];
}

async function plantCaptureEvent(trainer, chat, player, eventId, at) {
  await trainer.withWriterContext(chat, async () => {
    const row = await trainer.readAccount(chat, player);
    await trainer.compareAndSet(chat, player, row, {
      ...row.state,
      'adventure-eventos': {
        ...(row.state['adventure-eventos'] || {}),
        [eventId]: {'event-id': eventId, kind: 'capture', at}
      }
    });
  }, undefined, true, player);
}

async function seed(client) {
  await setupSchema(client);
  const execute = executor(client);
  const trainer = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-birth-seed'});
  const chat = 'birth-group';
  const player = 'player@lid';
  const peer = 'peer@lid';
  const saveA = account('birth-a', {moedas: 11, 'adventure-entregas': {}});
  const saveB = account('birth-b', {moedas: 22, 'adventure-entregas': {}});
  saveB.equipe[0].shiny = true;
  const peerAccount = account('birth-peer', {moedas: 55});

  await trainer.createAccount(chat, player, saveA, 'adventure-birth-a');
  await trainer.createAccount(chat, peer, peerAccount, 'adventure-birth-peer');

  const {slotStore, activation} = activationService(execute, trainer);
  await slotStore.importValidated({
    chat, player, slotId: 'historic-b',
    source: {kind: 'legacy_estado_particionado', version: '1'},
    account: saveB, currentAccount: (await trainer.readAccount(chat, player)).state
  });
  await activation.bootstrap(chat, player, 'a');
  await activation.importHistorical(chat, player, 'historic-b', 'b');

  applyDomainEnv();
  const domain = requireDomain();
  const clock = Date.now;
  let now = clock();
  Date.now = () => now;

  try {
    await domain.initialize();

    // 1) Incubate on save A through the real CLJS activity path (selo stamped).
    const activated = await domain.activityActivate(chat, player, 'birth-welcome');
    const pendingEggId = eggIdOf(activated);
    await domain.activityIncubate(chat, player, 'birth-incubate-pending', pendingEggId);
    const incubating = await domain.activityState(chat, player);
    const pendingEgg = eggById(incubating, pendingEggId);
    const fence = sealOf(pendingEgg);
    assert.ok(fence, 'incubate must persist save-fence via selo-save-atual');
    assert.equal(fence.activeSaveId || fence['active-save-id'], 'a');

    // 2) A→B before hatch delivery: pending must not mutate B.
    // Playable activation is not exported on domain; Node adapter switches the
    // canonical pointer, then domain reloads atoms from Cassandra.
    await domain.shutdown();
    await activation.activate(chat, player, 'b', 'birthAct-B-0001');
    assert.equal((await trainer.readAccount(chat, player)).activeSaveId, 'b');
    await domain.initialize();

    now += 8 * 60 * 60 * 1000;
    const whileOnB = await domain.activityTime(chat, player, now);
    const eggOnB = eggById(whileOnB, pendingEggId);
    assert.ok(eggOnB);
    assert.notEqual(eggOnB.state, 'hatched', 'A origin egg must not hatch into B');
    const onB = await trainer.readAccount(chat, player);
    assert.equal(onB.activeSaveId, 'b');
    assert.equal(onB.state.equipe[0]['id-pokemon'], 'birth-b');
    assert.equal(Object.keys(onB.state['adventure-entregas'] || {}).length, 0);
    assert.equal((await trainer.readAccount(chat, peer)).state.moedas, 55,
      'peer must remain untouched while player save switches');

    const hatchResume = new AdventureHatchResume(execute, KEYSPACE, {trainerStore: trainer});
    const claim = await hatchResume.loadPending(chat, player, pendingEggId);
    assert.ok(claim, 'CLJS path must register AdventureHatchResume pending claim');
    assert.equal(claim.originSaveId, 'a');
    assert.equal(claim.eggState, 'delivery_pending');

    // 3) B→A then resume exactly once via real recuperar-atividade! / entregar-nascimento-protegido!.
    await domain.shutdown();
    await activation.activate(chat, player, 'a', 'birthAct-A-0001');
    const backA = await trainer.readAccount(chat, player);
    assert.equal(backA.activeSaveId, 'a');
    assert.equal(backA.transitionOwner, '', 'activation must release the canonical transition owner');
    assert.ok(backA.fenceGeneration > (fence.fenceGeneration || fence['fence-generation'] || 1));
    const {hash} = require('../runtime/restore-protocol.cjs');
    const lockPartition = hash([chat, 'account', player]);
    const lockRow = (await client.execute(
      `SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo = ? AND particao = ?`,
      ['pokemon-save-transition-locks', lockPartition],
      {prepare: true, consistency: cassandra.types.consistencies.serial})).first();
    if (lockRow?.valor) {
      const lock = JSON.parse(lockRow.valor);
      assert.equal(lock.owner, null, 'activation must clear the durable transition lock owner');
    }
    try {
      await domain.initialize();
    } catch (error) {
      const cause = error?.cause || error?.data?.cause;
      throw Object.assign(new Error(`post_ba_initialize_failed:${JSON.stringify({
        message: error?.message, code: error?.code || error?.data?.code,
        causeCode: cause?.code, causeMessage: cause?.message
      })}`), {cause: error});
    }

    let resumed;
    try {
      resumed = await domain.activityTime(chat, player, now);
    } catch (error) {
      const cause = error?.cause || error?.data?.cause;
      const detail = {
        message: error?.message, code: error?.code || error?.data?.code,
        causeCode: cause?.code, causeMessage: cause?.message,
        exData: error?.data
      };
      throw Object.assign(new Error(`resume_delivery_failed:${JSON.stringify(detail)}`), detail);
    }
    const hatched = eggById(resumed, pendingEggId);
    assert.equal(hatched.state, 'hatched');
    const afterResume = trainerOf(domain, chat, player);
    const deliveries = afterResume['adventure-entregas'] || {};
    assert.equal(Object.keys(deliveries).length, 1, 'exactly one global hatch receipt after first resume');
    assert.equal(deliveries[claim.hatchId], claim.pokemonId);
    assert.equal(afterResume.equipe.filter(p => p['id-pokemon'] === claim.pokemonId).length, 1);
    assert.equal((await hatchResume.loadPending(chat, player, pendingEggId)).eggState, 'hatched');

    await domain.activityTime(chat, player, now);
    const replay = trainerOf(domain, chat, player);
    assert.equal(Object.keys(replay['adventure-entregas']).length, 1);
    assert.equal(replay.equipe.filter(p => p['id-pokemon'] === claim.pokemonId).length, 1);

    // 4) Restart between claim registration and trainer delivery (second egg on A).
    await domain.activityGrantIncubator(chat, player, 'birth-extra', 'extra');
    await domain.shutdown();
    await plantCaptureEvent(trainer, chat, player, 'birth-capture-1', now);
    await domain.initialize();
    await domain.activityDrain(chat);
    const withSecond = await domain.activityState(chat, player);
    const restartEgg = withSecond.eggs.find(e => e.state !== 'hatched' &&
      (e['egg-id'] || e.eggId) !== pendingEggId);
    assert.ok(restartEgg, 'capture event must grant a second activity egg');
    const restartEggId = restartEgg['egg-id'] || restartEgg.eggId;
    await domain.activityIncubate(chat, player, 'birth-incubate-restart', restartEggId);

    const beforeRestart = await domain.activityState(chat, player);
    assert.equal(eggById(beforeRestart, restartEggId).state, 'incubating');

    // Claim write is reconciled inside AdventureHatchResume.cas (lost acks are
    // re-read). Interrupt the *trainer* deliver after the claim is durable so
    // restart reconciles claim→delivery without duplicating.
    let blockDeliver = true;
    const originalExecute = cassandra.Client.prototype.execute;
    cassandra.Client.prototype.execute = function(sql, args, opts) {
      if (blockDeliver && typeof sql === 'string' && sql.startsWith('UPDATE') &&
          sql.includes('canonical_trainer_accounts') && sql.includes('state_json')) {
        blockDeliver = false;
        return Promise.reject(Object.assign(new Error('simulated lost response after hatch claim'),
          {code: 'synthetic_lost_after_claim'}));
      }
      return originalExecute.call(this, sql, args, opts);
    };
    try {
      now += 8 * 60 * 60 * 1000;
      let interrupted = false;
      try {
        await domain.activityTime(chat, player, now);
      } catch (error) {
        interrupted = true;
        const code = String(error?.code || error?.cause?.code || error?.data?.code ||
          error?.cause?.data?.code || '');
        const message = String(error?.message || '');
        assert.ok(
          /synthetic_lost_after_claim/.test(code) || /simulated lost|Gravação canônica/.test(message),
          `unexpected interrupt: code=${code} message=${message}`);
      }
      assert.equal(interrupted, true, 'deliver interrupt must reject activityTime');
    } finally {
      cassandra.Client.prototype.execute = originalExecute;
    }
    assert.equal(blockDeliver, false, 'fault injection must observe the trainer deliver write');

    const midClaim = await hatchResume.loadPending(chat, player, restartEggId);
    assert.ok(midClaim, 'claim must be durable before the interrupted deliver');
    assert.equal(midClaim.eggState, 'delivery_pending');
    assert.equal(Object.prototype.hasOwnProperty.call(
      trainerOf(domain, chat, player)['adventure-entregas'] || {}, midClaim.hatchId), false);

    // 5) Process restart: domain reload reconciles and delivers exactly once.
    await domain.shutdown();
    Date.now = () => now;
    await domain.initialize();
    const recovered = await domain.activityState(chat, player);
    assert.equal(eggById(recovered, restartEggId).state, 'hatched');
    const afterBoot = trainerOf(domain, chat, player);
    assert.equal(afterBoot['adventure-entregas'][midClaim.hatchId], midClaim.pokemonId);
    assert.equal(afterBoot.equipe.filter(p => p['id-pokemon'] === midClaim.pokemonId).length, 1);
    assert.equal(Object.keys(afterBoot['adventure-entregas']).length, 2,
      'global receipts for both hatches must be preserved');

    assert.equal((await trainer.readAccount(chat, peer)).state.moedas, 55);
    assert.equal((await trainer.readAccount(chat, peer)).state.equipe[0]['id-pokemon'], 'birth-peer');

    await client.execute(
      `INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor) VALUES (?, ?, ?)`,
      ['adventure-birth-marker', 'seed', JSON.stringify({
        chat, player, peer, pendingEggId, restartEggId,
        hatchIds: Object.keys(afterBoot['adventure-entregas']),
        pokemonIds: [claim.pokemonId, midClaim.pokemonId]
      })],
      {prepare: true});

    console.log(JSON.stringify({
      event: 'adventure_birth_seed_ok',
      pendingDeliveredOnce: true,
      resumeAfterABBA: true,
      restartBetweenClaimAndDeliver: true,
      globalReceipts: Object.keys(afterBoot['adventure-entregas']).length,
      peerUntouched: true
    }));
  } finally {
    Date.now = clock;
    try { await domain.shutdown(); } catch {}
  }
}

async function verifyRestart(client) {
  const marker = (await client.execute(
    `SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo = ? AND particao = ?`,
    ['adventure-birth-marker', 'seed'],
    {prepare: true, consistency: cassandra.types.consistencies.serial})).first();
  assert.ok(marker?.valor, 'seed marker required');
  const meta = JSON.parse(marker.valor);
  applyDomainEnv();
  const domain = requireDomain();
  await domain.initialize();
  try {
    const state = await domain.activityState(meta.chat, meta.player);
    for (const id of [meta.pendingEggId, meta.restartEggId]) {
      assert.equal(eggById(state, id).state, 'hatched');
    }
    const trainerState = trainerOf(domain, meta.chat, meta.player);
    assert.deepEqual(Object.keys(trainerState['adventure-entregas']).sort(), meta.hatchIds.sort());
    for (const pokemonId of meta.pokemonIds) {
      assert.equal(trainerState.equipe.filter(p => p['id-pokemon'] === pokemonId).length, 1);
    }
    const execute = executor(client);
    const trainerStore = new CanonicalTrainerStore(execute, KEYSPACE, {writerId: 'adventure-birth-verify'});
    assert.equal((await trainerStore.readAccount(meta.chat, meta.peer)).state.moedas, 55);
    console.log(JSON.stringify({
      event: 'adventure_birth_verify_restart_ok',
      receiptsSurvivedRestart: true,
      peerUntouched: true
    }));
  } finally {
    await domain.shutdown();
  }
}

async function main() {
  const client = new cassandra.Client({
    contactPoints: [host], localDataCenter: 'datacenter1',
    authProvider: new cassandra.auth.PlainTextAuthProvider('cassandra', 'cassandra'),
    socketOptions: {connectTimeout: 10000, readTimeout: 15000}
  });
  try {
    await connectReady(client);
    if (phase === 'seed') await seed(client);
    else if (phase === 'verify-restart') await verifyRestart(client);
    else throw new Error(`unknown_adventure_birth_phase:${phase}`);
  } finally {
    await client.shutdown();
  }
}

if (require.main === module) {
  main().catch(error => {
    console.error(JSON.stringify({
      event: 'adventure_birth_cassandra_failed', phase,
      error: error?.name || 'Error', code: error?.code || 'integration_failure',
      message: String(error?.message || '').replace(/[\r\n]/g, ' ').slice(0, 400)
    }));
    process.exitCode = 1;
  });
}

module.exports = {HOST, KEYSPACE};
