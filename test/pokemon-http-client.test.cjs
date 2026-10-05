'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createClient } = require('../scripts/lib/pokemon-http-client.cjs');

async function fixture(handler, config = {}) {
  const server = http.createServer(handler);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({ baseUrl: `http://127.0.0.1:${server.address().port}/api`, token: 'test-token', ...config });
  return { client, close: async () => {
    client.close();
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  } };
}

test('commands, binary media and event acknowledgments use authenticated keepalive HTTP', async () => {
  const received = [];
  const sockets = new Set();
  const bytes = Buffer.from([0, 255, 1, 128]);
  const f = await fixture((req, res) => {
    sockets.add(req.socket);
    const chunks = [];
    req.on('data', data => chunks.push(data));
    req.on('end', () => {
      received.push({ method: req.method, path: req.url, auth: req.headers.authorization,
        body: Buffer.concat(chunks).toString() });
      if (req.url === '/api/media/media-1') return res.end(bytes);
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify(req.url === '/api/commands' ? { requestId: 'req-1', messages: [], effects: [] }
        : req.url === '/api/events/pending' ? { events: [] } : { acknowledged: true }));
    });
  });
  try {
    const request = { requestId: 'req-1', command: 'pokemon treinador', chatId: 'group', playerId: 'player' };
    assert.equal((await f.client.command(request)).requestId, 'req-1');
    assert.deepEqual(await f.client.media('media-1'), bytes);
    assert.deepEqual(await f.client.pendingEvents(), { events: [] });
    assert.equal((await f.client.ack('event-1')).acknowledged, true);
    assert.equal((await f.client.health()).acknowledged, true);
    assert.equal(sockets.size, 1);
    assert.ok(received.every(r => r.auth === 'Bearer test-token'));
    assert.deepEqual(received.map(r => [r.method, r.path]), [
      ['POST', '/api/commands'], ['GET', '/api/media/media-1'], ['GET', '/api/events/pending'],
      ['POST', '/api/events/event-1/ack'], ['GET', '/api/health']]);
    assert.deepEqual(JSON.parse(received[0].body), request);
  } finally { await f.close(); }
});

test('command timeout is bounded and never retries a mutation', async () => {
  let calls = 0;
  const f = await fixture(() => calls++, { timeoutMs: 40 });
  try {
    await assert.rejects(f.client.command({ requestId: 'once' }), { code: 'TIMEOUT' });
    assert.equal(calls, 1);
  } finally { await f.close(); }
});

test('untrusted response bodies and redirects are not exposed or followed', async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls++;
    res.writeHead(302, { Location: '/should-not-follow' });
    res.end('secret-error-body');
  });
  try {
    await assert.rejects(f.client.health(), error => error.code === 'HTTP' && error.status === 302
      && !error.message.includes('secret-error-body'));
    assert.equal(calls, 1);
  } finally { await f.close(); }
});

test('binary body limits apply with and without Content-Length', async () => {
  for (const fixedLength of [true, false]) {
    const f = await fixture((req, res) => {
      if (fixedLength) res.setHeader('Content-Length', '9');
      else res.write(Buffer.alloc(3));
      res.end(Buffer.alloc(fixedLength ? 9 : 6));
    }, { maxMediaBytes: 8 });
    try { await assert.rejects(f.client.media('large'), { code: 'LIMIT' }); }
    finally { await f.close(); }
  }
});

test('binary media preserves every byte across fixed-length and chunked responses', async () => {
  const bytes = Buffer.alloc(512 * 1024 + 37);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  for (const fixedLength of [true, false]) {
    const f = await fixture((req, res) => {
      if (fixedLength) res.setHeader('Content-Length', String(bytes.length));
      else res.setHeader('Transfer-Encoding', 'chunked');
      const write = offset => {
        if (offset >= bytes.length) return res.end();
        res.write(bytes.subarray(offset, offset + 16384));
        setImmediate(() => write(offset + 16384));
      };
      write(0);
    }, { maxMediaBytes: bytes.length });
    try { assert.deepEqual(await f.client.media('multi-chunk'), bytes); }
    finally { await f.close(); }
  }
});

