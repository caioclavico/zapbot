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

test('caminhoSpritePermitido rejeita path fora do catálogo PokeAPI', () => {
  assert.equal(
    media.caminhoSpritePermitido('https://raw.githubusercontent.com/evil/repo/master/x.png'),
    false
  );
  assert.equal(
    media.caminhoSpritePermitido('https://cdn.jsdelivr.net/npm/lodash/package.json'),
    false
  );
  assert.equal(
    media.caminhoSpritePermitido('https://raw.githubusercontent.com/PokeAPI/sprites/master/../secrets'),
    false
  );
  assert.equal(
    media.hostPermitido('https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/25.png'),
    true
  );
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

test('formatoRasterMagic reconhece PNG/JPEG/WEBP e rejeita HEIF', () => {
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 0, 0, 0, 0, 0, 0, 0]);
  const webp = Buffer.alloc(12);
  webp.write('RIFF', 0); webp.write('WEBP', 8);
  const heif = Buffer.alloc(12);
  heif.writeUInt32BE(0, 0);
  heif.write('ftyp', 4);
  heif.write('heic', 8);
  assert.equal(media.formatoRasterMagic(png), 'png');
  assert.equal(media.formatoRasterMagic(jpeg), 'jpeg');
  assert.equal(media.formatoRasterMagic(webp), 'webp');
  assert.equal(media.formatoRasterMagic(heif), 'heif');
  assert.equal(media.formatoRasterMagic(Buffer.from('not-an-image!!')), null);
});

test('lerCorpoLimitado corta por streaming sem depender só de Content-Length', async () => {
  const big = Buffer.alloc(64, 7);
  let offset = 0;
  const body = {
    getReader() {
      return {
        async read() {
          if (offset >= big.length) return { done: true, value: undefined };
          const value = big.subarray(offset, offset + 16);
          offset += 16;
          return { done: false, value };
        },
        async cancel() { offset = big.length; }
      };
    }
  };
  const res = { body };
  await assert.rejects(() => media.lerCorpoLimitado(res, 32), /sprite_too_large/);
});

test('baixarBuffer rejeita host não allowlisted sem fetch', async () => {
  const out = await media.baixarBuffer('https://evil.example/payload.svg');
  assert.equal(out, null);
});

test('SVG interno (placeholder) continua rasterizável', async () => {
  const png = await require('sharp')(media.svgPlaceholder(64)).png().toBuffer();
  assert.ok(png.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
});
