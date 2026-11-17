'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const pokeapi = require('../scripts/lib/pokemon-pokeapi.cjs');

test('aliases escolher/evoluir/aprender', () => {
  assert.equal(present.resolveCommand(['escolher']), 'escolher');
  assert.equal(present.resolveCommand(['esc']), 'escolher');
  assert.equal(present.resolveCommand(['trocar']), 'escolher');
  assert.equal(present.resolveCommand(['evoluir']), 'evoluir');
  assert.equal(present.resolveCommand(['evo']), 'evoluir');
  assert.equal(present.resolveCommand(['aprender']), 'aprender');
  assert.equal(present.resolveCommand(['apr']), 'aprender');
  assert.equal(present.resolveCommand(['reaprender']), 'reaprender');
  assert.equal(present.resolveCommand(['rmg']), 'removergolpe');
  assert.equal(present.resolveCommand(['cancelar']), 'cancelar');
});

test('pedras de evolução espelham master', () => {
  assert.equal(pokeapi.destinoPedra('pedra-trovao', 'pikachu'), 'raichu');
  assert.equal(pokeapi.destinoPedra('pedra-fogo', 'eevee'), 'flareon');
  assert.equal(pokeapi.destinoPedra('pedra-agua', 'bulbasaur'), null);
  assert.deepEqual(pokeapi.pedrasCompativeis('pikachu'), ['pedra-trovao']);
});

test('escalarNivel usa fator 1.03', () => {
  const base = { hp: 100, ataque: 100, defesa: 100, atqEsp: 100, defEsp: 100, veloc: 100 };
  const n16 = pokeapi.escalarNivel(base, 16);
  assert.equal(n16.nivel, 16);
  assert.equal(n16.hp, Math.round(100 * Math.pow(1.03, 15)));
});

test('formatters A2', () => {
  assert.match(present.formatSetActive({
    ativoIndex: 1,
    pokemon: { nome: 'Squirtle' }
  }, '!pk'), /Squirtle/);
  assert.match(present.formatEvolve({
    itemId: 'pedra-trovao',
    pokemon: { nome: 'Raichu' }
  }), /Raichu/);
  assert.match(present.formatMoveOfferMenu({
    nome: 'Pikachu',
    golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }],
    ofertasGolpes: [{ nomeExibicao: 'Iron Tail', slug: 'iron-tail' }]
  }, '!pk'), /Iron Tail/);
  assert.match(present.formatError({ code: 'BATTLE_BUSY' }), /batalha/);
});

test('coleção orienta escolher', () => {
  const text = present.formatCollection({
    collection: [{ collectionIndex: 0, nome: 'Bulbasaur', nivel: 5, hp: 40, hpAtual: 40 }],
    ativoIndex: 0,
    trainerLevel: 1,
    count: 1,
    saveId: 'a'
  }, [], '!pk');
  assert.match(text, /escolher/);
  assert.match(text, /👉/);
});