test('empty and exactly limited binary media remain valid', async () => {
  for (const bytes of [Buffer.alloc(0), Buffer.from([0, 255, 128, 1, 2, 3, 4, 5])]) {
    for (const fixedLength of [true, false]) {
      const f = await fixture((req, res) => {
        if (fixedLength) res.setHeader('Content-Length', String(bytes.length));
        else res.setHeader('Transfer-Encoding', 'chunked');
        res.end(bytes);
      }, { maxMediaBytes: 8 });
      try { assert.deepEqual(await f.client.media('boundary'), bytes); }
      finally { await f.close(); }
    }
  }
});

test('truncated binary responses reject without returning partial or unwritten bytes', async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls++;
    if (req.url === '/api/health') return res.end(JSON.stringify({ status: 'ok' }));
    res.setHeader('Content-Length', '1024');
    res.write(Buffer.from([0, 255, 128]));
    setImmediate(() => res.destroy());
  });
  try {
    await assert.rejects(f.client.media('truncated'), { code: 'NETWORK' });
    assert.equal(calls, 1);
    assert.deepEqual(await f.client.health(), { status: 'ok' });
  } finally { await f.close(); }
});

test('partial binary responses preserve the total timeout classification', async () => {
  let calls = 0;
  const f = await fixture((req, res) => {
    calls++;
    res.setHeader('Content-Length', '1024');
    res.write(Buffer.from([0, 255, 128]));
  }, { timeoutMs: 40 });
  try {
    await assert.rejects(f.client.media('partial'), { code: 'TIMEOUT' });
    assert.equal(calls, 1);
  } finally { await f.close(); }
});

test('chunked JSON preserves UTF-8 values and the response contract', async () => {
  const expected = { requestId: 'utf8', messages: [{ type: 'text', text: '⚡ Pokémon' }], effects: [] };
  const bytes = Buffer.from(JSON.stringify(expected));
  const f = await fixture((req, res) => {
    const write = offset => {
      if (offset >= bytes.length) return res.end();
      res.write(bytes.subarray(offset, offset + 2));
      setImmediate(() => write(offset + 2));
    };
    write(0);
  });
  try { assert.deepEqual(await f.client.command({ requestId: 'utf8' }), expected); }
  finally { await f.close(); }
});

test('malformed JSON fails without retrying', async () => {
  let calls = 0;
  const f = await fixture((req, res) => { calls++; res.end('<html>error</html>'); });
  try {
    await assert.rejects(f.client.command({ requestId: 'bad-json' }), { code: 'INVALID_RESPONSE' });
    assert.equal(calls, 1);
  } finally { await f.close(); }
});

test('Cassandra HTTP 500 is classified as HTTP, never INVALID_RESPONSE or retried', async () => {
  let calls=0;
  const f=await fixture((req,res)=>{
    calls++;res.writeHead(500,{'Content-Type':'application/json'});
    res.end(JSON.stringify({error:4352,message:'Falha interna.'}));
  });
  try {
    await assert.rejects(f.client.command({requestId:'reservation-timeout'}),
      error=>error.code==='HTTP' && error.status===500 && !error.message.includes('4352'));
    assert.equal(calls,1);
  } finally { await f.close(); }
});

test('invalid configuration and resource paths fail before network access', () => {
  assert.throws(() => createClient({ baseUrl: 'file:///tmp/test', token: 'x' }), { code: 'CONFIG' });
  assert.throws(() => createClient({ baseUrl: 'http://user:password@localhost', token: 'x' }), { code: 'CONFIG' });
  assert.throws(() => createClient({ baseUrl: 'http://localhost' }), { code: 'CONFIG' });
  const client = createClient({ baseUrl: 'http://localhost', token: 'x' });
  try {
    assert.throws(() => client.media('../../outside'), { code: 'INVALID_RESPONSE' });
    assert.throws(() => client.ack('other?token=foo'), { code: 'INVALID_RESPONSE' });
  } finally { client.close(); }
});
