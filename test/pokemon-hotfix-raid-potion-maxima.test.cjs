'use strict';

/**
 * Hotfix A6 — Poção Máxima em raid:
 * parser multi-token, orientação sem escopo, painel pós-uso, contrato HTTP.
 * Usa o mesmo present.cjs do roteador CLJS (sem parser paralelo).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {
  createClient,
  stableOperationId
} = require('../scripts/lib/pokemon-jvm-client.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const ROOT = path.resolve(__dirname, '..');
const CLJS = path.join(ROOT, 'src/zapbot/pokemon_http.cljs');

const RAID_MAX_CASES = [
  { tokens: ['raid', 'pocao', 'maxima'], label: 'espaço' },
  { tokens: ['raid', 'poção', 'máxima'], label: 'acentos' },
  { tokens: ['raid', 'pocao-maxima'], label: 'hífen' },
  { tokens: ['raid', 'pmax'], label: 'pmax' },
  { tokens: ['raide', 'pocao', 'maxima'], label: 'raide' },
  { tokens: ['raid', 'maxima'], label: 'maxima' }
];

test('cljs: executar-raid-jvm! resolve poção multi-token via present', () => {
  const src = fs.readFileSync(CLJS, 'utf8');
  assert.match(src, /\.resolveRaidPotionTokens jvm-present/);
  assert.match(src, /\.potionContextGuide jvm-present/);
  assert.match(src, /pokemon_invalid_query/);
  const raidFn = src.indexOf('(defn- executar-raid-jvm!');
  const raidEnd = src.indexOf('(defn- executar-', raidFn + 10);
  assert.ok(raidFn > 0);
  const block = src.slice(raidFn, raidEnd > raidFn ? raidEnd : raidFn + 20000);
  assert.match(block, /resolveRaidPotionTokens/);
  assert.match(block, /formatRaidItemUse/);
  assert.match(block, /withRaidTurnMention/);
  assert.match(block, /\.-itemId potion/);
});

test('parser: poção comum vs máxima (espaço/hífen/acento/pmax)', () => {
  for (const { tokens, label } of RAID_MAX_CASES) {
    const plan = present.planPotionRoute(tokens);
    assert.equal(plan.scope, 'raid', label);
    assert.equal(plan.itemId, 'pocao-maxima', label);
    assert.equal(plan.write, 'raidUseItem', label);
    assert.equal(plan.handler, 'executar-raid-jvm!', label);
    const potion = present.resolveRaidPotionTokens(tokens.slice(1));
    assert.equal(potion.itemId, 'pocao-maxima', label);
  }
  const common = present.planPotionRoute(['raid', 'pocao']);
  assert.equal(common.itemId, 'pocao');
  assert.equal(common.write, 'raidUseItem');
  const pot = present.resolveRaidPotionTokens(['pot']);
  assert.equal(pot.itemId, 'pocao');
  assert.equal(pot.consumed, 1);
});

test('parser: unscoped pocao maxima → cmd pocao-maxima sem write', () => {
  for (const tokens of [['pocao', 'maxima'], ['pocao-maxima'], ['pmax'], ['poção', 'máxima']]) {
    const plan = present.planPotionRoute(tokens);
    assert.equal(plan.cmd, 'pocao-maxima', tokens.join(' '));
    assert.equal(plan.scope, 'auto', tokens.join(' '));
    assert.equal(plan.write, null, tokens.join(' '));
    assert.equal(plan.handler, 'top-level-pocao', tokens.join(' '));
  }
});

test('orientação: combate ativo sem escopo não debita', () => {
  const plan = present.planPotionRoute(['pocao', 'maxima']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: {
      fase: 'combate', inscrito: true,
      participantes: [{ playerId: 'ash', hp: 12, voce: true }]
    },
    inventoryQty: 2
  });
  assert.equal(d.mutate, false);
  assert.equal(d.method, null);
  assert.equal(d.action, 'ambiguous');
  assert.match(d.prompt, /POÇÃO MÁXIMA/);
  assert.match(d.prompt, /raid pocao maxima/);
  assert.match(d.prompt, /Nenhuma poção foi utilizada/);
});

test('orientação: fora de combate preserva useItem', () => {
  const plan = present.planPotionRoute(['pocao', 'maxima']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: null,
    encounter: null,
    pvpBattle: null,
    inventoryQty: 1
  });
  assert.equal(d.action, 'out-of-combat');
  assert.equal(d.method, 'useItem');
  assert.equal(d.mutate, true);
  assert.equal(d.itemId, 'pocao-maxima');
});

test('dispatch raid: estoque insuficiente / fora do turno', () => {
  const plan = present.planPotionRoute(['raid', 'pocao', 'maxima']);
  assert.equal(
    present.resolvePotionDispatch(plan, { raid: { fase: 'combate' }, suaVezRaid: false, inventoryQty: 1 }).reason,
    'not-your-turn'
  );
  assert.equal(
    present.resolvePotionDispatch(plan, { raid: { fase: 'combate' }, suaVezRaid: true, inventoryQty: 0 }).reason,
    'no-stock'
  );
});

test('formatRaidItemUse: turno avançado menciona próximo treinador', () => {
  const text = present.formatRaidItemUse({
    itemId: 'pocao-maxima',
    pokemonAtivo: { nome: 'Charizard' },
    hpAntes: 12,
    hpDepois: 120,
    hpMax: 120,
    hpAposRevide: 96,
    revide: 24,
    raid: {
      fase: 'combate',
      turnIndex: 4,
      vez: 'brock',
      vezNome: 'Brock',
      vezHp: 96,
      hpChefe: 450,
      hpMax: 800,
      chefe: { nome: 'Dragonite', tipos: ['dragao'] },
      vezPokemon: {
        nome: 'Charizard',
        hp: 120,
        golpes: [
          { nome: 'Lança-Chamas', slug: 'flamethrower', poder: 90, tipo: 'fogo', classe: 'especial' }
        ]
      },
      suaVez: false
    }
  }, '!pk');
  assert.match(text, /Vez de \*Brock\*/);
  assert.match(text, /raid atacar 1/);
  assert.doesNotMatch(text, /É a sua vez/);
});

