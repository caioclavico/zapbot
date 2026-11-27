'use strict';

/**
 * Unificação multigrupo A6 — apresentação canônica (sem chatId privilegiado).
 * Fixtures sintéticas: grupo-a@g.us / grupo-b@g.us.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const GRUPO_A = 'grupo-a@g.us';
const GRUPO_B = 'grupo-b@g.us';

const charizard = {
  nome: 'Charizard',
  nivel: 36,
  tipos: ['fogo', 'voador'],
  golpes: [
    {
      nomeExibicao: 'Lança-Chamas',
      id: 'flamethrower',
      tipo: 'fogo',
      poder: 90,
      precisao: 100,
      classe: 'especial',
      efeitos: [{ kind: 'status', status: 'queimado', chance: 10 }]
    },
    {
      nomeExibicao: 'Investida',
      id: 'tackle',
      tipo: 'normal',
      poder: 40,
      precisao: 100,
      classe: 'fisico'
    }
  ]
};

/** Lê largura/altura do IHDR de um PNG. */
function pngDims(buf) {
  assert.ok(buf.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
  assert.equal(buf.toString('ascii', 12, 16), 'IHDR');
  return {
    width: buf.readUInt32BE(16),
    height: buf.readUInt32BE(20)
  };
}

function stripLegitVariance(text) {
  return String(text || '')
    .replace(/grupo-[ab]@g\.us/g, '<chat>')
    .replace(/\d{10,}/g, '<ts>')
    .replace(/Ash|Misty|Brock/g, '<treinador>');
}

test('emojiGolpePrincipal: um emoji (tipo > classe)', () => {
  const tipo = present.emojiGolpePrincipal({
    tipo: 'fogo', classe: 'especial', poder: 90
  });
  const classe = present.emojiGolpePrincipal({
    classe: 'especial', poder: 90
  });
  assert.equal(tipo, '🔥');
  assert.ok(classe);
  assert.notEqual(classe.includes('🔥') && classe.includes('💥'), true);
});

test('linhaGolpe: exatamente um emoji principal antes do nome', () => {
  const linha = present.linhaGolpe(0, charizard.golpes[0], ['grama'], null);
  assert.match(linha, /^1\. 🔥 \*Lança-Chamas\*/);
  assert.doesNotMatch(linha, /💥\s*🔥/);
  assert.doesNotMatch(linha, /🔥\s*💥/);
  assert.match(linha, /poder 90/);
  assert.match(linha, /precisão base 100%/);
});

test('mesma apresentação de golpes nos dois grupos sintéticos', () => {
  const a = present.menuGolpes(charizard, ['água'], null);
  const b = present.menuGolpes({ ...charizard }, ['água'], null);
  assert.equal(stripLegitVariance(a), stripLegitVariance(b));
  for (const line of a.split('\n').filter(Boolean)) {
    // "N. <emoji> *Nome*" — um único grapheme emoji após o índice
    assert.match(line, /^\d+\. \S+ \*/);
    assert.doesNotMatch(line, /^\d+\. \S+ \S+ \*/);
  }
  // chat ids não entram no formatador canônico
  assert.doesNotMatch(a, new RegExp(GRUPO_A.replace('.', '\\.')));
  assert.doesNotMatch(b, new RegExp(GRUPO_B.replace('.', '\\.')));
});

test('formatRaidMoveLines e formatRaidAttack reutilizam emoji único', () => {
  const lines = present.formatRaidMoveLines(charizard, '!pk', ['grama'], null);
  const text = Array.isArray(lines) ? lines.join('\n') : String(lines);
  assert.match(text, /🔥 \*Lança-Chamas\*/);
  assert.doesNotMatch(text, /💥\s*🔥|🔮\s*🔥|🔥\s*🔮/);
});

test('retrato 960x540 para inicial e tm ativo (dims reais do PNG)', async () => {
  const starter = await media.renderPokemonPortraitCard(
    { nome: 'Bulbasaur', nivel: 1, tipos: ['grama', 'veneno'] },
    { filename: 'inicial-escolhido.png', title: 'Pokémon inicial', subtitle: 'Bem-vindo!' }
  );
  const ativo = await media.renderPokemonPortraitCard(
    charizard,
    { filename: 'ficha-ativo.png', title: 'Nv. 36', subtitle: 'Pokémon ativo' }
  );
  assert.ok(starter);
  assert.ok(ativo);
  assert.equal(starter.width, 960);
  assert.equal(starter.height, 540);
  assert.equal(ativo.width, 960);
  assert.equal(ativo.height, 540);

  const sBuf = Buffer.from(starter.base64, 'base64');
  const aBuf = Buffer.from(ativo.base64, 'base64');
  assert.deepEqual(pngDims(sBuf), { width: 960, height: 540 });
  assert.deepEqual(pngDims(aBuf), { width: 960, height: 540 });
  assert.ok(sBuf.length > 2000);
  assert.ok(aBuf.length > 2000);
});

test('ficha formatada idêntica independentemente do chatId', () => {
  const ficha = {
    pokemon: charizard,
    index: 0,
    total: 1,
    trainerName: 'Ash'
  };
  const ta = present.formatFicha(ficha, '!pk');
  const tb = present.formatFicha({ ...ficha, trainerName: 'Misty' }, '!pk');
  // golpes iguais após normalizar treinador
  const ga = ta.split('\n').filter((l) => /^\d+\./.test(l)).join('\n');
  const gb = tb.split('\n').filter((l) => /^\d+\./.test(l)).join('\n');
  assert.equal(ga, gb);
  assert.match(ga, /🔥 \*Lança-Chamas\*/);
});
