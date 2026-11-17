'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const path = require('node:path');
const {HOST} = require('./adventure-birth-cassandra.integration.cjs');

test('adventure birth Cassandra integration refuses hosts outside the disposable harness', () => {
  const script = path.join(__dirname, 'adventure-birth-cassandra.integration.cjs');
  const denied = spawnSync(process.execPath, [script], {
    env: {...process.env, ADVENTURE_BIRTH_CASSANDRA_ISOLATED: 'true',
      ADVENTURE_BIRTH_CASSANDRA_HOST: '127.0.0.1', ADVENTURE_BIRTH_CASSANDRA_PHASE: 'seed'},
    encoding: 'utf8'
  });
  assert.notEqual(denied.status, 0);
  assert.match(`${denied.stderr}${denied.stdout}`, /isolated test harness/);

  const wrongName = spawnSync(process.execPath, [script], {
    env: {...process.env, ADVENTURE_BIRTH_CASSANDRA_ISOLATED: 'true',
      ADVENTURE_BIRTH_CASSANDRA_HOST: 'zapbot-adventure-fence-cassandra-test',
      ADVENTURE_BIRTH_CASSANDRA_PHASE: 'seed'},
    encoding: 'utf8'
  });
  assert.notEqual(wrongName.status, 0);
  assert.match(`${wrongName.stderr}${wrongName.stdout}`, /isolated test harness/);
  assert.equal(HOST, 'zapbot-adventure-birth-cassandra-test');
});
