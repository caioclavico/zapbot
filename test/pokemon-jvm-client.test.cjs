'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createClient } = require('../scripts/lib/pokemon-jvm-client.cjs');

async function fixture(handler, config = {}) {
  const server = http.createServer(handler);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    token: 'test-token',
    ...config
  });
  return {
    client,
    close: async () => {
      client.close();
      server.closeAllConnections();
      await new Promise(resolve => server.close(resolve));
    }
  };
}

test('leituras autenticadas usam GET e envelope {ok,data}', async () => {
  const received = [];
  const f = await fixture((req, res) => {
    received.push({ method: req.method, path: req.url, auth: req.headers.authorization });
    res.setHeader('Content-Type', 'application/json');
    if (req.url.startsWith('/v1/trainer')) {
      return res.end(JSON.stringify({ ok: true, data: { chatId: 'c', playerId: 'p', activeSaveId: 'a' } }));
    }
    if (req.url.startsWith('/v1/collection')) {
      return res.end(JSON.stringify({ ok: true, data: { count: 0, collection: [] } }));
    }
    if (req.url.startsWith('/v1/active-save')) {
      return res.end(JSON.stringify({ ok: true, data: { activeSaveId: 'a', save: { coins: 1 } } }));
    }
    res.end(JSON.stringify({ ok: true, data: { status: 'up' } }));
  });
  try {
    assert.equal((await f.client.health()).status, 'up');
    assert.equal((await f.client.getTrainer({ chatId: 'c', playerId: 'p' })).activeSaveId, 'a');
    assert.equal((await f.client.getCollection({ chatId: 'c', playerId: 'p' })).count, 0);
    assert.equal((await f.client.getActiveSave({ chatId: 'c', playerId: 'p' })).save.coins, 1);
    assert.ok(received.every(r => r.auth === 'Bearer test-token'));
    assert.ok(received.every(r => r.method === 'GET'));
  } finally {
    await f.close();
  }
});

test('erro de negócio estruturado preserva code/status sem retry', async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls += 1;
    res.statusCode = 404;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({
      ok: false,
      error: { code: 'player_not_found', message: 'ausente', details: { chatId: 'c' } }
    }));
  });
  try {
    await assert.rejects(
      () => f.client.getTrainer({ chatId: 'c', playerId: 'p' }),
      err => err.code === 'PLAYER_NOT_FOUND' && err.status === 404
    );
    assert.equal(calls, 1);
  } finally {
    await f.close();
  }
});

test('timeout não repete a chamada', async () => {
  let calls = 0;
  const f = await fixture(() => { calls += 1; }, { timeoutMs: 40 });
  try {
    await assert.rejects(() => f.client.health(), { code: 'TIMEOUT' });
    assert.equal(calls, 1);
  } finally {
    await f.close();
  }
});

test('recusa URL fora de loopback', () => {
  assert.throws(
    () => createClient({ baseUrl: 'http://example.com:18090', token: 'x' }),
    { code: 'CONFIG' }
  );
});
