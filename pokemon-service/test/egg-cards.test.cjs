'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const { render, CARD_WIDTH, CARD_HEIGHT, MAX_PNG_BYTES } = require('../runtime/egg-cards.cjs');

const egg = (category, state = 'inventory', points = 0, goal = 100) => ({
  category,
  state,
  points,
  'points-goal': goal,
  pointsGoal: goal
});

test('renders empty, full and progress egg cards as bounded PNGs', async () => {
  for (const data of [
    { eggs: [] },
    {
      eggs: Array.from({ length: 9 }, (_, i) => egg(
        ['comum', 'raro', 'epico'][i % 3],
        i === 0 ? 'incubating' : 'inventory',
        i * 10
      ))
    }
  ]) {
    const out = await render('eggs', data);
    const meta = await sharp(out).metadata();
    assert.equal(meta.format, 'png');
    assert.deepEqual([meta.width, meta.height], [CARD_WIDTH, CARD_HEIGHT]);
    assert.ok(out.length < MAX_PNG_BYTES);
  }
});

test('renders capsule states and does not invent unavailable slots', async () => {
  const out = await render('incubators', {
    incubators: [
      { kind: 'permanente', uses: null },
      { kind: 'extra', uses: 0 },
      { kind: 'especial', uses: 3, bonusPoints: 25 }
    ]
  });
  const meta = await sharp(out).metadata();
  assert.equal(meta.format, 'png');
  assert.ok(out.length < MAX_PNG_BYTES);
});

test('renders especial egg and permanente/especial incubators', async () => {
  const eggs = await render('eggs', {
    eggs: [{ category: 'especial', state: 'incubating', points: 200, pointsGoal: 800 }]
  });
  const inc = await render('incubators', {
    eggs: [{ eggId: 'x', category: 'especial', state: 'incubating', points: 200, pointsGoal: 800 }],
    incubators: [
      { kind: 'permanente', uses: null },
      { kind: 'especial', uses: 2, bonusPoints: 25, activeEggId: 'x' }
    ]
  });
  assert.ok((await sharp(eggs).metadata()).width === 640);
  assert.ok((await sharp(inc).metadata()).width === 640);
});
