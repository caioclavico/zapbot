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
  assert.match(linha, /precisão base 90%/);
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

test('linhaGolpe: ignora precisão e evasão sem duplicar precisão %', () => {
  const golpe = {
    nome: 'Swift',
    slug: 'swift',
    tipo: 'normal',
    classe: 'especial',
    poder: 60,
    precisao: 100,
    mecanica: { ignoraPrecisao: true }
  };
  const linha = present.linhaGolpe(0, golpe, ['normal'], null);
  assert.match(linha, /ignora precisão e evasão/);
  const hits = [...linha.matchAll(/precis[aã]o/gi)];
  // "ignora precisão" + no máximo uma "precisão N%"
  assert.ok(hits.length <= 2, linha);
  assert.doesNotMatch(linha, /precisão 100,\s*precisão/);
});

test('linhaGolpe: físico com precisão 90% uma vez', () => {
  const golpe = {
    nome: 'Earthquake',
    slug: 'earthquake',
    tipo: 'ground',
    classe: 'fisico',
    poder: 100,
    precisao: 90
  };
  const linha = present.linhaGolpe(0, golpe, ['normal'], null);
  assert.equal([...linha.matchAll(/precis[aã]o/gi)].length, 1, linha);
  assert.match(linha, /precisão base 90%/);
});

test('resultado parcial distingue proteção, alvo inalcançável e imunidade de erro', () => {
  const formatar = (extra) => present.formatCanonicalAttackLine(
    { golpeSlug: 'fissure', dano: 0, ...extra }, { nome: 'A' }, { nome: 'B' }
  );
  assert.match(formatar({ bloqueado: true }), /Proteção bloqueou/);
  assert.match(formatar({ sumido: true }), /não alcançou/);
  assert.match(formatar({ nivelInsuficiente: true }), /nível do atacante/);
  assert.match(formatar({ multiplicador: 0 }), /não teve efeito/);
  assert.doesNotMatch(formatar({ multiplicador: 0, critico: true }), /crítico/);
  assert.match(formatar({ esquivou: true }), /esquivou/);
  const erro = formatar({ errou: true, multiplicador: 0, critico: true });
  assert.match(erro, /errou o alvo/);
  assert.doesNotMatch(erro, /Sem efeito|crítico/);
});
