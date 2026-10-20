'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {createClient, assertHost} = require('../scripts/lib/platform-jvm-client.cjs');

test('aceita loopback sem allowPrivateHost', () => {
  const c = createClient({baseUrl: 'http://127.0.0.1:18090', token: 't'});
  assert.equal(typeof c.getBlocks, 'function');
});

test('aceita odisseu-jvm:18090 só com allowPrivateHost', () => {
  assert.throws(
    () => createClient({baseUrl: 'http://odisseu-jvm:18090', token: 't'}),
    {code: 'CONFIG'}
  );
  const c = createClient({
    baseUrl: 'http://odisseu-jvm:18090',
    token: 't',
    allowPrivateHost: true
  });
  assert.equal(typeof c.getBlocks, 'function');
});

test('recusa IPs privados e outros hostnames mesmo com flag', () => {
  for (const baseUrl of [
    'http://10.0.0.2:18090',
    'http://192.168.0.1:18090',
    'http://other-svc:18090',
    'http://odisseu-jvm:9999',
    'https://odisseu-jvm:18090'
  ]) {
    assert.throws(
      () => createClient({baseUrl, token: 't', allowPrivateHost: true}),
      {code: 'CONFIG'},
      baseUrl
    );
  }
});

test('assertHost espelha a allowlist', () => {
  assert.doesNotThrow(() => assertHost(new URL('http://localhost:18090'), false));
  assert.throws(() => assertHost(new URL('http://odisseu-jvm:18090'), false), {code: 'CONFIG'});
  assert.doesNotThrow(() => assertHost(new URL('http://odisseu-jvm:18090'), true));
});
