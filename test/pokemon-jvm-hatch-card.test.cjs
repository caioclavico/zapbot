'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const eggCards = require('../scripts/lib/egg-cards.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('hatch reveal card PNG 640x820', async () => {
  const buf = await eggCards.renderHatchReveal({ speciesName: 'Bulbasaur', shiny: false });
  const meta = await sharp(buf).metadata();
  assert.equal(meta.format, 'png');
  assert.deepEqual([meta.width, meta.height], [640, 820]);
  assert.ok(buf.length < eggCards.MAX_PNG_BYTES);
});

test('formatEggHatch celebra nascimento', () => {
  const txt = present.formatEggHatch({
    registro: { nome: 'Bulbasaur', nivel: 1, shiny: false }
  }, '!pk');
  assert.match(txt, /SEU OVO CHOCOU/);
  assert.match(txt, /Bulbasaur/);
  assert.match(txt, /Nível: 1/);
});

test('media renderHatchRevealCard retorna base64 ou null', async () => {
  const out = await media.renderHatchRevealCard({
    registro: { nome: 'Bulbasaur', nivel: 1, shiny: false }
  });
  assert.ok(out === null || (out.base64 && out.mimeType === 'image/png'));
});
