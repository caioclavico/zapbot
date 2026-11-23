'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('v3: XP bar L50 displays 26/30 without reverting to 9', () => {
  const p = {
    nivel: 50, nome: 'Pikachu', xpConhecido: true,
    xpProgressVersion: 'progressive-xp-v3',
    xpNivelAtual: 26, xpProximoNivel: 30
  };
  const x = present.pokemonXpProgress(p);
  assert.equal(x.atual, 26);
  assert.equal(x.necessario, 30);
  assert.equal(x.label, 'XP 26/30');
  assert.equal(x.pct, 86.7);
  assert.match(present.blocoXpPokemon(p), /26\/30/);
});

test('v3: hyphenated DTO progression version and L20/17', () => {
  const p = {
    nivel: 20, nome: 'Pikachu', xpConhecido: true,
    'xp-progress-version': 'progressive-xp-v3',
    'xp-nivel-atual': 8, 'xp-proximo-nivel': 17
  };
  assert.equal(present.pokemonXpProgress(p).label, 'XP 8/17');
});

test('flat9 legacy: old official span still capped to 9; unknown stays unknown', () => {
  const p = {
    nivel: 20, nome: 'Pikachu', xpConhecido: true,
    'xp-progress-version': 'flat-9-v2',
    xpNivelAtual: 3, xpProximoNivel: 300
  };
  assert.equal(present.pokemonXpProgress(p).label, 'XP 3/9');
  assert.equal(present.pokemonXpProgress({nivel: 50}).known, false);
});
