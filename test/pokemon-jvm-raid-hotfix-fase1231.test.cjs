'use strict';

/**
 * Fase 12.3.1 — cartão de raide: Onix/Starmie sem caixa preta (alpha).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');
const sharp = require('sharp');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const OUT_DIR = path.join(__dirname, '..', 'tmp-raid-hotfix-1231');

async function samplePadColor(pngBuf, x, y) {
  const { data, info } = await sharp(pngBuf).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  const i = (y * info.width + x) * info.channels;
  return [data[i], data[i + 1], data[i + 2], data[i + 3]];
}

function isNearPurple(rgb, tol = 40) {
  // fundo do cartão #312e81 ≈ 49,46,129
  return Math.abs(rgb[0] - 49) < tol && Math.abs(rgb[1] - 46) < tol && Math.abs(rgb[2] - 129) < tol;
}

function isNearBlack(rgb, tol = 20) {
  return rgb[0] < tol && rgb[1] < tol && rgb[2] < tol;
}

test('renderRaidBattleCard Onix: padding transparente (sem caixa preta)', async () => {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const card = await media.renderRaidBattleCard({
    ginasio: '🪨 Pedra',
    chefe: {
      nome: 'Onix',
      species: 'onix',
      imagem: 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/official-artwork/95.png',
      hp: 91,
      hpAtual: 40
    },
    atacante: {
      nome: 'Pikachu',
      species: 'pikachu',
      imagem: 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/official-artwork/25.png',
      hp: 100,
      hpAtual: 80
    },
    treinador: 'CaioSmoke',
    hpChefe: 40,
    hpMax: 91,
    hpAtacante: 80,
    fase: 'combate',
    turnIndex: 2
  });
  assert.ok(card && card.base64, 'card PNG');
  assert.equal(card.mimeType, 'image/png');
  const buf = Buffer.from(card.base64, 'base64');
  const afterPath = path.join(OUT_DIR, 'onix-card-after.png');
  fs.writeFileSync(afterPath, buf);

  // Canto superior-esquerdo da área do chefe (~570-110, ~60) — padding do contain
  const pad = await samplePadColor(buf, 470, 70);
  assert.ok(
    isNearPurple(pad) || !isNearBlack(pad),
    `padding do Onix não deve ser preto opaco: got ${pad}`
  );
});

test('renderRaidBattleCard Starmie: outro chefe com alpha', async () => {
  const card = await media.renderRaidBattleCard({
    ginasio: '💧 Água',
    chefe: {
      nome: 'Starmie',
      species: 'starmie',
      imagem: 'https://cdn.jsdelivr.net/gh/PokeAPI/sprites@master/sprites/pokemon/other/official-artwork/121.png',
      hp: 80,
      hpAtual: 80
    },
    atacante: { nome: 'Quaxly', species: 'quaxly', hp: 65, hpAtual: 65 },
    treinador: 'PeerSmoke',
    hpChefe: 80,
    hpMax: 80,
    hpAtacante: 65,
    fase: 'combate',
    turnIndex: 0
  });
  assert.ok(card && card.base64);
  fs.mkdirSync(OUT_DIR, { recursive: true });
  fs.writeFileSync(path.join(OUT_DIR, 'starmie-card-after.png'), Buffer.from(card.base64, 'base64'));
});

test('sprite inválido: fallback placeholder sem throw', async () => {
  const card = await media.renderRaidBattleCard({
    ginasio: 'Teste',
    chefe: { nome: 'Broken', imagem: 'https://example.invalid/nope.png' },
    atacante: { nome: 'AshMon' },
    treinador: 'Ash',
    hpChefe: 10,
    hpMax: 10,
    hpAtacante: 10,
    fase: 'combate',
    turnIndex: 0
  });
  // Pode ser null (falha total) ou PNG com placeholder — nunca throw.
  assert.ok(card === null || (card && card.base64));
});
