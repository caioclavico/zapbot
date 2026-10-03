'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {State} = require('../runtime/state.cjs');

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return {promise, resolve};
}

test('request reservation waits for a snapshot write and is not deleted by it', async () => {
  const writing = deferred();
  const release = deferred();
  const data = {'request-a': {status: 'processing'}};
  let current = data;
  const calls = [];
  const state = new State({
    load: () => current,
    store: async (_module, value) => {
      calls.push('store');
      writing.resolve();
      await release.promise;
      current = value;
    },
    reserve: async (_module, key, value) => {
      calls.push('reserve');
      if (current[key]) return false;
      current[key] = value;
      return true;
    },
  });
  const completion = state.update('requests', records => {
    records['request-a'] = {status: 'completed'};
  });
  await writing.promise;
  const reservation = state.reserve('requests', 'request-b', {status: 'processing'});
  await Promise.resolve();
  assert.deepEqual(calls, ['store']);
  release.resolve();
  await completion;
  assert.equal(await reservation, true);
  assert.deepEqual(current, {
    'request-a': {status: 'completed'},
    'request-b': {status: 'processing'},
  });
});

test('snapshot read waits for an in-flight reservation in the same module', async () => {
  const reserving = deferred();
  const release = deferred();
  let current = {};
  let writes = 0;
  const state = new State({
    load: () => current,
    store: async (_module, value) => { writes++; current = value; },
    reserve: async (_module, key, value) => {
      reserving.resolve();
      await release.promise;
      current[key] = value;
      return true;
    },
  });
  const reservation = state.reserve('requests', 'request-b', {status: 'processing'});
  await reserving.promise;
  const completion = state.update('requests', records => {
    records['request-a'] = {status: 'completed'};
  });
  await Promise.resolve();
  assert.equal(writes, 0);
  release.resolve();
  await Promise.all([reservation, completion]);
  assert.deepEqual(current, {
    'request-a': {status: 'completed'},
    'request-b': {status: 'processing'},
  });
});

test('failed write does not stop reservations or independent module writes', async () => {
  const state = new State({
    load: () => ({}),
    store: async module => { if (module === 'requests') throw new Error('offline'); },
    reserve: async () => true,
  });
  await assert.rejects(state.update('requests', data => { data.a = 1; }), /offline/);
  assert.equal(await state.reserve('requests', 'b', {status: 'processing'}), true);
  await state.update('events', data => { data.event = {}; });
});
