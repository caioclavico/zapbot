'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const { createClient } = require('../scripts/lib/pokemon-jvm-client.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

async function withServer(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const client = createClient({ baseUrl: `http://127.0.0.1:${server.address().port}`, token: 'test', timeoutMs: 80 });
  try { await fn(client); } finally {
    client.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  }
}

const identity = { chatId: 'grupo@g.us', playerId: 'ash@c.us', saveId: 'a', fenceGeneration: 2 };

test('cliente usa contratos canônicos de missões e presentes', async () => {
  const seen = [];
  await withServer((req, res) => {
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      seen.push([req.method, req.url, body && JSON.parse(body)]);
      res.setHeader('content-type', 'application/json');
      res.end(JSON.stringify({ ok: true, data: { kind: 'daily', operationId: 'same', empty: true } }));
    });
  }, async client => {
    await client.getMissions(identity);
    await client.getMissionRewards(identity);
    await client.claimDailyMissions({ ...identity, operationId: 'daily-1', period: '2026-10-21' });
    await client.claimWeeklyMissions({ ...identity, operationId: 'weekly-1', period: '2026-10-19' });
    await client.sendGift({ ...identity, operationId: 'gift-1', targetPlayerId: 'misty@c.us' });
    await client.collectMissionPending({ ...identity, operationId: 'pending-1' });
  });
  assert.deepEqual(seen.map(x => x.slice(0, 2)), [
    ['GET', '/v1/missions?chatId=grupo%40g.us&playerId=ash%40c.us&saveId=a'],
    ['GET', '/v1/missions/rewards?chatId=grupo%40g.us&playerId=ash%40c.us&saveId=a'],
    ['POST', '/v1/missions/claim/daily'], ['POST', '/v1/missions/claim/weekly'],
    ['POST', '/v1/gifts/send'], ['POST', '/v1/missions/pending/collect']
  ]);
  assert.equal(seen[2][2].operationId, 'daily-1');
  assert.equal(seen[4][2].targetPlayerId, 'misty@c.us');
  assert.equal(Object.hasOwn(seen[4][2], 'items'), false);
});

test('timeout de resgate reconcilia pelo mesmo recibo sem nova operação', async () => {
  let claims = 0;
  await withServer((req, res) => {
    if (req.url.startsWith('/v1/operations/mission-timeout')) {
      res.setHeader('content-type', 'application/json');
      return res.end(JSON.stringify({ ok: true, data: {
        operationId: 'mission-timeout', channel: 'gameplay', payloadHash: 'h', saveId: 'a',
        summary: { result: { kind: 'daily', ids: ['capturas'], xp: 3, items: [], pending: [] } }
      } }));
    }
    if (req.url === '/v1/missions/claim/daily') {
      claims += 1;
      req.resume();
      return req.once('end', () => setTimeout(() => {
        if (!res.destroyed) res.end(JSON.stringify({ ok: true, data: {} }));
      }, 200));
    }
    res.statusCode = 404; res.end(JSON.stringify({ ok: false, error: { code: 'not_found' } }));
  }, async client => {
    const data = await client.claimDailyMissions({ ...identity, operationId: 'mission-timeout', period: '2026-10-21' });
    assert.equal(data.reconciled, true);
    assert.equal(data.operationId, 'mission-timeout');
  });
  assert.equal(claims, 1);
});

test('apresentação filtra todas, diárias e semanais sem misturar grupos', () => {
  assert.equal(present.resolveCommand(['mis']), 'missoes');
  assert.equal(present.resolveCommand(['missões']), 'missoes');
  assert.equal(present.resolveCommand(['pre']), 'presente');
  const missions = {
    daily: { period: '2026-10-21', level: 6, missions: [{ id: 'capturas', nome: 'Colecionador', objetivo: 'Capturar Pokémon selvagens', progresso: 1, meta: 4, xp: 6, pokebolas: 8, estado: 'in_progress' }] },
    weekly: { period: '2026-10-19', missions: [{ id: 'pvp', objetivo: 'Vencer 3 batalhas PvP', progresso: 3, meta: 3, moedas: 60, estado: 'completed' }] },
    events: [{ nome: 'Novo Recomeço', status: 'ended' }]
  };
  const all = present.formatMissions(missions, 'all', '!pk');
  const daily = present.formatMissions(missions, 'diarias', '!pk');
  const weekly = present.formatMissions(missions, 'semanais', '!pk');
  assert.match(all, /Colecionador/); assert.match(all, /Vencer 3 batalhas PvP/);
  assert.match(all, /Novo Recomeço.*encerrado/s);
  assert.match(daily, /Colecionador/); assert.match(daily, /1\/4/); assert.doesNotMatch(daily, /Vencer 3 batalhas PvP|Missões semanais|Novo Recomeço/);
  assert.match(weekly, /Vencer 3 batalhas PvP/); assert.doesNotMatch(weekly, /Colecionador|Missões diárias|Novo Recomeço/);
  assert.equal(present.missionScope('diárias'), 'daily');
  assert.equal(present.missionScope('semanais'), 'weekly');
  const text = all;
  assert.match(text, /resgatar/);
  const claim = present.formatMissionClaim({ kind: 'daily', ids: ['capturas'], xp: 3,
    items: [{ itemId: 'pokebola', qty: 4 }], pending: [{ itemId: 'grande-bola', qty: 1 }] }, 'daily');
  assert.match(claim, /\+3 PE/); assert.match(claim, /Pokébola/); assert.match(claim, /mochila resgatar/);
  assert.match(present.formatGift({ sent: true, targetPlayerId: 'misty@c.us', items: [{ itemId: 'pokebola', qty: 3 }], pending: [] }), /misty/);
  assert.match(present.formatMissionPending({ added: [{ itemId: 'pokebola', qty: 2 }], pending: [] }), /recolhidas/);
});
