'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {hash} = require('../runtime/restore-protocol.cjs');
const {SaveSlotStore} = require('../runtime/save-slot-store.cjs');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');

const clone = value => structuredClone(value);
const source = {kind:'legacy_estado_particionado',version:'1'};
function pokemon(id, extra = {}) { return {'id-pokemon':id,nome:id,nivel:5,hp:30,'hp-atual':30,ataque:18,defesa:17,'atq-esp':16,'def-esp':15,veloc:14,'xp-desde-nivel':4,golpes:[],tipos:['normal'],...extra}; }
function account(id, extra = {}) { return {ativo:0,equipe:[pokemon(id,{shiny:id === 'b-shiny'})],pc:[pokemon(`${id}-pc`)],enfermaria:[],inventario:{pokebola:id === 'a' ? 3 : 9},moedas:id === 'a' ? 10 : 70,...extra}; }

function database() {
  const rows = new Map(); let tail = Promise.resolve();
  const execute = (sql,args) => {
    const work = tail.then(async () => {
      if (sql.startsWith('SELECT ')) { const value = rows.get(`${args[0]}|${args[1]}`); const result = value == null ? null : {valor:value}; return {first:()=>result,rows:result?[result]:[]}; }
      if (sql.startsWith('INSERT ') && sql.endsWith('IF NOT EXISTS')) { const key=`${args[0]}|${args[1]}`; const applied=!rows.has(key); if (applied) rows.set(key,args[2]); return {wasApplied:()=>applied}; }
      if (sql.startsWith('UPDATE ') && sql.includes('IF valor = ?')) { const key=`${args[1]}|${args[2]}`; const applied=rows.get(key)===args[3]; if (applied) rows.set(key,args[0]); return {wasApplied:()=>applied}; }
      throw new Error(`unexpected_cql:${sql}`);
    });
    tail = work.catch(()=>{}); return work;
  };
  return {rows,execute};
}

function canonical(initial) {
  let record = {identityHash:hash(['g','p']),fenceGeneration:1,activeSaveId:'a',transitionOwner:'',
    generation:1,state:clone(initial),stateHash:hash(initial),operationReceipts:{}};
  let loseResponse = false, conflictNext = false;
  return {
    readAccount: async () => clone(record),
    async reserveTransition(_chat,_player,expected,operationId) {
      assert.equal(record.generation,expected.generation);
      assert.equal(record.fenceGeneration,expected.fenceGeneration);
      assert.equal(record.transitionOwner,'');
      record={...record,generation:record.generation+1,fenceGeneration:record.fenceGeneration+1,transitionOwner:operationId};
      return clone(record);
    },
    async releaseTransition(_chat,_player,expected,operationId) {
      assert.equal(record.transitionOwner,operationId);
      assert.equal(record.fenceGeneration,expected.fenceGeneration);
      record={...record,generation:record.generation+1,transitionOwner:''};
      return clone(record);
    },
    async applyOnce(_chat,_player,{effectId,intentHash,expectedGeneration,expectedStateHash,writerContext,targetSaveId,transform}) {
      if (record.operationReceipts[effectId] === intentHash) return clone(record);
      assert.equal(writerContext.fenceGeneration,record.fenceGeneration);
      assert.equal(writerContext.transitionOwner,record.transitionOwner);
      if (conflictNext) {
        conflictNext=false;
        const state={...record.state,moedas:record.state.moedas+1};
        record={...record,generation:record.generation+1,state,stateHash:hash(state)};
      }
      if (record.generation !== expectedGeneration || record.stateHash !== expectedStateHash) throw Object.assign(new Error('stale'),{code:'canonical_trainer_stale_generation'});
      const next = transform(clone(record.state));
      record = {...record,activeSaveId:targetSaveId,generation:record.generation + 1,state:next,stateHash:hash(next),operationReceipts:{...record.operationReceipts,[effectId]:intentHash}};
      if (loseResponse) { loseResponse=false; throw new Error('lost_after_canonical_lwt'); }
      return clone(record);
    },
    mutate(fn) { const state=fn(clone(record.state)); record={...record,generation:record.generation+1,state,stateHash:hash(state)}; },
    conflictNext() { conflictNext=true; },
    loseNextResponse() { loseResponse=true; }
  };
}