test('formatRaidItemUse: derrota por revide sem golpes inválidos', () => {
  const text = present.formatRaidItemUse({
    itemId: 'pocao',
    pokemonAtivo: { nome: 'Pikachu' },
    hpAntes: 5,
    hpDepois: 40,
    hpMax: 40,
    hpAposRevide: 0,
    revide: 40,
    derrota: true,
    raid: { fase: 'derrota', turnIndex: 9 }
  }, '!pk');
  assert.match(text, /derrotada/);
  assert.doesNotMatch(text, /raid atacar/);
});

async function mockBridge(routes) {
  const calls = [];
  const server = http.createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const bodyRaw = Buffer.concat(chunks).toString();
      let body = null;
      try { body = bodyRaw ? JSON.parse(bodyRaw) : null; } catch (_) { body = bodyRaw; }
      const entry = { method: req.method, url: req.url, body };
      calls.push(entry);
      const key = `${req.method} ${req.url.split('?')[0]}`;
      const handler = routes[key] || routes['*'];
      const payload = typeof handler === 'function' ? handler(entry, calls) : handler;
      res.setHeader('Content-Type', 'application/json');
      if (payload && payload.__status) {
        res.statusCode = payload.__status;
        res.end(JSON.stringify(payload.body || { ok: false, error: { code: 'ERR', message: 'x' } }));
        return;
      }
      res.end(JSON.stringify({ ok: true, data: payload == null ? {} : payload }));
    });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    token: 'test-token',
    connectTimeoutMs: 1000,
    timeoutMs: 2000
  });
  return {
    client,
    calls,
    close: async () => {
      client.close();
      server.closeAllConnections();
      await new Promise((r) => server.close(r));
    }
  };
}

