'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {hash} = require('../runtime/restore-protocol.cjs');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {
  AdventureHatchResume, assertResumeEligible, hatchEffectId
} = require('../runtime/adventure-hatch-resume.cjs');

function mock() {
  const accounts = new Map(), groups = new Set(), partitions = new Map();
  const key = (chat, player) => `${chat}\0${player}`;
  const partKey = (module, partition) => `${module}\0${partition}`;
  const execute = async (sql, args) => {
    const result = {rows: [], first() { return this.rows[0]; }, wasApplied() { return this.applied; }};
    if (sql.includes('canonical_trainer_control')) {
      result.rows = [{protocol_version: 2, state: 'active'}]; return result;
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
        result.rows = [...accounts].filter(([k]) => k.startsWith(`${args[0]}\0`))
          .map(([k, value]) => ({player_id: k.split('\0')[1], ...value}));
        return result;
      }
      if (sql.startsWith('INSERT')) {
        accounts.set(id, {
          generation: args[2], schema_version: args[3], protocol_version: args[4], state_hash: args[5],
          state_json: args[6], fence_generation: args[10], active_save_id: args[11],
          transition_owner: args[12], operation_receipts: {}
        });
        result.applied = true; return result;
      }
      if (sql.startsWith('UPDATE')) {
        const setColumns = sql.split(' SET ')[1].split(' WHERE ')[0].split(', ').map(part => part.split(' = ')[0]);
        const offset = setColumns.length;
        const conditions = sql.split(' IF ')[1].split(' AND ').map(part => part.split(' = ')[0]);
        const value = accounts.get(key(args[offset], args[offset + 1]));
        result.applied = Boolean(value && conditions.every((column, index) =>
          value[column] === args[offset + 2 + index]));
        if (result.applied) {
          accounts.set(key(args[offset], args[offset + 1]),
            {...value, ...Object.fromEntries(setColumns.map((column, index) => [column, args[index]]))});
        }
        return result;
      }
    }
    if (sql.includes('estado_particionado')) {
      const module = sql.startsWith('SELECT') || sql.startsWith('UPDATE') ? args[sql.startsWith('UPDATE') ? 1 : 0] : args[0];
      const partition = sql.startsWith('SELECT') ? args[1]
        : sql.startsWith('UPDATE') ? args[2]
          : args[1];
      const id = partKey(module, partition);
      if (sql.startsWith('SELECT')) {
        const valor = partitions.get(id);
        result.rows = valor != null ? [{valor}] : [];
        return result;
      }
      if (sql.startsWith('INSERT')) {
        if (partitions.has(id)) { result.applied = false; return result; }
        partitions.set(id, args[2]); result.applied = true; return result;
      }
      if (sql.startsWith('UPDATE')) {
        if (partitions.get(id) !== args[3]) { result.applied = false; return result; }
        partitions.set(id, args[0]); result.applied = true; return result;
      }
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute, accounts, partitions};
}

function body(extra = {}) {
  return {equipe: [], 'adventure-entregas': {}, moedas: 1, ...extra};
}

async function seedPair(db) {
  const store = new CanonicalTrainerStore(db.execute, 'zapbot');
  await store.createAccount('g', 'p', body(), 'm');
  await store.createAccount('g', 'q', body({moedas: 9}), 'm');
  return store;
}

async function incubatePending(store, resume, {eggId = 'egg-pending-a', hatchId = 'hatch-pending-a',
  pokemonId = 'from-save-a'} = {}) {
  let seal;
  await store.withWriterContext('g', () => { seal = store.freezeSaveBoundTask('p'); }, undefined, true, 'p');
  const pending = await resume.registerPending({
    chat: 'g', player: 'p', eggId, hatchId, pokemonId, incubateSeal: seal
  });
  return {seal, pending};
}

