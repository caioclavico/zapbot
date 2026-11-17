'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const colecao = {
  trainerLevel: 5,
  saveId: 'a',
  ativoIndex: 0,
  favorito: { id: 'id-squirtle', nome: 'Squirtle' },
  count: 3,
  collection: [
    {
      collectionIndex: 0, idPokemon: 'id-pika', nome: 'Pikachu', nivel: 20,
      raridade: 'comum', tipos: ['electric'], hp: 45, hpAtual: 40,
      xpDesdeNivel: 3, ataque: 55, defesa: 40, atqEsp: 50, defEsp: 50, veloc: 90,
      golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    {
      collectionIndex: 1, idPokemon: 'id-squirtle', nome: 'Squirtle', nivel: 10,
      raridade: 'incomum', tipos: ['water'], hp: 44, hpAtual: 44, xpDesdeNivel: 0
    },
    {
      collectionIndex: 2, idPokemon: 'id-zard', nome: 'Charizard', nivel: 36, shiny: true,
      raridade: 'raro', tipos: ['fire', 'flying'], hp: 78, hpAtual: 10, xpDesdeNivel: 1,
      ataque: 84, defesa: 78, atqEsp: 109, defEsp: 85, veloc: 100
    }
  ]
};

test('aliases pokedex/favorito/coleção', () => {
  assert.equal(present.resolveCommand(['pokedex']), 'pokedex');
  assert.equal(present.resolveCommand(['dex']), 'pokedex');
  assert.equal(present.resolveCommand(['colecao']), 'pokedex');
  assert.equal(present.resolveCommand(['coleção']), 'pokedex');
  assert.equal(present.resolveCommand(['favorito']), 'favorito');
  assert.equal(present.resolveCommand(['fav']), 'favorito');
  assert.equal(present.resolveCommand(['time']), 'time');
  assert.equal(present.resolveCommand(['meus']), 'time');
});

test('coleção vazia', () => {
  const text = present.formatCollection({ collection: [], count: 0 }, [], '!pk');
  assert.match(text, /Coleção vazia|iniciar/i);
});

test('coleção com vários + marcadores ativo/favorito', () => {
  const text = present.formatCollection(colecao, [], '!pk');
  assert.match(text, /👉.*Pikachu/);
  assert.match(text, /⭐.*Squirtle/);
  assert.match(text, /Charizard/);
  assert.match(text, /time ativo/);
  assert.match(text, /favorito/);
});

test('ordenação por força preserva collectionIndex (A2)', () => {
  const { list } = present.filtrarColecao(colecao.collection, ['>']);
  assert.equal(list[0].nome, 'Charizard');
  assert.equal(list[0].collectionIndex, 2);
  assert.equal(list[1].collectionIndex, 0);
  assert.equal(list[2].collectionIndex, 1);
  const text = present.formatCollection(colecao, ['>'], '!pk');
  assert.match(text, /3\.\s+✨?\s*\*Charizard\*/);
});

test('paginação PAGE_SIZE 12', () => {
  const many = {
    ...colecao,
    count: 15,
    collection: Array.from({ length: 15 }, (_, i) => ({
      collectionIndex: i,
      idPokemon: `id-${i}`,
      nome: `Mon${i}`,
      nivel: 1,
      hp: 10,
      hpAtual: 10
    }))
  };
  const p1 = present.collectionPageSlice(many, []);
  assert.equal(p1.slice.length, 12);
  assert.equal(p1.pages, 2);
  const p2 = present.collectionPageSlice(many, ['2']);
  assert.equal(p2.page, 2);
  assert.equal(p2.slice.length, 3);
  assert.equal(p2.slice[0].collectionIndex, 12);
});

test('filtro raridade', () => {
  const { list } = present.filtrarColecao(colecao.collection, ['raro']);
  assert.equal(list.length, 1);
  assert.equal(list[0].nome, 'Charizard');
});

test('formatPokedex e descobertas', () => {
  const dex = {
    totalPokemons: 1025,
    sequenciaCapturas: 2,
    ativoIndex: 0,
    ativo: { nome: 'Pikachu', nivel: 20 },
    entries: [
      {
        species: 'pikachu', nome: 'Pikachu', raridade: 'comum', capturas: 1,
        maiorNivel: 20, indices: [{ numero: 1, nivel: 20 }], tipos: ['electric']
      },
      {
        species: 'squirtle', nome: 'Squirtle', raridade: 'incomum', capturas: 1,
        maiorNivel: 10, indices: [{ numero: 2, nivel: 10 }], tipos: ['water']
      }
    ]
  };
  const text = present.formatPokedex(dex, [], '!pk');
  assert.match(text, /Sua Pokédex/);
  assert.match(text, /2\/1025/);
  assert.match(text, /#1.*Pikachu/);
  assert.match(text, /pokedex <número>/);
  const disc = present.formatDiscoveries({
    totalPokemons: 1025, vistos: 2, avistamentos: 3,
    capturados: 2, capturas: 2, shiny: 0, desconhecidos: 1023
  }, '!pk');
  assert.match(disc, /Descobertas/);
  assert.match(disc, /2\/1025/);
  assert.match(disc, /⬛/);
});

test('formatFicha atributos e golpes', () => {
  const text = present.formatFicha({
    idx: 0,
    isAtivo: true,
    isFavorito: false,
    pokemon: colecao.collection[0]
  }, '!pk');
  assert.match(text, /👉.*Pikachu/);
  assert.match(text, /Ataque: 55/);
  assert.match(text, /Choque/);
  assert.match(text, /Pokémon ativo/);
});

test('consulta por índice estável após filtro', () => {
  const { list } = present.filtrarColecao(colecao.collection, ['fogo']);
  assert.equal(list.length, 1);
  assert.equal(list[0].collectionIndex, 2);
  // escolher 3 continua sendo Charizard, não o 1º do filtro
  assert.notEqual(list[0].collectionIndex, 0);
});

test('formatFavorite', () => {
  assert.match(present.formatFavorite({ clear: true }), /removido/);
  assert.match(present.formatFavorite({
    favorito: { nome: 'Squirtle' }, ativado: true
  }), /favorito.*ativo/i);
});
