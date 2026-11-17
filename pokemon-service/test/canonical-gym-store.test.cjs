'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalGymStore} = require('../runtime/canonical-gym-store.cjs');

function database() {
  const controls = new Map([['gym-canonical', {protocol_version:1,state:'active'}]]);
  const groups = new Map();
  const records = new Map();
  let tail = Promise.resolve();
  let loseNextResponse = false;
  const key = (chat, id) => `${chat}|${id}`;
  const execute = (sql, args) => {
    const work = async () => {
      let found;
      if (sql.includes('FROM zapbot.canonical_gym_control')) {
        found = controls.get(args[0]) || null;
        return {first:() => found, rows:found ? [found] : []};
      }
      if (sql.includes('FROM zapbot.canonical_gym_records')) {
        if (sql.includes('record_id = ?')) {
          found = records.get(key(args[0],args[1])) || null;
          return {first:() => found, rows:found ? [found] : []};
        }
        const rows = [...records.entries()].filter(([k]) => k.startsWith(`${args[0]}|`)).map(([,value]) => value);
        return {first:() => rows[0] || null, rows};
      }
      if (sql.includes('FROM zapbot.canonical_gym_groups')) {
        const rows = [...groups.values()].filter(item => item.bucket === args[0]);
        return {first:() => rows[0] || null, rows};
      }
      if (sql.startsWith('INSERT INTO zapbot.canonical_gym_groups')) {
        const k = `${args[0]}|${args[1]}`, applied = !groups.has(k);
        if (applied) groups.set(k,{bucket:args[0],chat_id:args[1]});
        return {wasApplied:() => applied};
      }
      if (sql.startsWith('INSERT INTO zapbot.canonical_gym_records')) {
        const k = key(args[0],args[1]), applied = !records.has(k);
        if (applied) records.set(k,{generation:args[2],protocol_version:args[3],state_hash:args[4],
          state_json:args[5],deleted:args[6],updated_at:args[7],writer_id:args[8],migration_id:args[9],
          operation_receipts:sql.includes('operation_receipts)') ? args[10] : new Set(),record_id:args[1]});
        if (applied && loseNextResponse) { loseNextResponse = false; throw new Error('lost_lwt_response'); }
        return {wasApplied:() => applied};
      }
      if (sql.startsWith('UPDATE zapbot.canonical_gym_records')) {
        const hasReceipt = sql.includes('operation_receipts = ?');
        const offset = hasReceipt ? 1 : 0;
        const k = key(args[8 + offset],args[9 + offset]);
        found = records.get(k);
        const applied = Boolean(found && found.generation === args[10 + offset] && found.state_hash === args[11 + offset] && found.protocol_version === args[12 + offset]);
        if (applied) records.set(k,{...found,generation:args[0],protocol_version:args[1],state_hash:args[2],
          state_json:args[3],deleted:args[4],updated_at:args[5],writer_id:args[6],migration_id:args[7],
          operation_receipts:hasReceipt ? args[8] : found.operation_receipts});
        if (applied && loseNextResponse) { loseNextResponse = false; throw new Error('lost_lwt_response'); }
        return {wasApplied:() => applied};
      }
      throw new Error(`unexpected_cql: ${sql}`);
    };
    const result = tail.then(work);
    tail = result.catch(() => {});
    return result;
  };
  return {execute, records, loseResponse() { loseNextResponse = true; }};
}

const state = (pid, name = 'Gym') => ({pid,nome:name,time:[{id:`${pid}-defender`}],desde:100,motivacao:[100]});

test('canonical gym records use per-row CAS, preserve peers, and reject stale revisions', async () => {
  const db = database(), store = new CanonicalGymStore(db.execute,'zapbot');
  const a = state('player-a'), b = state('player-b');
  await store.writeRecord('group','ginasios','forest',null,a);
  await store.writeRecord('group','ginasios','lake',null,b);
  const update = {...a,desde:200};
  const revised = await store.writeRecord('group','ginasios','forest',a,update);
  assert.equal(revised.generation,2);
  assert.equal((await store.readRecord('group','ginasios','forest')).stateHash,revised.stateHash);
  await assert.rejects(store.writeRecord('group','ginasios','forest',a,{...a,desde:300}),
    {code:'canonical_gym_cas_conflict'});
  const view = await store.readGroup('group');
  assert.deepEqual(view.ginasios,{forest:update,lake:b});
});

test('lost LWT response is resolved by SERIAL read of exact canonical value', async () => {
  const db = database(), store = new CanonicalGymStore(db.execute,'zapbot');
  const occupancy = state('synthetic');
  db.loseResponse();
  const result = await store.writeRecordRecovering('group','ginasios','arena',null,occupancy);
  assert.equal(result.generation,1);
  assert.deepEqual((await store.readRecord('group','ginasios','arena')).state,occupancy);
});

test('tombstone generation prevents a stale snapshot from resurrecting a removed defender', async () => {
  const db = database(), store = new CanonicalGymStore(db.execute,'zapbot');
  const occupied = state('stale-player');
  await store.writeRecord('group','ginasios','volcano',null,occupied);
  await store.writeRecord('group','ginasios','volcano',occupied,null);
  await assert.rejects(store.writeRecordRecovering('group','ginasios','volcano',null,occupied),
    {code:'canonical_gym_tombstone_conflict'});
  assert.equal(await store.readRecord('group','ginasios','volcano'),null);
  assert.equal(db.records.get('group|ginasios:volcano').generation,2);
});

test('canonical module writes merge only intended gym keys and fail on conflicting key', async () => {
  const db = database(), store = new CanonicalGymStore(db.execute,'zapbot');
  const a = state('a'), b = state('b');
  await store.writeRecord('g1','ginasios','gym-a',null,a);
  await store.writeRecord('g1','ginasios','gym-b',null,b);
  const before = {g1:{'gym-a':a,'gym-b':b}};
  const updated = {...a,desde:500};
  const confirmed = await store.saveModule('ginasios',before,{g1:{'gym-a':updated,'gym-b':b}});
  assert.deepEqual(confirmed,{g1:{'gym-a':updated,'gym-b':b}});
  const competing = {...b,desde:900};
  await store.writeRecord('g1','ginasios','gym-b',b,competing);
  await assert.rejects(store.saveModule('ginasios',before,{g1:{'gym-a':a,'gym-b':{...b,desde:1000}}}),
    {code:'canonical_gym_cas_conflict'});
  assert.deepEqual((await store.readGroup('g1')).ginasios['gym-b'],competing);
});

test('canonical record event receipt and state commit atomically and retries are idempotent', async () => {
  const db = database(), store = new CanonicalGymStore(db.execute,'zapbot');
  const initial = state('player-a');
  await store.writeRecord('group','ginasios-estatisticas','forest',null,{defesas:0});
  const event = 'battle-uuid-0001';
  db.loseResponse();
  const applied = await store.updateRecordOnce('group','ginasios-estatisticas','forest',event,
    old => ({...old,defesas:old.defesas + 1}));
  assert.equal(applied.state.defesas,1);
  assert.ok(applied.operationReceipts.has(event));
  const replay = await store.updateRecordOnce('group','ginasios-estatisticas','forest',event,
    old => ({...old,defesas:old.defesas + 1}));
  assert.equal(replay.state.defesas,1);
  assert.equal(replay.generation,applied.generation);
  assert.deepEqual((await store.readRecord('group','ginasios-estatisticas','forest')).state,{defesas:1});
  assert.equal(initial.pid,'player-a');
});