async function setup({eligibility = null, historyReceipts = {rewardA:'a'}} = {}) {
  const db=database(), active=account('a',{'adventure-entregas':{rewardA:'a'}}), trainer=canonical(active);
  const slotStore=new SaveSlotStore(db.execute,'zapbot');
  const coordinator=() => new SaveTransitionCoordinator(db.execute,'zapbot',{enabled:true,legacyJournalCoverageVerified:true,writerProtocolCoverageVerified:true});
  const service=() => new PlayableSaveActivation(db.execute,'zapbot',{slotStore,trainerStore:trainer,coordinator:coordinator(),eligibility,enabled:true});
  const history=account('b-shiny',{'adventure-entregas':historyReceipts});
  await slotStore.importValidated({chat:'g',player:'p',slotId:'historic-b',source,account:history,currentAccount:active});
  const instance=service();
  await instance.bootstrap('g','p','a');
  await instance.importHistorical('g','p','historic-b','b');
  return {db,active,history,trainer,slotStore,service};
}

test('A to B to A preserves independent Pokémon, XP, inventory, shiny, and global receipts', async () => {
  const h=await setup();
  await h.service().activate('g','p','b','activate_B_01');
  let current=await h.trainer.readAccount('g','p');
  assert.equal(current.state.equipe[0]['id-pokemon'],'b-shiny');
  assert.equal(current.state.equipe[0].shiny,true);
  assert.equal(current.state.inventario.pokebola,9);
  assert.deepEqual(current.state['adventure-entregas'],{rewardA:'a'});
  h.trainer.mutate(state => ({...state,moedas:99,equipe:[{...state.equipe[0],'xp-desde-nivel':8},...state.equipe.slice(1)]}));
  await h.service().activate('g','p','a','activate_A_01');
  current=await h.trainer.readAccount('g','p');
  assert.equal(current.state.equipe[0]['id-pokemon'],'a');
  assert.equal(current.state.moedas,10);
  const b=await h.service().readPlayable('g','p','b');
  assert.equal(b.account.moedas,99);
  assert.equal(b.account.equipe[0]['xp-desde-nivel'],8);
  assert.equal(Object.hasOwn(b.account,'adventure-entregas'),false);
});

test('startup hydration accepts a canonical receipt subset of the monotonic global ledger', async () => {
  const h=await setup({historyReceipts:{rewardA:'a',rewardB:'b-shiny'}});
  const hydrated=await h.service().hydrateActive('g','p');
  assert.deepEqual(hydrated.account['adventure-entregas'],{rewardA:'a'},
    'hydration must not copy receipt records from an inactive slot into the active save');
  const ledger=await h.slotStore.read('pokemon-save-global-receipts',hash(['g','p']));
  assert.deepEqual(ledger.value.receipts,{rewardA:'a',rewardB:'b-shiny'},
    'the global receipt ledger remains the monotonic union of imported slots');
});

test('startup hydration follows the confirmed active pointer without replacing current slot progress', async () => {
  const h=await setup();
  await h.service().activate('g','p','b','activate_hydrate_B_01');
  h.trainer.mutate(state => ({...state, moedas:91,
    equipe:[{...state.equipe[0], shiny:true, 'xp-desde-nivel':8},...state.equipe.slice(1)]}));
  // A fresh service instance models a process restart: it reads only durable
  // pointer, slot, global ledger and canonical trainer state.
  const hydrated=await h.service().hydrateActive('g','p');
  assert.equal(hydrated.activeSlotId,'b');
  assert.equal(hydrated.account.moedas,91);
  assert.equal(hydrated.account.equipe[0].shiny,true);
  assert.equal(hydrated.account.equipe[0]['xp-desde-nivel'],8);
  assert.deepEqual(hydrated.account['adventure-entregas'],{rewardA:'a'});
  await h.service().activate('g','p','a','activate_hydrate_A_01');
  const restoredA=await h.service().hydrateActive('g','p');
  assert.equal(restoredA.activeSlotId,'a');
  assert.equal(restoredA.account.equipe[0]['id-pokemon'],'a');
  assert.equal(restoredA.account.moedas,10);
});

test('a hydration failure does not publish a different active pointer', async () => {
  const h=await setup();
  const before=await h.service().read('pokemon-active-save-pointer',hash(['g','p']));
  const original=await h.trainer.readAccount('g','p');
  await h.trainer.reserveTransition('g','p',original,'hydrate_fail_01');
  await assert.rejects(h.service().hydrateActive('g','p'),{code:'playable_save_transition_pending'});
  const after=await h.service().read('pokemon-active-save-pointer',hash(['g','p']));
  assert.deepEqual(after.value, before.value);
  assert.equal((await h.trainer.readAccount('g','p')).activeSaveId,'a');
});

