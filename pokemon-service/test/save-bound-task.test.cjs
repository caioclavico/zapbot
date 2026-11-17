'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {hash} = require('../runtime/restore-protocol.cjs');
const {normalizeSeal, freezeFromScope, assertSealMatchesRecord} = require('../runtime/save-bound-task.cjs');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');

function mock() {
  const accounts = new Map(), groups = new Set();
  const key = (chat, player) => `${chat}\0${player}`;
  const execute = async (sql, args) => {
    const result = {rows: [], first() { return this.rows[0]; }, wasApplied() { return this.applied; }};
    if (sql.includes('canonical_trainer_control')) {
      result.rows = [{protocol_version: 2, state:'active'}]; return result;
    }
    if (sql.includes('canonical_trainer_groups')) {
      if (sql.startsWith('INSERT')) { groups.add(args[1]); result.applied = true; }
      else result.rows = [...groups].map(chat_id => ({chat_id}));
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
        accounts.set(id, {generation:args[2], schema_version:args[3], protocol_version:args[4], state_hash:args[5],
          state_json:args[6], fence_generation:args[10], active_save_id:args[11], transition_owner:args[12], operation_receipts:{}});
        result.applied = true; return result;
      }
      if (sql.startsWith('UPDATE')) {
        const setColumns = sql.split(' SET ')[1].split(' WHERE ')[0].split(', ').map(part => part.split(' = ')[0]);
        const offset = setColumns.length;
        const conditions = sql.split(' IF ')[1].split(' AND ').map(part => part.split(' = ')[0]);
        const value = accounts.get(key(args[offset], args[offset+1]));
        result.applied = Boolean(value && conditions.every((column,index) => value[column] === args[offset+2+index]));
        if (result.applied) accounts.set(key(args[offset],args[offset+1]),
          {...value,...Object.fromEntries(setColumns.map((column,index) => [column,args[index]]))});
        return result;
      }
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute, accounts};
}

test('a tick started before reservation keeps its frozen fence and cannot recapture', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','a',{xp:1},'m');
  await store.createAccount('g','b',{xp:1},'m');
  let seal;
  await store.withWriterContext('g', () => { seal = store.freezeSaveBoundTask('a'); }, undefined, true, 'a');
  const started=await store.readAccount('g','a');
  const reserved=await store.reserveTransition('g','a',started,'transition_tick_01');
  await assert.rejects(store.runPersistedContext('g','a',seal,()=>store.saveGroup('g',{a:{xp:1}},{a:{xp:2}})),
    {code:'canonical_trainer_fence_conflict'});
  const reservedAccount=await store.readAccount('g','a');
  assert.throws(()=>assertSealMatchesRecord(seal,'a',reservedAccount),
    {code:'canonical_trainer_transition_reserved'});
  await store.withWriterContext('g', async () => {
    const peer=await store.readAccount('g','b');
    await store.compareAndSet('g','b',peer,{xp:8});
  }, undefined, true, 'b');
  assert.equal((await store.readAccount('g','b')).state.xp,8);
  await store.releaseTransition('g','a',reserved,'transition_tick_01');
});

test('a timer from save A cannot write after B is active even with identical JSON', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  const body={xp:1};
  await store.createAccount('g','p',body,'m');
  let seal;
  await store.withWriterContext('g',()=>{ seal=store.freezeSaveBoundTask('p'); },undefined,true,'p');
  const old=await store.readAccount('g','p');
  const reserved=await store.reserveTransition('g','p',old,'transition_timer_ab');
  await store.applyOnce('g','p',{effectId:'activate_b_timer',intentHash:hash(body),
    expectedGeneration:reserved.generation,expectedStateHash:reserved.stateHash,
    writerContext:reserved,targetSaveId:'b',transform:()=>body});
  const after=await store.readAccount('g','p');
  await store.releaseTransition('g','p',after,'transition_timer_ab');
  const switched=await store.readAccount('g','p');
  assert.throws(()=>assertSealMatchesRecord(seal,'p',switched),
    {code:'save_bound_fence_conflict'});
  await assert.rejects(store.runPersistedContext('g','p',seal,()=>store.saveGroup('g',{p:body},{p:{xp:9}})),
    {code:'canonical_trainer_fence_conflict'});
  assert.equal((await store.readAccount('g','p')).state.xp,1);
  await store.withWriterContext('g',async ()=>{
    const current=await store.readAccount('g','p');
    await store.compareAndSet('g','p',current,{xp:2});
  },undefined,true,'p');
  assert.equal((await store.readAccount('g','p')).state.xp,2);
});

