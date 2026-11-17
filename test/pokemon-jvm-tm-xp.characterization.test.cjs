'use strict';

/**
 * Contratos estáveis de !pk tm (alias de time) — coleção A6.
 * !pk tm → 'time'. Máquina Técnica de item continua em !pk mt.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const sampleCollection = {
  collection: [
    {
      collectionIndex: 0,
      idPokemon: 'p1',
      nome: 'Charmeleon',
      species: 'charmeleon',
      nivel: 34,
      xpDesdeNivel: 5,
      xpConhecido: true,
      xpNivelAtual: 5,
      xpProximoNivel: 9,
      hp: 80,
      hpAtual: 70,
      tipos: ['fire'],
      raridade: 'incomum'
    },
    {
      collectionIndex: 1,
      idPokemon: 'p2',
      nome: 'Pikachu',
      species: 'pikachu',
      nivel: 22,
      xpDesdeNivel: 3,
      xpConhecido: true,
      xpNivelAtual: 3,
      xpProximoNivel: 9,
      hp: 60,
      hpAtual: 60,
      tipos: ['electric'],
      raridade: 'comum'
    }
  ],
  count: 2,
  saveId: 'a',
  displayName: 'Ash',
  trainerLevel: 4,
  ativoIndex: 0,
  capacidadePokemon: 50,
  ocupacaoPokemon: 2
};

test('caracterização: tm/meus/equipe resolvem para time', () => {
  assert.equal(present.resolveCommand(['tm']), 'time');
  assert.equal(present.resolveCommand(['time']), 'time');
  assert.equal(present.resolveCommand(['meus']), 'time');
  assert.equal(present.resolveCommand(['equipe']), 'time');
  assert.notEqual(present.resolveCommand(['mt']), 'time');
});

test('caracterização: formatCollection lista Pokémon com XP canônico', () => {
  const text = present.formatCollection(sampleCollection, [], '!pk');
  assert.match(text, /Meu Time Pokémon/);
  assert.doesNotMatch(text, /Máquinas Técnicas/);
  assert.match(text, /Charmeleon/);
  assert.match(text, /Pikachu/);
  assert.match(text, /XP 5\/9/);
  assert.match(text, /XP 3\/9/);
  assert.match(text, /Nível de treinador: 4/);
});

test('caracterização: caption do card não duplica a listagem', () => {
  const caption = present.formatCollectionCardCaption(sampleCollection, [], '!pk');
  const env = present.envelope('req-1', caption, {
    image: { base64: 'aaa', mimeType: 'image/png', filename: 'meu-time-pokemon.png' },
    caption,
    effects: []
  });
  assert.equal(env.messages.length, 1);
  assert.equal(env.messages[0].type, 'image');
  assert.doesNotMatch(env.messages[0].text, /Charmeleon/);
  assert.doesNotMatch(env.messages[0].text, /XP 5\/9/);
});

test('caracterização: XP ausente não inventa zero', () => {
  const text = present.formatCollection({
    collection: [{
      collectionIndex: 0, nome: 'MissingXp', species: 'missing',
      nivel: 10, hp: 10, hpAtual: 10, tipos: ['normal'],
      xpConhecido: false
    }],
    count: 1, saveId: 'a', trainerLevel: 1, ativoIndex: 0
  }, [], '!pk');
  assert.match(text, /XP \?/);
  assert.doesNotMatch(text, /XP 0\/9/);
});

test('caracterização: collectionPageSlice pagina 12 e preserva índices', () => {
  const big = {
    collection: Array.from({ length: 15 }, (_, i) => ({
      collectionIndex: i,
      nome: `P${i}`,
      nivel: 1,
      hp: 10,
      hpAtual: 10,
      xpDesdeNivel: 0,
      xpConhecido: true
    })),
    count: 15
  };
  const page1 = present.collectionPageSlice(big, []);
  assert.equal(page1.slice.length, 12);
  assert.equal(page1.pages, 2);
  const page2 = present.collectionPageSlice(big, ['2']);
  assert.equal(page2.slice.length, 3);
});
