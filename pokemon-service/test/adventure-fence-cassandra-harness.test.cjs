'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const path = require('node:path');
const {hash} = require('../runtime/restore-protocol.cjs');
const {
  HOST, hatchEffectId, normalizeSeal
} = require('./adventure-fence-cassandra.integration.cjs');

test('adventure fence Cassandra integration refuses hosts outside the disposable harness', () => {
  const script = path.join(__dirname, 'adventure-fence-cassandra.integration.cjs');
  const denied = spawnSync(process.execPath, [script], {
    env: {...process.env, ADVENTURE_FENCE_CASSANDRA_ISOLATED: 'true',
      ADVENTURE_FENCE_CASSANDRA_HOST: '127.0.0.1', ADVENTURE_FENCE_CASSANDRA_PHASE: 'seed'},
    encoding: 'utf8'
  });
  assert.notEqual(denied.status, 0);
  assert.match(`${denied.stderr}${denied.stdout}`, /isolated test harness/);

  const wrongName = spawnSync(process.execPath, [script], {
    env: {...process.env, ADVENTURE_FENCE_CASSANDRA_ISOLATED: 'true',
      ADVENTURE_FENCE_CASSANDRA_HOST: 'zapbot-save-slots-cassandra-test',
      ADVENTURE_FENCE_CASSANDRA_PHASE: 'seed'},
    encoding: 'utf8'
  });
  assert.notEqual(wrongName.status, 0);
  assert.match(`${wrongName.stderr}${wrongName.stdout}`, /isolated test harness/);
  assert.equal(HOST, 'zapbot-adventure-fence-cassandra-test');
});

test('hatch effect ids stay within the canonical receipt alphabet', () => {
  assert.match(hatchEffectId('hatch-deliver-01'), /^[A-Za-z0-9_-]{8,100}$/);
  assert.equal(hatchEffectId('hatch-deliver-01'), 'hatch_hatch-deliver-01');
});

test('a lock-then-insert style payload is rejected as a seal', () => {
  assert.equal(normalizeSeal({chat: 'g', player: 'p', lockOwner: 'op'}), null);
  const seal = normalizeSeal({
    chat: 'g', player: 'p', identityHash: hash(['g', 'p']),
    fenceGeneration: 1, activeSaveId: 'a', transitionOwner: ''
  });
  assert.equal(seal.contexts.p.fenceGeneration, 1);
  assert.equal(seal.contexts.p.activeSaveId, 'a');
});
