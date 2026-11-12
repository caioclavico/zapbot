'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const eggCards = require('../scripts/lib/egg-cards.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const dtoEggs = {
  inventarioCount: 3,
  limiteInventario: 9,
  pendingEggGrants: 1,
  piDaily: { day: '2026-11-12', points: 40, cap: 150, restante: 110 },
  eggs: [
    {
      eggId: 'e1',
      category: 'comum',
      state: 'incubating',
      points: 45,
      pointsGoal: 100,
      progressPct: 45,
      restanteMs: 3600000,
      speciesHidden: true
    },
    {
      eggId: 'e2',
      category: 'especial',
      state: 'inventory',
      points: 0,
      pointsGoal: 800,
      progressPct: 0,
      speciesHidden: true
    },
    {
      eggId: 'e3',
      category: 'raro',
      state: 'ready-to-hatch',
      points: 250,
      pointsGoal: 250,
      progressPct: 100,
      speciesHidden: true
    }
  ],
  incubators: [
    {
      incubatorId: 'permanente',
      kind: 'permanente',
      uses: null,
      bonusPoints: 0,
      activeEggId: 'e1',
      livre: false
    },
    {
      incubatorId: 'especial',
      kind: 'especial',
      uses: 2,
      bonusPoints: 25,
      activeEggId: null,
      livre: true
    }
  ]
};

test('egg-cards: dimensões 640×820 e PNG limitado', async () => {
  for (const kind of ['eggs', 'incubators']) {
    const buf = await eggCards.render(kind, dtoEggs);
    const meta = await sharp(buf).metadata();
    assert.equal(meta.format, 'png');
    assert.deepEqual([meta.width, meta.height], [640, 820]);
    assert.ok(buf.length < eggCards.MAX_PNG_BYTES, `${kind} size ${buf.length}`);
  }
});

test('egg-cards: especial 800 PI e delivery-pending', async () => {
  const buf = await eggCards.render('eggs', {
    eggs: [
      { category: 'especial', state: 'inventory', points: 0, pointsGoal: 800 },
      { category: 'raro', state: 'delivery-pending', points: 250, pointsGoal: 250 }
    ],
    inventarioCount: 2,
    limiteInventario: 9
  });
  const meta = await sharp(buf).metadata();
  assert.equal(meta.format, 'png');
  assert.equal(eggCards.GOALS.especial, 800);
  assert.ok(eggCards.RARITY_COLORS.especial);
});

test('egg-cards: inventário cheio + pending grants', async () => {
  const buf = await eggCards.render('eggs', {
    eggs: Array.from({ length: 9 }, () => ({ category: 'comum', state: 'inventory', points: 0, pointsGoal: 100 })),
    inventarioCount: 9,
    limiteInventario: 9,
    pendingEggGrants: 3
  });
  assert.ok(Buffer.isBuffer(buf));
  assert.ok(buf.length > 1000);
});

test('egg-cards: campos ausentes não quebram', async () => {
  const a = await eggCards.render('eggs', {});
  const b = await eggCards.render('incubators', { incubators: [{}] });
  assert.ok(a.length > 500);
  assert.ok(b.length > 500);
});

test('egg-cards: DTO camelCase JVM é normalizado', () => {
  const n = eggCards.normalizeAdventure(dtoEggs);
  assert.equal(n.eggs.length, 3);
  assert.equal(n.eggs[1].category, 'especial');
  assert.equal(n.eggs[1].pointsGoal, 800);
  assert.equal(n.incubators[0].activeEggId, 'e1');
  assert.equal(n.incubators[0].rarity, 'comum');
  assert.equal(n.pendingEggGrants, 1);
});

test('media: renderEggInventoryCard / renderIncubatorCard retornam base64', async () => {
  const eggs = await media.renderEggInventoryCard(dtoEggs);
  const inc = await media.renderIncubatorCard(dtoEggs);
  assert.equal(eggs.mimeType, 'image/png');
  assert.equal(eggs.filename, 'ovos.png');
  assert.ok(eggs.base64.length > 100);
  assert.equal(inc.filename, 'incubadoras.png');
  assert.ok(inc.base64.length > 100);
  const decoded = Buffer.from(eggs.base64, 'base64');
  const meta = await sharp(decoded).metadata();
  assert.deepEqual([meta.width, meta.height], [640, 820]);
});

test('media: tipo inválido cai em null (fallback texto)', async () => {
  const eggCardsMod = require('../scripts/lib/egg-cards.cjs');
  await assert.rejects(() => eggCardsMod.render('invalid', {}), /card_type_invalid/);
});

test('present: texto de ovos/incubacao permanece como fallback', () => {
  const status = present.formatAdventureStatus(dtoEggs, '!pk');
  assert.match(status, /Ovos e incubação/);
  assert.match(status, /especial/);
  assert.match(status, /aguardando espaço/);
  const help = present.formatIncubationHelp(dtoEggs, '!pk');
  assert.match(help, /40\/150/);
  assert.match(help, /Pontos de Incubação/);
});

test('render idempotente para o mesmo DTO', async () => {
  const a = await eggCards.render('eggs', dtoEggs);
  const b = await eggCards.render('eggs', dtoEggs);
  assert.deepEqual(a, b);
});