test('startup hydration fails closed for a reserved transition, pointer conflict, or receipt mismatch', async () => {
  const h=await setup();
  const original=await h.trainer.readAccount('g','p');
  await h.trainer.reserveTransition('g','p',original,'hydrate_pending_01');
  await assert.rejects(h.service().hydrateActive('g','p'),{code:'playable_save_transition_pending'});

  const h2=await setup();
  h2.trainer.mutate(state => ({...state,'adventure-entregas':{other:'receipt'}}));
  await assert.rejects(h2.service().hydrateActive('g','p'),{code:'playable_save_global_receipts_changed'});

  const h3=await setup();
  const originalRead=h3.trainer.readAccount;
  const instance=h3.service();
  instance.trainerStore={...h3.trainer,readAccount:async (...args)=>
    ({...await originalRead(...args),activeSaveId:'b'})};
  await assert.rejects(instance.hydrateActive('g','p'),{code:'playable_save_hydration_conflict'});

  const h4=await setup(), hashInstance=h4.service();
  const originalPointer=hashInstance.read.bind(hashInstance);
  hashInstance.read=async (module, partition) => {
    const observed=await originalPointer(module,partition);
    if (module !== 'pokemon-active-save-pointer') return observed;
    const current=await h4.trainer.readAccount('g','p');
    return {...observed,value:{...observed.value,canonicalGeneration:current.generation,
      canonicalStateHash:'0'.repeat(64)}};
  };
  await assert.rejects(hashInstance.hydrateActive('g','p'),{code:'playable_save_hydration_conflict'});
});

test('concurrent activation performs one switch; a duplicate is either fenced or a durable no-op', async () => {
  const h=await setup();
  const concurrent=await Promise.allSettled([
    h.service().activate('g','p','b','activate_B_02'), h.service().activate('g','p','b','activate_B_03')
  ]);
  assert.equal(concurrent.filter(item=>item.value?.idempotent === false).length,1);
  const duplicate = concurrent.find(item => item.value?.idempotent === true || item.status === 'rejected');
  assert.ok(duplicate, 'the duplicate request must not perform a second canonical switch');
  if (duplicate.status === 'rejected') {
    assert.match(`${duplicate.reason?.code || duplicate.reason}`, /transition_(?:resource_locked|startup_unresolved)/);
  }
  const afterConcurrent = await h.trainer.readAccount('g','p');
  assert.equal(Object.keys(afterConcurrent.operationReceipts).length,1,
    'only the winning operation may write a canonical trainer receipt');
  const h2=await setup();
  h2.trainer.conflictNext();
  await assert.rejects(h2.service().activate('g','p','b','activate_B_04'),/playable_save_generation_conflict/);
});

test('a lost canonical acknowledgement recovers from the persisted receipt after restart without duplicate activation', async () => {
  const h=await setup();
  h.trainer.loseNextResponse();
  await assert.rejects(h.service().activate('g','p','b','activate_B_05'),/lost_after_canonical_lwt/);
  const recovered=await h.service().recover('g','p','activate_B_05');
  assert.equal(recovered.activeSlotId,'b');
  const current=await h.trainer.readAccount('g','p');
  assert.equal(current.state.equipe[0]['id-pokemon'],'b-shiny');
  assert.equal(current.generation,4);
  assert.equal(Object.keys(current.operationReceipts).length,1);
  const again=await h.service().recover('g','p','activate_B_05');
  assert.equal(again.completed,true);
});

test('failure before canonical activation preserves the active pointer and source account', async () => {
  const h=await setup();
  const target=await h.service().readPlayable('g','p','b');
  target.account.moedas=999; // only local copy; durable record remains intact
  h.trainer.conflictNext();
  await assert.rejects(h.service().activate('g','p','b','activate_B_06'),/playable_save_generation_conflict/);
  const pointer=await h.service().read('pokemon-active-save-pointer',hash(['g','p']));
  assert.equal(pointer.value.activeSlotId,'a');
  assert.equal((await h.trainer.readAccount('g','p')).state.moedas,11);
});

test('activation revalidates activity after the durable reservation', async () => {
  let checks = 0;
  const eligibility = {check: async () => {
    checks += 1;
    return checks === 1 ? {eligible:true,blocks:[]} : {eligible:false,blocks:[{code:'activity_battle_active'}]};
  }};
  const h=await setup({eligibility});
  const identity=hash(['g','p']);
  await assert.rejects(h.service().activate('g','p','b','activate_B_07'), error =>
    error?.code === 'save_transition_activity_blocked' && error.blocks[0].code === 'activity_battle_active');
  assert.equal(checks,2);
  const pointer=await h.service().read('pokemon-active-save-pointer',identity);
  assert.equal(pointer.value.activeSlotId,'a');
  const account=await h.trainer.readAccount('g','p');
  assert.equal(account.state.equipe[0]['id-pokemon'],'a');
  assert.equal(account.transitionOwner,'','post-reserve eligibility failure must release the reservation');
  const operation=await h.service().read('pokemon-playable-save-operations',`${identity}:activate_B_07`);
  assert.equal(operation.value.phase,'blocked',
    'unwind without activation must not look completed to recover()');
  await assert.rejects(h.service().recover('g','p','activate_B_07'),{code:'playable_save_activation_blocked'});
});

