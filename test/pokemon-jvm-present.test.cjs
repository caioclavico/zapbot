'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('resolveCommand mapeia aliases do legado', () => {
  assert.equal(present.resolveCommand(['perfil']), 'treinador');
  assert.equal(present.resolveCommand(['tm']), 'time');
  assert.equal(present.resolveCommand(['meus']), 'time');
  assert.equal(present.resolveCommand(['equipe']), 'time');
  assert.equal(present.resolveCommand(['ativar']), 'ativar');
  assert.equal(present.resolveCommand(['batalhar']), null);
});

test('formatTrainer produz envelope texto compatível', () => {
  const text = present.formatTrainer({
    activeSaveId: 'a',
    fenceGeneration: 2,
    collectionCount: 1,
    ativoIndex: 0,
    profile: {
      nivel: 3, xp: 12, xpAtual: 2, xpNecessario: 9, coins: 40,
      peGinasios: 1, peRaids: 0, sequenciaCapturas: 2, maiorSequenciaCapturas: 5
    },
    ativo: { nome: 'Bulbasaur', species: 'Bulbasaur', nivel: 12, shiny: false }
  }, 'Ash');
  assert.match(text, /Treinador: Ash/);
  assert.match(text, /Nível 3/);
  assert.match(text, /Bulbasaur/);
  assert.match(text, /Save: a/);
  const env = present.envelope('req-1', text);
  assert.equal(env.requestId, 'req-1');
  assert.equal(env.messages[0].type, 'text');
  assert.deepEqual(env.effects, []);
});

test('formatCollection pagina e filtra', () => {
  const collection = Array.from({ length: 15 }, (_, i) => ({
    collectionIndex: i,
    nome: i === 3 ? 'Charmander' : `Poke${i}`,
    species: i === 3 ? 'Charmander' : `Poke${i}`,
    nivel: i === 3 ? 20 : 5,
    hp: 40, hpAtual: 20, raridade: 'comum', shiny: i === 3, tipos: i === 3 ? ['fire'] : ['normal']
  }));
  const text = present.formatCollection(
    { collection, count: 15, saveId: 'a', trainerLevel: 2, ativoIndex: 0 },
    ['fogo', '1'],
    '!pk'
  );
  assert.match(text, /Charmander/);
  assert.match(text, /Filtros/);
  assert.match(text, /Página 1/);
});

test('formatError distingue pending local de recibo ausente', () => {
  assert.match(present.formatError({ code: 'OPERATION_PENDING' }), /neste processo JVM/);
  assert.match(present.formatError({ code: 'OPERATION_NOT_FOUND' }), /não persistida/);
  assert.match(present.formatError({ code: 'STALE_WRITER_FENCE' }), /obsoleto/);
  assert.match(present.formatError({ code: 'ECONNREFUSED' }), /Cassandra/);
});

test('formatUnavailable bloqueia gameplay não integrado', () => {
  const text = present.formatUnavailable('batalhar');
  assert.match(text, /não integrado/);
  assert.match(text, /batalhar/);
  assert.doesNotMatch(text, /Cassandra legado/);
});
