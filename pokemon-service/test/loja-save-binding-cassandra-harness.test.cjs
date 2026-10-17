'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {ENVELOPE, KEYSPACE, HOST} = require('./loja-save-binding-cassandra.integration.cjs');

test('fixture envelope isola A de B e preserva peer', () => {
  assert.equal(ENVELOPE.g.ash.saves['save-A'].moedas, 150);
  assert.equal(ENVELOPE.g.ash.saves['save-B'].moedas, 0);
  assert.equal(ENVELOPE.g.ash['legacy-claim']['save-id'], 'save-A');
  assert.notEqual(ENVELOPE.g.ash['legacy-claim']['save-id'], 'save-B');
  assert.equal(ENVELOPE.g.peer.saves['peer-A'].moedas, 80);
});

test('harness usa keyspace e host descartáveis dedicados', () => {
  assert.equal(KEYSPACE, 'loja_save_binding_validation');
  assert.equal(HOST, 'zapbot-loja-save-cassandra-test');
});
