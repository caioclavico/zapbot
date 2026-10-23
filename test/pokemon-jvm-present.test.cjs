'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('resolveCommand mapeia aliases do legado', () => {
  assert.equal(present.resolveCommand([]), 'menu');
  assert.equal(present.resolveCommand(['perfil']), 'treinador');
  assert.equal(present.resolveCommand(['tm']), 'time');
  assert.equal(present.resolveCommand(['meus']), 'time');
  assert.equal(present.resolveCommand(['equipe']), 'time');
  assert.equal(present.resolveCommand(['ativar']), 'ativar');
  assert.equal(present.resolveCommand(['iniciar']), 'iniciar');
  assert.equal(present.resolveCommand(['inicial']), 'inicial');
  assert.equal(present.resolveCommand(['ini']), 'inicial');
  assert.equal(present.resolveCommand(['capturar']), 'capturar');
  assert.equal(present.resolveCommand(['cap']), 'capturar');
  assert.equal(present.resolveCommand(['cacar']), 'cacar');
  assert.equal(present.resolveCommand(['1']), 'atacar');
  assert.equal(present.resolveCommand(['4']), 'atacar');
  assert.equal(present.resolveCommand(['batalhar']), 'batalhar');
});

test('formatMainMenu e jornada sem jargão técnico', () => {
  const novo = present.formatMainMenu({ hasTrainer: false, status: 'not_started' }, '!pk');
  assert.match(novo, /Bem-vindo/);
  assert.match(novo, /!pk iniciar/);
  assert.doesNotMatch(novo, /JVM|Cassandra|POKEMON_ADAPTER/);

  const pendente = present.formatMainMenu({
    hasTrainer: true, status: 'awaiting_starter'
  }, '!pk');
  assert.match(pendente, /inicial/);

  const completo = present.formatMainMenu({
    hasTrainer: true, status: 'completed'
  }, '!pk');
  assert.match(completo, /treinador/);
  assert.match(completo, /cacar/);

  const start = present.formatStartJourney({
    created: true,
    alreadyComplete: false,
    kit: { qty: 10, kitAlreadyClaimed: false },
    onboarding: {
      status: 'awaiting_starter',
      starterAvailable: true,
      starters: [
        { index: 1, nome: 'Bulbasaur', emoji: '🌱', tipos: ['grass', 'poison'], descricao: 'x' },
        { index: 2, nome: 'Charmander', emoji: '🔥', tipos: ['fire'], descricao: 'y' },
        { index: 3, nome: 'Squirtle', emoji: '💧', tipos: ['water'], descricao: 'z' }
      ]
    }
  }, '!pk');
  assert.match(start, /10 Pokébolas/);
  assert.match(start, /Bulbasaur/);
  assert.match(start, /Planta\/Veneno/);
  assert.match(start, /Charmander/);
  assert.match(start, /Fogo/);
  assert.match(start, /Squirtle/);
  assert.match(start, /Água/);
  assert.match(start, /inicial <número>/);

  const chosen = present.formatStarterChosen({
    nome: 'Charmander', emoji: '🔥', species: 'charmander'
  }, '!pk');
  assert.match(chosen, /Charmander/);
  assert.match(chosen, /cacar/);
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

test('shiny e armazenamento usam a coleção canônica', () => {
  const data = {
    collection: [{ collectionIndex: 0, nome: 'Pikachu', shiny: true, nivel: 5, hp: 20, hpAtual: 20 }],
    count: 1, saveId: 'a', trainerLevel: 1, ativoIndex: 0
  };
  assert.match(present.formatCollection(data, ['shiny'], '!pk'), /Pikachu/);
  assert.match(present.formatStorage(data, [], '!pk'), /PC Pokémon/);
  assert.match(present.formatSpace({ capacidadePokemon: 30, ocupacaoPokemon: 1 }, '!pk'), /1\/30/);
  assert.equal(present.resolveCommand(['computador']), 'pc');
  assert.equal(present.resolveCommand(['centro']), 'pc');
  assert.equal(present.resolveCommand(['espaco']), 'espaco');
  assert.equal(present.resolveCommand(['shiny']), 'shiny');
});

test('formatTeams apresenta escalações persistidas por nome e índices atuais', () => {
  const text = present.formatTeams({ teams: [
    { name: 'Liga 1', pokemonIds: ['p-1', 'p-2', 'p-3'], indices: [2, 0, 4] }
  ] }, '!pk');
  assert.match(text, /Liga 1/);
  assert.match(text, /3, 1, 5/);
  assert.match(text, /times usar/);
});

test('formatError distingue pending local de recibo ausente', () => {
  assert.match(present.formatError({ code: 'OPERATION_PENDING' }), /neste processo JVM/);
  assert.match(present.formatError({ code: 'OPERATION_NOT_FOUND' }), /não persistida/);
  assert.match(present.formatError({ code: 'STALE_WRITER_FENCE' }), /obsoleto/);
  assert.match(present.formatError({ code: 'ECONNREFUSED' }), /indisponível/);
  assert.doesNotMatch(present.formatError({ code: 'ECONNREFUSED' }), /Cassandra/);
  assert.match(present.formatError({ code: 'INVALID_STARTER' }), /1, 2 ou 3/);
});

test('formatUnavailable amigável sem jargão de infra', () => {
  const text = present.formatUnavailable('batalhar');
  assert.match(text, /não está disponível/);
  assert.match(text, /batalhar/);
  assert.doesNotMatch(text, /Cassandra|POKEMON_ADAPTER|JVM/);
});
