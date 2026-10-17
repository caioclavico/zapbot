'use strict';

// Opt-in integration test. This script accepts only the Cassandra container
// name created by scripts/test-save-slots-cassandra.sh on its private network.
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');
const {SaveSlotStore} = require('../runtime/save-slot-store.cjs');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');
const {saveGroup} = require('../runtime/trainer-state-store.cjs');
const {CanonicalTrainerStore, PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');
const {CanonicalTrainerMigration, verifyLegacyWriterFence} = require('../runtime/canonical-trainer-migration.cjs');
const {CanonicalGymStore} = require('../runtime/canonical-gym-store.cjs');
const {CanonicalGymMigration} = require('../runtime/canonical-gym-migration.cjs');
const {GymBattleJournal} = require('../runtime/gym-battle-journal.cjs');
const {createRealGymBattleAdapters, finalizeRealGymRound} = require('../runtime/gym-battle-real-effects.cjs');
const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');

const HOST = 'zapbot-save-slots-cassandra-test';
const KEYSPACE = 'save_slots_validation';
const PLAYABLE_RACE_KEYSPACE = 'playable_save_race_validation';
const PLAYABLE_RECOVERY_KEYSPACE = 'playable_save_recovery_validation';
// Tests the coordinator mechanics against synthetic rows only. This test-only
// override does not certify domain writers in the production runtime.
const TEST_WRITER_COVERAGE = {writerProtocolCoverageVerified: true};
const host = process.env.SAVE_SLOTS_CASSANDRA_HOST;
const phase = process.env.SAVE_SLOTS_CASSANDRA_PHASE || 'seed';
if (require.main === module && (process.env.SAVE_SLOTS_CASSANDRA_ISOLATED !== 'true' || host !== HOST ||
    !['seed', 'verify-restart', 'verify-pointer-restart'].includes(phase))) {
  throw new Error('Refusing Cassandra integration without the isolated test harness.');
}

const account = (id, extra = {}) => ({
  ativo: 0,
  equipe: [{
    'id-pokemon': id, nome: 'Synthetic', nivel: 1, hp: 10, 'hp-atual': 0,
    ataque: 5, defesa: 6, 'atq-esp': 7, 'def-esp': 8, veloc: 9,
    'xp-desde-nivel': 0, shiny: true, golpes: [], tipos: ['normal']
  }],
  pc: [{
    'id-pokemon': `${id}-pc`, nome: 'Stored', nivel: 2, hp: 12, 'hp-atual': 12,
    ataque: 10, defesa: 11, 'atq-esp': 12, 'def-esp': 13, veloc: 14,
    'xp-desde-nivel': 8, golpes: [], tipos: ['normal'], item: 'synthetic-item'
  }],
  enfermaria: [{
    pokemon: {
      'id-pokemon': `${id}-ward`, nome: 'Ward', nivel: 3, hp: 14, 'hp-atual': 7,
      ataque: 15, defesa: 16, 'atq-esp': 17, 'def-esp': 18, veloc: 19,
      'xp-desde-nivel': 0, shiny: false, golpes: [], tipos: ['normal']
    },
    'pronto-em': 123456
  }],
  inventario: {pokebola: 4}, moedas: 77,
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
    ...(options.fetchSize ? {fetchSize:options.fetchSize} : {}),
    ...(options.pageState ? {pageState:options.pageState} : {})
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

async function setup(client) {
  await client.execute(`CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  await client.execute(`CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`);
  await client.execute(`CREATE TABLE IF NOT EXISTS ${KEYSPACE}.pokemon_gym_battle_journal (chat_id text, battle_id text, generation bigint, state_hash text, state_json text, updated_at timestamp, writer_id text, PRIMARY KEY ((chat_id), battle_id))`);
  await client.execute(`CREATE TABLE IF NOT EXISTS ${KEYSPACE}.pokemon_gym_battle_index (bucket int, chat_id text, battle_id text, plan_hash text, state text, updated_at timestamp, PRIMARY KEY ((bucket), chat_id, battle_id))`);
  await client.execute(`CREATE TABLE IF NOT EXISTS ${KEYSPACE}.synthetic_battle_targets (chat_id text, target_id text, generation bigint, state_hash text, state_json text, operation_receipts set<text>, PRIMARY KEY ((chat_id), target_id))`);
}

function cassandraBattleAdapter(client) {
  const table = `${KEYSPACE}.synthetic_battle_targets`;
  const hasReceipt = (receipts,id) => receipts instanceof Set ? receipts.has(id) :
    Array.isArray(receipts) ? receipts.includes(id) : receipts instanceof Map ? receipts.has(id) :
      Boolean(receipts?.[id]);
  const read = async (chat, target) => (await client.execute(
    `SELECT generation, state_hash, state_json, operation_receipts FROM ${table} WHERE chat_id = ? AND target_id = ?`,
    [chat, target], {prepare:true,consistency:cassandra.types.consistencies.serial})).first();
  return {
    async hasReceipt({chat, effectId, payload}) {
      const record = await read(chat, payload.targetId);
      if (!record) return false;
      return hasReceipt(record.operation_receipts,effectId);
    },
    async applyOnce({chat, effectId, payload}) {
      const prior = await read(chat, payload.targetId);
      if (hasReceipt(prior?.operation_receipts,effectId)) {
        return;
      }
      if (!prior || Number(prior.generation) !== payload.expectedGeneration || prior.state_hash !== payload.expectedHash) {
        throw Object.assign(new Error('synthetic_effect_stale_generation'),{code:'synthetic_effect_stale_generation'});
      }
      const stateHash = hash(payload.nextState);
      const receipts = [...new Set([...(prior.operation_receipts || []), effectId])];
      const result = await client.execute(
        `UPDATE ${table} SET generation = ?, state_hash = ?, state_json = ?, operation_receipts = ? WHERE chat_id = ? AND target_id = ? IF generation = ? AND state_hash = ?`,
        [Number(prior.generation) + 1, stateHash, JSON.stringify(payload.nextState), receipts,
          chat, payload.targetId, Number(prior.generation), prior.state_hash],
        {prepare:true,consistency:cassandra.types.consistencies.quorum,serialConsistency:cassandra.types.consistencies.serial});
      if (!result.wasApplied()) {
        if (await this.hasReceipt({chat,effectId,payload})) return;
        throw Object.assign(new Error('synthetic_effect_cas_conflict'),{code:'synthetic_effect_cas_conflict'});
      }
    }
  };
}

async function seedSyntheticBattle(client) {
  const chat = 'battle-journal-synthetic', battleId = 'battle_journal_0001';
  const initial = [
    ['gym-01', {defenders:['synthetic-starter'],wins:0}]
  ];
  const snapshots = {};
  for (const [targetId,state] of initial) {
    const stateHash = hash(state);
    snapshots[targetId] = {generation:1,stateHash};
    await client.execute(`INSERT INTO ${KEYSPACE}.synthetic_battle_targets (chat_id,target_id,generation,state_hash,state_json,operation_receipts) VALUES (?,?,?,?,?,?) IF NOT EXISTS`,
      [chat,targetId,1,stateHash,JSON.stringify(state),[]],
      {prepare:true,consistency:cassandra.types.consistencies.quorum,serialConsistency:cassandra.types.consistencies.serial});
  }
  const gymPayload = {targetId:'gym-01',expectedGeneration:1,expectedHash:snapshots['gym-01'].stateHash,
    nextState:{defenders:['synthetic-starter'],wins:1}};
  const canonicalTrainer = new CanonicalTrainerStore(executor(client),KEYSPACE,{writerId:'battle-journal-test'});
  const trainerInitial = {equipe:[{'id-pokemon':'synthetic-starter','hp-atual':10,hp:10}],xp:0,moedas:0};
  await canonicalTrainer.createAccount(chat,'player',trainerInitial,'battle-journal-synthetic');
  const trainerSnapshot = await canonicalTrainer.readAccount(chat,'player');
  const trainerPayload = {expectedGeneration:trainerSnapshot.generation,expectedHash:trainerSnapshot.stateHash,
    writerContext:canonicalTrainer.context(chat,'player',trainerSnapshot),
    nextState:{...trainerInitial,equipe:[{'id-pokemon':'synthetic-starter','hp-atual':7,hp:10}],xp:5,moedas:10}};
  const input = {chat,player:'player',battleId,effects:[
    {id:'battle_journal_0001_gym',type:'gym-statistics',payload:gymPayload},
    {id:'battle_journal_0001_trainer',type:'trainer-account',payload:trainerPayload}
  ]};
  const journal = new GymBattleJournal(executor(client),KEYSPACE);
  await journal.begin(input);
  const adapter = cassandraBattleAdapter(client);
  const trainerAdapter = {
    async hasReceipt({chat: effectChat, player, effectId}) {
      const current = await canonicalTrainer.readAccount(effectChat,player);
      return Boolean(current?.operationReceipts?.[effectId]);
    },
    async applyOnce({chat: effectChat, player, effectId, payload}) {
      return canonicalTrainer.applyOnce(effectChat,player,{effectId,intentHash:hash(payload),
        expectedGeneration:payload.expectedGeneration, expectedStateHash:payload.expectedHash,
        writerContext:payload.writerContext,
        transform: () => payload.nextState});
    }
  };
  // Inject a lost acknowledgement after the durable trainer LWT. The journal
  // must remain discoverable, and startup replay must observe its target receipt.
  let loseOnce = true;
  const wrappedTrainer = {...trainerAdapter, async applyOnce(effect) {
    await trainerAdapter.applyOnce(effect);
    if (effect.effectId === 'battle_journal_0001_trainer' && loseOnce) {
      loseOnce = false;
      throw new Error('synthetic_lost_effect_response');
    }
  }};
  await assert.rejects(journal.apply(chat,battleId,{'gym-statistics':adapter,'trainer-account':wrappedTrainer}), error => {
    console.log(JSON.stringify({event:'synthetic_battle_lost_response',code:error?.code,
      effectId:error?.effectId, effectType:error?.effectType, payloadHash:error?.payloadHash,
      causeCode:error?.cause?.code, causeMessage:String(error?.cause?.message || '').slice(0,120)}));
    return error?.code === 'battle_effect_unresolved';
  });
  const restartedJournal = new GymBattleJournal(executor(client),KEYSPACE);
  const recovered = await restartedJournal.reconcileStartup(
    {'gym-statistics':adapter,'trainer-account':trainerAdapter}, {chat,battleId});
  assert.deepEqual(recovered,{ready:true,reconciled:1});
  const player = await canonicalTrainer.readAccount(chat,'player');
  const gym = await client.execute(`SELECT generation,state_json,operation_receipts FROM ${KEYSPACE}.synthetic_battle_targets WHERE chat_id=? AND target_id=?`,
    [chat,'gym-01'],{prepare:true,consistency:cassandra.types.consistencies.serial});
  assert.deepEqual(player.state,trainerPayload.nextState);
  assert.deepEqual(JSON.parse(gym.first().state_json),gymPayload.nextState);
  assert.equal(player.generation,2);
  // The Cassandra driver materializes bigint columns as Long.  Normalize at
  // this boundary; the protocol itself still compares persisted generations.
  assert.equal(Number(gym.first().generation),2);
  assert.equal(Object.keys(player.operationReceipts).length,1);
  assert.equal(gym.first().operation_receipts.size || gym.first().operation_receipts.length,1);
  assert.equal((await restartedJournal.read(chat,battleId)).state,'completed');

  // Two separate trainers race for the same gym generation. One LWT may win;
  // a stale battle callback must leave its own trainer unchanged and remain
  // blocked rather than overwriting the new occupancy or issuing a retry reward.
  const raceGym = {defenders:['shared-defender'],wins:0};
  const raceGymHash = hash(raceGym);
  await client.execute(`INSERT INTO ${KEYSPACE}.synthetic_battle_targets (chat_id,target_id,generation,state_hash,state_json,operation_receipts) VALUES (?,?,?,?,?,?) IF NOT EXISTS`,
    [chat,'shared-gym',1,raceGymHash,JSON.stringify(raceGym),[]],
    {prepare:true,consistency:cassandra.types.consistencies.quorum,serialConsistency:cassandra.types.consistencies.serial});
  for (const targetId of ['player-A','player-B']) {
    const state = {pokemon:[`${targetId}-starter`],xp:0,coins:0};
    await client.execute(`INSERT INTO ${KEYSPACE}.synthetic_battle_targets (chat_id,target_id,generation,state_hash,state_json,operation_receipts) VALUES (?,?,?,?,?,?) IF NOT EXISTS`,
      [chat,targetId,1,hash(state),JSON.stringify(state),[]],
      {prepare:true,consistency:cassandra.types.consistencies.quorum,serialConsistency:cassandra.types.consistencies.serial});
  }
  const racers = ['A','B'].map(suffix => {
    const id = `battle_race_${suffix}01`, playerId = `player-${suffix}`;
    return {input:{chat,player:playerId,battleId:id,effects:[
      {id:`${id}_gym`,type:'gym-statistics',payload:{targetId:'shared-gym',expectedGeneration:1,
        expectedHash:raceGymHash,nextState:{defenders:[`${playerId}-starter`],wins:1}}},
      {id:`${id}_trainer`,type:'trainer-account',payload:{targetId:playerId,expectedGeneration:1,
        expectedHash:hash({pokemon:[`${playerId}-starter`],xp:0,coins:0}),
        nextState:{pokemon:[`${playerId}-starter`],xp:5,coins:10}}}
    ]}};
  });
  const [journalA,journalB] = [new GymBattleJournal(executor(client),KEYSPACE),new GymBattleJournal(executor(client),KEYSPACE)];
  await Promise.all([journalA.begin(racers[0].input),journalB.begin(racers[1].input)]);
  const raceResults = await Promise.allSettled([
    journalA.apply(chat,racers[0].input.battleId,{'gym-statistics':adapter,'trainer-account':adapter}),
    journalB.apply(chat,racers[1].input.battleId,{'gym-statistics':adapter,'trainer-account':adapter})
  ]);
  assert.equal(raceResults.filter(item => item.status === 'fulfilled').length,1);
  assert.equal(raceResults.filter(item => item.status === 'rejected').length,1);
  const battleA = await journalA.read(chat,racers[0].input.battleId);
  const battleB = await journalB.read(chat,racers[1].input.battleId);
  assert.equal([battleA,battleB].filter(item => item.state === 'completed').length,1);
  assert.equal([battleA,battleB].filter(item => item.state === 'blocked').length,1);
  const trainerA = JSON.parse((await client.execute(`SELECT state_json FROM ${KEYSPACE}.synthetic_battle_targets WHERE chat_id=? AND target_id=?`,
    [chat,'player-A'],{prepare:true,consistency:cassandra.types.consistencies.serial})).first().state_json);
  const trainerB = JSON.parse((await client.execute(`SELECT state_json FROM ${KEYSPACE}.synthetic_battle_targets WHERE chat_id=? AND target_id=?`,
    [chat,'player-B'],{prepare:true,consistency:cassandra.types.consistencies.serial})).first().state_json);
  assert.equal(Number(trainerA.xp) + Number(trainerB.xp),5,'only the battle whose gym CAS won may reward its trainer');
  const blocked = battleA.state === 'blocked' ? racers[0] : racers[1];
  await assert.rejects((blocked === racers[0] ? journalA : journalB).apply(chat,blocked.input.battleId,
    {'gym-statistics':adapter,'trainer-account':adapter}), {code:'battle_effect_unresolved'});
  return {journalSurvived: true, lostResponseReconciled: true, trainerReceipt: true, gymReceipt: true,
    concurrentBattlesSingleWinner:true, staleBattleBlocked:true, noDuplicateReward:true};
}

// This uses the same finalizer exported to the ClojureScript storage bridge,
// rather than calling a target adapter directly.  It deliberately settles
// only HP/status and defender wear; rewards and occupancy changes stay out.
async function seedRealGymFinalizerRound(client) {
  const chat = 'real-gym-round', player = 'round-player';
  const trainerStore = new CanonicalTrainerStore(executor(client), KEYSPACE, {writerId:'real-round-trainer'});
  const gymStore = new CanonicalGymStore(executor(client), KEYSPACE, {writerId:'real-round-gym'});
  const initialTrainer = {equipe:[{'id-pokemon':'round-mon','hp-atual':10,hp:10,status:null},{'id-pokemon':'peer','hp-atual':9,hp:9}],moedas:77};
  const initialGym = {pid:'defender',time:[{uuid:'defender-1'}],motivacao:[100]};
  await trainerStore.createAccount(chat,player,initialTrainer,'real-round-seed');
  await gymStore.writeRecord(chat,'ginasios','forest',null,initialGym);

  const battleId = 'real_round_0001';
  const journal = new GymBattleJournal(executor(client),KEYSPACE);
  let loseTrainerResponse = true;
  const lostTrainer = {
    readAccount: (...args) => trainerStore.readAccount(...args),
    async applyOnce(...args) {
      const applied = await trainerStore.applyOnce(...args);
      if (loseTrainerResponse) { loseTrainerResponse = false; throw new Error('real_round_trainer_lost_response'); }
      return applied;
    }
  };
  await assert.rejects(finalizeRealGymRound({journal,trainerStore:lostTrainer,gymStore,chat,player,battleId,
    activeIndex:0,hp:4,status:'burn',gymId:'forest',wornOccupancy:{...initialGym,motivacao:[88]}}),
    {code:'battle_effect_unresolved'});
  const afterLostTrainer = await trainerStore.readAccount(chat,player);
  assert.equal(afterLostTrainer.state.equipe[0]['hp-atual'],4);
  assert.equal(afterLostTrainer.state.equipe[1]['hp-atual'],9,'other Pokémon remains unchanged');
  assert.equal((await gymStore.readRecord(chat,'ginasios','forest')).state.motivacao[0],100,
    'gym wear remains pending when trainer acknowledgement is lost');

  const restarted = new GymBattleJournal(executor(client),KEYSPACE);
  const adapters = createRealGymBattleAdapters({trainerStore,gymStore});
  assert.deepEqual(await restarted.reconcileStartup(adapters, {chat,battleId}), {ready:true,reconciled:1});
  const completed = await restarted.read(chat,battleId);
  assert.equal(completed.state,'completed');
  const afterRecovery = await gymStore.readRecord(chat,'ginasios','forest');
  assert.equal(afterRecovery.state.motivacao[0],88);
  assert.equal((await trainerStore.readAccount(chat,player)).generation,2);
  assert.equal(afterRecovery.generation,2);
  assert.equal(Object.keys((await trainerStore.readAccount(chat,player)).operationReceipts).length,1);
  assert.equal(afterRecovery.operationReceipts.size,1);

  // A completed journal is immutable: a replay cannot inflict HP or wear twice.
  await finalizeRealGymRound({journal:restarted,trainerStore,gymStore,chat,player,battleId,
    activeIndex:0,hp:4,status:'burn',gymId:'forest',wornOccupancy:{...initialGym,motivacao:[88]}});
  assert.equal((await trainerStore.readAccount(chat,player)).generation,2);
  assert.equal((await gymStore.readRecord(chat,'ginasios','forest')).generation,2);

  // The finalizer reads its plan from the same production stores.  This
  // wrapper creates a genuine read/write race: planning observes generation
  // two, while the effect application sees generation three.  The journal
  // must block rather than apply wear against an obsolete occupancy.
  const stalePlayer = 'round-stale-player';
  await trainerStore.createAccount(chat,stalePlayer,initialTrainer,'real-round-stale-seed');
  const staleGym = await gymStore.readRecord(chat,'ginasios','forest');
  await gymStore.writeRecord(chat,'ginasios','forest',staleGym.state,{...staleGym.state,motivacao:[80]});
  const snapshot = await gymStore.readRecord(chat,'ginasios','forest');
  await gymStore.writeRecord(chat,'ginasios','forest',snapshot.state,{...snapshot.state,motivacao:[72]});
  let firstGymRead = true;
  const staleGymStore = {
    readRecord(...args) {
      if (firstGymRead) { firstGymRead = false; return Promise.resolve(snapshot); }
      return gymStore.readRecord(...args);
    },
    writeRecordRecovering(...args) { return gymStore.writeRecordRecovering(...args); }
  };
  await assert.rejects(finalizeRealGymRound({journal:new GymBattleJournal(executor(client),KEYSPACE),trainerStore,
    gymStore:staleGymStore,chat,player:stalePlayer,battleId:'real_round_0002',activeIndex:0,hp:3,status:null,
    gymId:'forest',wornOccupancy:{...initialGym,motivacao:[76]}}), {code:'battle_effect_unresolved'});
  assert.equal((await gymStore.readRecord(chat,'ginasios','forest')).state.motivacao[0],72,
    'stale round cannot overwrite the latest defender wear');
  return {trainerReceipt:true,gymReceipt:true,lostTrainerResponseReconciled:true,replayIdempotent:true,staleGenerationRejected:true};
}

// Exercises the internal activation path against the same Cassandra rows used
// by the canonical trainer and transition coordinator.  It has no public
// command surface and uses only synthetic accounts.
async function seedPlayableSaveActivation(client, keyspace = KEYSPACE) {
  const chat = 'playable-save-cassandra', player = 'player-a', peer = 'player-b';
  const execute = executor(client);
  const slotStore = new SaveSlotStore(execute,keyspace,{now:()=> '2026-10-15T02:00:00.000Z'});
  const coordinator = () => new SaveTransitionCoordinator(execute,keyspace,{enabled:true,
    legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE,now:()=> '2026-10-15T02:00:00.000Z'});
  const trainer = new CanonicalTrainerStore(execute,keyspace,{writerId:'playable-save-test'});
  const service = (trainerStore=trainer, executeOverride=execute) => new PlayableSaveActivation(executeOverride,keyspace,
    {slotStore,trainerStore,coordinator:coordinator(),enabled:true,now:()=> '2026-10-15T02:00:00.000Z'});
  const a = account('playable-a',{'adventure-entregas':{globalA:'playable-a'}});
  const b = account('playable-b',{'adventure-entregas':{globalA:'playable-a',globalB:'playable-b'}});
  b.equipe[0].shiny = true; b.equipe[0]['xp-desde-nivel'] = 8; b.inventario = {pokebola:9}; b.moedas = 88;
  const other = account('playable-peer', {moedas:55});
  await trainer.createAccount(chat,player,a,'playable-a-seed');
  await trainer.createAccount(chat,peer,other,'playable-peer-seed');
  await slotStore.importValidated({chat,player,slotId:'historic-b',source:{kind:'legacy_estado_particionado',version:'1'},
    account:b,currentAccount:a});
  const activation = service();
  await activation.bootstrap(chat,player,'a');
  await activation.importHistorical(chat,player,'historic-b','b');
  // Save A evolves before switching. Hydration must use the canonical account,
  // not the bootstrap slot snapshot that predates this normal gameplay write.
  let current = await trainer.readAccount(chat,player);
  const progressedA = {...current.state,moedas:81,
    equipe:[{...current.state.equipe[0],'xp-desde-nivel':3},...current.state.equipe.slice(1)]};
  await trainer.compareAndSet(chat,player,current,progressedA);
  let hydrated = await activation.hydrateActive(chat,player);
  assert.equal(hydrated.activeSlotId,'a');
  assert.equal(hydrated.account.moedas,81);
  assert.equal(hydrated.account.equipe[0]['xp-desde-nivel'],3);
  // A callback captures A's fence before the switch. It must be rejected at
  // its final canonical LWT after B becomes active, even though its domain
  // snapshot was valid when the callback started.
  let releaseCallback, callbackCaptured;
  const callbackReady=new Promise(resolve=>{callbackCaptured=resolve;});
  const delayedCallback=trainer.withWriterContext(chat,async ()=>{
    callbackCaptured();
    await new Promise(resolve=>{releaseCallback=resolve;});
    return trainer.compareAndSet(chat,player,current,{...current.state,moedas:999});
  });
  await callbackReady;
  await activation.activate(chat,player,'b','playable-B-0001');
  releaseCallback();
  await assert.rejects(delayedCallback,{code:'canonical_trainer_fence_conflict'});
  current = await trainer.readAccount(chat,player);
  assert.equal(current.state.equipe[0]['id-pokemon'],'playable-b');
  assert.equal(current.state.equipe[0].shiny,true);
  assert.equal(current.state.equipe[0]['xp-desde-nivel'],8);
  assert.equal(current.state.inventario.pokebola,9);
  assert.deepEqual(current.state['adventure-entregas'],{globalA:'playable-a',globalB:'playable-b'});
  hydrated = await service().hydrateActive(chat,player);
  assert.equal(hydrated.activeSlotId,'b');
  assert.equal(hydrated.account.equipe[0].shiny,true);
  assert.equal(hydrated.account.equipe[0]['xp-desde-nivel'],8);
  assert.equal(hydrated.account.inventario.pokebola,9);
  assert.deepEqual(hydrated.account['adventure-entregas'],{globalA:'playable-a',globalB:'playable-b'});
  const progressedB = {...current.state,moedas:99,equipe:[{...current.state.equipe[0],'xp-desde-nivel':6},...current.state.equipe.slice(1)]};
  await trainer.compareAndSet(chat,player,current,progressedB);
  hydrated = await service().hydrateActive(chat,player);
  assert.equal(hydrated.account.moedas,99);
  assert.equal(hydrated.account.equipe[0]['xp-desde-nivel'],6);
  await activation.activate(chat,player,'a','playable-A-0001');
  current = await trainer.readAccount(chat,player);
  assert.equal(current.state.equipe[0]['id-pokemon'],'playable-a');
  assert.equal(current.state.moedas,81);
  hydrated = await service().hydrateActive(chat,player);
  assert.equal(hydrated.activeSlotId,'a');
  assert.equal(hydrated.account.moedas,81);
  assert.equal(hydrated.account.equipe[0]['xp-desde-nivel'],3);
  await activation.activate(chat,player,'b','playable-B-0002');
  current = await trainer.readAccount(chat,player);
  assert.equal(current.state.moedas,99);
  assert.equal(current.state.equipe[0]['xp-desde-nivel'],6);
  hydrated = await service().hydrateActive(chat,player);
  assert.equal(hydrated.activeSlotId,'b');
  assert.equal(hydrated.account.moedas,99);
  assert.equal(hydrated.account.equipe[0].shiny,true);
  assert.equal((await trainer.readAccount(chat,peer)).state.moedas,55,'peer account is isolated');
  const ledger = JSON.parse(await select(client,'pokemon-save-global-receipts',hash([chat,player]),keyspace));
  assert.deepEqual(ledger.receipts,{globalA:'playable-a',globalB:'playable-b'});

  // Persist the canonical effect but lose its acknowledgement.  The pointer
  // is intentionally left at A until verify-restart reconciles the durable
  // effect receipt; this covers a restart before pointer publication.
  const lostChat = 'playable-save-lost', lostPlayer = 'lost-player';
  const lostA = account('lost-a'), lostB = account('lost-b',{moedas:66});
  await trainer.createAccount(lostChat,lostPlayer,lostA,'playable-lost-a');
  await slotStore.importValidated({chat:lostChat,player:lostPlayer,slotId:'historic-b',source:{kind:'legacy_estado_particionado',version:'1'},account:lostB,currentAccount:lostA});
  const lostSetup = service();
  await lostSetup.bootstrap(lostChat,lostPlayer,'a'); await lostSetup.importHistorical(lostChat,lostPlayer,'historic-b','b');
  let loseOnce = true;
  const lostTrainer = {readAccount:(...args)=>trainer.readAccount(...args),
    reserveTransition:(...args)=>trainer.reserveTransition(...args), releaseTransition:(...args)=>trainer.releaseTransition(...args),
    async applyOnce(...args) {
    const applied = await trainer.applyOnce(...args);
    if (loseOnce) { loseOnce=false; throw new Error('synthetic_playable_lost_lwt_response'); }
    return applied;
  }};
  await assert.rejects(service(lostTrainer).activate(lostChat,lostPlayer,'b','playable-L-0001'),/synthetic_playable_lost_lwt_response/);
  const lostPointer = await service().read('pokemon-active-save-pointer',hash([lostChat,lostPlayer]));
  assert.equal(lostPointer.value.activeSlotId,'a');
  const lostOperation = await lostSetup.read('pokemon-playable-save-operations',
    `${hash([lostChat,lostPlayer])}:playable-L-0001`);
  assert.equal(lostOperation.value.phase,'source-saved','lost acknowledgement leaves a recoverable operation before pointer publication');
  const lostCurrent = await trainer.readAccount(lostChat,lostPlayer);
  assert.equal(lostCurrent.state.equipe[0]['id-pokemon'],'lost-b');
  assert.ok(lostCurrent.operationReceipts['saveact_playable-L-0001']);
  const hydrationFailures = await seedPlayableHydrationFailures(client,keyspace);
  return {aToBToA:true,hydrationAtoBtoA:true,shinyAndXpPreserved:true,globalReceiptsMonotonic:true,
    staleCallbackRejected:true,lostResponsePendingForRestart:true,peerIsolation:true,hydrationFailures};
}

async function seedPlayableHydrationFailures(client, keyspace) {
  const execute = executor(client);
  const slots = new SaveSlotStore(execute,keyspace);
  const trainer = new CanonicalTrainerStore(execute,keyspace,{writerId:'playable-hydration-failures'});
  const coordinator = () => new SaveTransitionCoordinator(execute,keyspace,{enabled:true,
    legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE});
  const service = () => new PlayableSaveActivation(execute,keyspace,{slotStore:slots,trainerStore:trainer,
    coordinator:coordinator(),enabled:true});
  const create = async (chat, player) => {
    const state=account(`hydrate-${player}`,{'adventure-entregas':{[`receipt-${player}`]:player}});
    await trainer.createAccount(chat,player,state,`hydrate-${player}`);
    await slots.importValidated({chat,player,slotId:'historic',source:{kind:'legacy_estado_particionado',version:'1'},
      account:state,currentAccount:state});
    await service().bootstrap(chat,player,'a');
    return state;
  };
  const pendingChat='playable-hydration-pending', pendingPlayer='pending-player';
  await create(pendingChat,pendingPlayer);
  const pendingBefore=await trainer.readAccount(pendingChat,pendingPlayer);
  const reservation=await trainer.reserveTransition(pendingChat,pendingPlayer,pendingBefore,'hydrate-pending-01');
  await assert.rejects(service().hydrateActive(pendingChat,pendingPlayer),{code:'playable_save_transition_pending'});
  await trainer.releaseTransition(pendingChat,pendingPlayer,reservation,'hydrate-pending-01');

  const pointerChat='playable-hydration-pointer', pointerPlayer='pointer-player';
  await create(pointerChat,pointerPlayer);
  const pointerService=service(), pointerIdentity=hash([pointerChat,pointerPlayer]);
  const pointerRow=await pointerService.read('pokemon-active-save-pointer',pointerIdentity);
  await pointerService.update('pokemon-active-save-pointer',pointerIdentity,
    old=>({...old,activeContentHash:'f'.repeat(64)}),value=>pointerService.validatePointer(value,pointerIdentity));
  await assert.rejects(pointerService.hydrateActive(pointerChat,pointerPlayer),{code:'playable_save_slot_pointer_conflict'});
  // Keep the deliberately corrupt fixture isolated. It is never used as an
  // active account by later scenarios.
  assert.ok(pointerRow.value.activeContentHash !== 'f'.repeat(64));

  const generationChat='playable-hydration-generation', generationPlayer='generation-player';
  await create(generationChat,generationPlayer);
  const generationService=service(), generationIdentity=hash([generationChat,generationPlayer]);
  await generationService.update('pokemon-active-save-pointer',generationIdentity,
    old=>({...old,canonicalGeneration:old.canonicalGeneration+10}),value=>generationService.validatePointer(value,generationIdentity));
  await assert.rejects(generationService.hydrateActive(generationChat,generationPlayer),{code:'playable_save_hydration_conflict'});

  const hashChat='playable-hydration-hash', hashPlayer='hash-player';
  await create(hashChat,hashPlayer);
  const hashService=service(), hashIdentity=hash([hashChat,hashPlayer]);
  const hashCurrent=await trainer.readAccount(hashChat,hashPlayer);
  await hashService.update('pokemon-active-save-pointer',hashIdentity,
    old=>({...old,canonicalGeneration:hashCurrent.generation,canonicalStateHash:'0'.repeat(64)}),
    value=>hashService.validatePointer(value,hashIdentity));
  await assert.rejects(hashService.hydrateActive(hashChat,hashPlayer),{code:'playable_save_hydration_conflict'});
  return {pendingRejected:true,pointerRejected:true,generationRejected:true,hashRejected:true};
}

async function setupPlayableRecoveryKeyspace(client) {
  const keyspace = PLAYABLE_RECOVERY_KEYSPACE;
  await client.execute(`CREATE KEYSPACE IF NOT EXISTS ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${keyspace}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_accounts (chat_id text, player_id text, generation bigint, schema_version int, protocol_version int, state_hash text, state_json text, operation_receipts map<text,text>, fence_generation bigint, active_save_id text, transition_owner text, updated_at timestamp, writer_id text, migration_id text, PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`
  ]) await client.execute(ddl);
  await activateCanonicalTrainerControl(client,keyspace,'playable-recovery');
  return keyspace;
}

// A rejected contender can intentionally leave a fail-closed transition
// journal. Run that proof in a distinct disposable keyspace so it cannot
// obscure the separate lost-acknowledgement recovery scenario.
// Classifies the non-winning concurrent activation. A loser may be rejected
// (reservation/journal/fencing) or resolve as an idempotent no-op when the
// winner already published the target pointer before the loser's post-reserve
// re-read (finishWithoutActivation). duplicateFenced is only true for rejects.
function classifyPlayableRaceDuplicate(raced) {
  const winners = raced.filter(item => item.status === 'fulfilled' && item.value?.idempotent === false);
  assert.equal(winners.length, 1, 'only one concurrent request may perform the canonical switch');
  const duplicate = raced.find(item => item !== winners[0] &&
    (item.status === 'rejected' || item.value?.idempotent === true));
  assert.ok(duplicate, 'the duplicate must be fenced or become a no-op');
  if (duplicate.status === 'rejected') {
    const code = `${duplicate.reason?.code || duplicate.reason}`;
    let duplicateOutcome;
    if (/transition_resource_locked/.test(code)) duplicateOutcome = 'rejected_active_reservation';
    else if (/transition_startup_unresolved/.test(code)) duplicateOutcome = 'rejected_pending_journal';
    else if (/playable_save_(?:generation|pointer)_conflict|canonical_trainer_fence/.test(code)) {
      duplicateOutcome = 'rejected_fencing';
    } else {
      assert.fail(`unexpected duplicate rejection code: ${code}`);
    }
    return {concurrentSingleWinner:true, duplicateFenced:true, duplicateOutcome, duplicateCode:code};
  }
  assert.equal(duplicate.value?.idempotent, true,
    'fulfilled duplicate must acknowledge the already-active target');
  return {
    concurrentSingleWinner:true,
    duplicateFenced:false,
    duplicateOutcome:'idempotent_already_active',
    duplicateCode:null
  };
}

async function seedPlayableActivationRace(client) {
  const keyspace = PLAYABLE_RACE_KEYSPACE;
  await client.execute(`CREATE KEYSPACE IF NOT EXISTS ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  for (const ddl of [
    `CREATE TABLE IF NOT EXISTS ${keyspace}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_accounts (chat_id text, player_id text, generation bigint, schema_version int, protocol_version int, state_hash text, state_json text, operation_receipts map<text,text>, fence_generation bigint, active_save_id text, transition_owner text, updated_at timestamp, writer_id text, migration_id text, PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${keyspace}.canonical_trainer_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`
  ]) await client.execute(ddl);
  await activatePlayableRaceControl(client,keyspace);
  const execute = executor(client);
  const slots = new SaveSlotStore(execute,keyspace);
  const trainer = new CanonicalTrainerStore(execute,keyspace,{writerId:'playable-save-race'});
  const service = () => new PlayableSaveActivation(execute,keyspace,{slotStore:slots,trainerStore:trainer,
    coordinator:new SaveTransitionCoordinator(execute,keyspace,{enabled:true,legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE}),enabled:true});
  const chat = 'playable-save-race', player = 'race-player', a = account('race-a'), b = account('race-b');
  await trainer.createAccount(chat,player,a,'playable-race-a');
  await slots.importValidated({chat,player,slotId:'historic-b',source:{kind:'legacy_estado_particionado',version:'1'},account:b,currentAccount:a});
  await service().bootstrap(chat,player,'a');
  await service().importHistorical(chat,player,'historic-b','b');
  const raced = await Promise.allSettled([
    service().activate(chat,player,'b','playable-R-0001'),
    service().activate(chat,player,'b','playable-R-0002')
  ]);
  const classified = classifyPlayableRaceDuplicate(raced);
  const current = await trainer.readAccount(chat,player);
  assert.equal(Object.keys(current.operationReceipts).filter(id => id.startsWith('saveact_playable-R-')).length,1,
    'only the winning request may leave a canonical trainer receipt');
  assert.equal(current.activeSaveId, 'b');
  return classified;
}

// `protocol_version` is CQL int. This statement must be prepared so the
// driver encodes the JavaScript number using the schema's four-byte int codec,
// rather than inferring an eight-byte bigint for an unprepared bind.
async function activatePlayableRaceControl(client, keyspace) {
  return activateCanonicalTrainerControl(client,keyspace,'playable-race');
}

async function activateCanonicalTrainerControl(client, keyspace, migrationId) {
  return client.execute(
    `INSERT INTO ${keyspace}.canonical_trainer_control (capability,protocol_version,state,migration_id,updated_at) VALUES (?,?,?,?,toTimestamp(now())) IF NOT EXISTS`,
    ['trainer-canonical',PROTOCOL_VERSION,'active',migrationId],
    {prepare:true, consistency:cassandra.types.consistencies.quorum,
      serialConsistency:cassandra.types.consistencies.serial}
  );
}

function authClient(username, password) {
  return new cassandra.Client({contactPoints:[host], localDataCenter:'datacenter1',
    authProvider:new cassandra.auth.PlainTextAuthProvider(username,password),
    socketOptions:{connectTimeout:15000,readTimeout:20000}});
}

function roleExecutor(client) { return executor(client); }
function permissionDenied(error) { return /no (?:SELECT|MODIFY) permission|not authorized|unauthorized/i.test(`${error.name} ${error.message}`); }

async function canonicalGymIsolation(admin, cassandraVersion, phase) {
  let stage = 'schema';
  const mark = name => { stage = name; console.log(JSON.stringify({event:'canonical_gym_cassandra_stage',phase,stage:name})); };
  try {
    const tables = [
      `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_gym_records (chat_id text, record_id text, generation bigint, protocol_version int, state_hash text, state_json text, deleted boolean, updated_at timestamp, writer_id text, migration_id text, operation_receipts set<text>, PRIMARY KEY ((chat_id), record_id))`,
      `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_gym_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
      `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_gym_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
      `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_gym_backups (chat_id text, module text, backup_id text, source_hash text, source_json text, created_at timestamp, PRIMARY KEY ((chat_id, module), backup_id))`
    ];
    for (const ddl of tables) await admin.execute(ddl);
    mark('create-test-roles');
    for (const [name,password] of [
      ['zapbot_gym_legacy_test','gym-legacy-secret'],
      ['zapbot_gym_runtime_test','gym-runtime-secret'],
      ['zapbot_gym_migrator_test','gym-migrator-secret']
    ]) await admin.execute(`CREATE ROLE IF NOT EXISTS ${name} WITH PASSWORD='${password}' AND LOGIN=true`);

    mark('grant-isolated-permissions');
    const legacyRole = 'zapbot_gym_legacy_test', runtimeRole = 'zapbot_gym_runtime_test', migratorRole = 'zapbot_gym_migrator_test';
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.estado_particionado TO ${legacyRole}`);
    await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.estado_particionado TO ${legacyRole}`);
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.estado_particionado TO ${migratorRole}`);
    for (const role of [runtimeRole,migratorRole]) {
      for (const table of ['canonical_gym_records','canonical_gym_groups']) {
        await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.${table} TO ${role}`);
        await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.${table} TO ${role}`);
      }
      await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_gym_control TO ${role}`);
    }
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_gym_backups TO ${runtimeRole}`);
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_gym_backups TO ${migratorRole}`);
    await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_gym_backups TO ${migratorRole}`);
    await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_gym_control TO ${migratorRole}`);
    await new Promise(resolve => setTimeout(resolve,2500));

    const legacy = authClient(legacyRole,'gym-legacy-secret');
    const runtime = authClient(runtimeRole,'gym-runtime-secret');
    const migrator = authClient(migratorRole,'gym-migrator-secret');
    try {
      mark('connect-roles-and-seed-legacy');
      await Promise.all([legacy.connect(),runtime.connect(),migrator.connect()]);
      const group = 'canonical-gym-synthetic';
      const occupancy = {
        'forest': {pid:'player-a',nome:'Synthetic A',time:[{uuid:'defender-a-1'},{uuid:'defender-a-2'}],desde:100,motivacao:[90,80]},
        'lake': {pid:'player-b',nome:'Synthetic B',time:[{uuid:'defender-b-1'}],desde:200,motivacao:[100]}
      };
      const statistics = {'forest':{historico:[{quando:100,desafiante:'synthetic',venceu:true}],lideres:{'player-a':{defesas:4,'tempo-ms':900}}}};
      for (const [module,chat,body] of [
        ['ginasios',group,occupancy],
        ['ginasios-estatisticas',group,statistics],
        ['ginasios','empty-gym-group',{}]
      ]) {
        await admin.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
          [module,chat,JSON.stringify(body)],{prepare:true});
      }
      const lojaBefore = JSON.stringify({coins:42,rewardReceipt:'already-delivered'});
      await admin.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
        ['loja',group,lojaBefore],{prepare:true});

      const legacyExecute = roleExecutor(legacy), migrationExecute = roleExecutor(migrator);
      const store = new CanonicalGymStore(migrationExecute,KEYSPACE,{writerId:'gym-migration-test'});
      const migrationOptions = (assertExclusiveWriters, canonicalStore = store) => new CanonicalGymMigration({
        legacyExecute,controlExecute:migrationExecute,backupExecute:migrationExecute,
        canonicalStore,keyspace:KEYSPACE,
        assertExclusiveWriters
      });
      const migrationId = 'gym-canonical-cutover-01';

      // A missing exclusive-writer proof must stop before backup/import. The
      // durable control remains migrating and can resume under the same ID.
      if (phase === 'seed') {
        mark('fail-closed-with-active-legacy-writer');
        const blockedMigration = migrationOptions(async()=>({verified:true,activeLegacyWriters:1,permissionHash:'a'.repeat(64)}));
        await assert.rejects(blockedMigration.migrate(migrationId),{code:'canonical_gym_writers_not_exclusive'});
        assert.equal((await migrator.execute(`SELECT COUNT(*) AS n FROM ${KEYSPACE}.canonical_gym_backups`,[],{prepare:true})).first().n.toNumber(),0);
      }

      // Fault after a durable backup and during canonical content import. A
      // retry uses the same immutable backup and converges without rewriting
      // legacy rows or touching the global reward ledger.
      mark('backup-before-import-and-resume');
      let interrupt = phase === 'seed';
      const faultStore = new CanonicalGymStore(async(sql,args,options)=>{
        if (interrupt && sql.startsWith('INSERT') && sql.includes('canonical_gym_records')) {
          interrupt = false;
          throw new Error('synthetic interruption after gym backup');
        }
        return migrationExecute(sql,args,options);
      },KEYSPACE,{writerId:'gym-migration-fault-test'});
      const writersVerified = async()=>({verified:true,activeLegacyWriters:0,permissionHash:'b'.repeat(64)});
      if (phase === 'seed') {
        await assert.rejects(migrationOptions(writersVerified,faultStore).migrate(migrationId),
          {code:'canonical_gym_result_unknown'});
      }
      const migration = new CanonicalGymMigration({legacyExecute,controlExecute:migrationExecute,
        backupExecute:migrationExecute,canonicalStore:store,keyspace:KEYSPACE,
        assertExclusiveWriters:writersVerified});
      if (phase === 'seed') {
        let proofCall = 0;
        const changingFence = migrationOptions(async()=>({verified:true,activeLegacyWriters:0,
          permissionHash:(++proofCall === 1 ? 'b' : 'c').repeat(64)}));
        await assert.rejects(changingFence.migrate(migrationId),
          {code:'canonical_gym_writer_fence_changed'});
        const stillMigrating = (await migrator.execute(
          `SELECT state FROM ${KEYSPACE}.canonical_gym_control WHERE capability=?`,
          ['gym-canonical'],{prepare:true,consistency:cassandra.types.consistencies.serial})).first();
        assert.equal(stillMigrating.state,'migrating','changed exclusivity proof cannot activate the canonical reader');
        const migrated = await migration.migrate(migrationId);
        assert.equal(migrated.state,'active');
        assert.equal(migrated.backups,3);
        assert.equal(migrated.groups,3);
        const capability = (await migrator.execute(
          `SELECT protocol_version,state,migration_id FROM ${KEYSPACE}.canonical_gym_control WHERE capability=?`,
          ['gym-canonical'],{prepare:true,consistency:cassandra.types.consistencies.serial})).first();
        assert.equal(capability?.protocol_version,1);
        assert.equal(capability?.state,'active');
        console.log(JSON.stringify({event:'canonical_gym_capability_activated',protocol:capability.protocol_version,state:capability.state}));
        assert.equal((await migrator.execute(`SELECT COUNT(*) AS n FROM ${KEYSPACE}.canonical_gym_backups`,[],{prepare:true})).first().n.toNumber(),3);
        const repeated = await migration.migrate(migrationId);
        assert.equal(repeated.state,'active','migration retry is idempotent once active');
      }

      mark('runtime-read-only-canonical-and-cas');
      const runtimeStore = new CanonicalGymStore(roleExecutor(runtime),KEYSPACE,{writerId:'gym-runtime-test'});
      const current = await runtimeStore.readGroup(group);
      if (phase === 'seed') assert.deepEqual(current.ginasios,occupancy);
      else {
        assert.equal(current.ginasios.lake.pid,'player-b');
        assert.ok([Number(occupancy.forest.desde) + 101,Number(occupancy.forest.desde) + 202]
          .includes(current.ginasios.forest.desde));
        assert.ok((await runtimeStore.readRecord(group,'ginasios','lost-response')),
          'lost-response LWT result survives Cassandra restart');
      }
      assert.deepEqual(current['ginasios-estatisticas'],phase === 'seed' ? statistics : {
        ...statistics, forest:{...statistics.forest,syntheticBattleCount:1}
      });
      if (phase === 'seed') {
        let loseResponse = true;
        const uncertainStore = new CanonicalGymStore(async(sql,args,options)=>{
          const result = await roleExecutor(runtime)(sql,args,options);
          if (loseResponse && sql.includes('canonical_gym_records') && /^(INSERT|UPDATE)/.test(sql)) {
            loseResponse = false;
            throw new Error('synthetic lost gym LWT response after apply');
          }
          return result;
        },KEYSPACE,{writerId:'gym-lost-response-test'});
        const recovered = await uncertainStore.writeRecordRecovering(group,'ginasios','lost-response',null,
          {pid:'player-lost-response',time:[{uuid:'defender-lost-response'}]});
        assert.equal(recovered.generation,1,'lost LWT response is resolved from the canonical row');
      }
      const before = await runtimeStore.readRecord(group,'ginasios','forest');
      const contenderValues = [Number(before.state.desde) + 101, Number(before.state.desde) + 202];
      const concurrent = await Promise.allSettled([
        runtimeStore.writeRecord(group,'ginasios','forest',before.state,{...before.state,desde:contenderValues[0]}),
        runtimeStore.writeRecord(group,'ginasios','forest',before.state,{...before.state,desde:contenderValues[1]})
      ]);
      assert.equal(concurrent.filter(item=>item.status==='fulfilled').length,1);
      assert.equal(concurrent.filter(item=>item.status==='rejected').length,1);
      await assert.rejects(runtimeStore.writeRecord(group,'ginasios','forest',before.state,{...before.state,desde:999}),
        {code:'canonical_gym_cas_conflict'});

      const opStore = new CanonicalGymStore(roleExecutor(runtime),KEYSPACE,{writerId:'gym-battle-operation-test'});
      const battleEvent = 'battle-event-synthetic-001';
      const statsBefore = await opStore.readRecord(group,'ginasios-estatisticas','forest');
      const statsOnce = await opStore.updateRecordOnce(group,'ginasios-estatisticas','forest',battleEvent,
        old => ({...old,syntheticBattleCount:(old.syntheticBattleCount || 0) + 1}));
      const statsReplay = await opStore.updateRecordOnce(group,'ginasios-estatisticas','forest',battleEvent,
        old => ({...old,syntheticBattleCount:(old.syntheticBattleCount || 0) + 1}));
      assert.equal(statsReplay.stateHash,statsOnce.stateHash,'same durable battle effect does not duplicate stats');
      assert.equal(statsReplay.generation,statsOnce.generation);
      assert.equal(statsOnce.state.syntheticBattleCount,1);
      assert.ok(statsOnce.operationReceipts.has(battleEvent));
      assert.equal(statsBefore.state.historico.length,1);

      // A legacy process can still produce a late snapshot in its old table,
      // but its role has no access to canonical records and active runtime
      // readers never fall back to the stale legacy value.
      const legacyOccupancy = JSON.stringify({forest:{pid:'stale-player',time:[{uuid:'stale-defender'}]}});
      await legacy.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
        ['ginasios',group,legacyOccupancy],{prepare:true});
      assert.deepEqual((await runtimeStore.readGroup(group)).ginasios.forest,
        concurrent.find(item=>item.status==='fulfilled').value.state);
      await assert.rejects(legacy.execute(`INSERT INTO ${KEYSPACE}.canonical_gym_records (chat_id,record_id,generation,protocol_version,state_hash,state_json,deleted,updated_at,writer_id,migration_id) VALUES (?,?,?,?,?,?,?,?,?,?)`,
        [group,'ginasios:unauthorized',1,1,'x','{}',false,new Date(),'legacy','legacy'],{prepare:true}),permissionDenied);
      await assert.rejects(runtime.execute(`SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo=? AND particao=?`,
        ['ginasios',group],{prepare:true}),permissionDenied);
      assert.equal(await select(admin,'loja',group),lojaBefore,'migration does not write rewards/currency');

      if (phase === 'seed') {
        // Stale callback / tombstone: after a confirmed removal, an old whole
        // module snapshot cannot resurrect the same defender record.
        const temporary = {pid:'temporary-defender',time:[{uuid:'temporary'}]};
        await runtimeStore.writeRecord(group,'ginasios','temporary',null,temporary);
        await runtimeStore.writeRecord(group,'ginasios','temporary',temporary,null);
        await assert.rejects(runtimeStore.writeRecord(group,'ginasios','temporary',null,temporary),
          {code:'canonical_gym_tombstone_conflict'});
      } else {
        assert.ok((await runtimeStore.readRecord(group,'ginasios','forest')).generation >= 2,
          'canonical gym state survives disposable Cassandra restart');
      }
      console.log(JSON.stringify({event:`canonical_gym_${phase}_passed`,cassandra:cassandraVersion,
        runtimeCanonicalOnly:true,legacyRoleDeniedCanonicalWrite:true,legacySnapshotIgnored:true,
        realLwtConcurrency:true,staleRevisionRejected:true,backupBeforeImport:true,
        interruptedMigrationResumed:true,emptyPartitionHandled:true,globalRewardsUnchanged:true}));
    } finally {
      await Promise.all([legacy.shutdown(),runtime.shutdown(),migrator.shutdown()]);
    }
  } catch (error) {
    console.error(JSON.stringify({event:'canonical_gym_cassandra_stage_failed',phase,stage,
      error:error?.name||'Error',code:error?.code||'integration_failure',
      observedState:error?.observedState,expectedState:error?.expectedState,
      observedProtocolVersion:error?.observedProtocolVersion,
      expectedErrorCode:error?.expected?.code,
      assertedErrorCode:error?.actual?.code || error?.actual?.name,
      causeCode:error?.cause?.code,
      causeName:error?.cause?.name,
      causeMessage:String(error?.cause?.message || '').slice(0,180),
      testLocation:error?.code==='ERR_ASSERTION' ? error.stack?.split('\n')[1]?.trim() : undefined}));
    throw error;
  }
}

