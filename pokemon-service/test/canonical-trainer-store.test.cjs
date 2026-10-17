'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {CanonicalTrainerMigration} = require('../runtime/canonical-trainer-migration.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');
const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const {SaveSlotStore} = require('../runtime/save-slot-store.cjs');

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
        result.rows = [...accounts].filter(([k]) => k.startsWith(`${args[0]}\0`)).map(([k, value]) => ({player_id:k.split('\0')[1], ...value}));
        return result;
      }
      if (sql.startsWith('INSERT')) {
        if (accounts.has(id)) result.applied = false;
        else { accounts.set(id, {generation:args[2], schema_version:args[3], protocol_version:args[4], state_hash:args[5], state_json:args[6],
          fence_generation:args[10],active_save_id:args[11],transition_owner:args[12],operation_receipts:{}}); result.applied = true; }
        return result;
      }
      if (sql.startsWith('UPDATE')) {
        const setColumns = sql.split(' SET ')[1].split(' WHERE ')[0].split(', ').map(part => part.split(' = ')[0]);
        const offset = setColumns.length;
        const conditions = sql.split(' IF ')[1].split(' AND ').map(part => part.split(' = ')[0]);
        const value = accounts.get(key(args[offset],args[offset+1]));
        result.applied = Boolean(value && conditions.every((column,index) => value[column] === args[offset+2+index]));
        if (result.applied) accounts.set(key(args[offset],args[offset+1]),
          {...value,...Object.fromEntries(setColumns.map((column,index) => [column,args[index]]))});
        return result;
      }
    }
    if (sql.includes('canonical_trainer_backups')) {
      const id = key(args[0], args[1]) + `\0${args[2]}`;
      if (sql.startsWith('INSERT')) { if (!backups.has(id)) backups.set(id, {source_hash:args[3], source_json:args[4]}); result.applied = true; }
      else result.rows = backups.has(id) ? [backups.get(id)] : [];
      return result;
    }
    if (sql.includes('estado_particionado')) {
      result.rows = [{valor: JSON.stringify({playerA:{level:10,team:[{shiny:true}]},playerB:{coins:2}})}]; return result;
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute, accounts, state(value) {state=value;}, backups};
}

test('canonical storage is per-player, hashed, generation-versioned, and CAS rejects stale writers', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  await store.createAccount('g', 'a', {level:1, shiny:true}, 'migration-1');
  await store.createAccount('g', 'b', {coins:2}, 'migration-1');
  const before = await store.readAccount('g', 'a');
  const writes = await Promise.allSettled([
    store.compareAndSet('g', 'a', before, {level:2, shiny:true}),
    store.compareAndSet('g', 'a', before, {level:3, shiny:true})
  ]);
  assert.equal(writes.filter(x=>x.status==='fulfilled').length,1);
  assert.equal(writes.filter(x=>x.status==='rejected').length,1);
  assert.equal((await store.readAccount('g','a')).generation,2);
  assert.equal((await store.readAccount('g','b')).state.coins,2);
  assert.equal((await store.readAccount('g','a')).stateHash, hash((await store.readAccount('g','a')).state));
});

test('a callback captured before a save switch cannot overwrite the new active generation', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  await store.createAccount('group', 'ash', {slot:'a', equipe:[{id:'a', hp:20}]}, 'migration');
  await store.createAccount('group', 'misty', {slot:'m', moedas:7}, 'migration');
  const beforeSwitch = await store.readAccount('group', 'ash');
  const callbackSnapshot = structuredClone(beforeSwitch);
  const switched = {slot:'b', equipe:[{id:'b-shiny', hp:30, shiny:true}]};
  await store.compareAndSet('group', 'ash', beforeSwitch, switched);
  await assert.rejects(store.compareAndSet('group', 'ash', callbackSnapshot,
    {slot:'a', equipe:[{id:'a', hp:1}]}), {code:'canonical_trainer_cas_conflict'});
  assert.deepEqual((await store.readAccount('group','ash')).state, switched);
  assert.equal((await store.readAccount('group','misty')).state.moedas, 7);
});

test('canonical saveGroup rejects a stale whole-group snapshot without touching another player', async () => {
  const db = mock(), store = new CanonicalTrainerStore(db.execute, 'zapbot');
  await store.createAccount('group', 'ash', {xp:1}, 'migration');
  await store.createAccount('group', 'misty', {moedas:5}, 'migration');
  const stale = await store.readGroup('group');
  const current = await store.readAccount('group','ash');
  await store.compareAndSet('group','ash',current,{xp:2});
  await assert.rejects(store.withWriterContext('group', () => store.saveGroup('group', stale, {...stale, ash:{xp:9}})),
    {code:'canonical_trainer_cas_conflict'});
  assert.equal((await store.readAccount('group','ash')).state.xp,2);
  assert.equal((await store.readAccount('group','misty')).state.moedas,5);
});