test('HTTP: raid pocao maxima → raidUseItem com itemId canônico + retry idempotente', async () => {
  let uses = 0;
  const opId = stableOperationId('msg-max', 'raid-item:r1:3:pocao-maxima');
  const f = await mockBridge({
    'GET /v1/raids/active': {
      raid: {
        raidId: 'r1', fase: 'combate', inscrito: true, suaVez: true,
        turnIndex: 3, vez: 'ash', vezHp: 12,
        participantes: [{ playerId: 'ash', hp: 12, voce: true }]
      }
    },
    'GET /v1/active-save': { activeSaveId: 'a', fenceGeneration: 2 },
    'POST /v1/raids/item': (entry) => {
      uses += 1;
      assert.equal(entry.body.itemId, 'pocao-maxima');
      assert.equal(entry.body.operationId, opId);
      assert.equal(entry.body.expectedTurnIndex, 3);
      assert.equal(entry.body.saveId, 'a');
      assert.equal(entry.body.fenceGeneration, 2);
      return {
        duplicate: uses > 1,
        itemId: 'pocao-maxima',
        hpAntes: 12,
        hpDepois: 120,
        hpMax: 120,
        revide: 24,
        hpAposRevide: 96,
        derrota: false,
        raid: {
          raidId: 'r1',
          fase: 'combate',
          turnIndex: 4,
          vez: 'misty',
          vezNome: 'Misty',
          vezHp: 80,
          hpChefe: 450,
          hpMax: 800,
          chefe: { nome: 'Dragonite' },
          vezPokemon: {
            nome: 'Onix',
            hp: 100,
            golpes: [{ nome: 'Investida', slug: 'tackle', poder: 40, tipo: 'normal', classe: 'fisico' }]
          }
        }
      };
    }
  });
  try {
    const plan = present.planPotionRoute(['raid', 'pocao', 'maxima']);
    assert.equal(plan.itemId, 'pocao-maxima');
    const raidData = await f.client.getActiveRaid({ chatId: 'c', playerId: 'ash' });
    const save = await f.client.getActiveSave({ chatId: 'c', playerId: 'ash' });
    const body = {
      chatId: 'c',
      playerId: 'ash',
      saveId: save.activeSaveId,
      fenceGeneration: save.fenceGeneration,
      operationId: opId,
      raidId: raidData.raid.raidId,
      expectedTurnIndex: raidData.raid.turnIndex,
      itemId: plan.itemId
    };
    const first = await f.client.raidUseItem(body);
    const retry = await f.client.raidUseItem(body);
    assert.equal(first.duplicate, false);
    assert.equal(retry.duplicate, true);
    assert.equal(uses, 2);
    assert.equal(first.hpAposRevide, 96);
    const panel = present.formatRaidItemUse(first, '!pk');
    assert.match(panel, /Vez de \*Misty\*/);
    assert.match(panel, /raid atacar 1/);
    assert.match(panel, /TURNO 4/);
  } finally {
    await f.close();
  }
});

test('HTTP: orientação unscoped não chama raidUseItem/useItem', async () => {
  let writes = 0;
  const f = await mockBridge({
    'GET /v1/raids/active': {
      raid: {
        raidId: 'r1', fase: 'combate', inscrito: true,
        participantes: [{ playerId: 'ash', hp: 12, voce: true }]
      }
    },
    'GET /v1/encounter': { encounter: null },
    'GET /v1/pvp/active': { battle: null },
    'POST /v1/raids/item': () => { writes += 1; return {}; },
    'POST /v1/items/use': () => { writes += 1; return {}; }
  });
  try {
    const plan = present.planPotionRoute(['pocao', 'maxima']);
    const raidData = await f.client.getActiveRaid({ chatId: 'c', playerId: 'ash' });
    const enc = await f.client.getEncounter({ chatId: 'c', playerId: 'ash' });
    const pvp = await f.client.getActivePvp({ chatId: 'c' });
    const d = present.resolvePotionDispatch(plan, {
      playerId: 'ash',
      raid: raidData.raid,
      encounter: enc.encounter,
      pvpBattle: pvp.battle,
      inventoryQty: 1
    });
    assert.equal(d.mutate, false);
    assert.equal(writes, 0);
    assert.ok(!f.calls.some((c) => c.method === 'POST'));
  } finally {
    await f.close();
  }
});

test('HTTP: invalid_query de raidUseItem expõe required sem mutar inventário local', async () => {
  const f = await mockBridge({
    'POST /v1/raids/item': () => ({
      __status: 400,
      body: {
        ok: false,
        error: {
          code: 'INVALID_QUERY',
          message: 'invalid_query',
          details: { required: ['expectedTurnIndex', 'itemId'] }
        }
      }
    })
  });
  try {
    let err = null;
    try {
      await f.client.raidUseItem({
        chatId: 'c', playerId: 'ash', saveId: 'a', fenceGeneration: 1,
        operationId: 'op-bad', raidId: 'r1'
        // missing expectedTurnIndex + itemId
      });
    } catch (e) {
      err = e;
    }
    assert.ok(err);
    assert.equal(String(err.code || '').toUpperCase(), 'INVALID_QUERY');
    const msg = present.formatError(err);
    assert.match(msg, /Pedido|inválido|incompleto|raid pocao maxima/i);
  } finally {
    await f.close();
  }
});

test('inventário canônico: mochila e raidUseItem usam pocao-maxima', () => {
  const bag = present.formatInventory({
    coins: 10,
    ocupacaoMochila: 1,
    capacidadeMochila: 50,
    itens: [{ itemId: 'pocao-maxima', nome: 'Poção Máxima', emoji: '💖', qty: 2 }]
  });
  assert.match(bag, /Poção Máxima/);
  assert.match(bag, /×2/);
  const plan = present.planPotionRoute(['raid', 'pocao', 'maxima']);
  assert.equal(plan.itemId, 'pocao-maxima');
});
