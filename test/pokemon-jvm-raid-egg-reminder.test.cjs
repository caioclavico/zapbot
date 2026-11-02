'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { describe, it } = require('node:test');

const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const ASSETS = path.join(__dirname, '..', 'assets');

describe('raid egg reminder media (12.7.1)', () => {
  for (const minutes of [30, 15, 5]) {
    it(`asset raid-egg-${minutes}min.png exists and is small`, () => {
      const file = path.join(ASSETS, `raid-egg-${minutes}min.png`);
      assert.equal(fs.existsSync(file), true);
      const st = fs.statSync(file);
      assert.ok(st.size > 1000, 'png too small');
      assert.ok(st.size < 400_000, 'png too large for WA');
    });
  }

  it('isRaidReminderKind recognizes outbox kinds', () => {
    assert.equal(media.isRaidReminderKind('raid-reminder-30'), true);
    assert.equal(media.isRaidReminderKind('raid-reminder-15'), true);
    assert.equal(media.isRaidReminderKind('raid-reminder-5'), true);
    assert.equal(media.isRaidReminderKind('raid-opened'), false);
    assert.equal(media.isRaidOpenedKind('raid-opened'), true);
    assert.equal(media.isRaidOpenedKind('raid-reminder-30'), false);
  });

  it('renderRaidEggReminder returns png base64 without network', async () => {
    for (const kind of ['raid-reminder-30', 'raid-reminder-15', 'raid-reminder-5']) {
      const out = await media.renderRaidEggReminder(kind);
      assert.ok(out);
      assert.equal(out.mimeType, 'image/png');
      assert.match(out.filename, /raid-egg-\d+min\.png/);
      assert.ok(out.base64.length > 100);
      // sanity: PNG magic in decoded header
      const buf = Buffer.from(out.base64, 'base64');
      assert.equal(buf[0], 0x89);
      assert.equal(buf[1], 0x50);
    }
  });

  it('render by minutes also works', async () => {
    const out = await media.renderRaidEggReminder(15);
    assert.ok(out);
    assert.match(out.filename, /15min/);
  });

  it('renderRaidOpenedReveal shows boss (sprite or placeholder)', async () => {
    const out = await media.renderRaidOpenedReveal({
      nome: 'Mewtwo',
      species: 'mewtwo',
      categoria: 'lendario',
      ginasio: 'Ginásio Central',
      imagem:
        'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/150.png'
    });
    assert.ok(out);
    assert.equal(out.mimeType, 'image/png');
    assert.match(out.filename, /raid-revelada/);
    const buf = Buffer.from(out.base64, 'base64');
    assert.equal(buf[0], 0x89);
    assert.equal(buf[1], 0x50);
    assert.ok(buf.length > 5000);
  });
});