test('canonical applyOnce persists the effect receipt with synthetic HP and reconciles a lost response', async () => {
  const db = mock(), setup = new CanonicalTrainerStore(db.execute, 'zapbot');
  const state = {equipe:[{id:'starter', hp:12, hpMax:20}], coins:0};
  await setup.createAccount('g', 'a', state, 'migration-1');
  const expected = await setup.readAccount('g', 'a');
  let loseResponse = true;
  const interrupted = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await db.execute(sql, args, options);
    if (loseResponse && sql.startsWith('UPDATE') && sql.includes('operation_receipts = ?')) {
      loseResponse = false;
      throw Object.assign(new Error('synthetic_lost_lwt_response'), {code:'synthetic_lost_lwt_response'});
    }
    return result;
  }, 'zapbot');
  const effect = {effectId:'battle_hp_effect_0001', intentHash:hash({pokemon:'starter',hp:7}),
    expectedGeneration:expected.generation, expectedStateHash:expected.stateHash,
    writerContext:expected,
    transform: account => ({...account, equipe:account.equipe.map(item => item.id === 'starter' ? {...item,hp:7} : item)})};
  const recovered = await interrupted.applyOnce('g','a',effect);
  assert.equal(recovered.state.equipe[0].hp,7);
  assert.equal(recovered.operationReceipts[effect.effectId],effect.intentHash);
  const replay = await setup.applyOnce('g','a',effect);
  assert.equal(replay.state.equipe[0].hp,7);
  await assert.rejects(setup.applyOnce('g','a',{...effect,intentHash:hash({pokemon:'starter',hp:1})}),
    {code:'canonical_trainer_effect_conflict'});
  await assert.rejects(setup.applyOnce('g','a',{...effect,effectId:'battle_hp_effect_0002'}),
    {code:'canonical_trainer_stale_generation'});
});

test('canonical runtime refuses absent activation and never falls back to legacy', async () => {
  const db = mock(); db.state('migrating');
  assert.equal((await db.execute('SELECT protocol_version, state FROM canonical_trainer_control',[])).first().state,'migrating');
  const store = new CanonicalTrainerStore(db.execute, 'zapbot');
  await assert.rejects(store.readGroup('g'), {code:'canonical_trainer_not_activated'});
  db.state('active');
  assert.deepEqual(await store.readGroup('g'), {});
});

test('multi-player snapshot reports partial durable progress for explicit reconciliation', async () => {
  const db = mock(), setup = new CanonicalTrainerStore(db.execute,'zapbot');
  await setup.createAccount('g','a',{value:1},'migration');
  await setup.createAccount('g','b',{value:2},'migration');
  const failB = new CanonicalTrainerStore(async (sql,args,options) => {
    if (sql.startsWith('UPDATE') && args[6] === 'b') throw Object.assign(new Error('write unavailable'),{code:'synthetic'});
    return db.execute(sql,args,options);
  },'zapbot');
  await assert.rejects(failB.withWriterContext('g', () => failB.saveGroup('g',{a:{value:1},b:{value:2}},{a:{value:10},b:{value:20}})),
    {code:'canonical_trainer_partial_write'});
  assert.equal((await setup.readAccount('g','a')).state.value,10);
  assert.equal((await setup.readAccount('g','b')).state.value,2);
});

test('migration requires legacy writer revocation and immutable verified backup before canonical import', async () => {
  const db = mock(); db.state('migrating');
  assert.equal((await db.execute('SELECT protocol_version, state FROM canonical_trainer_control',[])).first().state,'migrating');
  const store = new CanonicalTrainerStore(db.execute, 'zapbot');
  let revoked = false;
  const migration = new CanonicalTrainerMigration({legacyExecute:db.execute,backupExecute:db.execute,
    canonicalStore:store,keyspace:'zapbot',verifyLegacyWriterRevoked:async()=>revoked
      ? {verified:true,legacyModifyDenied:true,role:'legacy_writer',permissionHash:hash({roles:[],permissions:[]})}
      : false});
  await assert.rejects(migration.migrate('g','playerA','mig'), {code:'legacy_writer_not_fenced'});
  revoked = true;
  const result = await migration.migrate('g','playerA','mig');
  assert.equal(result.stateHash, hash({level:10,team:[{shiny:true}]}));
  assert.equal(db.backups.size,1);
  db.state('active');
  assert.equal((await store.readAccount('g','playerA')).state.team[0].shiny,true);
});