test('pending A egg survives A→B→A and resumes exactly once under a new audited fence', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  const {seal, pending} = await incubatePending(store, resume);
  assert.equal(pending.originSaveId, 'a');
  assert.equal(pending.originFenceGeneration, seal.fenceGeneration);

  const before = await store.readAccount('g', 'p');
  const reserved = await store.reserveTransition('g', 'p', before, 'transition_to_b_01');
  await store.applyOnce('g', 'p', {
    effectId: 'activate_b_resume01', intentHash: hash(body({slot: 'b'})),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'b',
    transform: () => body({equipe: [{'id-pokemon': 'save-b'}], 'adventure-entregas': {}})
  });
  const onB = await store.readAccount('g', 'p');
  await store.releaseTransition('g', 'p', onB, 'transition_to_b_01');

  await assert.rejects(resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-0001'),
    {code: 'adventure_hatch_resume_save_mismatch'});
  assert.equal((await store.readAccount('g', 'p')).state.equipe[0]['id-pokemon'], 'save-b');

  const beforeA = await store.readAccount('g', 'p');
  const reservedA = await store.reserveTransition('g', 'p', beforeA, 'transition_to_a_01');
  await store.applyOnce('g', 'p', {
    effectId: 'activate_a_resume01', intentHash: hash(body({slot: 'a'})),
    expectedGeneration: reservedA.generation, expectedStateHash: reservedA.stateHash,
    writerContext: reservedA, targetSaveId: 'a',
    transform: () => body({equipe: [{'id-pokemon': 'save-a'}], 'adventure-entregas': {}})
  });
  const onA = await store.readAccount('g', 'p');
  await store.releaseTransition('g', 'p', onA, 'transition_to_a_01');
  assert.ok((await store.readAccount('g', 'p')).fenceGeneration > seal.fenceGeneration);

  const first = await resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-0001');
  assert.equal(first.applied, true);
  assert.equal(first.account.state.equipe.filter(p => p['id-pokemon'] === 'from-save-a').length, 1);
  assert.equal(first.account.state['adventure-entregas']['hatch-pending-a'], 'from-save-a');
  assert.equal((await resume.loadPending('g', 'p', 'egg-pending-a')).eggState, 'hatched');

  const second = await resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-0001');
  assert.equal(second.idempotent, true);
  assert.equal(second.account.state.equipe.filter(p => p['id-pokemon'] === 'from-save-a').length, 1);
});

test('save B cannot claim an egg bound to origin save A', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume);
  const before = await store.readAccount('g', 'p');
  const reserved = await store.reserveTransition('g', 'p', before, 'transition_b_claim');
  await store.applyOnce('g', 'p', {
    effectId: 'activate_b_claim001', intentHash: hash('b'),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'b', transform: state => state
  });
  await store.releaseTransition('g', 'p', await store.readAccount('g', 'p'), 'transition_b_claim');
  await assert.rejects(resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-bclaim'),
    {code: 'adventure_hatch_resume_save_mismatch'});
  assert.equal(Object.hasOwn((await store.readAccount('g', 'p')).state['adventure-entregas'], 'hatch-pending-a'), false);
});

test('an old callback cannot refresh the incubate fence on a registered pending delivery', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  const {seal} = await incubatePending(store, resume);
  const forged = {...seal, fenceGeneration: seal.fenceGeneration + 5};
  await assert.rejects(resume.registerPending({
    chat: 'g', player: 'p', eggId: 'egg-pending-a', hatchId: 'hatch-pending-a',
    pokemonId: 'from-save-a', incubateSeal: forged
  }), {code: 'adventure_hatch_pending_immutable'});
  const kept = await resume.loadPending('g', 'p', 'egg-pending-a');
  assert.equal(kept.originFenceGeneration, seal.fenceGeneration);
  assert.equal(kept.incubateSeal.fenceGeneration, seal.fenceGeneration);
});

test('resume rejects while reserved and peer players remain writable', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume);
  const before = await store.readAccount('g', 'p');
  const reserved = await store.reserveTransition('g', 'p', before, 'transition_race_01');
  await assert.rejects(resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-race01'),
    {code: 'canonical_trainer_transition_reserved'});
  await store.withWriterContext('g', async () => {
    const peer = await store.readAccount('g', 'q');
    await store.compareAndSet('g', 'q', peer, {...peer.state, moedas: 11});
  }, undefined, true, 'q');
  assert.equal((await store.readAccount('g', 'q')).state.moedas, 11);
  await store.releaseTransition('g', 'p', reserved, 'transition_race_01');
  const done = await resume.resume('g', 'p', 'egg-pending-a', 'resumeOp-race01');
  assert.equal(done.applied, true);
});

