'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');
const {activatePlayableRaceControl, classifyPlayableRaceDuplicate} = require('./save-slot-cassandra.integration.cjs');
const {PROTOCOL_VERSION} = require('../runtime/canonical-trainer-store.cjs');

test('playable race classifies idempotent loser without forcing duplicateFenced', () => {
  const raced = [
    {status:'fulfilled', value:{activeSlotId:'b', idempotent:false}},
    {status:'fulfilled', value:{activeSlotId:'b', idempotent:true}}
  ];
  assert.deepEqual(classifyPlayableRaceDuplicate(raced), {
    concurrentSingleWinner:true, duplicateFenced:false,
    duplicateOutcome:'idempotent_already_active', duplicateCode:null
  });
});

test('playable race classifies reservation rejection as fenced', () => {
  const raced = [
    {status:'fulfilled', value:{activeSlotId:'b', idempotent:false}},
    {status:'rejected', reason:Object.assign(new Error('locked'), {code:'transition_resource_locked'})}
  ];
  assert.deepEqual(classifyPlayableRaceDuplicate(raced), {
    concurrentSingleWinner:true, duplicateFenced:true,
    duplicateOutcome:'rejected_active_reservation', duplicateCode:'transition_resource_locked'
  });
});

test('playable race classifies pending journal rejection separately from fencing', () => {
  const raced = [
    {status:'fulfilled', value:{activeSlotId:'b', idempotent:false}},
    {status:'rejected', reason:Object.assign(new Error('startup'), {code:'transition_startup_unresolved'})}
  ];
  assert.deepEqual(classifyPlayableRaceDuplicate(raced), {
    concurrentSingleWinner:true, duplicateFenced:true,
    duplicateOutcome:'rejected_pending_journal', duplicateCode:'transition_startup_unresolved'
  });
});

test('the playable-race capability uses a prepared bind for CQL int protocol_version', async () => {
  const calls = [];
  const client = {execute: async (...args) => { calls.push(args); return {wasApplied:()=>true}; }};
  await activatePlayableRaceControl(client,'playable_save_race_validation');
  assert.equal(calls.length,1);
  const [query, parameters, options] = calls[0];
  assert.match(query,/canonical_trainer_control/);
  assert.deepEqual(parameters,['trainer-canonical',PROTOCOL_VERSION,'active','playable-race']);
  assert.equal(typeof parameters[1],'number');
  assert.equal(options.prepare,true);
  assert.equal(options.consistency,cassandra.types.consistencies.quorum);
  assert.equal(options.serialConsistency,cassandra.types.consistencies.serial);
});
