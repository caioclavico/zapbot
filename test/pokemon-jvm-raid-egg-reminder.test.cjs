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

  it('resolveRevealTheme covers 18 types with distinct accents', () => {
    assert.equal(Object.keys(media.TYPE_REVEAL_THEMES).length, 18);
    const fire = media.resolveRevealTheme({ tipo: 'fire' });
    const water = media.resolveRevealTheme({ tipos: ['water'] });
    const psychic = media.resolveRevealTheme({ tipo: 'psychic' });
    assert.equal(fire.label, 'Fogo');
    assert.equal(water.label, 'Água');
    assert.equal(psychic.label, 'Psíquico');
    assert.notEqual(fire.sky[0], water.sky[0]);
  });

  it('renderRaidOpenedReveal themes by primary type', async () => {
    const samples = [
      { nome: 'Moltres', tipo: 'fire', ginasio: '🔥 Fogo', species: 'moltres',
        imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/146.png' },
      { nome: 'Kyogre', tipo: 'water', ginasio: '💧 Água', species: 'kyogre',
        imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/382.png' },
      { nome: 'Mewtwo', tipo: 'psychic', ginasio: '🔮 Psíquico', species: 'mewtwo',
        imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/150.png' }
    ];
    for (const s of samples) {
      const out = await media.renderRaidOpenedReveal({
        ...s, categoria: 'lendario'
      });
      assert.ok(out, s.nome);
      const buf = Buffer.from(out.base64, 'base64');
      assert.equal(buf[0], 0x89);
      assert.ok(buf.length > 5000, s.nome);
    }
  });
});
