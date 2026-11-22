'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('linhaGolpe: precisão aparece uma vez com %', () => {
  const golpe = {
    nome: 'Surf',
    slug: 'surf',
    tipo: 'water',
    classe: 'especial',
    poder: 90,
    precisao: 90
  };
  // força mecanica com precisao duplicável
  const linha = present.linhaGolpe(0, golpe, ['normal'], null);
  const hits = [...linha.matchAll(/precis[aã]o/gi)];
  assert.equal(hits.length, 1, linha);
  assert.match(linha, /precisão 90%/);
  assert.doesNotMatch(linha, /precisão 90,/);
});

test('linhaGolpe: sem precisão inventada', () => {
  const golpe = {
    nome: 'Splash',
    slug: 'splash',
    tipo: 'normal',
    classe: 'status',
    alvo: 'proprio'
  };
  const linha = present.linhaGolpe(0, golpe, [], null);
  assert.doesNotMatch(linha, /precisão/);
});
