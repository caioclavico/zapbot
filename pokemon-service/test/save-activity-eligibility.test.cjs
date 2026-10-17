'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {evaluate, SaveActivityEligibility} = require('../runtime/save-activity-eligibility.cjs');

const base = () => ({chat:'group-a', player:'player-a', account:{enfermaria:[]}, states:{}, writerCoverageVerified:true});
const codes = report => report.blocks.map(item => item.code);

test('activity eligibility approves an idle account only when writer coverage is certified', () => {
  assert.deepEqual(evaluate(base()), {eligible:true, blocks:[]});
  assert.deepEqual(codes(evaluate({...base(), writerCoverageVerified:false})), ['activity_writer_coverage_unverified']);
});

test('activity eligibility identifies the player only in their occupied gym', () => {
  const mine = evaluate({...base(), states:{gyms:{alpha:{pid:'player-a',time:[{nome:'Pikachu'}]}, beta:{pid:'player-b',time:[{nome:'Eevee'}]}}}});
  assert.deepEqual(codes(mine), ['activity_gym_occupied']);
  assert.deepEqual(mine.blocks[0].defenders, ['Pikachu']);
  assert.equal(evaluate({...base(), player:'player-b', states:{gyms:{alpha:{pid:'player-a',time:[]}}}}).eligible, true);
});

test('activity eligibility blocks battles, hunts, raid work, offers and trades', () => {
  const report = evaluate({...base(), now:100, states:{
    battles:{one:{pid:'player-a'}}, hunts:{two:{jogador:'player-a'}},
    raids:{three:{participantes:{'player-a':{}},fase:'inscricoes'},
      four:{participantes:{'player-a':{dano:1}},fase:'vitoria','captura-expira':101,capturas:{}}},
    trades:{five:{a:'player-a',b:'player-b'}}, moveOffers:{six:{player:'player-a'}}
  }});
  assert.deepEqual(codes(report), ['activity_battle_active','activity_hunt_active','activity_raid_active',
    'activity_raid_capture_pending','activity_trade_pending','activity_move_offer_pending']);
});

test('activity eligibility accepts a single raid partition value and captura-expira', () => {
  const active = evaluate({...base(), now:50, states:{
    raids:{fase:'combate', participantes:{'player-a':{dano:0}}, expira:100}}});
  assert.deepEqual(codes(active), ['activity_raid_active']);
  const capture = evaluate({...base(), now:50, states:{
    raids:{fase:'vitoria', participantes:{'player-a':{dano:2}}, 'captura-expira':100, capturas:{}}}});
  assert.deepEqual(codes(capture), ['activity_raid_capture_pending']);
  const expired = evaluate({...base(), now:100, states:{
    raids:{fase:'vitoria', participantes:{'player-a':{dano:2}}, 'captura-expira':100, capturas:{}}}});
  assert.equal(expired.eligible, true);
});

test('activity eligibility blocks Joy timers and incomplete journals but ignores its own reservation', () => {
  const options = {...base(), now:100, account:{enfermaria:[{'pronto-em':101,pokemon:{nome:'Machop'}}]},
    transition:{operationId:'transition-a',complete:false}};
  assert.deepEqual(codes(evaluate(options)), ['activity_joy_pending','activity_transition_pending']);
  assert.deepEqual(codes(evaluate({...options, ignoreOperationId:'transition-a'})), ['activity_joy_pending']);
});

test('activity eligibility blocks Adventure eggs, deliveries, events and professor transfers', () => {
  assert.deepEqual(codes(evaluate({...base(), adventure:{eggs:[{state:'incubating'}]}})),
    ['activity_adventure_egg_pending']);
  assert.deepEqual(codes(evaluate({...base(), adventure:{eggs:[{state:'hatched'}],
    pendingDeliveries:['egg-1']}})), ['activity_adventure_delivery_pending']);
  assert.deepEqual(codes(evaluate({...base(), now:50,
    account:{enfermaria:[], 'adventure-eventos':{'ev-1':{'event-id':'ev-1'}},
      'transferencia-professor':{expira:100}}})),
    ['activity_adventure_events_pending','activity_professor_transfer_pending']);
  assert.equal(evaluate({...base(), adventure:{eggs:[{state:'hatched'}]}}).eligible, true);
});

test('activity eligibility fails closed for malformed durable activity state and preserves group isolation', () => {
  assert.deepEqual(codes(evaluate({...base(), states:{battles:[]}})), ['activity_battle_active_state_invalid']);
  assert.equal(evaluate({...base(), chat:'group-b', states:{battles:{other:{pid:'player-b'}}}}).eligible, true);
});

test('activity start is refused when a transition lock or reserved account is present', async () => {
  const execute = async () => ({first: () => null});
  let lockOwner = 'op-lock';
  let accountOwner = null;
  const gate = new SaveActivityEligibility(execute, 'zapbot', {
    writerCoverageVerified:true,
    accountReader: async () => ({state:{enfermaria:[]}, transitionOwner:accountOwner})
  });
  const coordinator = {
    resourcePartition: (_chat, resource) => `part:${resource.id}`,
    read: async () => ({value:{owner:lockOwner}})
  };
  await assert.rejects(gate.assertActivityMayStart({chat:'g',player:'a',coordinator}),
    error => error.code === 'save_transition_activity_reserved' && error.operationId === 'op-lock');
  lockOwner = 'op-self';
  await gate.assertActivityMayStart({chat:'g',player:'a',coordinator,ignoreOperationId:'op-self'});
  lockOwner = null;
  accountOwner = 'op-account';
  await assert.rejects(gate.assertActivityMayStart({chat:'g',player:'a',coordinator,ignoreOperationId:'op-self'}),
    error => error.code === 'save_transition_activity_reserved' && error.operationId === 'op-account');
});

test('durable checker reads the real per-chat modules and treats an uncertified callback path as a blocker', async () => {
  const rows = new Map([
    ['ginasios|group-a', JSON.stringify({alpha:{pid:'other',time:[]}})],
    ['pokemon-batalhas-ativas|group-a', JSON.stringify({})],
    ['pokemon-cacadas-ativas|group-a', JSON.stringify({})], ['raids|group-a', JSON.stringify({})],
    ['pokemon-propostas-troca|group-a', JSON.stringify({})], ['pokemon-remocoes-pendentes|group-a', JSON.stringify({})]
  ]);
  const execute = async (_sql, [module, partition]) => {
    const valor = rows.get(`${module}|${partition}`);
    return {first: () => valor == null ? null : {valor}};
  };
  const gate = new SaveActivityEligibility(execute, 'zapbot', {accountReader: async () => ({state:{enfermaria:[]}})});
  const report = await gate.check({chat:'group-a',player:'player-a'});
  assert.deepEqual(codes(report), ['activity_writer_coverage_unverified']);
});
