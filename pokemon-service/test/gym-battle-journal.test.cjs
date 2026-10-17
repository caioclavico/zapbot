'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {GymBattleJournal} = require('../runtime/gym-battle-journal.cjs');

function fakeCassandra() {
  const journals = new Map(), index = new Map();
  let loseNextJournalWrite = false;
  const result = (rows = [], applied = false) => ({rows, first: () => rows[0], wasApplied: () => applied});
  const execute = async (sql, args) => {
    if (sql.startsWith('SELECT generation')) {
      const item = journals.get(`${args[0]}|${args[1]}`);
      return result(item ? [{...item}] : []);
    }
    if (sql.startsWith('SELECT plan_hash, state FROM')) {
      const item = index.get(`${args[0]}|${args[1]}|${args[2]}`);
      return result(item ? [{...item}] : []);
    }
    if (sql.startsWith('SELECT chat_id, battle_id, plan_hash, state FROM')) {
      return result([...index.entries()].filter(([key]) => key.startsWith(`${args[0]}|`)).map(([, item]) => ({...item})));
    }
    if (sql.startsWith('INSERT INTO') && sql.includes('pokemon_gym_battle_index')) {
      const [bucket, chat, battleId, plan_hash, state] = args;
      const key = `${bucket}|${chat}|${battleId}`;
      if (index.has(key)) return result([], false);
      index.set(key, {plan_hash, state, bucket, chat_id: chat, battle_id: battleId});
      return result([], true);
    }
    if (sql.startsWith('UPDATE') && sql.includes('pokemon_gym_battle_index')) {
      const [state, , bucket, chat, battleId, planHash, priorState] = args;
      const key = `${bucket}|${chat}|${battleId}`, item = index.get(key);
      if (!item || item.plan_hash !== planHash || item.state !== priorState) return result([], false);
      item.state = state;
      return result([], true);
    }
    if (sql.startsWith('INSERT INTO') && sql.includes('pokemon_gym_battle_journal')) {
      const [chat, battleId, generation, state_hash, state_json] = args;
      const key = `${chat}|${battleId}`;
      if (journals.has(key)) return result([], false);
      journals.set(key, {generation, state_hash, state_json});
      if (loseNextJournalWrite) { loseNextJournalWrite = false; throw new Error('simulated_response_loss'); }
      return result([], true);
    }
    if (sql.startsWith('UPDATE') && sql.includes('pokemon_gym_battle_journal')) {
      const [generation, state_hash, state_json, , , chat, battleId, expectedGeneration, expectedHash] = args;
      const key = `${chat}|${battleId}`, item = journals.get(key);
      if (!item || item.generation !== expectedGeneration || item.state_hash !== expectedHash) return result([], false);
      journals.set(key, {generation, state_hash, state_json});
      if (loseNextJournalWrite) { loseNextJournalWrite = false; throw new Error('simulated_response_loss'); }
      return result([], true);
    }
    throw new Error(`unexpected_cql:${sql}`);
  };
  return {execute, journals, index, loseResponse() { loseNextJournalWrite = true; }};
}

const plan = () => ({chat: 'group-1', player: 'player-1', battleId: 'battle_00000001', effects: [
  {id: 'battle_00000001_trainer', type: 'trainer-account', payload: {xp: 5, coins: 10}},
  {id: 'battle_00000001_gym', type: 'gym-statistics', payload: {wins: 1}}
]});

function durableAdapter() {
  const receipts = new Map(), states = new Map();
  return {
    receipts,
    async applyOnce({effectId, payload}) {
      if (receipts.has(effectId)) return;
      states.set(effectId, payload);
      receipts.set(effectId, true);
    },
    async hasReceipt({effectId}) { return receipts.has(effectId); },
    states
  };
}

test('journal applies a planned battle once and marks completion after every durable receipt', async () => {
  const db = fakeCassandra(), journal = new GymBattleJournal(db.execute, 'zapbot_test');
  const trainer = durableAdapter(), stats = durableAdapter();
  const adapters = {'trainer-account': trainer, 'gym-statistics': stats};
  const created = await journal.begin(plan());
  assert.equal(created.state, 'prepared');
  const done = await journal.apply('group-1', 'battle_00000001', adapters);
  assert.equal(done.state, 'completed');
  assert.equal(trainer.receipts.size, 1);
  assert.equal(stats.receipts.size, 1);
  await journal.apply('group-1', 'battle_00000001', adapters);
  assert.equal(trainer.receipts.size, 1);
  assert.equal(stats.receipts.size, 1);
});

