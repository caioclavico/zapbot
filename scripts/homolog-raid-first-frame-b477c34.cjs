'use strict';

/**
 * Homologação visual/textual do fix b477c34 (1º frame de raid).
 * Gera PNGs reais, analisa alpha/xadrez, compara frame1 vs frame2.
 * Sem deploy / rede obrigatória para fallbacks; com rede preferida para artwork.
 */

const fs = require('fs');
const path = require('path');
const assert = require('assert');
const sharp = require('sharp');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const OUT = path.join(__dirname, '..', 'docs', 'previews', 'raid-first-frame', 'homolog-b477c34');
fs.mkdirSync(OUT, { recursive: true });

const results = [];
function ok(id, detail) {
  results.push({ id, pass: true, detail });
  console.log(`PASS ${id}: ${detail}`);
}
function fail(id, detail) {
  results.push({ id, pass: false, detail });
  console.error(`FAIL ${id}: ${detail}`);
}

function fixture(overrides = {}) {
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
        { nomeExibicao: 'Water Gun', slug: 'water-gun', tipo: 'water', classe: 'especial', poder: 40 },
        { nomeExibicao: 'Bubble', slug: 'bubble', tipo: 'water', classe: 'especial', poder: 40 },
        { nomeExibicao: 'Withdraw', slug: 'withdraw', tipo: 'water', classe: 'status', poder: 0, alvo: 'proprio' },
        { nome: 'Bite', tipo: 'dark', poder: 60 }
      ]
    },
    ...overrides
  };
}

async function analyzePng(buf, label) {
  const meta = await sharp(buf).metadata();
  const { data, info } = await sharp(buf).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let opaque = 0;
  let transparent = 0;
  let checkerish = 0;
  let samples = 0;
  for (let i = 0; i < data.length; i += info.channels) {
    const a = data[i + 3];
    samples += 1;
    if (a < 8) {
      transparent += 1;
      continue;
    }
    opaque += 1;
    const r = data[i];
    const g = data[i + 1];
    const b = data[i + 2];
    const max = Math.max(r, g, b);
    const min = Math.min(r, g, b);
    if (max - min < 12 && r >= 220 && r <= 248 && g >= 220 && g <= 248) checkerish += 1;
  }
  const checkerFlag = await media.looksLikeCheckerboard(buf);
  return {
    label,
    width: meta.width,
    height: meta.height,
    hasAlpha: Boolean(meta.hasAlpha),
    channels: meta.channels,
    format: meta.format,
    opaque,
    transparent,
    checkerishRatio: opaque ? checkerish / opaque : 0,
    looksLikeCheckerboard: checkerFlag,
    bytes: buf.length
  };
}

async function assertCleanCard(buf, label) {
  const a = await analyzePng(buf, label);
  assert.equal(a.format, 'png', `${label} png`);
  assert.equal(a.looksLikeCheckerboard, false, `${label} detector xadrez`);
  assert.ok(a.checkerishRatio < 0.12, `${label} cinza-xadrez ${(a.checkerishRatio * 100).toFixed(1)}%`);
  return a;
}

