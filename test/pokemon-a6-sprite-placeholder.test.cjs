'use strict';
const assert = require('assert');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

// svgPlaceholder is not exported — exercise via spriteProporcional with bad URL
(async () => {
  const r = await media.spriteProporcional({ imagem: 'http://127.0.0.1:9/nope.png' }, 64);
  assert.ok(r.placeholder || r.buffer);
  assert.ok(Buffer.isBuffer(r.buffer));
  // PNG signature
  assert.equal(r.buffer[0], 0x89);
  // Should not be an all-black tiny jpeg; we emit PNG with checker
  const sharp = require('sharp');
  const { data, info } = await sharp(r.buffer).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let nonBlack = 0;
  for (let i = 0; i < data.length; i += info.channels) {
    if (data[i] > 20 || data[i + 1] > 20 || data[i + 2] > 20) nonBlack++;
  }
  assert.ok(nonBlack > 10, 'placeholder should not be solid black box');
  console.log('pokemon-a6-sprite-placeholder.test.cjs OK', { nonBlack, size: r.buffer.length });
})().catch((e) => { console.error(e); process.exit(1); });