async function canonicalIsolation(admin, cassandraVersion, phase) {
  let stage = 'schema';
  const mark = name => { stage = name; console.log(JSON.stringify({event:'canonical_cassandra_stage',phase,stage:name})); };
  try {
  const tables = [
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_accounts (chat_id text, player_id text, generation bigint, schema_version int, protocol_version int, state_hash text, state_json text, operation_receipts map<text,text>, fence_generation bigint, active_save_id text, transition_owner text, updated_at timestamp, writer_id text, migration_id text, PRIMARY KEY ((chat_id), player_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_groups (bucket int, chat_id text, PRIMARY KEY ((bucket), chat_id))`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_control (capability text PRIMARY KEY, protocol_version int, state text, migration_id text, updated_at timestamp)`,
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.canonical_trainer_backups (chat_id text, player_id text, backup_id text, source_hash text, source_json text, created_at timestamp, PRIMARY KEY ((chat_id, player_id), backup_id))`
  ];
  for (const ddl of tables) await admin.execute(ddl);
  mark('create-test-roles');
  const adminRole = (await admin.execute("SELECT is_superuser FROM system_auth.roles WHERE role = 'cassandra'")).first();
  console.log(JSON.stringify({event:'canonical_test_admin_role',superuser:adminRole?.is_superuser===true}));
  await admin.execute("CREATE ROLE IF NOT EXISTS zapbot_legacy_test WITH PASSWORD='legacy-test-secret' AND LOGIN=true");
  await admin.execute("CREATE ROLE IF NOT EXISTS zapbot_canonical_test WITH PASSWORD='canonical-test-secret' AND LOGIN=true");
  await admin.execute("CREATE ROLE IF NOT EXISTS zapbot_migrator_test WITH PASSWORD='migrator-test-secret' AND LOGIN=true");
  mark('grant-legacy-role');
  await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.estado_particionado TO zapbot_legacy_test`);
  await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.estado_particionado TO zapbot_legacy_test`);
  await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.estado_particionado TO zapbot_migrator_test`);
  for (const role of ['zapbot_canonical_test','zapbot_migrator_test']) {
    mark(`grant-canonical-tables-${role}`);
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_trainer_control TO ${role}`);
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_trainer_accounts TO ${role}`);
    await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_trainer_accounts TO ${role}`);
    await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_trainer_groups TO ${role}`);
    await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_trainer_groups TO ${role}`);
  }
  mark('grant-backup-and-control');
  await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_trainer_backups TO zapbot_canonical_test`);
  await admin.execute(`GRANT SELECT ON TABLE ${KEYSPACE}.canonical_trainer_backups TO zapbot_migrator_test`);
  await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_trainer_backups TO zapbot_migrator_test`);
  await admin.execute(`GRANT MODIFY ON TABLE ${KEYSPACE}.canonical_trainer_control TO zapbot_migrator_test`);

  const legacy = authClient('zapbot_legacy_test','legacy-test-secret');
  const runtime = authClient('zapbot_canonical_test','canonical-test-secret');
  const migrator = authClient('zapbot_migrator_test','migrator-test-secret');
  try {
    mark('authenticate-isolated-roles');
    await Promise.all([legacy.connect(),runtime.connect(),migrator.connect()]);
    const legacyState = {one:{level:12, shiny:true,team:[{uuid:'synthetic-one'}]},two:{coins:42,team:[]}};
    mark('legacy-and-control-seed');
    if (phase === 'seed') {
      await admin.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
        ['treinador','canonical-cutover-group',JSON.stringify(legacyState)],{prepare:true});
      await admin.execute(`INSERT INTO ${KEYSPACE}.canonical_trainer_control (capability,protocol_version,state,migration_id,updated_at) VALUES (?,?,?,?,toTimestamp(now())) IF NOT EXISTS`,
        ['trainer-canonical',PROTOCOL_VERSION,'migrating','cutover-synthetic'],{prepare:true,serialConsistency:cassandra.types.consistencies.serial});
    }
    const control = (await admin.execute(`SELECT protocol_version,state,migration_id FROM ${KEYSPACE}.canonical_trainer_control WHERE capability=?`,
      ['trainer-canonical'],{prepare:true,consistency:cassandra.types.consistencies.serial})).first();
    assert.deepEqual({protocolVersion:control?.protocol_version,state:control?.state,migration:control?.migration_id},
      {protocolVersion:PROTOCOL_VERSION,state:phase === 'seed' ? 'migrating' : 'active',migration:'cutover-synthetic'},
      phase === 'seed' ? 'fresh isolated control must enter migration before import'
        : 'restart must preserve activated canonical control');

    // The old application role loses legacy MODIFY before migration/cutover.
    mark('revoke-legacy-writer');
    await admin.execute(`REVOKE MODIFY ON TABLE ${KEYSPACE}.estado_particionado FROM zapbot_legacy_test`);
    await new Promise(resolve=>setTimeout(resolve,2500));
    let oldWriterRevoked = false;
    try {
      await legacy.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
        ['treinador','canonical-cutover-group',JSON.stringify({one:{stale:true}})],{prepare:true});
    } catch (error) { oldWriterRevoked = permissionDenied(error); }
    assert.equal(oldWriterRevoked,true,'legacy Cassandra role must be unable to write after cutover fence');

    mark('immutable-backup-and-migration');
    const migrationExecute = roleExecutor(migrator);
    const migrationStore = new CanonicalTrainerStore(migrationExecute,KEYSPACE,{writerId:'migration-test'});
    let failAfterBackup = phase === 'seed';
    const faultedStore = new CanonicalTrainerStore(async (sql,args,options) => {
      if (failAfterBackup && sql.startsWith('INSERT') && sql.includes('canonical_trainer_accounts')) {
        failAfterBackup = false;
        throw new Error('synthetic interruption after immutable backup');
      }
      return migrationExecute(sql,args,options);
    },KEYSPACE,{writerId:'migration-test'});
    const migration = new CanonicalTrainerMigration({legacyExecute:migrationExecute,backupExecute:migrationExecute,
      canonicalStore:phase === 'seed' ? faultedStore : migrationStore,keyspace:KEYSPACE,
      verifyLegacyWriterRevoked:()=>verifyLegacyWriterFence(sql=>admin.execute(sql), 'zapbot_legacy_test', KEYSPACE)});
    if (phase === 'seed') {
      await assert.rejects(migration.migrate('canonical-cutover-group','one','cutover-synthetic'),/synthetic interruption/);
      const retry = new CanonicalTrainerMigration({legacyExecute:migrationExecute,backupExecute:migrationExecute,
        canonicalStore:migrationStore,keyspace:KEYSPACE,
        verifyLegacyWriterRevoked:()=>verifyLegacyWriterFence(sql=>admin.execute(sql), 'zapbot_legacy_test', KEYSPACE)});
      await retry.migrate('canonical-cutover-group','one','cutover-synthetic');
      await retry.migrate('canonical-cutover-group','two','cutover-synthetic');
      assert.equal((await admin.execute(`SELECT COUNT(*) AS n FROM ${KEYSPACE}.canonical_trainer_backups`)).first().n.toNumber(),2);
      mark('verify-manifest-and-activate');
      const proof = await retry.verifyAndActivate('cutover-synthetic');
      assert.equal(proof.state,'active');
      assert.equal(proof.accounts,2);
    }

    mark('assert-runtime-role-isolation-and-cas');
    const canonical = new CanonicalTrainerStore(roleExecutor(runtime),KEYSPACE,{writerId:'runtime-test'});
    const group = await canonical.readGroup('canonical-cutover-group');
    if (phase === 'seed') {
      assert.deepEqual(group,legacyState,'all accounts in the shared legacy group must migrate independently');
    } else {
      assert.ok([13,14].includes(group.one.level),'canonical generation update must survive Cassandra restart');
      assert.equal(group.one.team[0].uuid,'synthetic-one');
      assert.deepEqual(group.two,legacyState.two,'other player must remain intact after restart');
    }
    const before = await canonical.readAccount('canonical-cutover-group','one');
    const contests = await Promise.allSettled([
      canonical.compareAndSet('canonical-cutover-group','one',before,{...before.state,level:13}),
      canonical.compareAndSet('canonical-cutover-group','one',before,{...before.state,level:14})
    ]);
    assert.equal(contests.filter(x=>x.status==='fulfilled').length,1);
    assert.equal(contests.filter(x=>x.status==='rejected').length,1);
    await assert.rejects(canonical.compareAndSet('canonical-cutover-group','one',before,{...before.state,level:99}),/conflict/);
    await assert.rejects(runtime.execute(`SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo=? AND particao=?`,
      ['treinador','canonical-cutover-group'],{prepare:true}),permissionDenied);
    await assert.rejects(legacy.execute(`INSERT INTO ${KEYSPACE}.canonical_trainer_accounts (chat_id,player_id,generation,schema_version,protocol_version,state_hash,state_json,updated_at,writer_id,migration_id) VALUES (?,?,?,?,?,?,?,?,?,?)`,
      ['canonical-cutover-group','hacker',1,1,1,'x','{}',new Date(),'old','old'],{prepare:true}),permissionDenied);

    // Even an out-of-band stale write to the old table cannot become canonical
    // state: canonical readers have no fallback path and no permission to read it.
    await admin.execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
      ['treinador','canonical-cutover-group',JSON.stringify({one:{staleLegacy:true}})],{prepare:true});
    assert.equal((await canonical.readAccount('canonical-cutover-group','two')).state.coins,42);
    console.log(JSON.stringify({event:`canonical_trainer_${phase}_passed`,cassandra:cassandraVersion,
      oldWriterLegacyWriteRejected:oldWriterRevoked,oldWriterCanonicalWriteRejected:true,
      runtimeLegacyReadRejected:true,staleCasRejected:true,groupIsolation:true,
      migrationBackupBeforeImport:true,migrationRetryIdempotent:true,noLegacyFallback:true}));
  } finally {
    await Promise.all([legacy.shutdown(),runtime.shutdown(),migrator.shutdown()]);
  }
  } catch (error) {
    console.error(JSON.stringify({event:'canonical_cassandra_stage_failed',phase,stage,
      error:error?.name||'Error',code:error?.code||'integration_failure',
      message:String(error?.message || '').slice(0,180),
      stackLine:error?.stack?.split('\n')[1]?.trim(),
      testLocation:error?.code==='ERR_ASSERTION' ? error.stack?.split('\n')[1]?.trim() : undefined}));
    throw error;
  }
}

async function select(client, module, partition, keyspace = KEYSPACE) {
  return (await client.execute(
    `SELECT valor FROM ${keyspace}.estado_particionado WHERE modulo = ? AND particao = ?`,
    [module, partition], {prepare: true, consistency: cassandra.types.consistencies.serial}
  )).first()?.valor ?? null;
}

async function seed(client, cassandraVersion) {
  await setup(client);
  await canonicalIsolation(client,cassandraVersion,'seed');
  const playableSaveRace = await seedPlayableActivationRace(client);
  const playableRecoveryKeyspace = await setupPlayableRecoveryKeyspace(client);
  const playableSaveActivation = await seedPlayableSaveActivation(client,playableRecoveryKeyspace);
  await canonicalGymIsolation(client,cassandraVersion,'seed');
  const battleJournal = await seedSyntheticBattle(client);
  const realBattleFinalizer = await seedRealGymFinalizerRound(client);
  const execute = executor(client);
  const store = new SaveSlotStore(execute, KEYSPACE, {now: () => '2026-10-15T00:00:00.000Z'});
  const active = account('active', {'adventure-entregas': {globalReward: 'active-pokemon'}, 'time-ginasio': [0, null, null]});
  const other = account('other');
  const activeBefore = JSON.stringify({player: active, other});
  await execute(
    `INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
    ['treinador', 'group-synthetic', JSON.stringify({player: active, other})]
  );
  await execute(
    `INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
    ['ginasios', 'global-synthetic', JSON.stringify({gym: {defenders: ['active']}})]
  );
  const gymBefore = await select(client, 'ginasios', 'global-synthetic');

  const v1 = account('v1');
  v1['adventure-entregas'] = {historicReward: 'historic-pokemon'};
  const v1Before = JSON.stringify(v1);
  const v2 = account('v2', {moedas: 123});
  const v2Before = JSON.stringify(v2);
  await store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'v1',
    source: {kind: 'legacy_estado_particionado', version: '1'}, account: v1, currentAccount: active});
  await store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'v2',
    source: {kind: 'legacy_estado', version: '2'}, account: v2, currentAccount: active});
  assert.equal(JSON.stringify(v1), v1Before);
  assert.equal(JSON.stringify(v2), v2Before);

  const listed = await store.list('group-synthetic', 'player');
  assert.deepEqual(listed.map(item => item.slotId).sort(), ['v1', 'v2']);
  assert.ok(listed.every(item => !Object.hasOwn(item, 'account')));
  assert.equal(await store.metadata('other-group', 'player', 'v1'), null);
  assert.equal(await store.metadata('group-synthetic', 'other-player', 'v1'), null);

  const identity = hash(['group-synthetic', 'player']);
  const rawV1 = JSON.parse(await select(client, 'pokemon-save-slots', `${identity}:v1`));
  const originalV1 = {...v1}; delete originalV1['adventure-entregas'];
  assert.deepEqual(rawV1.account, originalV1, 'slot JSON must preserve the full historical account except global receipts');
  assert.equal(rawV1.integrityHash, hash(Object.fromEntries(Object.entries(rawV1).filter(([key]) => key !== 'integrityHash'))));
  assert.equal(rawV1.metadata.sourceVersion, '1');
  assert.equal(rawV1.metadata.format, 'json-account-v1');
  const rawV2 = JSON.parse(await select(client, 'pokemon-save-slots', `${identity}:v2`));
  const originalV2 = {...v2}; delete originalV2['adventure-entregas'];
  assert.deepEqual(rawV2.account, originalV2, 'V2 account data must remain logically identical');
  assert.equal(rawV2.metadata.sourceVersion, '2');

  // Real SERIAL/LWT contention: both requests attempt the same immutable row.
  const concurrent = await Promise.allSettled([
    store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'race',
      source: {kind: 'legacy_estado_particionado', version: '1'}, account: account('same-race'), currentAccount: active}),
    store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'race',
      source: {kind: 'legacy_estado_particionado', version: '1'}, account: account('same-race'), currentAccount: active})
  ]);
  assert.deepEqual(concurrent.map(item => item.status).sort(), ['fulfilled', 'fulfilled']);
  assert.equal(await store.metadata('group-synthetic', 'player', 'race') !== null, true);
  await assert.rejects(store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'race',
    source: {kind: 'legacy_estado_particionado', version: '1'}, account: account('different-race'), currentAccount: active}), /slot_conflict/);

  // Fail all index CAS writes after the immutable slot is inserted. Retrying
  // the exact import must publish the already durable row without overwriting.
  let interrupt = true;
  const faulted = new SaveSlotStore((sql, args, options) => {
    if (interrupt && sql.startsWith('UPDATE ') && args[1] === 'pokemon-save-slot-index') {
      throw new Error('synthetic interruption before index publication');
    }
    return execute(sql, args, options);
  }, KEYSPACE, {now: () => '2026-10-15T00:00:01.000Z'});
  const orphanInput = {chat: 'group-synthetic', player: 'player', slotId: 'interrupted',
    source: {kind: 'legacy_estado_particionado', version: '1'}, account: account('orphan'), currentAccount: active};
  await assert.rejects(faulted.importValidated(orphanInput), /slot_index_contention/);
  assert.ok(await select(client, 'pokemon-save-slots', `${identity}:interrupted`));
  assert.equal(await faulted.metadata('group-synthetic', 'player', 'interrupted'), null);
  interrupt = false;
  await faulted.importValidated(orphanInput);
  assert.ok(await faulted.metadata('group-synthetic', 'player', 'interrupted'));

  // Global receipts are a monotonic union; repeated imports neither duplicate
  // nor remove IDs from the active and historical reward receipts.
  await store.importValidated({chat: 'group-synthetic', player: 'player', slotId: 'v1',
    source: {kind: 'legacy_estado_particionado', version: '1'}, account: v1, currentAccount: active});
  const ledger = JSON.parse(await select(client, 'pokemon-save-global-receipts', identity));
  assert.deepEqual(ledger.receipts, {
    globalReward: 'active-pokemon', historicReward: 'historic-pokemon'
  });
  assert.equal(ledger.integrityHash, hash({schemaVersion: 1, receipts: ledger.receipts}));

  // Existing live trainer and gym rows are byte-identical after imports.
  assert.equal(await select(client, 'treinador', 'group-synthetic'), activeBefore);
  assert.equal(await select(client, 'ginasios', 'global-synthetic'), gymBefore);

  // Exercise durable phase-2 coordination on synthetic rows only. Both
  // contenders reserve the same gym; exactly one acquires the LWT lock.
  const coordinator = new SaveTransitionCoordinator(execute, KEYSPACE, {
    enabled: true, legacyJournalCoverageVerified: true,
    ...TEST_WRITER_COVERAGE,
    now: () => '2026-10-15T00:01:00.000Z'
  });
  const resourcesA = [{type: 'account', id: 'player-A'}, {type: 'gym', id: 'gym-synthetic'}];
  const resourcesB = [{type: 'account', id: 'player-B'}, {type: 'gym', id: 'gym-synthetic'}];
  await coordinator.begin({chat: 'group-synthetic', player: 'player-A', operationId: 'transition-race-A', resources: resourcesA});
  await coordinator.begin({chat: 'group-synthetic', player: 'player-B', operationId: 'transition-race-B', resources: resourcesB});
  const attempts = await Promise.allSettled([
    coordinator.reserve('group-synthetic', 'player-A', 'transition-race-A'),
    coordinator.reserve('group-synthetic', 'player-B', 'transition-race-B')
  ]);
  assert.equal(attempts.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(attempts.filter(result => result.status === 'rejected').length, 1);
  const winner = attempts[0].status === 'fulfilled' ? 'transition-race-A' : 'transition-race-B';
  const loser = winner === 'transition-race-A' ? 'transition-race-B' : 'transition-race-A';
  const winnerPlayer = winner === 'transition-race-A' ? 'player-A' : 'player-B';
  const loserPlayer = loser === 'transition-race-A' ? 'player-A' : 'player-B';
  const winnerState = await coordinator.inspect('group-synthetic', winnerPlayer, winner);
  const winnerFence = winnerState.resources.find(item => item.resource.type === 'account').fence;
  await coordinator.advance('group-synthetic', winnerPlayer, winner, 'backup');

  // Simulate a response lost after Cassandra applied trainer-row CAS. The
  // coordinator resolves it by SERIAL read and durable effect-journal update.
  let loseResponse = true;
  const uncertainExecute = async (sql, args, options) => {
    const result = await execute(sql, args, options);
    if (loseResponse && sql.startsWith('INSERT ') && args[0] === 'treinador' && args[1] === 'synthetic-transition-account') {
      loseResponse = false;
      throw new Error('synthetic lost LWT response');
    }
    return result;
  };
  const uncertainCoordinator = new SaveTransitionCoordinator(uncertainExecute, KEYSPACE,
    {enabled: true, ...TEST_WRITER_COVERAGE});
  assert.equal(await uncertainCoordinator.writeFenced({chat: 'group-synthetic', operationId: winner,
    fence: winnerFence, effectId: 'transition-effect-001', module: 'treinador',
    partition: 'synthetic-transition-account', expectedValue: null,
    nextValue: {synthetic: true, pokemon: ['synthetic-only']} }), true);
  assert.deepEqual(JSON.parse(await select(client, 'treinador', 'synthetic-transition-account')),
    {synthetic: true, pokemon: ['synthetic-only']});
  assert.equal((await coordinator.inspect('group-synthetic', winnerPlayer, winner)).effects[0].status, 'applied');
  for (const phaseName of ['encerramento', 'ativacao', 'verificacao', 'conclusao']) {
    await coordinator.advance('group-synthetic', winnerPlayer, winner, phaseName);
  }
  await coordinator.release('group-synthetic', winnerPlayer, winner);
  await assert.rejects(coordinator.assertFence('group-synthetic', winner, winnerFence), /stale_transition_fence/);

  // A partial reservation is not silently discarded: after the winner
  // releases, retry resumes the durable loser journal and completes safely.
  const resumed = await coordinator.reserve('group-synthetic', loserPlayer, loser);
  assert.ok(resumed.resources.every(item => item.owned && item.journalFenceMatches));
  for (const phaseName of ['backup', 'encerramento', 'ativacao', 'verificacao', 'conclusao']) {
    await coordinator.advance('group-synthetic', loserPlayer, loser, phaseName);
  }
  await coordinator.release('group-synthetic', loserPlayer, loser);

  // The exact trainer writer adapter used by ClojureScript is exercised on
  // Cassandra: disjoint player deltas preserve each other under same-row LWT.
  const trainerBase = {playerA: {coins: 1}, playerB: {coins: 2}};
  await execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
    ['treinador', 'trainer-cas-group', JSON.stringify(trainerBase)]);
  await Promise.all([
    saveGroup(execute, `${KEYSPACE}.estado_particionado`, 'trainer-cas-group', trainerBase,
      {playerA: {coins: 11}, playerB: trainerBase.playerB}),
    saveGroup(execute, `${KEYSPACE}.estado_particionado`, 'trainer-cas-group', trainerBase,
      {playerA: trainerBase.playerA, playerB: {coins: 22}})
  ]);
  assert.deepEqual(JSON.parse(await select(client, 'treinador', 'trainer-cas-group')),
    {playerA: {coins: 11}, playerB: {coins: 22}});

  const lockOperation = 'transition-cas-lock-01';
  await coordinator.begin({chat: 'group-synthetic', player: 'playerLocked', operationId: lockOperation,
    resources: [{type: 'account', id: 'playerLocked'}]});
  await coordinator.reserve('group-synthetic', 'playerLocked', lockOperation);
  const lockedBefore = {playerLocked: {coins: 1}};
  await execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
    ['treinador', 'trainer-lock-group', JSON.stringify(lockedBefore)]);
  await assert.rejects(saveGroup(execute, `${KEYSPACE}.estado_particionado`, 'group-synthetic',
    lockedBefore, {playerLocked: {coins: 2}}), /save_transition_pending/);
  assert.deepEqual(JSON.parse(await select(client, 'treinador', 'trainer-lock-group')), lockedBefore);

  // Demonstrate the current architectural blocker using only a synthetic row:
  // an unconditional legacy upsert is not constrained by the new lock table.
  const legacyOperation = 'transition-legacy-01';
  await coordinator.begin({chat: 'group-synthetic', player: 'legacy-player', operationId: legacyOperation,
    resources: [{type: 'account', id: 'legacy-player'}]});
  await coordinator.reserve('group-synthetic', 'legacy-player', legacyOperation);
  await execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor) VALUES (?,?,?)`,
    ['treinador', 'group-synthetic', JSON.stringify({staleSnapshot: true})]);
  assert.deepEqual(JSON.parse(await select(client, 'treinador', 'group-synthetic')), {staleSnapshot: true});
  // Restore the disposable fixture after proving the bypass; no player or
  // production data is involved.
  await execute(`INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor) VALUES (?,?,?)`,
    ['treinador', 'group-synthetic', activeBefore]);
  assert.equal(await select(client, 'treinador', 'group-synthetic'), activeBefore,
    'the synthetic active trainer/group fixture is restored byte-for-byte');
  assert.equal(await select(client, 'ginasios', 'global-synthetic'), gymBefore,
    'the defender snapshot is untouched by reservation and reconciliation');
  assert.equal(await select(client, 'loja', 'group-synthetic'), null,
    'coordination does not issue a currency/reward write');
  const pendingAfterRestart = await new SaveTransitionCoordinator(execute, KEYSPACE,
    {enabled: true, ...TEST_WRITER_COVERAGE})
    .inspect('group-synthetic', 'legacy-player', legacyOperation);
  assert.equal(pendingAfterRestart.phase, 'reserva');
  assert.ok(pendingAfterRestart.resources.every(item => item.owned));

  console.log(JSON.stringify({event: 'save_slot_cassandra_seed_passed', cassandra: cassandraVersion,
    slots: listed.length + 2, accountPayloadReturnedByList: false,
    transitionRaceWinnerCount: 1, lostResponseReconciled: true,
    staleFenceRejected: true, trainerCasConcurrentPlayersPreserved: true,
    trainerDurableLockRespected: true, legacyWriterBypassObserved: true,
    syntheticBattleJournal: battleJournal,
    realBattleFinalizer,
    playableSaveRace,
    playableSaveActivation}));
}