(async () => {
  // --- 5/6 texto: pipeline unificado ---
  const raid = fixture();
  const manual = present.formatRaidStart({ raid }, '!pk');
  const auto = present.formatRaidStart({ raid, auto: true }, '!pk');
  const attack = present.formatRaidAttack({
    dano: 40,
    multiplicador: 2,
    golpe: { nomeExibicao: 'Water Gun', tipo: 'water', classe: 'especial', poder: 40 },
    pokemonAtacante: { nome: 'Wartortle' },
    raid: fixture({ turnIndex: 1, hpChefe: 400, vezHp: 100 })
  }, '!pk');

  fs.writeFileSync(path.join(OUT, 'msg-manual-start.txt'), manual);
  fs.writeFileSync(path.join(OUT, 'msg-auto-start.txt'), auto);
  fs.writeFileSync(path.join(OUT, 'msg-attack-frame2.txt'), attack);

  try {
    assert.match(manual, /Combate iniciado/);
    assert.match(manual, /Water Gun/);
    assert.match(manual, /💧/);
    assert.match(manual, /🔥/); // efetividade vs fogo
    assert.match(manual, /🔮|💥/);
    assert.match(manual, /Lendário/);
    assert.match(manual, /Incomum/);
    assert.match(manual, /█|░/);
    assert.match(manual, /raid atacar 1/);
    assert.doesNotMatch(manual, /causou \d+ de dano/); // pré-ataque
    ok('texto-manual', 'golpes+emoji+efetividade+raridade+HP; sem narrativa de dano');
  } catch (e) {
    fail('texto-manual', e.message);
  }

  try {
    assert.match(auto, /RAID INICIADA AUTOMATICAMENTE/);
    assert.match(auto, /Water Gun/);
    assert.match(auto, /💧/);
    assert.match(auto, /Lendário/);
    assert.match(auto, /Incomum/);
    // Mesmo bloco de turno que o manual (menu).
    assert.match(auto, /raid atacar 1/);
    ok('texto-auto', 'mesmo formatRaidTurnBlock do manual');
  } catch (e) {
    fail('texto-auto', e.message);
  }

  try {
    assert.match(attack, /causou 40 de dano|Water Gun/);
    assert.match(attack, /Super efetivo!🔥/);
    assert.match(attack, /raid atacar 1/); // menu pós-ataque
    ok('texto-frame2', 'narrativa de ataque + mesmo menu de turno');
  } catch (e) {
    fail('texto-frame2', e.message);
  }

  // Pipeline: start e attack compartilham formatRaidTurnBlock (menu).
  try {
    const block = present.formatRaidTurnBlock(raid, '!pk').join('\n');
    assert.ok(manual.includes(block.split('\n')[0]));
    assert.ok(attack.includes('Vez de'));
    ok('pipeline', 'formatRaidStart/Attack → formatRaidTurnBlock compartilhado');
  } catch (e) {
    fail('pipeline', e.message);
  }

  // --- 1/6–7 cache frio / aquecido + PNGs reais ---
  // Limpa cache interno via miss URL + depois hit real.
  const coldMiss = await media.spriteProporcional(
    { nome: 'X', species: 'x', imagem: 'http://127.0.0.1:9/cold.png' },
    96
  );
  try {
    assert.equal(coldMiss.placeholder, true);
    assert.equal(coldMiss.source, 'silhouette_fallback');
    assert.equal(await media.looksLikeCheckerboard(coldMiss.buffer), false);
    fs.writeFileSync(path.join(OUT, 'fallback-cold-miss.png'), coldMiss.buffer);
    ok('cache-frio-miss', 'silhueta sem xadrez');
  } catch (e) {
    fail('cache-frio-miss', e.message);
  }

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
    renderPhase: 'homolog-frame1'
  });
  assert.ok(card1 && card1.base64, 'card1 gerado');
  const buf1 = Buffer.from(card1.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame1-manual.png'), buf1);

  const card1b = await media.renderRaidBattleCard({
    ginasio: raid.nomeGinasio,
    chefe: raid.chefe,
    atacante: raid.vezPokemon,
    treinador: raid.vezNome,
    hpChefe: raid.hpChefe,
    hpMax: raid.hpMax,
    hpAtacante: raid.vezHp,
    fase: 'combate',
    turnIndex: 0,
    renderPhase: 'homolog-frame1-warm'
  });
  const buf1w = Buffer.from(card1b.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame1-manual-warm-cache.png'), buf1w);

  const card2 = await media.renderRaidBattleCard({
    ginasio: raid.nomeGinasio,
    chefe: raid.chefe,
    atacante: raid.vezPokemon,
    treinador: 'Misty',
    hpChefe: 400,
    hpMax: 440,
    hpAtacante: 100,
    fase: 'combate',
    turnIndex: 1,
    renderPhase: 'homolog-frame2'
  });
  const buf2 = Buffer.from(card2.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame2-attack.png'), buf2);

  // Auto start card (mesmo render)
  const cardAuto = await media.renderRaidBattleCard({
    ginasio: raid.nomeGinasio,
    chefe: raid.chefe,
    atacante: raid.vezPokemon,
    treinador: raid.vezNome,
    hpChefe: raid.hpChefe,
    hpMax: raid.hpMax,
    hpAtacante: raid.vezHp,
    fase: 'combate',
    turnIndex: 0,
    renderPhase: 'homolog-auto-start'
  });
  const bufAuto = Buffer.from(cardAuto.base64, 'base64');
  fs.writeFileSync(path.join(OUT, 'frame1-auto.png'), bufAuto);

  let a1;
  let a2;
  let aW;
  let aAuto;
  try {
    a1 = await assertCleanCard(buf1, 'frame1');
    aW = await assertCleanCard(buf1w, 'frame1-warm');
    a2 = await assertCleanCard(buf2, 'frame2');
    aAuto = await assertCleanCard(bufAuto, 'frame1-auto');
    ok('sem-xadrez', `frame1/2/auto/warm limpos (checkerish≤${(Math.max(a1.checkerishRatio, a2.checkerishRatio) * 100).toFixed(1)}%)`);
  } catch (e) {
    fail('sem-xadrez', e.message);
  }

  try {
    assert.equal(card1.meta && card1.meta.chefePlaceholder, false, 'chefe real');
    assert.equal(card1.meta && card1.meta.atkPlaceholder, false, 'atacante real');
    assert.equal(card1.meta.chefeSource, 'url');
    assert.equal(card1.meta.atkSource, 'url');
    ok('sprites-reais', `sources=${card1.meta.chefeSource}/${card1.meta.atkSource}`);
  } catch (e) {
    fail('sprites-reais', `${e.message} meta=${JSON.stringify(card1.meta)}`);
  }

  // Comparar qualidade frame1 vs frame2 (dimensões / alpha do card composto)
  try {
    assert.equal(a1.width, a2.width);
    assert.equal(a1.height, a2.height);
    assert.equal(a1.hasAlpha, a2.hasAlpha);
    // Cards têm fundo opaco da arena; ambos PNG.
    assert.equal(a1.format, 'png');
    assert.equal(a2.format, 'png');
    ok('paridade-visual', `ambos ${a1.width}x${a1.height} png alpha=${a1.hasAlpha}`);
  } catch (e) {
    fail('paridade-visual', e.message);
  }

  // Sprites individuais (transparência real do artwork)
  const bossSp = await media.spriteProporcional(raid.chefe, 220, 'homolog-boss');
  const atkSp = await media.spriteProporcional(raid.vezPokemon, 220, 'homolog-atk');
  fs.writeFileSync(path.join(OUT, 'sprite-chefe-magmar.png'), bossSp.buffer);
  fs.writeFileSync(path.join(OUT, 'sprite-atacante-wartortle.png'), atkSp.buffer);
  try {
    assert.equal(bossSp.placeholder, false);
    assert.equal(atkSp.placeholder, false);
    assert.equal(bossSp.hasAlpha, true);
    assert.equal(atkSp.hasAlpha, true);
    assert.equal(await media.looksLikeCheckerboard(bossSp.buffer), false);
    assert.equal(await media.looksLikeCheckerboard(atkSp.buffer), false);
    const ba = await analyzePng(bossSp.buffer, 'boss-sprite');
    const aa = await analyzePng(atkSp.buffer, 'atk-sprite');
    assert.ok(ba.transparent > 100, 'boss tem padding transparente');
    assert.ok(aa.transparent > 100, 'atk tem padding transparente');
    ok('alpha-sprites', `boss t=${ba.transparent} atk t=${aa.transparent} hasAlpha=true`);
  } catch (e) {
    fail('alpha-sprites', e.message);
  }

  // Forma alternativa
  try {
    const deoxys = await media.ensurePokemonSprite({ species: 'deoxys-attack', nome: 'Deoxys' });
    const sp = await media.spriteProporcional(
      deoxys.imagem ? deoxys : {
        ...deoxys,
        imagem: 'https://raw.githubusercontent.com/PokeAPI/sprites/master/sprites/pokemon/other/official-artwork/10001.png'
      },
      160,
      'homolog-deoxys'
    );
    fs.writeFileSync(path.join(OUT, 'sprite-deoxys-attack.png'), sp.buffer);
    assert.equal(await media.looksLikeCheckerboard(sp.buffer), false);
    ok('forma-alt', sp.placeholder ? 'fallback silhueta OK' : `artwork source=${sp.source}`);
  } catch (e) {
    fail('forma-alt', e.message);
  }

  // Artwork indisponível
  try {
    const miss = await media.spriteProporcional(
      { nome: 'MissingNo', species: 'missingno', imagem: 'https://example.invalid/nope.png' },
      120
    );
    fs.writeFileSync(path.join(OUT, 'fallback-artwork-indisponivel.png'), miss.buffer);
    assert.equal(miss.placeholder, true);
    assert.equal(await media.looksLikeCheckerboard(miss.buffer), false);
    ok('fallback-indisponivel', 'silhueta determinística');
  } catch (e) {
    fail('fallback-indisponivel', e.message);
  }

  // Detector rejeita xadrez clássico
  try {
    const checkerSvg = Buffer.from(
      `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64">`
      + `<defs><pattern id="p" width="16" height="16" patternUnits="userSpaceOnUse">`
      + `<rect width="8" height="8" fill="#e5e7eb"/><rect x="8" y="8" width="8" height="8" fill="#e5e7eb"/>`
      + `<rect x="8" width="8" height="8" fill="#f3f4f6"/><rect y="8" width="8" height="8" fill="#f3f4f6"/>`
      + `</pattern></defs><rect width="64" height="64" fill="url(#p)"/></svg>`
    );
    const checker = await sharp(checkerSvg).png().toBuffer();
    fs.writeFileSync(path.join(OUT, 'control-checkerboard.png'), checker);
    assert.equal(await media.looksLikeCheckerboard(checker), true);
    ok('detector-xadrez', 'controle positivo');
  } catch (e) {
    fail('detector-xadrez', e.message);
  }

  // Relatório JSON
  const report = {
    commit: 'b477c34',
    at: new Date().toISOString(),
    outDir: OUT,
    results,
    analyses: { frame1: a1, frame1Warm: aW, frame2: a2, frame1Auto: aAuto },
    cardMeta: {
      frame1: card1.meta,
      frame2: card2.meta,
      auto: cardAuto.meta
    },
    summary: {
      pass: results.filter((r) => r.pass).length,
      fail: results.filter((r) => !r.pass).length,
      verdict: results.every((r) => r.pass) ? 'PASS' : 'FAIL'
    }
  };
  fs.writeFileSync(path.join(OUT, 'REPORT.json'), JSON.stringify(report, null, 2));
  console.log('\n=== HOMOLOG SUMMARY ===');
  console.log(JSON.stringify(report.summary, null, 2));
  process.exit(report.summary.fail ? 1 : 0);
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
