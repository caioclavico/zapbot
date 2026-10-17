'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {hash} = require('../runtime/restore-protocol.cjs');
const {createRealGymBattleAdapters, trainerPayload, gymWearPayload} = require('../runtime/gym-battle-real-effects.cjs');

function clone(value) { return JSON.parse(JSON.stringify(value)); }

function fixture() {
  const trainer = {generation: 7, stateHash: hash({equipe:[{'hp-atual':10,hp:10,status:null}]}),
    state:{equipe:[{'hp-atual':10,hp:10,status:null}]}, operationReceipts:{}};
  const gym = {generation: 3, stateHash: hash({motivacao:[100,100,100]}),
    state:{motivacao:[100,100,100]}, operationReceipts:new Set()};
  const trainerStore = {
    async readAccount() { return clone(trainer); },
    async applyOnce(_chat, _player, effect) {
      if (trainer.operationReceipts[effect.effectId]) return clone(trainer);
      assert.equal(effect.expectedGeneration, trainer.generation);
      assert.equal(effect.expectedStateHash, trainer.stateHash);
      trainer.state = effect.transform(clone(trainer.state));
      trainer.generation++;
      trainer.stateHash = hash(trainer.state);
      trainer.operationReceipts[effect.effectId] = effect.intentHash;
      return clone(trainer);
    }
  };
  const gymStore = {
    async readRecord() { return clone({...gym, operationReceipts:[...gym.operationReceipts]}); },
    async writeRecordRecovering(_chat, _module, _id, expected, desired, _migration, effectId) {
      if (gym.operationReceipts.has(effectId)) return clone({...gym, operationReceipts:[...gym.operationReceipts]});
      assert.deepEqual(expected, gym.state);
      gym.state = clone(desired); gym.generation++; gym.stateHash = hash(gym.state); gym.operationReceipts.add(effectId);
      return clone({...gym, operationReceipts:[...gym.operationReceipts]});
    }
  };
  return {trainer, gym, trainerStore, gymStore};
}

test('real HP and defender wear effects use stable receipts and do not replay damage', async () => {
  const db = fixture();
  const adapters = createRealGymBattleAdapters(db);
  const trainer = trainerPayload({record:db.trainer,activeIndex:0,hp:4,status:'burn'});
  const wear = gymWearPayload({record:db.gym,targetId:'fogo',nextState:{motivacao:[88,88,88]}});
  await adapters['trainer-account'].applyOnce({chat:'chat',player:'player',effectId:'battle_hp_0001',payload:trainer});
  await adapters['gym-occupancy'].applyOnce({chat:'chat',effectId:'battle_wear_0001',payload:wear});
  await adapters['trainer-account'].applyOnce({chat:'chat',player:'player',effectId:'battle_hp_0001',payload:trainer});
  await adapters['gym-occupancy'].applyOnce({chat:'chat',effectId:'battle_wear_0001',payload:wear});
  assert.equal(db.trainer.state.equipe[0]['hp-atual'],4);
  assert.equal(db.trainer.generation,8);
  assert.deepEqual(db.gym.state.motivacao,[88,88,88]);
  assert.equal(db.gym.generation,4);
  assert.equal(await adapters['trainer-account'].hasReceipt({chat:'chat',player:'player',effectId:'battle_hp_0001'}),true);
  assert.equal(await adapters['gym-occupancy'].hasReceipt({chat:'chat',effectId:'battle_wear_0001',payload:wear}),true);
});

test('a stale real battle plan is rejected before mutating the target', async () => {
  const db = fixture();
  const adapters = createRealGymBattleAdapters(db);
  const stale = gymWearPayload({record:db.gym,targetId:'fogo',nextState:{motivacao:[88,88,88]}});
  db.gym.generation++;
  await assert.rejects(adapters['gym-occupancy'].applyOnce({chat:'chat',effectId:'battle_wear_0002',payload:stale}),
    {code:'real_battle_gym_stale_generation'});
  assert.deepEqual(db.gym.state.motivacao,[100,100,100]);
});
