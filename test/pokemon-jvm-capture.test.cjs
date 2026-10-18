'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {
  createClient,
  trustedIdentityFromMessage,
  stableOperationId
} = require('../scripts/lib/pokemon-jvm-client.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

async function withJvm(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    token: 'test-token',
    timeoutMs: 2000
  });
  try {
    await fn(client);
  } finally {
    client.close();
    server.closeAllConnections();
    await new Promise(r => server.close(r));
  }
}

function fakeMessage({ chatId, playerId, requestId }) {
  return {
    fromMe: false,
    from: chatId,
    author: playerId,
    id: { _serialized: requestId }
  };
}

async function dispatchCapture(client, message, tokens) {
  const identity = trustedIdentityFromMessage(message);
  const cmd = present.resolveCommand(tokens);
  if (cmd !== 'capturar') {
    return present.envelope(
      identity.requestId,
      present.formatUnavailable(tokens.join(' '))
    );
  }
  const enc = await client.getEncounter(identity);
  if (tokens.length < 2) {
    return present.envelope(
      identity.requestId,
      present.formatCaptureMenu(enc, '!pk')
    );
  }
  const ativo = await client.getActiveSave(identity);
  const operationId = stableOperationId(
    identity.requestId,
    `encounter-capture:${tokens.slice(1).join('-')}`
  );
  const data = await client.attemptEncounterCapture({
    ...identity,
    saveId: ativo.activeSaveId,
    fenceGeneration: ativo.fenceGeneration,
    operationId,
    bola: tokens.slice(1).join(' '),
    battleId: enc.encounter && enc.encounter.battleId,
    roll: 0
  });
  return present.envelope(
    identity.requestId,
    present.formatCaptureResult(data, '!pk')
  );
}

test('aliases capturar/cap e cacar habilitado', () => {
  assert.equal(present.resolveCommand(['capturar']), 'capturar');
  assert.equal(present.resolveCommand(['cap']), 'capturar');
  assert.equal(present.resolveCommand(['cacar']), 'cacar');
  assert.match(present.formatUnavailable('batalhar'), /cacar/);
});

test('menu e captura bem-sucedida via roteador simulado', async () => {
  let captureCalls = 0;
  await withJvm((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    const url = new URL(req.url, 'http://127.0.0.1');
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      if (req.method === 'GET' && url.pathname === '/v1/encounter') {
        return res.end(JSON.stringify({
          ok: true,
          data: {
            activeSaveId: 'a',
            fenceGeneration: 1,
            encounter: {
              battleId: 'b1',
              aguardandoCaptura: true,
              tentativasCaptura: 0,
              chanceBaseCaptura: 80,
              pokemonO: { nome: 'Magikarp', raridade: 'comum' },
              inventory: [{ itemId: 'pokebola', qty: 3 }]
            }
          }
        }));
      }
      if (req.method === 'GET' && url.pathname === '/v1/active-save') {
        return res.end(JSON.stringify({
          ok: true,
          data: { activeSaveId: 'a', fenceGeneration: 1, save: { coins: 10 }, inventory: [] }
        }));
      }
      if (req.method === 'POST' && url.pathname === '/v1/encounter/capture') {
        captureCalls += 1;
        const payload = JSON.parse(body);
        assert.equal(payload.chatId, 'g@g.us');
        assert.equal(payload.playerId, 'ash');
        assert.equal(payload.saveId, 'a');
        assert.equal(payload.fenceGeneration, 1);
        assert.equal(payload.bola, '1');
        assert.match(payload.operationId, /encounter-capture/);
        return res.end(JSON.stringify({
          ok: true,
          data: {
            operationId: payload.operationId,
            duplicate: captureCalls > 1,
            capturou: true,
            fugiu: false,
            chance: 75,
            bola: 'pokebola',
            indice: 1,
            recompensa: { xp: 3, moedas: 3, sequencia: 1 },
            sequenciaCapturas: 1,
            inventory: [{ itemId: 'pokebola', qty: 2 }]
          }
        }));
      }
      res.statusCode = 404;
      res.end(JSON.stringify({ ok: false, error: { code: 'not_found' } }));
    });
  }, async client => {
    const msg = fakeMessage({ chatId: 'g@g.us', playerId: 'ash', requestId: 'req-cap' });
    const menu = await dispatchCapture(client, msg, ['capturar']);
    assert.match(menu.messages[0].text, /Escolha a bola/);
    assert.match(menu.messages[0].text, /Magikarp/);

    const ok = await dispatchCapture(client, msg, ['cap', '1']);
    assert.match(ok.messages[0].text, /captura concluída/);
    assert.match(ok.messages[0].text, /nº 2/);
    assert.equal(captureCalls, 1);

    const other = await dispatchCapture(client, msg, ['batalhar']);
    assert.match(other.messages[0].text, /não integrado/);
  });
});

test('fuga e envelope {messages,effects}', () => {
  const text = present.formatCaptureResult({
    capturou: false,
    fugiu: true,
    chance: 40,
    bola: 'grande-bola',
    tentativa: 3,
    encounter: { pokemonO: { nome: 'Abra' } }
  });
  assert.match(text, /fugiu/);
  assert.match(text, /Abra/);
  const env = present.envelope('r1', text);
  assert.equal(env.messages.length, 1);
  assert.deepEqual(env.effects, []);
});

test('timeout em captura reconcilia com o mesmo operationId', async () => {
  let posts = 0;
  let gets = 0;
  const server = http.createServer((req, res) => {
    if (req.method === 'POST' && req.url === '/v1/encounter/capture') {
      posts += 1;
      return; // hang → TIMEOUT
    }
    if (req.method === 'GET' && req.url.startsWith('/v1/operations/')) {
      gets += 1;
      res.setHeader('Content-Type', 'application/json');
      return res.end(JSON.stringify({
        ok: true,
        data: {
          operationId: 'op-cap-reconcile',
          channel: 'gameplay',
          payloadHash: 'abc',
          saveId: 'a',
          summary: { capturou: true, fugiu: false, bola: 'pokebola', chance: 90 },
          persisted: true
        }
      }));
    }
    res.statusCode = 404;
    res.end('{}');
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    token: 'test-token',
    timeoutMs: 40
  });
  try {
    const data = await client.attemptEncounterCapture({
      chatId: 'c', playerId: 'p', saveId: 'a', fenceGeneration: 0,
      operationId: 'op-cap-reconcile', bola: 'pokebola', roll: 0
    });
    assert.equal(data.reconciled, true);
    assert.equal(data.duplicate, true);
    assert.equal(posts, 1);
    assert.equal(gets, 1);
    const text = present.formatCaptureResult({
      ...data,
      capturou: data.summary.capturou,
      fugiu: data.summary.fugiu,
      chance: data.summary.chance,
      bola: data.summary.bola
    });
    assert.match(text, /captura concluída|idempotente/i);
  } finally {
    client.close();
    server.closeAllConnections();
    await new Promise(r => server.close(r));
  }
});
