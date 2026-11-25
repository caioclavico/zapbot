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

const pokeapi = require('../scripts/lib/pokemon-pokeapi.cjs');
const artwork = 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/1.png';

async function pixelSampler(card) {
  assert.equal(card.mimeType, 'image/png');
  const { data, info } = await sharp(Buffer.from(card.base64, 'base64'))
    .removeAlpha().raw().toBuffer({ resolveWithObject: true });
  return (x, y) => [...data.subarray((y * info.width + x) * 3, (y * info.width + x) * 3 + 3)];
}

test('hatch resolve arte ausente e desenha Pokémon atrás da borda aberta', async (t) => {
  const png = await sharp({
    create: { width: 120, height: 120, channels: 4, background: '#ff00ff' }
  }).png().toBuffer();
  const lookup = t.mock.method(pokeapi, 'fetchPokemonByName', async () => ({ imagem: artwork }));
  t.mock.method(globalThis, 'fetch', async () => new Response(png, {
    headers: { 'content-type': 'image/png' }
  }));
  const card = await media.renderHatchRevealCard({
    registro: { species: 'bulbasaur', nome: 'Bulbasaur', nivel: 1 }
  });
  assert.equal(lookup.mock.calls[0].arguments[0], 'bulbasaur');
  const pixel = await pixelSampler(card);
  assert.deepEqual(pixel(290, 480), [255, 0, 255], 'Pokémon acima da abertura');
  assert.deepEqual(pixel(290, 542), [247, 250, 251], 'borda da casca na frente do Pokémon');
  assert.deepEqual(pixel(320, 680), [247, 250, 251], 'fundo da casca abaixo do Pokémon');
  assert.notDeepEqual(pixel(150, 680), [247, 250, 251], 'base arredondada, mais estreita');
});

test('hatch com asset indisponível mantém silhueta em vez de entregar só ovo', async (t) => {
  t.mock.method(pokeapi, 'fetchPokemonByName', async () => ({ imagem: null }));
  const card = await media.renderHatchRevealCard({
    registro: { nome: 'Charmander', imagem: 'https://invalid.example/broken.png' }
  });
  const pixel = await pixelSampler(card);
  const [r, g, b] = pixel(240, 395);
  assert.ok(r > 60 && g > 60 && b > 150, 'silhueta visível acima da casca');
});
