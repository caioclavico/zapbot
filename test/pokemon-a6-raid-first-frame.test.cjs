'use strict';

/**
 * P1 — 1º frame de raid: sem quadriculado, emojis/golpes alinhados ao 2º frame.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const OUT = path.join(__dirname, '..', 'docs', 'previews', 'raid-first-frame');
fs.mkdirSync(OUT, { recursive: true });

function raidCombatFixture(overrides = {}) {
  return {
    fase: 'combate',
    nomeGinasio: '🔥 Fogo',
    ginasio: 'fogo',
    hpChefe: 440,
    hpMax: 440,
    vez: 'ash@lid',
    vezNome: 'Ash',
    vezHp: 120,
    suaVez: true,
    turnIndex: 0,
    chefe: {
      nome: 'Magmar',
      species: 'magmar',
      raridade: 'lendario',
      tipos: ['fire'],
      hp: 440,
      imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/126.png'
    },
    vezPokemon: {
      nome: 'Wartortle',
      species: 'wartortle',
      raridade: 'incomum',
      tipos: ['water'],
      hp: 150,
      hpAtual: 120,
      imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/8.png',
      golpes: [
        {
          nomeExibicao: 'Water Gun',
          slug: 'water-gun',
          tipo: 'water',
          classe: 'especial',
          poder: 40
        },
        {
          nomeExibicao: 'Bubble',
          slug: 'bubble',
          tipo: 'water',
          classe: 'especial',
          poder: 40
        },
        {
          nomeExibicao: 'Withdraw',
          slug: 'withdraw',
          tipo: 'water',
          classe: 'status',
          poder: 0,
          alvo: 'proprio'
        },
        {
          // Legado Wartortle: só nome/poder (sem classe) — deve virar ofensivo.
          nome: 'Bite',
          tipo: 'dark',
          poder: 60
        }
      ]
    },
    ...overrides
  };
}

async function assertNoCheckerboard(pngBuf, label) {
  assert.ok(Buffer.isBuffer(pngBuf), `${label}: buffer`);
  const flagged = await media.looksLikeCheckerboard(pngBuf);
  assert.equal(flagged, false, `${label}: não deve ter xadrez embutido`);
  const { data, info } = await sharp(pngBuf).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  // Conta pixels próximos ao cinza do antigo placeholder (#e5e7eb / #f3f4f6).
  let checkerish = 0;
  let total = 0;
  for (let i = 0; i < data.length; i += info.channels) {
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const a = data[i + 3];
    if (a < 8) continue;
    total += 1;
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    if (max - min < 12 && r >= 220 && r <= 248 && g >= 220 && g <= 248) checkerish += 1;
  }
  if (total > 100) {
    assert.ok(checkerish / total < 0.25, `${label}: excesso de cinza-xadrez (${checkerish}/${total})`);
  }
}

test('1) formatRaidStart manual: golpes + tipo emoji + efetividade + raridade + HP', () => {
  const raid = raidCombatFixture();
  const text = present.formatRaidStart({ raid }, '!pk');
  fs.writeFileSync(path.join(OUT, '01-manual-start.txt'), text);
  assert.match(text, /Combate iniciado/);
  assert.match(text, /Magmar/);
  assert.match(text, /Wartortle/);
  assert.match(text, /Lendário/);
  assert.match(text, /Incomum/);
  assert.match(text, /Water Gun/);
  assert.match(text, /💧/); // tipo água
  assert.match(text, /🔥/); // super efetivo vs fogo
  assert.match(text, /Bite/); // legado sem classe
  assert.match(text, /raid atacar 1/);
  assert.match(text, /█|░/); // barra ASCII
  assert.doesNotMatch(text, /RAID INICIADA AUTOMATICAMENTE/);
});

test('2) formatRaidStart automático: mesmo bloco de turno', () => {
  const raid = raidCombatFixture({ auto: true });
  const text = present.formatRaidStart({ raid, auto: true }, '!pk');
  fs.writeFileSync(path.join(OUT, '02-auto-start.txt'), text);
  assert.match(text, /RAID INICIADA AUTOMATICAMENTE/);
  assert.match(text, /Water Gun/);
  assert.match(text, /💧/);
  assert.match(text, /Vez de \*Ash\*/);
  assert.match(text, /É a sua vez/);
});