test('reservation beats a writer paused AFTER its preflight: the final account LWT rejects it', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','a',{hp:20},'m');
  await store.createAccount('g','b',{hp:30},'m');
  const old=await store.readAccount('g','a');
  let entered, resume;
  const paused=new Promise(resolve=>{entered=resolve;});
  const barrier=new Promise(resolve=>{resume=resolve;});
  const writer=new CanonicalTrainerStore(async (sql,args,opts)=>{
    if (sql.startsWith('UPDATE') && sql.includes('state_json = ?')) {
      assert.match(sql,/AND fence_generation = \? AND active_save_id = \? AND transition_owner = \?/);
      entered(); await barrier;
    }
    return db.execute(sql,args,opts);
  },'zapbot');
  const pending=writer.compareAndSet('g','a',old,{hp:1});
  await paused;
  const reserved=await store.reserveTransition('g','a',old,'transition_0001');
  assert.equal(reserved.fenceGeneration,old.fenceGeneration+1);
  resume();
  await assert.rejects(pending,{code:'canonical_trainer_cas_conflict'});
  assert.equal((await store.readAccount('g','a')).state.hp,20);
  const peer=await store.readAccount('g','b');
  await store.compareAndSet('g','b',peer,{hp:25});
  assert.equal((await store.readAccount('g','b')).state.hp,25);
});

test('a writer which wins before the reservation invalidates its old source snapshot', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{xp:1},'m');
  const old=await store.readAccount('g','p');
  await store.compareAndSet('g','p',old,{xp:2});
  await assert.rejects(store.reserveTransition('g','p',old,'transition_race_02'),
    {code:'canonical_trainer_reservation_conflict'});
  const current=await store.readAccount('g','p');
  assert.equal(current.state.xp,2);
  assert.equal(current.transitionOwner,'');
});

test('an old callback cannot refresh its fence through a nested runtime scope after A to B', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  const a={slot:'a',shiny:true,xp:4};
  await store.createAccount('g','p',a,'m');
  let entered,resume;
  const paused=new Promise(resolve=>{entered=resolve;});
  const barrier=new Promise(resolve=>{resume=resolve;});
  const callback=store.withWriterContext('g',async ()=>{
    entered(); await barrier;
    return store.withWriterContext('g',()=>store.saveGroup('g',{p:a},{p:{...a,xp:99}}));
  });
  await paused;
  const old=await store.readAccount('g','p');
  const reserved=await store.reserveTransition('g','p',old,'transition_0002');
  const b={slot:'b',shiny:false,xp:10};
  const effect={effectId:'activate_effect_0002',intentHash:hash(b),expectedGeneration:reserved.generation,
    expectedStateHash:reserved.stateHash,writerContext:reserved,targetSaveId:'b',transform:()=>b};
  const applied=await store.applyOnce('g','p',effect);
  await store.releaseTransition('g','p',applied,'transition_0002');
  resume();
  await assert.rejects(callback,{code:'canonical_trainer_cas_conflict'});
  await assert.rejects(store.compareAndSet('g','p',old,{...a,xp:100}),{code:'canonical_trainer_fence_conflict'});
  const current=await store.readAccount('g','p');
  assert.equal(current.activeSaveId,'b');
  assert.deepEqual(current.state,b);
  await store.withWriterContext('g',()=>store.saveGroup('g',{p:b},{p:{...b,xp:11}}));
  assert.equal((await store.readAccount('g','p')).state.xp,11);
});

test('even identical A/B content cannot let an old callback adopt the new generation', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  const a={xp:1}; await store.createAccount('g','p',a,'m');
  await store.withWriterContext('g',async ()=>{
    const old=await store.readAccount('g','p');
    const reserved=await store.reserveTransition('g','p',old,'transition_0003');
    await store.releaseTransition('g','p',reserved,'transition_0003');
    await assert.rejects(store.saveGroup('g',{p:a},{p:{xp:2}}),{code:'canonical_trainer_fence_conflict'});
  });
  await assert.rejects(store.saveGroup('g',{p:a},{p:{xp:2}}),{code:'canonical_trainer_context_required'});
  // allowCapture false runs the action without installing a live fence; writes
  // still fail closed until a persisted seal or a capturing entry is used.
  let bare = false;
  await store.withWriterContext('g', async () => {
    bare = !store.currentWriterContext();
    await assert.rejects(store.saveGroup('g',{p:a},{p:{xp:3}}),{code:'canonical_trainer_context_required'});
  }, undefined, false);
  assert.equal(bare, true);
});