test('restarted work without a persisted seal cannot adopt the live generation', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  await store.createAccount('g','p',{xp:1},'m');
  await assert.throws(()=>store.freezeSaveBoundTask('p'),{code:'save_bound_context_required'});
  await assert.rejects(store.runPersistedContext('g','p',null,()=>{}), {code:'save_bound_context_required'});
  const bad={chat:'g',player:'p',identityHash:hash(['g','p']),fenceGeneration:1,activeSaveId:'a',transitionOwner:''};
  const current=await store.readAccount('g','p');
  assertSealMatchesRecord(bad,'p',current);
  const reserved=await store.reserveTransition('g','p',current,'transition_restart_01');
  const held=await store.readAccount('g','p');
  assert.throws(()=>assertSealMatchesRecord(bad,'p',held),
    {code:'canonical_trainer_transition_reserved'});
  await store.releaseTransition('g','p',reserved,'transition_restart_01');
});

test('hatch receipt remains idempotent when the trainer LWT acknowledgement is lost', async () => {
  const db=mock(), store=new CanonicalTrainerStore(db.execute,'zapbot');
  const state={equipe:[], 'adventure-entregas':{}};
  await store.createAccount('g','p',state,'m');
  let seal;
  await store.withWriterContext('g',()=>{ seal=store.freezeSaveBoundTask('p'); },undefined,true,'p');
  const expected=await store.readAccount('g','p');
  const effect={effectId:'hatch_receipt_0001',intentHash:hash({hatch:'h1',pokemon:'id-1'}),
    expectedGeneration:expected.generation,expectedStateHash:expected.stateHash,writerContext:expected,
    transform: account => ({...account, equipe:[{id:'id-1'}], 'adventure-entregas':{h1:'id-1'}})};
  let lose=true;
  const interrupted=new CanonicalTrainerStore(async (sql,args,opts)=>{
    const result=await db.execute(sql,args,opts);
    if (lose && sql.startsWith('UPDATE') && sql.includes('operation_receipts = ?')) {
      lose=false; throw Object.assign(new Error('lost'),{code:'synthetic_lost_lwt_response'});
    }
    return result;
  },'zapbot');
  const recovered=await interrupted.applyOnce('g','p',effect);
  assert.equal(recovered.state.equipe[0].id,'id-1');
  const replay=await store.applyOnce('g','p',effect);
  assert.equal(replay.applied,false);
  assert.equal(replay.state['adventure-entregas'].h1,'id-1');
  await store.runPersistedContext('g','p',seal, async () => {
    const current=await store.readAccount('g','p');
    store.assertPersistedFence('g','p',seal,current);
  });
});

test('normalizeSeal rejects a lock-then-insert style payload without the frozen fence fields', () => {
  assert.equal(normalizeSeal({chat:'g',player:'p',lockOwner:'op'}), null);
  const scope={chat:'g',contexts:{p:{identityHash:hash(['g','p']),fenceGeneration:1,activeSaveId:'a',transitionOwner:''},
    q:{identityHash:hash(['g','q']),fenceGeneration:1,activeSaveId:'a',transitionOwner:''}}};
  const group=freezeFromScope(scope,null);
  assert.equal(Object.keys(group.contexts).length,2);
});