test('3) segundo frame (attack) reusa formatRaidTurnBlock', () => {
  const raid = raidCombatFixture({ turnIndex: 1, hpChefe: 400 });
  const start = present.formatRaidStart({ raid: raidCombatFixture() }, '!pk');
  const atk = present.formatRaidAttack({
    dano: 40,
    multiplicador: 2,
    golpe: {
      nomeExibicao: 'Water Gun',
      tipo: 'water',
      classe: 'especial',
      poder: 40
    },
    pokemonAtacante: { nome: 'Wartortle' },
    raid
  }, '!pk');
  fs.writeFileSync(path.join(OUT, '03-second-frame.txt'), atk);
  assert.match(atk, /Water Gun/);
  assert.match(atk, /Super efetivo!🔥/);
  assert.match(atk, /Vez de/);
  // Bloco de menu presente nos dois.
  assert.match(start, /raid atacar 1/);
  assert.match(atk, /raid atacar 1/);
});

test('4–5) silhueta fallback sem xadrez; detector rejeita xadrez embutido', async () => {
  const sil = await sharp(media.svgPlaceholder(96)).png().toBuffer();
  await assertNoCheckerboard(sil, 'silhouette');
  assert.equal(await media.looksLikeCheckerboard(sil), false);

  // Xadrez clássico (como o antigo placeholder).
  const checkerSvg = Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">`
    + `<defs><pattern id="p" width="16" height="16" patternUnits="userSpaceOnUse">`
    + `<rect width="8" height="8" fill="#e5e7eb"/><rect x="8" y="8" width="8" height="8" fill="#e5e7eb"/>`
    + `<rect x="8" width="8" height="8" fill="#f3f4f6"/><rect y="8" width="8" height="8" fill="#f3f4f6"/>`
    + `</pattern></defs><rect width="64" height="64" fill="url(#p)"/></svg>`
  );
  const checker = await sharp(checkerSvg).png().toBuffer();
  assert.equal(await media.looksLikeCheckerboard(checker), true);
  fs.writeFileSync(path.join(OUT, '05-checker-rejected.png'), checker);
  fs.writeFileSync(path.join(OUT, '04-silhouette-ok.png'), sil);
});

test('6–7) cache frio/quente: spriteProporcional placeholder flag estável', async () => {
  const cold = await media.spriteProporcional({ imagem: 'http://127.0.0.1:9/nope.png' }, 64);
  assert.equal(cold.placeholder, true);
  assert.equal(cold.source, 'silhouette_fallback');
  await assertNoCheckerboard(cold.buffer, 'cold-fallback');
  const warm = await media.spriteProporcional({ imagem: 'http://127.0.0.1:9/nope.png' }, 64);
  assert.equal(warm.placeholder, true);
  await assertNoCheckerboard(warm.buffer, 'warm-fallback');
});

test('8–9) artwork ausente / forma alternativa → silhueta determinística', async () => {
  const missing = await media.spriteProporcional({ species: '', nome: '' }, 80);
  assert.equal(missing.placeholder, true);
  const form = await media.ensurePokemonSprite({
    species: 'deoxys-attack',
    nome: 'Deoxys'
  });
  // Sem rede pode não hidratar; se hidratar, URL oficial; senão mantém species.
  assert.ok(form.species === 'deoxys-attack' || form.imagem);
});

test('10–12) golpe ofensivo, status e legado Wartortle', () => {
  const lines = present.formatRaidMoveLines(raidCombatFixture().vezPokemon, '!pk', ['fire', 'flying']);
  const joined = lines.join('\n');
  fs.writeFileSync(path.join(OUT, '10-12-moves.txt'), joined);
  assert.match(joined, /Water Gun/);
  assert.match(joined, /poder 40/);
  assert.match(joined, /Withdraw/);
  assert.match(joined, /indisponível na raid/);
  assert.match(joined, /Bite/);
  assert.match(joined, /poder 60/);
  assert.match(joined, /🌑|💧/);
});

