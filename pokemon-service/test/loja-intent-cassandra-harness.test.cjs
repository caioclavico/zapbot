'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {HOST, KEYSPACE} = require('./loja-intent-cassandra.integration.cjs');

test('harness loja-intent usa host/keyspace descartáveis', () => {
  assert.equal(HOST, 'zapbot-loja-intent-cassandra-test');
  assert.equal(KEYSPACE, 'loja_intent_validation');
});

test('shell isola rede interna e exige opt-in', () => {
  const sh = fs.readFileSync(
    path.join(__dirname, '../scripts/test-loja-intent-cassandra.sh'), 'utf8');
  assert.match(sh, /docker network create --internal/);
  assert.match(sh, /LOJA_INTENT_CASSANDRA_ISOLATED=true/);
  assert.match(sh, /cassandra:5\.0\.9/);
  assert.doesNotMatch(sh, /-p\s+9042/);
});

test('protocolo de lease documenta ALTER mínimo e rejeita SELECT extra como correção', () => {
  const src = fs.readFileSync(
    path.join(__dirname, '../runtime/loja-lateral-lease.cjs'), 'utf8');
  assert.match(src, /ADD IF NOT EXISTS write_lease/);
  assert.match(src, /WHY AN EXTRA SELECT IS NOT A FIX/);
  assert.match(src, /IF write_lease/);
});
