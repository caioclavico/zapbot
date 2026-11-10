'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

test('hostPermitido: apenas hosts de sprite allowlisted via https', () => {
  assert.equal(
    media.hostPermitido('https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png'),
    true
  );
  assert.equal(
    media.hostPermitido('https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/25.png'),
    true
  );
  assert.equal(media.hostPermitido('http://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/x.png'), false);
  assert.equal(media.hostPermitido('https://evil.example/sprite.png'), false);
  assert.equal(media.hostPermitido('https://127.0.0.1/sprite.png'), false);
});

test('candidatosUrl descarta URL fora do allowlist', () => {
  assert.deepEqual(media.candidatosUrl('https://evil.example/a.png'), []);
  const raw = 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png';
  assert.deepEqual(media.candidatosUrl(raw), [
    'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/25.png',
    raw
  ]);
});

test('pareceSvgOuXml detecta SVG/XML e poupa PNG', () => {
  assert.equal(media.pareceSvgOuXml(Buffer.from('<svg xmlns="http://www.w3.org/2000/svg"></svg>')), true);
  assert.equal(media.pareceSvgOuXml(Buffer.from('<?xml version="1.0"?><svg></svg>')), true);
  assert.equal(media.pareceSvgOuXml(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])), false);
});

test('baixarBuffer rejeita host não allowlisted sem fetch', async () => {
  const out = await media.baixarBuffer('https://evil.example/payload.svg');
  assert.equal(out, null);
});

test('SVG interno (placeholder) continua rasterizável', async () => {
  const png = await require('sharp')(media.svgPlaceholder(64)).png().toBuffer();
  assert.ok(png.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
});