async function verifyRestart(client) {
  await canonicalIsolation(client,'5.0.9','verify-restart');
  await canonicalGymIsolation(client,'5.0.9','verify-restart');
  const keyspace = PLAYABLE_RECOVERY_KEYSPACE;
  const activationExecute = executor(client);
  const activationSlots = new SaveSlotStore(activationExecute,keyspace);
  const activationTrainer = new CanonicalTrainerStore(activationExecute,keyspace,{writerId:'playable-save-restart'});
  const activationCoordinator = new SaveTransitionCoordinator(activationExecute,keyspace,{enabled:true,
    legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE});
  const identity = hash(['playable-save-lost','lost-player']);
  let failAfterPointer = true;
  const activation = new PlayableSaveActivation(activationExecute,keyspace,{slotStore:activationSlots,
    trainerStore:activationTrainer,coordinator:activationCoordinator,enabled:true});
  const persistPhase = activation.setOperationPhase.bind(activation);
  activation.setOperationPhase = async (operationIdentity, operationId, phase) => {
    // `recover` reaches this only after the pointer was written. Simulate a
    // process loss before it can durably mark the operation completed.
    if (failAfterPointer && operationIdentity === identity && operationId === 'playable-L-0001' && phase === 'completed') {
      failAfterPointer = false;
      throw new Error('synthetic_stop_after_pointer');
    }
    return persistPhase(operationIdentity, operationId, phase);
  };
  // The first restart sees the durable trainer receipt, publishes the pointer,
  // then stops before completion.  This is intentionally a second process
  // boundary, not a concurrent operation sharing the prior pending journal.
  await assert.rejects(activation.recover('playable-save-lost','lost-player','playable-L-0001'),/synthetic_stop_after_pointer/);
  const recoveredPointer = await activation.read('pokemon-active-save-pointer',identity);
  assert.equal(recoveredPointer.value.activeSlotId,'b');
  const recoveredTrainer = await activationTrainer.readAccount('playable-save-lost','lost-player');
  assert.equal(recoveredTrainer.state.equipe[0]['id-pokemon'],'lost-b');
  assert.equal(Object.keys(recoveredTrainer.operationReceipts).filter(id=>id === 'saveact_playable-L-0001').length,1);
  console.log(JSON.stringify({event:'playable_save_pointer_published_pending_restart',
    pointerPublished:true,canonicalReceiptPresent:true,operationId:'playable-L-0001'}));
}