test('a timer inherits its original save context even if it reads a fresh record after the barrier', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{hp:20},'m');
  const old=await store.readAccount('g','p');
  let fire;
  const signal=new Promise(resolve=>{fire=resolve;});
  let completion;
  await store.withWriterContext('g',()=>{
    completion=new Promise(resolve=>{
      setTimeout(async ()=>{
        await signal;
        try {
          const fresh=await store.readAccount('g','p');
          await store.compareAndSet('g','p',fresh,{hp:1});
          resolve(null);
        } catch (error) {resolve(error.code);}
      },0);
    });
  });
  const reserved=await store.reserveTransition('g','p',old,'transition_timer_01');
  await store.releaseTransition('g','p',reserved,'transition_timer_01');
  fire();
  assert.equal(await completion,'canonical_trainer_fence_conflict');
  assert.equal((await store.readAccount('g','p')).state.hp,20);
});

test('protocol-1 LWT cannot write a protocol-2 row even with the current content generation', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{xp:1},'m');
  const record=await store.readAccount('g','p');
  const result=await db.execute('UPDATE zapbot.canonical_trainer_accounts SET state_json = ? WHERE chat_id = ? AND player_id = ? IF generation = ? AND state_hash = ? AND protocol_version = ?',
    ['{"xp":999}','g','p',record.generation,record.stateHash,1]);
  assert.equal(result.wasApplied(),false);
  assert.equal((await store.readAccount('g','p')).state.xp,1);
});

test('lost reservation LWT response is confirmed by durable receipt and survives a new store', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{xp:1},'m');
  const old=await store.readAccount('g','p');
  let lose=true;
  const interrupted=new CanonicalTrainerStore(async (sql,args,opts)=>{
    const result=await db.execute(sql,args,opts);
    if (lose && sql.startsWith('UPDATE') && sql.includes('SET generation = ?, fence_generation')) {
      lose=false; throw new Error('lost_reservation_response');
    }
    return result;
  },'zapbot');
  const reserved=await interrupted.reserveTransition('g','p',old,'transition_0004');
  const restarted=new CanonicalTrainerStore(db.execute,'zapbot');
  const again=await restarted.reserveTransition('g','p',old,'transition_0004');
  assert.equal(again.generation,reserved.generation);
  await assert.rejects(restarted.withWriterContext('g',()=>{},undefined,true,'p'),
    {code:'canonical_trainer_transition_reserved'});
  await assert.rejects(restarted.compareAndSet('g','p',old,{xp:2}),{code:'canonical_trainer_fence_conflict'});
  await restarted.releaseTransition('g','p',again,'transition_0004');
  assert.equal((await restarted.readAccount('g','p')).transitionOwner,'');
});

test('an uncertain reservation stays closed; protocol 1 and records lacking fence metadata are refused', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{xp:1},'m');
  const old=await store.readAccount('g','p');
  let unavailable=false;
  const broken=new CanonicalTrainerStore(async (sql,args,opts)=>{
    if (unavailable) throw new Error('Cassandra unavailable');
    const result=await db.execute(sql,args,opts);
    if (sql.startsWith('UPDATE')) {unavailable=true;throw new Error('lost_response');}
    return result;
  },'zapbot');
  await assert.rejects(broken.reserveTransition('g','p',old,'transition_0005'),{code:'canonical_trainer_result_unknown'});
  assert.equal((await store.readAccount('g','p')).transitionOwner,'transition_0005');
  assert.throws(()=>new CanonicalTrainerStore(db.execute,'zapbot',{protocolVersion:1}),{code:'canonical_trainer_writer_incompatible'});
  const raw=db.accounts.get('g\0p');
  delete raw.fence_generation;
  await assert.rejects(store.readAccount('g','p'),{code:'canonical_trainer_fence_incompatible'});
});

test('admission refuses a reserved player without recapturing the new save for an old callback', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','a',{xp:1},'m');
  await store.createAccount('g','b',{xp:1},'m');
  const started=await store.readAccount('g','a');
  let entered, resume;
  const paused=new Promise(resolve=>{entered=resolve;});
  const barrier=new Promise(resolve=>{resume=resolve;});
  const pending=store.withWriterContext('g', async () => {
    entered(); await barrier;
    return store.saveGroup('g',{a:{xp:1},b:{xp:1}},{a:{xp:9},b:{xp:1}});
  }, undefined, true, 'a');
  await paused;
  const reserved=await store.reserveTransition('g','a',started,'transition_admit_01');
  resume();
  await assert.rejects(pending,{code:'canonical_trainer_fence_conflict'});
  await assert.rejects(store.withWriterContext('g',()=>{},undefined,true,'a'),
    {code:'canonical_trainer_transition_reserved'});
  await store.withWriterContext('g', async () => {
    const peer=await store.readAccount('g','b');
    await store.compareAndSet('g','b',peer,{xp:4});
  }, undefined, true, 'b');
  assert.equal((await store.readAccount('g','a')).state.xp,1);
  assert.equal((await store.readAccount('g','a')).transitionOwner,'transition_admit_01');
  assert.equal((await store.readAccount('g','b')).state.xp,4);
  await store.releaseTransition('g','a',reserved,'transition_admit_01');
  const released=await store.readAccount('g','a');
  await store.withWriterContext('g',()=>store.compareAndSet('g','a',released,{xp:2}),undefined,true,'a');
  assert.equal((await store.readAccount('g','a')).state.xp,2);
});