test('lost LWT acknowledgement during resume stays idempotent on retry', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume, {eggId: 'egg-lost', hatchId: 'hatch-lost-01', pokemonId: 'lost-mon'});
  let lose = true;
  const interruptedStore = new CanonicalTrainerStore(async (sql, args, opts) => {
    const result = await db.execute(sql, args, opts);
    if (lose && sql.startsWith('UPDATE') && sql.includes('operation_receipts = ?') &&
        sql.includes('canonical_trainer_accounts')) {
      lose = false;
      throw Object.assign(new Error('lost'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, 'zapbot');
  const interruptedResume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: interruptedStore});
  const recovered = await interruptedResume.resume('g', 'p', 'egg-lost', 'resumeOp-lost01');
  assert.equal(recovered.account.state['adventure-entregas']['hatch-lost-01'], 'lost-mon');
  const retry = await resume.resume('g', 'p', 'egg-lost', 'resumeOp-lost01');
  assert.equal(retry.idempotent, true);
  assert.equal(retry.account.state.equipe.filter(p => p['id-pokemon'] === 'lost-mon').length, 1);
});

test('restart mid-resume recovers from durable plan and trainer receipt', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume, {eggId: 'egg-restart', hatchId: 'hatch-restart', pokemonId: 'restart-mon'});
  let loseAfterDeliver = true;
  const flaky = new CanonicalTrainerStore(db.execute, 'zapbot');
  const originalApply = flaky.applyOnce.bind(flaky);
  flaky.applyOnce = async (...args) => {
    const result = await originalApply(...args);
    if (loseAfterDeliver) {
      loseAfterDeliver = false;
      throw Object.assign(new Error('lost after deliver phase'), {code: 'synthetic_lost_after_deliver'});
    }
    return result;
  };
  const flakyResume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: flaky});
  await assert.rejects(flakyResume.resume('g', 'p', 'egg-restart', 'resumeOp-restart'),
    {code: 'synthetic_lost_after_deliver'});
  assert.equal((await store.readAccount('g', 'p')).state['adventure-entregas']['hatch-restart'], 'restart-mon');
  // Egg mark may still be pending; recover finishes markers without duplicating.
  const recovered = await resume.recover('g', 'p', 'egg-restart', 'resumeOp-restart');
  assert.equal(recovered.idempotent, true);
  assert.equal((await resume.loadPending('g', 'p', 'egg-restart')).eggState, 'hatched');
  assert.equal(recovered.account.state.equipe.filter(p => p['id-pokemon'] === 'restart-mon').length, 1);
});

