'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {HOST, KEYSPACE, PENDING_ACTIVE, PENDING_UNCERTAIN} =
  require('./lateral-intent-cassandra.integration.cjs');

test('harness usa host e keyspace descartáveis dedicados', () => {
  assert.equal(HOST, 'zapbot-lateral-intent-cassandra-test');
  assert.equal(KEYSPACE, 'lateral_intent_validation');
  assert.match(PENDING_ACTIVE, /^[A-Za-z0-9_-]{8,100}$/);
  assert.match(PENDING_UNCERTAIN, /^[A-Za-z0-9_-]{8,100}$/);
});

test('CQL canônico declara ALTER controlado de lateral_intents', () => {
  const cql = fs.readFileSync(
    path.join(__dirname, '../scripts/cassandra/canonical-trainer-v1.cql'), 'utf8');
  assert.match(cql, /ADD IF NOT EXISTS lateral_intents map<text, text>/);
  assert.match(cql, /Lateral-write intents/);
});

test('shell do harness isola rede e exige opt-in', () => {
  const sh = fs.readFileSync(
    path.join(__dirname, '../scripts/test-lateral-intent-cassandra.sh'), 'utf8');
  assert.match(sh, /docker network create --internal/);
  assert.match(sh, /LATERAL_INTENT_CASSANDRA_ISOLATED=true/);
  assert.match(sh, /cassandra:5\.0\.9/);
  assert.match(sh, /zapbot-lateral-intent-cassandra-test/);
  assert.doesNotMatch(sh, /-p\s+9042/);
});
