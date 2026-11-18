'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
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
  const a6 = createClient({
    baseUrl: 'http://odisseu-a6-jvm:18090',
    token: 't',
    allowPrivateHost: true
  });
  assert.equal(typeof a6.getBlocks, 'function');
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
  assert.doesNotThrow(() => assertHost(new URL('http://odisseu-a6-jvm:18090'), true));
});

test('close encerra o agente keep-alive e é idempotente', async () => {
  const sockets = new Set();
  const server = http.createServer((_req, res) => {
    res.writeHead(200, {'content-type': 'application/json'});
    res.end(JSON.stringify({ok: true, data: {blocks: []}}));
  });
  server.on('connection', socket => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const {port} = server.address();
  const client = createClient({baseUrl: `http://127.0.0.1:${port}`, token: 'test-token'});
  let timeout;
  try {
    await client.getBlocks();
    const socket = [...sockets][0];
    assert.ok(socket, 'GET deve abrir um socket HTTP');
    const socketClosed = new Promise(resolve => {
      if (socket.destroyed) resolve();
      else socket.once('close', resolve);
    });
    const closedOrTimedOut = Promise.race([
      socketClosed,
      new Promise((_, reject) => {
        timeout = setTimeout(() => reject(new Error('agent_socket_not_closed')), 1000);
      })
    ]);

    client.close();
    client.close();
    await closedOrTimedOut;
    assert.equal(socket.destroyed, true);
  } finally {
    clearTimeout(timeout);
    client.close();
    await new Promise(resolve => server.close(resolve));
  }
});