test('13–15) super / pouco / imunidade nas marcas', () => {
  const waterVsFire = present.formatRaidMoveLines(
    { golpes: [{ nomeExibicao: 'Surf', tipo: 'water', classe: 'especial', poder: 90 }] },
    '!pk',
    ['fire']
  ).join('\n');
  assert.match(waterVsFire, /🔥/);

  const waterVsWater = present.formatRaidMoveLines(
    { golpes: [{ nomeExibicao: 'Surf', tipo: 'water', classe: 'especial', poder: 90 }] },
    '!pk',
    ['water']
  ).join('\n');
  assert.match(waterVsWater, /😕/);

  const normalVsGhost = present.formatRaidMoveLines(
    { golpes: [{ nomeExibicao: 'Tackle', tipo: 'normal', classe: 'fisico', poder: 40 }] },
    '!pk',
    ['ghost']
  ).join('\n');
  assert.match(normalVsGhost, /🚫/);
});

test('16) emojis de tipo e efetividade no 1º frame', () => {
  const text = present.formatRaidStart({ raid: raidCombatFixture() }, '!pk');
  assert.match(text, /💧/);
  assert.match(text, /🔥|😕|🚫/);
  assert.match(text, /💥|🔮|📊/);
});

test('17–18) raridade ambos + barras HP', () => {
  const block = present.formatRaidTurnBlock(raidCombatFixture(), '!pk').join('\n');
  assert.match(block, /Magmar/);
  assert.match(block, /Wartortle/);
  assert.match(block, /Lendário/);
  assert.match(block, /Incomum/);
  assert.match(block, /█|░/);
});

test('19) restart lobby→combate: fase combate exige vez', () => {
  assert.deepEqual(present.formatRaidTurnBlock({ fase: 'inscricoes', vez: 'x' }, '!pk'), []);
  const ok = present.formatRaidTurnBlock(raidCombatFixture(), '!pk');
  assert.ok(ok.length > 3);
});

test('20) falha temporária de download → fallback sem xadrez', async () => {
  const r = await media.spriteProporcional(
    { nome: 'Pikachu', species: 'pikachu', imagem: 'http://127.0.0.1:9/fail.png' },
    96
  );
  assert.equal(r.placeholder, true);
  await assertNoCheckerboard(r.buffer, 'download-fail');
  fs.writeFileSync(path.join(OUT, '20-download-fail-fallback.png'), r.buffer);
});

test('renderRaidBattleCard 1º vs 2º frame — sem xadrez na composição', async () => {
  const raid = raidCombatFixture();
  const card1 = await media.renderRaidBattleCard({
    ginasio: raid.nomeGinasio,
    chefe: raid.chefe,
    atacante: raid.vezPokemon,
    treinador: raid.vezNome,
    hpChefe: raid.hpChefe,
    hpMax: raid.hpMax,
    hpAtacante: raid.vezHp,
    fase: 'combate',
    turnIndex: 0,
    renderPhase: 'raid-start-manual'
  });
  if (!card1) {
    console.warn('card1 null (rede/offline) — skip visual assert');
    return;
  }
  const buf1 = Buffer.from(card1.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame1-raid-batalha.png'), buf1);
  await assertNoCheckerboard(buf1, 'frame1');

  const card2 = await media.renderRaidBattleCard({
    ginasio: raid.nomeGinasio,
    chefe: raid.chefe,
    atacante: raid.vezPokemon,
    treinador: raid.vezNome,
    hpChefe: 400,
    hpMax: raid.hpMax,
    hpAtacante: 100,
    fase: 'combate',
    turnIndex: 1,
    renderPhase: 'raid-attack'
  });
  if (!card2) return;
  const buf2 = Buffer.from(card2.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame2-raid-batalha.png'), buf2);
  await assertNoCheckerboard(buf2, 'frame2');
});
