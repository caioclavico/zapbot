'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const eggCards = require('../scripts/lib/egg-cards.cjs');

const dto = {
  eggs: [
    { eggId: 'e1', category: 'comum', state: 'inventory', points: 0, pointsGoal: 100 },
    { eggId: 'e2', category: 'raro', state: 'inventory', points: 0, pointsGoal: 250 }
  ],
  incubators: [
    { incubatorId: 'permanente', kind: 'permanente', uses: null, bonusPoints: 0, activeEggId: null, livre: true },
    { incubatorId: 'extra-abc', kind: 'extra', uses: 3, bonusPoints: 0, activeEggId: null, livre: true },
    { incubatorId: 'especial-xyz', kind: 'especial', uses: 2, bonusPoints: 25, activeEggId: 'busy', livre: false }
  ]
};

test('resolveIncubatorSelection: índices 1/2 e legado', () => {
  const a = present.resolveIncubatorSelection(dto, '1');
  assert.equal(a.incubatorId, 'permanente');
  assert.equal(a.index, 1);
  const b = present.resolveIncubatorSelection(dto, '2');
  assert.equal(b.incubatorId, 'extra-abc');
  assert.equal(b.index, 2);
  const legacy = present.resolveIncubatorSelection(dto, 'extra');
  assert.equal(legacy.incubatorId, 'extra-abc');
  const byId = present.resolveIncubatorSelection(dto, 'especial-xyz');
  assert.equal(byId.incubatorId, 'especial-xyz');
});

test('resolveIncubatorSelection: rejeita índice inválido', () => {
  assert.equal(present.resolveIncubatorSelection(dto, '9'), null);
  assert.equal(present.resolveIncubatorSelection(dto, '0'), null);
});

test('assertIncubatorUsable: ocupada e sem usos', () => {
  const busy = present.resolveIncubatorSelection(dto, '3');
  assert.equal(present.assertIncubatorUsable(busy).reason, 'busy');
  const spent = {
    incubator: { incubatorId: 'x', uses: 0, activeEggId: null, livre: true }
  };
  assert.equal(present.assertIncubatorUsable(spent).reason, 'no_uses');
  const ok = present.resolveIncubatorSelection(dto, '1');
  assert.equal(present.assertIncubatorUsable(ok).ok, true);
});

test('formatIncubators e card usam a mesma numeração', async () => {
  const text = present.formatIncubators(dto, '!pk');
  assert.match(text, /INCUBADORA 1/);
  assert.match(text, /INCUBADORA 2/);
  assert.match(text, /incubar <ovo> 2/);
  const buf = await eggCards.render('incubators', dto);
  const meta = await sharp(buf).metadata();
  assert.equal(meta.format, 'png');
  assert.deepEqual([meta.width, meta.height], [640, 820]);
  assert.ok(buf.length < eggCards.MAX_PNG_BYTES);
});

test('default incubar sem 2º arg prioriza permanente livre', () => {
  const sel = present.resolveIncubatorSelection(dto, '');
  assert.equal(sel.incubatorId, 'permanente');
});
