'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {
  createClient,
  trustedIdentityFromMessage,
  stableOperationId
} = require('../scripts/lib/pokemon-jvm-client.cjs');

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

test('leituras e escritas autenticadas com envelope {ok,data}', async () => {
  const received = [];
  const f = await fixture((req, res) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      received.push({
        method: req.method,
        path: req.url,
        auth: req.headers.authorization,
        body: Buffer.concat(chunks).toString()
      });
      res.setHeader('Content-Type', 'application/json');
      if (req.url === '/v1/trainer/ensure') {
        return res.end(JSON.stringify({ ok: true, data: { created: true, activeSaveId: 'a', fenceGeneration: 0 } }));
      }
      if (req.url === '/v1/rewards/coins') {
        return res.end(JSON.stringify({ ok: true, data: { duplicate: false, coins: 10, operationId: 'op-1' } }));
      }
      if (req.url.startsWith('/v1/saves') && req.method === 'GET') {
        return res.end(JSON.stringify({ ok: true, data: { activeSaveId: 'a', fenceGeneration: 0, saves: [] } }));
      }
      if (req.url === '/v1/saves/activate') {
        return res.end(JSON.stringify({ ok: true, data: { activeSaveId: 'b', fenceGeneration: 1 } }));
      }
      res.end(JSON.stringify({ ok: true, data: { status: 'up' } }));
    });
  });
  try {
    assert.equal((await f.client.health()).status, 'up');
    assert.equal((await f.client.ensureTrainer({ chatId: 'c', playerId: 'p' })).created, true);
    assert.equal((await f.client.listSaves({ chatId: 'c', playerId: 'p' })).activeSaveId, 'a');
    assert.equal((await f.client.activateSave({ chatId: 'c', playerId: 'p', saveId: 'b' })).activeSaveId, 'b');
    assert.equal((await f.client.creditCoins({
      chatId: 'c', playerId: 'p', saveId: 'a', fenceGeneration: 0,
      operationId: 'op-1', coinsDelta: 10
    })).coins, 10);
    assert.ok(received.every(r => r.auth === 'Bearer test-token'));
    assert.deepEqual(received.map(r => r.method), ['GET', 'POST', 'GET', 'POST', 'POST']);
  } finally {
    await f.close();
  }
});

test('erro de negócio estruturado sem retry com novo operationId', async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls += 1;
    res.statusCode = 409;
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({
      ok: false,
      error: { code: 'stale_writer_fence', message: 'fence', details: { fence: 2 } }
    }));
  });
  try {
    await assert.rejects(
      () => f.client.creditCoins({
        chatId: 'c', playerId: 'p', saveId: 'a', fenceGeneration: 0,
        operationId: 'op-x', coinsDelta: 1
      }),
      err => err.code === 'STALE_WRITER_FENCE' && err.status === 409
    );
    assert.equal(calls, 1);
  } finally {
    await f.close();
  }
});

test('cliente expõe criação e renomeação de slots com operationId estável', async () => {
  const received = [];
  const f = await fixture((req, res) => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      received.push({method: req.method, path: req.url, body: JSON.parse(Buffer.concat(chunks).toString())});
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({ok: true, data: {saveId: 'c', displayName: 'Jornada'}}));
    });
  });
  try {
    const create = await f.client.createSave({chatId: 'c', playerId: 'p', saveId: 'c', operationId: 'save-create-1'});
    const rename = await f.client.renameSave({chatId: 'c', playerId: 'p', saveId: 'c', displayName: 'Jornada', expectedFenceGeneration: 0, operationId: 'save-rename-1'});
    assert.equal(create.saveId, 'c');
    assert.equal(rename.displayName, 'Jornada');
    assert.deepEqual(received.map(r => r.path), ['/v1/saves/create', '/v1/saves/rename']);
    assert.equal(received[0].body.operationId, 'save-create-1');
    assert.equal(received[1].body.expectedFenceGeneration, 0);
  } finally {
    await f.close();
  }
});

test('timeout em escrita tenta reconciliar com o mesmo operationId', async () => {
  let posts = 0;
  let gets = 0;
  const f = await fixture((req, res) => {
    if (req.method === 'POST') {
      posts += 1;
      return; // hang → TIMEOUT no cliente
    }
    gets += 1;
    assert.match(req.url, /chatId=c/);
    assert.match(req.url, /playerId=p/);
    res.setHeader('Content-Type', 'application/json');
    res.end(JSON.stringify({
      ok: true,
      data: {
        operationId: 'op-reconcile', channel: 'gameplay', payloadHash: 'abc',
        saveId: 'a', persisted: true
      }
    }));
  }, { timeoutMs: 40 });
  try {
    const data = await f.client.creditCoins({
      chatId: 'c', playerId: 'p', saveId: 'a', fenceGeneration: 0,
      operationId: 'op-reconcile', coinsDelta: 1
    });
    assert.equal(data.reconciled, true);
    assert.equal(data.duplicate, true);
    assert.equal(data.persisted, true);
    assert.equal(posts, 1);
    assert.equal(gets, 1);
  } finally {
    await f.close();
  }
});

test('identidade confiável ignora texto do usuário', () => {
  const id = trustedIdentityFromMessage({
    fromMe: false,
    from: 'group@g.us',
    author: 'user@c.us',
    id: { _serialized: 'true-id' },
    body: '!pk moedas 99 chatId=evil playerId=evil'
  });
  assert.equal(id.chatId, 'group@g.us');
  assert.equal(id.playerId, 'user@c.us');
  assert.equal(id.requestId, 'true-id');
  assert.equal(stableOperationId(id.requestId, 'coins:1'), 'true-id:coins:1');
});

test('recusa URL fora de loopback', () => {
  assert.throws(
    () => createClient({ baseUrl: 'http://example.com:18090', token: 'x' }),
    { code: 'CONFIG' }
  );
});

test('aceita loopback sem flag Docker', () => {
  const c = createClient({ baseUrl: 'http://127.0.0.1:18090', token: 'x' });
  assert.equal(typeof c.close, 'function');
  c.close();
});

test('aceita odisseu-jvm:18090 só com allowDockerJvmHost', () => {
  assert.throws(
    () => createClient({ baseUrl: 'http://odisseu-jvm:18090', token: 'x' }),
    { code: 'CONFIG' }
  );
  const c = createClient({
    baseUrl: 'http://odisseu-jvm:18090',
    token: 'x',
    allowDockerJvmHost: true
  });
  assert.equal(typeof c.close, 'function');
  c.close();
});

test('recusa hostnames/IPs/portas fora da allowlist Docker', () => {
  const bad = [
    'http://odisseu-jvm:18091',
    'https://odisseu-jvm:18090',
    'http://jvm:18090',
    'http://10.0.0.5:18090',
    'http://192.168.1.10:18090',
    'http://bridge.internal:18090',
    'http://odisseu-jvm.example.com:18090'
  ];
  for (const baseUrl of bad) {
    assert.throws(
      () => createClient({ baseUrl, token: 'x', allowDockerJvmHost: true }),
      { code: 'CONFIG' },
      baseUrl
    );
  }
});