test('receipt verification receives the immutable target payload from the journal plan', async () => {
  const db = fakeCassandra(), journal = new GymBattleJournal(db.execute, 'zapbot_test');
  const trainer = durableAdapter();
  const gym = {receipts:new Set(), async applyOnce({effectId, payload}) {
    this.receipts.add(`${payload.targetId}:${effectId}`);
  }, async hasReceipt({effectId, payload, payloadHash}) {
    assert.equal(payload.targetId, 'gym-01');
    assert.equal(typeof payloadHash, 'string');
    return this.receipts.has(`${payload.targetId}:${effectId}`);
  }};
  const input = {...plan(), effects:[plan().effects[0],
    {id:'battle_00000001_gym', type:'gym-statistics', payload:{targetId:'gym-01',wins:1}}]};
  await journal.begin(input);
  assert.equal((await journal.apply(input.chat,input.battleId,{'trainer-account':trainer,'gym-statistics':gym})).state,'completed');
});

test('restart replays the same effect idempotently when the effect landed before journal acknowledgement', async () => {
  const db = fakeCassandra(), first = new GymBattleJournal(db.execute, 'zapbot_test');
  const trainer = durableAdapter(), stats = durableAdapter();
  const adapters = {'trainer-account': trainer, 'gym-statistics': stats};
  await first.begin(plan());
  const originalApply = stats.applyOnce.bind(stats);
  let failAfterApplying = true;
  stats.applyOnce = async input => {
    await originalApply(input);
    if (failAfterApplying) { failAfterApplying = false; throw new Error('response_lost_after_effect'); }
  };
  await assert.rejects(first.apply('group-1', 'battle_00000001', adapters), {code: 'battle_effect_unresolved'});
  const restarted = new GymBattleJournal(db.execute, 'zapbot_test');
  stats.applyOnce = originalApply;
  const recovered = await restarted.reconcileStartup(adapters);
  assert.equal(recovered.ready, true);
  assert.equal((await restarted.read('group-1', 'battle_00000001')).state, 'completed');
  assert.equal(stats.receipts.size, 1);
  assert.equal(trainer.receipts.size, 1);
});

test('unverifiable effect remains pending and blocks startup reconciliation', async () => {
  const db = fakeCassandra(), journal = new GymBattleJournal(db.execute, 'zapbot_test');
  await journal.begin(plan());
  const adapters = {
    'trainer-account': {async applyOnce() { throw new Error('cassandra_unavailable'); }, async hasReceipt() { return false; }},
    'gym-statistics': durableAdapter()
  };
  await assert.rejects(journal.apply('group-1', 'battle_00000001', adapters), {code: 'battle_effect_unresolved'});
  const restarted = new GymBattleJournal(db.execute, 'zapbot_test');
  await assert.rejects(restarted.reconcileStartup(adapters), {code: 'battle_startup_reconciliation_blocked'});
  assert.equal((await restarted.read('group-1', 'battle_00000001')).state, 'blocked');
});

test('targeted reconciliation uses only the immutable selected journal while global startup remains fail-closed', async () => {
  const db = fakeCassandra(), journal = new GymBattleJournal(db.execute, 'zapbot_test');
  const adapters = {'trainer-account': durableAdapter(), 'gym-statistics': durableAdapter()};
  const selected = plan();
  const foreign = {...plan(), chat: 'group-foreign', battleId: 'battle_00000002', effects: [
    {id: 'battle_00000002_occupancy', type: 'gym-occupancy', payload: {targetId: 'gym-foreign'}}
  ]};
  await journal.begin(selected);
  await journal.begin(foreign);

  const selectedResult = await journal.reconcileStartup(adapters, {
    chat: selected.chat, battleId: selected.battleId
  });
  assert.deepEqual(selectedResult, {ready: true, reconciled: 1});
  assert.equal((await journal.read(selected.chat, selected.battleId)).state, 'completed');
  await assert.rejects(journal.reconcileStartup(adapters), error =>
    error?.code === 'battle_startup_reconciliation_blocked' &&
    error.unresolved?.some(item => item.code === 'battle_effect_adapter_missing'));
  assert.equal((await journal.read(foreign.chat, foreign.battleId)).state, 'applying');
});

test('operation identity is immutable and completed journal cannot be reopened', async () => {
  const db = fakeCassandra(), journal = new GymBattleJournal(db.execute, 'zapbot_test');
  const adapters = {'trainer-account': durableAdapter(), 'gym-statistics': durableAdapter()};
  await journal.begin(plan());
  await assert.rejects(journal.begin({...plan(), effects: [{...plan().effects[0], payload: {xp: 99}}]}),
    {code: 'battle_journal_index_conflict'});
  await journal.apply('group-1', 'battle_00000001', adapters);
  const repeated = await journal.begin(plan());
  assert.equal(repeated.state, 'completed');
});