test('uncertified lateral writers fail closed during a reservation and require inherited context', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','a',{xp:1},'m');
  await store.createAccount('g','b',{xp:1},'m');
  const old=await store.readAccount('g','a');
  await store.reserveTransition('g','a',old,'transition_lateral_01');
  await assert.rejects(store.assertLateralAdmission('loja'),{code:'canonical_trainer_context_required'});
  await assert.rejects(store.withWriterContext('g',()=>store.assertLateralAdmission('loja'),undefined,true,'a'),
    {code:'canonical_trainer_transition_reserved'});
  await store.withWriterContext('g',()=>store.assertLateralAdmission('loja'),undefined,true,'b');
  await store.withWriterContext('g',()=>store.assertLateralAdmission('treinador'),undefined,true,'b');
});

test('playable activation uses the real canonical reservation and recovers after its acknowledgement is lost', async () => {
  const db=mock(), rows=new Map();
  const execute=async (sql,args,opts)=>{
    if (!sql.includes('estado_particionado')) {
      return db.execute(sql,args,opts);
    }
    const result={first:()=>null,wasApplied:()=>false};
    if (sql.startsWith('SELECT')) {
      const valor=rows.get(`${args[0]}|${args[1]}`);
      return {first:()=>valor === undefined ? null : {valor}};
    }
    if (sql.startsWith('INSERT')) {
      const key=`${args[0]}|${args[1]}`, applied=!rows.has(key);
      if (applied) rows.set(key,args[2]);
      return {wasApplied:()=>applied};
    }
    const key=`${args[1]}|${args[2]}`, applied=rows.get(key)===args[3];
    if (applied) rows.set(key,args[0]);
    return {...result,wasApplied:()=>applied};
  };
  const trainer=new CanonicalTrainerStore(execute,'zapbot');
  const slots=new SaveSlotStore(execute,'zapbot');
  const coordinator=()=>new SaveTransitionCoordinator(execute,'zapbot',{enabled:true,
    legacyJournalCoverageVerified:true,writerProtocolCoverageVerified:true});
  const service=store=>new PlayableSaveActivation(execute,'zapbot',{slotStore:slots,
    trainerStore:store || trainer,coordinator:coordinator(),enabled:true});
  const a={equipe:[],enfermaria:[],moedas:10}, b={equipe:[],enfermaria:[],moedas:20};
  await trainer.createAccount('g','p',a,'m');
  const activation=service();
  await activation.bootstrap('g','p','a');
  await activation.createPlayable('g','p','b',b,'historical-import');
  // Lose the adapter acknowledgement after applyOnce has confirmed the LWT.
  const adapter={readAccount:(...args)=>trainer.readAccount(...args),
    reserveTransition:(...args)=>trainer.reserveTransition(...args),
    releaseTransition:(...args)=>trainer.releaseTransition(...args),
    applyOnce:async (...args)=>{await trainer.applyOnce(...args);throw new Error('lost_adapter_ack');}};
  await assert.rejects(service(adapter).activate('g','p','b','activate_atomic_01'),/lost_adapter_ack/);
  const applied=await trainer.readAccount('g','p');
  assert.equal(applied.activeSaveId,'b');
  assert.equal(applied.transitionOwner,'activate_atomic_01');
  assert.equal(applied.fenceGeneration,2);
  const restarted=new CanonicalTrainerStore(execute,'zapbot');
  await service(restarted).recover('g','p','activate_atomic_01');
  assert.equal((await restarted.readAccount('g','p')).transitionOwner,'');
  assert.equal((await activation.readPlayable('g','p','a')).account.moedas,10);
  await service(restarted).activate('g','p','a','activate_atomic_02');
  assert.equal((await restarted.readAccount('g','p')).state.moedas,10);
  assert.equal((await activation.readPlayable('g','p','b')).account.moedas,20);
});

