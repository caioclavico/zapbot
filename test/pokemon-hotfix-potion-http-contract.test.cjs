'use strict';

/**
 * Contrato HTTP + roteamento de poções (PR #126).
 * - Auditoria estática: pokemon_http.cljs só chama métodos existentes no client.
 * - Roteamento: WhatsApp tokens → planPotionRoute → reads/writes JVM.
 * - Integração mock: getEncounter (não getActiveEncounter), raidUseItem, useItem,
 *   confirmBattleTurn, confirmPvpTurn, ambiguidade sem mutação, idempotência.
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
const CLIENT = path.join(ROOT, 'scripts/lib/pokemon-jvm-client.cjs');

function cljsJvmMethods(src) {
  const stripped = String(src).replace(/;.*$/gm, '');
  const re = /(?:#)?\(\.([a-zA-Z][a-zA-Z0-9]*)\s+jvm(?=[\s\)])/g;
  const out = new Set();
  let m;
  while ((m = re.exec(stripped))) out.add(m[1]);
  return out;
}

function clientExportedMethods(src) {
  const m = String(src).match(/return \{([\s\S]*?)\n  \};/);
  assert.ok(m, 'client return object not found');
  return new Set([...m[1].matchAll(/^\s*([a-zA-Z][a-zA-Z0-9]*)\s*:/gm)].map((x) => x[1]));
}

test('compatibilidade CLJS↔client: nenhum método JVM inexistente', () => {
  const methods = cljsJvmMethods(fs.readFileSync(CLJS, 'utf8'));
  const exported = clientExportedMethods(fs.readFileSync(CLIENT, 'utf8'));
  const missing = [...methods].filter((n) => !exported.has(n)).sort();
  assert.deepEqual(missing, [], `métodos ausentes no client: ${missing.join(', ')}`);
  assert.equal(methods.has('getActiveEncounter'), false);
  assert.equal(methods.has('getEncounter'), true);
  assert.equal(methods.has('raidUseItem'), true);
  assert.equal(exported.has('getEncounter'), true);
  assert.equal(exported.has('raidUseItem'), true);
});

test('compatibilidade: poção top-level lê getEncounter no fonte', () => {
  const src = fs.readFileSync(CLJS, 'utf8');
  const i = src.indexOf('("pocao" "pocao-maxima")');
  assert.ok(i > 0);
  const block = src.slice(i, i + 2500);
  assert.match(block, /\.getEncounter jvm/);
  assert.doesNotMatch(block.replace(/;.*$/gm, ''), /getActiveEncounter/);
  assert.match(block, /\.-encounter/);
});

const POTION_COMMANDS = [
  { tokens: ['pocao'], label: '!pk pocao fora de combate' },
  { tokens: ['pocao-maxima'], label: '!pk pocao-maxima fora de combate' },
  { tokens: ['raid', 'pocao'], label: '!pk raid pocao' },
  { tokens: ['raid', 'pocao-maxima'], label: '!pk raid pocao-maxima' },
  { tokens: ['cacar', 'pocao'], label: '!pk cacar pocao' },
  { tokens: ['cacar', 'pocao-maxima'], label: '!pk cacar pocao-maxima' },
  { tokens: ['pvp', 'pocao'], label: '!pk pvp pocao' },
  { tokens: ['pvp', 'pocao-maxima'], label: '!pk pvp pocao-maxima' }
];

test('roteamento: 8 comandos explícitos de poção', () => {
  for (const { tokens, label } of POTION_COMMANDS) {
    const plan = present.planPotionRoute(tokens);
    assert.ok(plan.itemId, label);
    if (tokens[0] === 'raid') {
      assert.equal(plan.handler, 'executar-raid-jvm!', label);
      assert.equal(plan.write, 'raidUseItem', label);
      assert.ok(plan.reads.includes('getActiveRaid'), label);
      assert.equal(plan.encounterRead, null, label);
    } else {
      assert.equal(plan.handler, 'top-level-pocao', label);
      assert.deepEqual(plan.reads, ['getActiveRaid', 'getEncounter', 'getActivePvp'], label);
      assert.equal(plan.encounterRead, 'getEncounter', label);
      if (tokens[0] === 'cacar') assert.equal(plan.scope, 'cacar', label);
      if (tokens[0] === 'pvp') assert.equal(plan.scope, 'pvp', label);
      if (tokens[0].startsWith('pocao')) assert.equal(plan.scope, 'auto', label);
    }
  }
});

test('roteamento: poção sem escopo em combate → orientação sem mutação', () => {
  const plan = present.planPotionRoute(['pocao']);
  const onlyHunt = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: { fase: 'combate', inscrito: false },
    encounter: { active: true, aguardandoCaptura: false },
    pvpBattle: null,
    inventoryQty: 1
  });
  assert.equal(onlyHunt.action, 'ambiguous');
  assert.equal(onlyHunt.mutate, false);
  assert.equal(onlyHunt.method, null);
  assert.deepEqual(onlyHunt.candidates, ['cacar']);
  assert.match(onlyHunt.prompt, /cacar pocao/);
  assert.match(onlyHunt.prompt, /Nenhuma poção foi utilizada/);

  const onlyRaid = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: {
      fase: 'combate', inscrito: true,
      participantes: [{ playerId: 'ash', hp: 40, voce: true }]
    },
    encounter: null,
    inventoryQty: 1
  });
  assert.equal(onlyRaid.action, 'ambiguous');
  assert.equal(onlyRaid.mutate, false);
  assert.deepEqual(onlyRaid.candidates, ['raid']);
  assert.match(onlyRaid.prompt, /raid pocao/);
});

test('roteamento: raid+caça simultâneas → ambíguo sem mutação', () => {
  const plan = present.planPotionRoute(['pocao-maxima']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: {
      fase: 'combate', inscrito: true,
      participantes: [{ playerId: 'ash', hp: 12, voce: true }]
    },
    encounter: { active: true, aguardandoCaptura: false },
    inventoryQty: 2
  });
  assert.equal(d.action, 'ambiguous');
  assert.equal(d.mutate, false);
  assert.equal(d.method, null);
  assert.deepEqual(d.candidates.sort(), ['cacar', 'raid']);
  assert.match(d.prompt, /raid pocao/);
  assert.match(d.prompt, /cacar pocao/);
});

test('roteamento: caçando enquanto outros na raid → orientar caçada (sem mutar)', () => {
  const plan = present.planPotionRoute(['pocao']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    // raid ativa no chat, mas ash NÃO inscrito
    raid: {
      fase: 'combate', inscrito: false,
      participantes: [{ playerId: 'misty', hp: 100 }]
    },
    encounter: { active: true, aguardandoCaptura: false },
    inventoryQty: 1
  });
  assert.equal(d.action, 'ambiguous');
  assert.equal(d.mutate, false);
  assert.deepEqual(d.candidates, ['cacar']);
  assert.match(d.prompt, /cacar pocao/);
});

test('roteamento: !pk cacar pocao ignora PvP/raid elegíveis', () => {
  const plan = present.planPotionRoute(['cacar', 'pocao']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: {
      fase: 'combate', inscrito: true,
      participantes: [{ playerId: 'ash', hp: 50, voce: true }]
    },
    encounter: { active: true, aguardandoCaptura: false },
    pvpBattle: { phase: 'active' },
    pvpSideOk: true,
    inventoryQty: 1
  });
  assert.equal(d.chosen, 'cacar');
  assert.equal(d.method, 'confirmBattleTurn');
});

test('roteamento: raid fora do turno / sem estoque', () => {
  const plan = present.planPotionRoute(['raid', 'pocao']);
  const notTurn = present.resolvePotionDispatch(plan, {
    raid: { fase: 'combate' }, suaVezRaid: false, inventoryQty: 1
  });
  assert.equal(notTurn.reason, 'not-your-turn');
  assert.equal(notTurn.mutate, false);

  const noStock = present.resolvePotionDispatch(plan, {
    raid: { fase: 'combate' }, suaVezRaid: true, inventoryQty: 0
  });
  assert.equal(noStock.reason, 'no-stock');
  assert.equal(noStock.mutate, false);
});

test('roteamento: fora de combate → useItem legado', () => {
  const plan = present.planPotionRoute(['pocao']);
  const d = present.resolvePotionDispatch(plan, {
    playerId: 'ash',
    raid: null,
    encounter: null,
    pvpBattle: null,
    inventoryQty: 3
  });
  assert.equal(d.action, 'out-of-combat');
  assert.equal(d.method, 'useItem');
  assert.equal(d.mutate, true);
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

test('integração mock: poção top-level usa getEncounter e ambíguo não muta', async () => {
  let itemPosts = 0;
  const f = await mockBridge({
    'GET /v1/raids/active': {
      raid: {
        raidId: 'r1', fase: 'combate', inscrito: true, turnIndex: 2,
        participantes: [{ playerId: 'ash', hp: 12, voce: true }]
      }
    },
    'GET /v1/encounter': { encounter: { active: true, aguardandoCaptura: false, battleId: 'b1' } },
    'GET /v1/pvp/active': { battle: null },
    'POST /v1/raids/item': () => { itemPosts += 1; return { duplicate: false }; },
    'POST /v1/items/use': () => { itemPosts += 1; return { duplicate: false }; },
    'POST /v1/battles/turn': () => { itemPosts += 1; return { duplicate: false }; }
  });
  try {
    const plan = present.planPotionRoute(['pocao']);
    assert.deepEqual(plan.reads, ['getActiveRaid', 'getEncounter', 'getActivePvp']);

    const raidData = await f.client.getActiveRaid({ chatId: 'c', playerId: 'ash' });
    const encData = await f.client.getEncounter({ chatId: 'c', playerId: 'ash' });
    const pvp = await f.client.getActivePvp({ chatId: 'c' });
    assert.ok(encData.encounter);
    assert.equal(typeof f.client.getActiveEncounter, 'undefined');

    const dispatch = present.resolvePotionDispatch(plan, {
      playerId: 'ash',
      raid: raidData.raid,
      encounter: encData.encounter,
      pvpBattle: pvp.battle,
      inventoryQty: 1
    });
    assert.equal(dispatch.action, 'ambiguous');
    assert.equal(dispatch.mutate, false);
    assert.equal(itemPosts, 0);
    assert.ok(f.calls.some((c) => c.url.startsWith('/v1/encounter')));
    assert.ok(!f.calls.some((c) => /active-encounter|getActiveEncounter/i.test(c.url)));
  } finally {
    await f.close();
  }
});

test('integração mock: !pk raid pocao-maxima → raidUseItem 1x + retry idempotente', async () => {
  let uses = 0;
  const opId = stableOperationId('msg-1', 'raid-item:r1:3:pocao-maxima');
  const f = await mockBridge({
    'GET /v1/raids/active': {
      raid: {
        raidId: 'r1', fase: 'combate', inscrito: true, suaVez: true,
        turnIndex: 3, vez: 'ash', vezHp: 12,
        participantes: [{ playerId: 'ash', hp: 12, voce: true }]
      }
    },
    'GET /v1/active-save': { activeSaveId: 'a', fenceGeneration: 1 },
    'POST /v1/raids/item': (entry) => {
      uses += 1;
      assert.equal(entry.body.itemId, 'pocao-maxima');
      assert.equal(entry.body.operationId, opId);
      assert.equal(entry.body.expectedTurnIndex, 3);
      return {
        duplicate: uses > 1,
        itemId: 'pocao-maxima',
        hpAntes: 12,
        hpDepois: 120,
        hpMax: 120,
        revide: 24,
        hpAposRevide: 96,
        derrota: false,
        raid: { raidId: 'r1', turnIndex: 4, vezHp: 96, fase: 'combate' }
      };
    }
  });
  try {
    const plan = present.planPotionRoute(['raid', 'pocao-maxima']);
    assert.equal(plan.write, 'raidUseItem');
    const raidData = await f.client.getActiveRaid({ chatId: 'c', playerId: 'ash' });
    const save = await f.client.getActiveSave({ chatId: 'c', playerId: 'ash' });
    const dispatch = present.resolvePotionDispatch(plan, {
      raid: raidData.raid, suaVezRaid: true, inventoryQty: 1
    });
    assert.equal(dispatch.mutate, true);
    assert.equal(dispatch.method, 'raidUseItem');

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
    assert.equal(uses, 2); // HTTP retries once at transport; domain duplicate flag
    assert.equal(first.hpAposRevide, 96);
    assert.equal(first.revide, 24);
    // Contra-ataque após cura: 120 - 24 = 96 (HP pós-cura considerado).
    assert.equal(first.hpDepois - first.revide, first.hpAposRevide);
  } finally {
    await f.close();
  }
});

test('integração mock: !pk cacar pocao → confirmBattleTurn com action item', async () => {
  let turns = 0;
  const f = await mockBridge({
    'GET /v1/raids/active': { raid: null },
    'GET /v1/encounter': {
      encounter: {
        active: true, aguardandoCaptura: false, battleId: 'b-hunt',
        expectedRevision: 2
      }
    },
    'GET /v1/pvp/active': { battle: { phase: 'active', active: true } },
    'GET /v1/active-save': { activeSaveId: 'a', fenceGeneration: 0 },
    'POST /v1/battles/turn': (entry) => {
      turns += 1;
      assert.equal(entry.body.action, 'item');
      assert.equal(entry.body.itemId, 'pocao');
      return {
        duplicate: turns > 1,
        battle: { battleId: 'b-hunt', revision: 3 },
        hpAntes: 10, hpDepois: 40, hpMax: 40
      };
    }
  });
  try {
    const plan = present.planPotionRoute(['cacar', 'pocao']);
    const enc = await f.client.getEncounter({ chatId: 'c', playerId: 'ash' });
    const pvp = await f.client.getActivePvp({ chatId: 'c' });
    // Escopo cacar: PvP ativo NÃO desvia
    const d = present.resolvePotionDispatch(plan, {
      playerId: 'ash',
      raid: null,
      encounter: enc.encounter,
      pvpBattle: pvp.battle,
      pvpSideOk: true,
      inventoryQty: 1
    });
    assert.equal(d.method, 'confirmBattleTurn');
    const save = await f.client.getActiveSave({ chatId: 'c', playerId: 'ash' });
    const op = stableOperationId('msg-cacar', 'hunt-item:b-hunt:pocao');
    const out = await f.client.confirmBattleTurn({
      chatId: 'c',
      playerId: 'ash',
      saveId: save.activeSaveId,
      fenceGeneration: save.fenceGeneration,
      operationId: op,
      battleId: enc.encounter.battleId,
      expectedRevision: enc.encounter.expectedRevision,
      action: 'item',
      itemId: 'pocao'
    });
    assert.equal(out.duplicate, false);
    assert.equal(turns, 1);
  } finally {
    await f.close();
  }
});

test('integração mock: !pk pvp pocao-maxima → confirmPvpTurn', async () => {
  let turns = 0;
  const f = await mockBridge({
    'GET /v1/active-save': { activeSaveId: 'a', fenceGeneration: 2 },
    'POST /v1/pvp/turn': (entry) => {
      turns += 1;
      assert.equal(entry.body.action, 'item');
      assert.equal(entry.body.itemId, 'pocao-maxima');
      return { duplicate: false, battle: { phase: 'active' } };
    }
  });
  try {
    const plan = present.planPotionRoute(['pvp', 'pocao-maxima']);
    const d = present.resolvePotionDispatch(plan, {
      playerId: 'ash',
      pvpBattle: { phase: 'active' },
      pvpSideOk: true,
      inventoryQty: 1
    });
    assert.equal(d.method, 'confirmPvpTurn');
    const save = await f.client.getActiveSave({ chatId: 'c', playerId: 'ash' });
    await f.client.confirmPvpTurn({
      chatId: 'c',
      playerId: 'ash',
      saveId: save.activeSaveId,
      fenceGeneration: save.fenceGeneration,
      operationId: stableOperationId('msg-pvp', 'pvp-item:pocao-maxima'),
      action: 'item',
      itemId: 'pocao-maxima'
    });
    assert.equal(turns, 1);
  } finally {
    await f.close();
  }
});

test('integração mock: timeout após TX — getOperation recupera recibo sem novo consumo', async () => {
  let uses = 0;
  const opId = 'op-timeout-pocao-1';
  const f = await mockBridge({
    'POST /v1/items/use': () => {
      uses += 1;
      return {
        __status: 504,
        body: { ok: false, error: { code: 'TIMEOUT', message: 'gateway' } }
      };
    },
    'GET /v1/operations/op-timeout-pocao-1': {
      found: true,
      duplicate: true,
      itemId: 'pocao',
      hpAntes: 5,
      hpDepois: 25,
      operationId: opId
    }
  });
  try {
    let threw = false;
    try {
      await f.client.useItem({
        chatId: 'c', playerId: 'ash', saveId: 'a', fenceGeneration: 0,
        operationId: opId, itemId: 'pocao'
      });
    } catch (_) {
      threw = true;
    }
    // Cliente pode mapear 5xx; o importante é recuperar via getOperation
    // sem incrementar consumo de domínio (uses já registrou a tentativa HTTP).
    const receipt = await f.client.getOperation({
      chatId: 'c', playerId: 'ash', operationId: opId
    });
    assert.equal(receipt.found, true);
    assert.equal(receipt.operationId, opId);
    assert.equal(uses, 1);
    assert.ok(threw || receipt.duplicate === true);
  } finally {
    await f.close();
  }
});