test('resume deliver callback uses the real game writer and stays idempotent', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume, {eggId: 'egg-deliver', hatchId: 'hatch-deliver', pokemonId: 'cb-mon'});
  const before = await store.readAccount('g', 'p');
  const reserved = await store.reserveTransition('g', 'p', before, 'transition_cb_b');
  await store.applyOnce('g', 'p', {
    effectId: 'activate_b_cb00001', intentHash: hash('b'),
    expectedGeneration: reserved.generation, expectedStateHash: reserved.stateHash,
    writerContext: reserved, targetSaveId: 'b', transform: state => state
  });
  await store.releaseTransition('g', 'p', await store.readAccount('g', 'p'), 'transition_cb_b');
  const back = await store.readAccount('g', 'p');
  const reservedA = await store.reserveTransition('g', 'p', back, 'transition_cb_a');
  await store.applyOnce('g', 'p', {
    effectId: 'activate_a_cb00001', intentHash: hash('a'),
    expectedGeneration: reservedA.generation, expectedStateHash: reservedA.stateHash,
    writerContext: reservedA, targetSaveId: 'a', transform: state => state
  });
  await store.releaseTransition('g', 'p', await store.readAccount('g', 'p'), 'transition_cb_a');

  let calls = 0;
  const first = await resume.resume('g', 'p', 'egg-deliver', 'resumeOp-cb0001', {
    deliver: async ({claim, account}) => {
      calls += 1;
      assert.equal(account.activeSaveId, 'a');
      assert.equal(claim.originSaveId, 'a');
      return store.applyOnce('g', 'p', {
        effectId: hatchEffectId(claim.hatchId),
        intentHash: hash({hatch: claim.hatchId, pokemon: claim.pokemonId, origin: claim.originSaveId}),
        expectedGeneration: account.generation, expectedStateHash: account.stateHash,
        writerContext: account,
        transform: state => ({
          ...state,
          equipe: [...(state.equipe || []), {'id-pokemon': claim.pokemonId, nome: 'Callback'}],
          'adventure-entregas': {...(state['adventure-entregas'] || {}), [claim.hatchId]: claim.pokemonId}
        })
      });
    }
  });
  assert.equal(first.applied, true);
  assert.equal(calls, 1);
  assert.equal(first.account.state['adventure-entregas']['hatch-deliver'], 'cb-mon');
  const again = await resume.recover('g', 'p', 'egg-deliver', 'resumeOp-cb0001', {
    deliver: async () => { calls += 1; throw new Error('should not deliver again'); }
  });
  assert.equal(again.idempotent, true);
  assert.equal(calls, 1);
});

test('assertResumeEligible requires origin save, pending state, and no receipt', () => {
  const pending = {
    schemaVersion: 1, chat: 'g', player: 'p', eggId: 'egg-1', hatchId: 'hatch-abc1',
    pokemonId: 'mon-1', originSaveId: 'a', originFenceGeneration: 1,
    incubateSeal: {
      chat: 'g', player: 'p', identityHash: hash(['g', 'p']),
      fenceGeneration: 1, activeSaveId: 'a', transitionOwner: ''
    },
    eggState: 'delivery_pending', createdAt: 't', updatedAt: 't'
  };
  pending.integrityHash = hash((() => { const c = {...pending}; delete c.integrityHash; return c; })());
  assert.throws(() => assertResumeEligible({
    pending, account: {activeSaveId: 'b', transitionOwner: '', state: {'adventure-entregas': {}}},
    globalReceipts: {}
  }), {code: 'adventure_hatch_resume_save_mismatch'});
  assert.equal(hatchEffectId('hatch-abc1'), 'hatch_hatch-abc1');
});

test('a concurrent reservation beating resume leaves the pending claim untouched', async () => {
  const db = mock(), store = await seedPair(db);
  const resume = new AdventureHatchResume(db.execute, 'zapbot', {trainerStore: store});
  await incubatePending(store, resume, {eggId: 'egg-race', hatchId: 'hatch-race01', pokemonId: 'race-mon'});
  let releaseResume, resumeEntered;
  const entered = new Promise(resolve => { resumeEntered = resolve; });
  const gate = new Promise(resolve => { releaseResume = resolve; });
  const originalWith = store.withWriterContext.bind(store);
  store.withWriterContext = async (chat, action, expected, allow, player) => {
    resumeEntered();
    await gate;
    return originalWith(chat, action, expected, allow, player);
  };
  const racing = resume.resume('g', 'p', 'egg-race', 'resumeOp-race2');
  await entered;
  const before = await store.readAccount('g', 'p');
  const reserved = await store.reserveTransition('g', 'p', before, 'transition_vs_resume');
  releaseResume();
  await assert.rejects(racing, {code: 'canonical_trainer_transition_reserved'});
  assert.equal((await resume.loadPending('g', 'p', 'egg-race')).eggState, 'delivery_pending');
  assert.equal(Object.hasOwn((await store.readAccount('g', 'p')).state['adventure-entregas'], 'hatch-race01'), false);
  await store.releaseTransition('g', 'p', reserved, 'transition_vs_resume');
  store.withWriterContext = originalWith;
  const done = await resume.resume('g', 'p', 'egg-race', 'resumeOp-race2');
  assert.equal(done.applied, true);
});
