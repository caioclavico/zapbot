'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');

/**
 * Espelha o contrato do handler CLJS: JSON sempre, Connection: close,
 * descoberta só após responder (via setTimeout no callback).
 */
function createBootstrapServer({onDiscover, whatsappState = 'READY'}) {
  const server = http.createServer((req, res) => {
    const path = (req.url || '').split('?')[0];
    if (path === '/health') {
      const ok = whatsappState === 'READY';
      res.writeHead(ok ? 200 : 503, {
        'Content-Type': 'application/json',
        Connection: 'close',
      });
      res.end(JSON.stringify({status: ok ? 'ok' : 'degraded', whatsapp: whatsappState}));
      return;
    }
    if (path === '/bootstrap/discover-groups' && (req.method === 'GET' || req.method === 'POST')) {
      let result;
      try {
        result = onDiscover();
      } catch (err) {
        result = {status: 'error', http: 500, note: 'fail'};
      }
      const code = result.http || 202;
      res.writeHead(code, {
        'Content-Type': 'application/json',
        'Cache-Control': 'no-store',
        Connection: 'close',
      });
      res.end(
        JSON.stringify({
          status: result.status,
          note: result.note || '',
          accepted: !!result.accepted,
          commandsProcessed: false,
        }),
      );
      return;
    }
    res.writeHead(404, {'Content-Type': 'application/json', Connection: 'close'});
    res.end(JSON.stringify({status: 'not_found'}));
  });
  return server;
}

function listen(server) {
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port));
  });
}

function request(port, method, path) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      {host: '127.0.0.1', port, method, path, headers: {Connection: 'close'}},
      (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
          resolve({
            status: res.statusCode,
            body: Buffer.concat(chunks).toString('utf8'),
            headers: res.headers,
          });
        });
      },
    );
    req.on('error', reject);
    req.end();
  });
}

test('endpoint retorna 202 JSON e agenda descoberta sem resetar conexão', async () => {
  let scheduled = 0;
  const server = createBootstrapServer({
    onDiscover() {
      setTimeout(() => {
        scheduled += 1;
      }, 0);
      return {
        status: 'ready',
        http: 202,
        accepted: true,
        note: 'Descoberta agendada',
      };
    },
  });
  const port = await listen(server);
  try {
    const res = await request(port, 'POST', '/bootstrap/discover-groups');
    assert.equal(res.status, 202);
    assert.match(res.headers['content-type'], /application\/json/);
    const body = JSON.parse(res.body);
    assert.equal(body.accepted, true);
    assert.equal(body.commandsProcessed, false);
    await new Promise((r) => setTimeout(r, 20));
    assert.equal(scheduled, 1);
  } finally {
    server.close();
  }
});

test('endpoint 409 quando descoberta concorrente', async () => {
  const server = createBootstrapServer({
    onDiscover() {
      return {status: 'busy', http: 409, note: 'em andamento'};
    },
  });
  const port = await listen(server);
  try {
    const res = await request(port, 'GET', '/bootstrap/discover-groups');
    assert.equal(res.status, 409);
    assert.equal(JSON.parse(res.body).status, 'busy');
  } finally {
    server.close();
  }
});

test('endpoint 503 antes de READY', async () => {
  const server = createBootstrapServer({
    whatsappState: 'STARTING',
    onDiscover() {
      return {status: 'not_ready', http: 503, note: 'sem READY'};
    },
  });
  const port = await listen(server);
  try {
    const res = await request(port, 'POST', '/bootstrap/discover-groups');
    assert.equal(res.status, 503);
    assert.equal(JSON.parse(res.body).status, 'not_ready');
  } finally {
    server.close();
  }
});

test('endpoint 500 em falha do callback, com JSON', async () => {
  const server = createBootstrapServer({
    onDiscover() {
      throw new Error('boom');
    },
  });
  const port = await listen(server);
  try {
    const res = await request(port, 'POST', '/bootstrap/discover-groups');
    assert.equal(res.status, 500);
    assert.equal(JSON.parse(res.body).status, 'error');
  } finally {
    server.close();
  }
});