async function verifyPointerRestart(client) {
  const keyspace = PLAYABLE_RECOVERY_KEYSPACE;
  const activationExecute = executor(client);
  const activationSlots = new SaveSlotStore(activationExecute,keyspace);
  const activationTrainer = new CanonicalTrainerStore(activationExecute,keyspace,{writerId:'playable-save-final-restart'});
  const activationCoordinator = new SaveTransitionCoordinator(activationExecute,keyspace,{enabled:true,
    legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE});
  const activation = new PlayableSaveActivation(activationExecute,keyspace,{slotStore:activationSlots,
    trainerStore:activationTrainer,coordinator:activationCoordinator,enabled:true});
  const recoveredActivation = await activation.recover('playable-save-lost','lost-player','playable-L-0001');
  assert.equal(recoveredActivation.activeSlotId,'b');
  const recoveredPointer = await activation.read('pokemon-active-save-pointer',hash(['playable-save-lost','lost-player']));
  assert.equal(recoveredPointer.value.activeSlotId,'b');
  const recoveredTrainer = await activationTrainer.readAccount('playable-save-lost','lost-player');
  assert.equal(recoveredTrainer.state.equipe[0]['id-pokemon'],'lost-b');
  assert.equal(Object.keys(recoveredTrainer.operationReceipts).filter(id=>id === 'saveact_playable-L-0001').length,1);
  const inspection = await activationCoordinator.inspect('playable-save-lost','lost-player','playable-L-0001');
  assert.equal(inspection.phase,'conclusao');
  assert.ok(inspection.resources.every(item => item.lock?.owner !== 'playable-L-0001'));
  // This is a new process after two real Cassandra container restarts. The
  // active B account must hydrate from canonical state, retaining progress
  // made after B was first activated rather than reverting to its slot copy.
  const activeHydration = new PlayableSaveActivation(activationExecute,keyspace,{slotStore:activationSlots,
    trainerStore:activationTrainer,coordinator:new SaveTransitionCoordinator(activationExecute,keyspace,{enabled:true,
      legacyJournalCoverageVerified:true,...TEST_WRITER_COVERAGE}),enabled:true});
  const hydratedB = await activeHydration.hydrateActive('playable-save-cassandra','player-a');
  assert.equal(hydratedB.activeSlotId,'b');
  assert.equal(hydratedB.account.moedas,99);
  assert.equal(hydratedB.account.equipe[0].shiny,true);
  assert.equal(hydratedB.account.equipe[0]['xp-desde-nivel'],6);
  assert.equal(hydratedB.account.inventario.pokebola,9);
  assert.deepEqual(hydratedB.account['adventure-entregas'],{globalA:'playable-a',globalB:'playable-b'});
  assert.equal((await activationTrainer.readAccount('playable-save-cassandra','player-b')).state.moedas,55,
    'active-save hydration never changes a peer in the same group');
  const store = new SaveSlotStore(executor(client), KEYSPACE);
  const listed = await store.list('group-synthetic', 'player');
  assert.deepEqual(listed.map(item => item.slotId).sort(), ['interrupted', 'race', 'v1', 'v2']);
  assert.ok(listed.every(item => !Object.hasOwn(item, 'account')));
  const identity = hash(['group-synthetic', 'player']);
  const ledger = JSON.parse(await select(client, 'pokemon-save-global-receipts', identity));
  assert.equal(ledger.receipts.globalReward, 'active-pokemon');
  assert.equal(ledger.receipts.historicReward, 'historic-pokemon');
  assert.equal(ledger.integrityHash, hash({schemaVersion: 1, receipts: ledger.receipts}));
  const transition = new SaveTransitionCoordinator(executor(client), KEYSPACE,
    {enabled: true, ...TEST_WRITER_COVERAGE});
  await assert.rejects(transition.reconcileStartup(), /transition_startup_unresolved/);
  const completed = await transition.inspect('group-synthetic', 'player-A', 'transition-race-A');
  const otherCompleted = await transition.inspect('group-synthetic', 'player-B', 'transition-race-B');
  assert.ok([completed, otherCompleted].some(item => item?.phase === 'conclusao'));
  assert.ok([completed, otherCompleted].some(item => item?.phase === 'conclusao' && item.effects.some(effect => effect.status === 'applied')));
  const legacyPending = await transition.inspect('group-synthetic', 'legacy-player', 'transition-legacy-01');
  assert.equal(legacyPending.phase, 'reserva');
  assert.ok(legacyPending.resources.every(item => item.owned));
  const battleJournal = new GymBattleJournal(executor(client),KEYSPACE);
  const battleState = await battleJournal.read('battle-journal-synthetic','battle_journal_0001');
  assert.equal(battleState.state,'completed');
  const battleIndex = await client.execute(
    `SELECT state FROM ${KEYSPACE}.pokemon_gym_battle_index WHERE bucket = ? AND chat_id = ? AND battle_id = ?`,
    [battleJournal.bucket('battle-journal-synthetic'),'battle-journal-synthetic','battle_journal_0001'],
    {prepare:true,consistency:cassandra.types.consistencies.serial});
  assert.equal(battleIndex.first().state,'completed');
  const realBattleJournal = new GymBattleJournal(executor(client),KEYSPACE);
  const realBattle = await realBattleJournal.read('real-gym-round','real_round_0001');
  assert.equal(realBattle.state,'completed');
  const trainerStore = new CanonicalTrainerStore(executor(client),KEYSPACE,{writerId:'verify-real-round'});
  const gymStore = new CanonicalGymStore(executor(client),KEYSPACE,{writerId:'verify-real-round'});
  const realTrainer = await trainerStore.readAccount('real-gym-round','round-player');
  const realGym = await gymStore.readRecord('real-gym-round','ginasios','forest');
  assert.equal(realTrainer.state.equipe[0]['hp-atual'],4);
  assert.equal(realTrainer.state.equipe[1]['hp-atual'],9);
  assert.equal(realGym.state.motivacao[0],72);
  assert.equal(Object.keys(realTrainer.operationReceipts).length,1);
  assert.equal(realGym.operationReceipts.size,1);
  console.log(JSON.stringify({event: 'save_slot_cassandra_restart_passed',
    slotsSurvivedRestart: listed.length, receiptsSurvivedRestart: Object.keys(ledger.receipts).length,
    transitionJournalSurvivedRestart: true, transitionIndexSurvivedRestart: true,
    startupFailClosedOnPending: true, unresolvedLockPreserved: true,
    battleJournalSurvivedRestart:true,battleReceiptsSurvivedRestart:true,
    realBattleFinalizerSurvivedRestart:true,realTrainerReceiptSurvivedRestart:true,
    realGymReceiptsSurvivedRestart:true,playableActivationRecoveredAfterRestart:true,
    playableActivationReceiptSurvivedRestart:true,playableActiveHydrationAfterRestart:true}));
}