test('real eligibility gate blocks each impediment and isolates the peer', async () => {
  const {SaveActivityEligibility} = require('../runtime/save-activity-eligibility.cjs');
  const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');
  const moduleByState = {
    gyms:'ginasios', battles:'pokemon-batalhas-ativas', hunts:'pokemon-cacadas-ativas',
    raids:'raids', trades:'pokemon-propostas-troca', moveOffers:'pokemon-remocoes-pendentes'
  };
  const impediments = [
    {states:{battles:{x:{pid:'p'}}}, code:'activity_battle_active'},
    {states:{hunts:{x:{jogador:'p'}}}, code:'activity_hunt_active'},
    {states:{raids:{x:{participantes:{p:{}},fase:'combate'}}}, code:'activity_raid_active'},
    {states:{raids:{x:{participantes:{p:{}},fase:'vitoria',expira:Date.now()+1000}}},
      code:'activity_raid_capture_pending'},
    {states:{trades:{x:{a:'p',b:'peer'}}}, code:'activity_trade_pending'},
    {states:{moveOffers:{x:{player:'p'}}}, code:'activity_move_offer_pending'},
    {states:{gyms:{alpha:{pid:'p',time:[]}}}, code:'activity_gym_occupied'},
    {accountExtra:{enfermaria:[{'pronto-em':Date.now()+1000}]}, code:'activity_joy_pending'},
    {accountExtra:{'adventure-eventos':{e1:{'event-id':'e1'}}}, code:'activity_adventure_events_pending'},
    {adventure:{eggs:[{state:'delivery_pending'}]}, code:'activity_adventure_egg_pending'}
  ];
  for (const case_ of impediments) {
    const h = await setup();
    const rows = new Map();
    for (const [name, module] of Object.entries(moduleByState)) {
      if (case_.states?.[name] !== undefined) rows.set(`${module}|g`, JSON.stringify(case_.states[name]));
    }
    const execute = async (_sql, args) => {
      const valor = rows.get(`${args[0]}|${args[1]}`);
      return {first: () => valor == null ? null : {valor}};
    };
    const eligibility = new SaveActivityEligibility(execute, 'zapbot', {
      writerCoverageVerified: true,
      accountReader: async () => ({state: {enfermaria: [], ...(case_.accountExtra || {})}, transitionOwner: ''}),
      adventureReader: case_.adventure ? async () => case_.adventure : null
    });
    const activation = h.service();
    const service = new PlayableSaveActivation(h.db.execute, 'zapbot', {
      slotStore: h.slotStore, trainerStore: h.trainer, coordinator: activation.coordinator,
      eligibility, enabled: true
    });
    await assert.rejects(service.activate('g', 'p', 'b', `block_${case_.code}`), error =>
      error?.code === 'save_transition_activity_blocked' &&
      error.blocks.some(item => item.code === case_.code));
    assert.equal((await h.trainer.readAccount('g', 'p')).activeSaveId, 'a');
    assert.equal((await h.trainer.readAccount('g', 'p')).transitionOwner, '');
  }

  const idle = await setup();
  const peerRows = new Map([['pokemon-batalhas-ativas|g', JSON.stringify({x: {pid: 'peer'}})]]);
  const peerGate = new SaveActivityEligibility(async (_sql, args) => {
    const valor = peerRows.get(`${args[0]}|${args[1]}`);
    return {first: () => valor == null ? null : {valor}};
  }, 'zapbot', {
    writerCoverageVerified: true,
    accountReader: async () => ({state: {enfermaria: []}, transitionOwner: ''})
  });
  assert.equal((await peerGate.check({chat: 'g', player: 'p'})).eligible, true);
  const activation = idle.service();
  const ok = new PlayableSaveActivation(idle.db.execute, 'zapbot', {
    slotStore: idle.slotStore, trainerStore: idle.trainer, coordinator: activation.coordinator,
    eligibility: peerGate, enabled: true
  });
  assert.equal((await ok.activate('g', 'p', 'b', 'peer_ok_01')).activeSlotId, 'b');
});

