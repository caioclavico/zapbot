'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

test('resolveEggAutoHatchDelivery: save mismatch defers', () => {
  const plan = present.resolveEggAutoHatchDelivery({
    event: { eggId: 'e1', hatchId: 'h1', saveId: 'a', playerId: 'ash' },
    activeSave: { activeSaveId: 'b', fenceGeneration: 3 },
    adventure: {
      eggs: [{ eggId: 'e1', state: 'ready-to-hatch', saveId: 'a' }]
    }
  });
  assert.equal(plan.ok, false);
  assert.equal(plan.defer, true);
  assert.equal(plan.reason, 'save_mismatch');
  assert.equal(plan.sealedSaveId, 'a');
  assert.equal(plan.activeSaveId, 'b');
});

test('resolveEggAutoHatchDelivery: matching save hatches on sealed id', () => {
  const plan = present.resolveEggAutoHatchDelivery({
    event: { eggId: 'e1', hatchId: 'h1', saveId: 'a', playerId: 'ash' },
    activeSave: { activeSaveId: 'a', fenceGeneration: 4 },
    adventure: {
      eggs: [{ eggId: 'e1', state: 'ready-to-hatch', saveId: 'a' }]
    }
  });
  assert.equal(plan.ok, true);
  assert.equal(plan.alreadyHatched, false);
  assert.equal(plan.saveId, 'a');
  assert.equal(plan.fenceGeneration, 4);
});

test('resolveEggAutoHatchDelivery: already hatched → silent done', () => {
  const plan = present.resolveEggAutoHatchDelivery({
    event: { eggId: 'e1', saveId: 'a' },
    activeSave: { activeSaveId: 'b', fenceGeneration: 9 },
    adventure: {
      eggs: [{ eggId: 'e1', state: 'hatched', saveId: 'a' }]
    }
  });
  assert.equal(plan.ok, true);
  assert.equal(plan.alreadyHatched, true);
});

test('formatEggHatch pending: sem SEU OVO CHOCOU / sem adicionado', () => {
  const txt = present.formatEggHatch({ collectionFull: true, pending: true }, '!pk');
  assert.match(txt, /pendente/i);
  assert.doesNotMatch(txt, /SEU OVO CHOCOU/);
  assert.doesNotMatch(txt, /Adicionado à sua coleção/);
});

test('shouldRenderHatchReveal: false quando pending', () => {
  assert.equal(present.shouldRenderHatchReveal({ pending: true }), false);
  assert.equal(present.shouldRenderHatchReveal({ collectionFull: true }), false);
  assert.equal(present.shouldRenderHatchReveal({ registro: { nome: 'X' } }), true);
});

test('renderHatchRevealCard: null quando collection full', async () => {
  const out = await media.renderHatchRevealCard({
    collectionFull: true,
    pending: true,
    registro: { nome: 'Bulbasaur', species: 'bulbasaur' }
  });
  assert.equal(out, null);
});