async function main() {
  const client = new cassandra.Client({contactPoints: [host], localDataCenter: 'datacenter1',
    authProvider:new cassandra.auth.PlainTextAuthProvider('cassandra','cassandra'),
    socketOptions: {connectTimeout: 10000, readTimeout: 15000}});
  try {
    const cassandraVersion = await connectReady(client);
    if (phase === 'seed') await seed(client, cassandraVersion);
    else if (phase === 'verify-restart') await verifyRestart(client);
    else if (phase === 'verify-pointer-restart') await verifyPointerRestart(client);
    else throw new Error(`unknown_save_slots_cassandra_phase:${phase}`);
  } finally {
    await client.shutdown();
  }
}

if (require.main === module) {
  main().catch(error => {
    console.error(JSON.stringify({event: 'save_slot_cassandra_failed', phase,
      error: error?.name || 'Error', code: error?.code || 'integration_failure',
      message: String(error?.message || '').replace(/[\r\n]/g, ' ').slice(0,220),
      unresolvedCodes: Array.isArray(error?.unresolved) ? error.unresolved.map(item => item.code).slice(0,8) : undefined,
      unresolvedCauseCodes: Array.isArray(error?.unresolved) ? error.unresolved.map(item => item.causeCode).filter(Boolean).slice(0,8) : undefined,
      causeCode: error?.cause?.code || undefined,
      causeName: error?.cause?.name || undefined}));
    process.exitCode = 1;
  });
}

module.exports = {activatePlayableRaceControl, classifyPlayableRaceDuplicate};