test('uncertified writers fail closed even for an otherwise idle account', async () => {
  const {SaveActivityEligibility} = require('../runtime/save-activity-eligibility.cjs');
  const {PlayableSaveActivation} = require('../runtime/playable-save-activation.cjs');
  const h = await setup();
  const gate = new SaveActivityEligibility(async () => ({first: () => null}), 'zapbot', {
    writerCoverageVerified: false,
    accountReader: async () => ({state: {enfermaria: []}, transitionOwner: ''})
  });
  const activation = h.service();
  const service = new PlayableSaveActivation(h.db.execute, 'zapbot', {
    slotStore: h.slotStore, trainerStore: h.trainer, coordinator: activation.coordinator,
    eligibility: gate, enabled: true
  });
  await assert.rejects(service.activate('g', 'p', 'b', 'uncertified_01'), error =>
    error?.code === 'save_transition_activity_blocked' &&
    error.blocks[0].code === 'activity_writer_coverage_unverified');
  assert.equal((await h.trainer.readAccount('g', 'p')).activeSaveId, 'a');
});

for (const stage of ['reserved', 'canonical-applied']) {
  test(`a contender sees a resource lock while the winner is ${stage}`, async () => {
    const h=await setup(), winner=h.service();
    let reached, resume;
    const entered=new Promise(resolve=>{reached=resolve;});
    const barrier=new Promise(resolve=>{resume=resolve;});
    const holder=stage === 'reserved' ? winner.coordinator : winner;
    const method=stage === 'reserved' ? 'reserve' : 'updatePointer';
    const original=holder[method].bind(holder);
    holder[method]=async (...args)=>{
      if (stage === 'reserved') {
        const result=await original(...args); reached(); await barrier; return result;
      }
      reached(); await barrier; return original(...args);
    };
    const winning=winner.activate('g','p','b',`winner_${stage.replaceAll('-','_')}`);
    await entered;
    try {
      const current=await h.trainer.readAccount('g','p');
      assert.ok(current.transitionOwner);
      assert.equal(current.activeSaveId,stage === 'reserved' ? 'a' : 'b');
      const beforeRows=clone([...h.db.rows]);
      await assert.rejects(h.service().activate('g','p','b',`loser_${stage.replaceAll('-','_')}`),
        {code:'transition_resource_locked'});
      assert.deepEqual([...h.db.rows],beforeRows,'the rejected contender must not create a new operation or change any row');
    } finally {
      resume(); await winning;
    }
    assert.equal((await h.trainer.readAccount('g','p')).activeSaveId,'b');
    assert.equal(Object.keys((await h.trainer.readAccount('g','p')).operationReceipts).length,1);
    assert.equal((await h.service().activate('g','p','b','duplicate_completed_01')).idempotent,true);
  });
}

test('missing atomic-fence capability or corrupt metadata still fails closed', async () => {
  for (const kind of ['method', 'metadata']) {
    const h=await setup(), instance=h.service();
    if (kind === 'method') instance.trainerStore={...h.trainer,reserveTransition:undefined};
    else instance.trainerStore={...h.trainer,readAccount:async (...args)=>
      ({...await h.trainer.readAccount(...args),transitionOwner:undefined})};
    const beforeRows=clone([...h.db.rows]);
    await assert.rejects(instance.activate('g','p','b','invalid_fence_01'),{code:'playable_save_atomic_fence_required'});
    assert.deepEqual([...h.db.rows],beforeRows);
  }
});

test('a winner completing between pointer and account reads permits only a confirmed no-op', async () => {
  const h=await setup(), contender=h.service();
  let intercepted=false;
  contender.trainerStore={...h.trainer,readAccount:async (...args)=>{
    if (!intercepted) {
      intercepted=true;
      await h.service().activate('g','p','b','winner_read_race_01');
    }
    return h.trainer.readAccount(...args);
  }};
  const result=await contender.activate('g','p','b','loser_read_race_01');
  assert.equal(result.idempotent,true);
  assert.equal(Object.keys((await h.trainer.readAccount('g','p')).operationReceipts).length,1);
  assert.equal(await contender.read('pokemon-playable-save-operations',`${hash(['g','p'])}:loser_read_race_01`),null);
});

test('a persistent unowned pointer mismatch remains blocked instead of being accepted as a duplicate', async () => {
  const h=await setup(), contender=h.service();
  contender.trainerStore={...h.trainer,readAccount:async (...args)=>
    ({...await h.trainer.readAccount(...args),activeSaveId:'b'})};
  const beforeRows=clone([...h.db.rows]);
  await assert.rejects(contender.activate('g','p','b','inconsistent_read_01'),{code:'playable_save_pointer_conflict'});
  assert.deepEqual([...h.db.rows],beforeRows);
});
