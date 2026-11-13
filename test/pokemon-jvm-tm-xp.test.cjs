'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const FIXTURES = path.join(__dirname, 'fixtures', 'a6-tm-xp');

// DTOs growth-v2: xpNecessario(n) = 10 + 2*(n-1) — JVM é a fonte; Node só apresenta.
const collection = {
  collection: [
    {
      collectionIndex: 0,
      idPokemon: 'id-charm',
      nome: 'Charmeleon',
      species: 'charmeleon',
      nivel: 34,
      xpDesdeNivel: 38,
      xpNivelAtual: 38,
      xpProximoNivel: 76,
      xpNecessario: 76,
      xpConhecido: true,
      progressoXpPercentual: 50,
      proximaEvolucao: 'Charizard',
      nivelProximaEvolucao: 36,
      hp: 80,
      hpAtual: 70,
      tipos: ['fire'],
      raridade: 'incomum'
    },
    {
      collectionIndex: 1,
      idPokemon: 'id-pika',
      nome: 'Pikachu',
      species: 'pikachu',
      nivel: 22,
      xpDesdeNivel: 13,
      xpNivelAtual: 13,
      xpProximoNivel: 52,
      xpNecessario: 52,
      xpConhecido: true,
      hp: 60,
      hpAtual: 60,
      tipos: ['electric'],
      raridade: 'comum'
    },
    {
      collectionIndex: 2,
      idPokemon: 'id-max',
      nome: 'Mewtwo',
      species: 'mewtwo',
      nivel: 100,
      nivelMaximo: true,
      xpConhecido: true,
      xpDesdeNivel: 0,
      hp: 200,
      hpAtual: 200,
      tipos: ['psychic'],
      raridade: 'lendario'
    },
    {
      collectionIndex: 3,
      idPokemon: 'id-unk',
      nome: 'MissingNo',
      species: 'missingno',
      nivel: 10,
      xpConhecido: false,
      hp: 40,
      hpAtual: 40,
      tipos: ['normal'],
      raridade: 'comum'
    }
  ],
  count: 4,
  saveId: 'a',
  displayName: 'Ash',
  trainerLevel: 4,
  ativoIndex: 0,
  capacidadePokemon: 50,
  ocupacaoPokemon: 4
};

test('XP canônico Node: fallback L1=10; meta vem do DTO JVM (growth-v2)', () => {
  assert.equal(present.XP_POR_NIVEL_POKEMON, 10);
  assert.equal(present.NIVEL_MAXIMO_POKEMON, 100);
  const a = present.pokemonXpProgress(collection.collection[0]);
  assert.equal(a.atual, 38);
  assert.equal(a.necessario, 76);
  assert.equal(present.pokemonXpProgress({ nivel: 5 }).known, false);
});

test('pokemonXpProgress: parcial, máximo e ausente', () => {
  const a = present.pokemonXpProgress(collection.collection[0]);
  assert.equal(a.known, true);
  assert.equal(a.pct, 50);
  assert.equal(present.pokemonXpProgress(collection.collection[2]).label, 'NÍVEL MÁXIMO');
  assert.equal(present.pokemonXpProgress(collection.collection[3]).label, 'XP ?');
});

test('blocoXpPokemon: barra no nível sem XP acumulado histórico', () => {
  const bloco = present.blocoXpPokemon({
    nome: 'Charmeleon',
    nivel: 20,
    xpNivelAtual: 24,
    xpProximoNivel: 48,
    xpConhecido: true
  });
  assert.match(bloco, /Charmeleon — Nv\. 20/);
  assert.match(bloco, /XP: 24 \/ 48/);
  assert.match(bloco, /Próximo nível: 21/);
  assert.doesNotMatch(bloco, /growth|PokéAPI|acumulad|medium-/i);

  const max = present.blocoXpPokemon({
    nome: 'Mewtwo', nivel: 100, nivelMaximo: true, xpConhecido: true
  });
  assert.match(max, /NÍVEL MÁXIMO/);
  assert.doesNotMatch(max, /XP: \d+ \/ \d+/);
});

test('!pk tm txt: Meu Time Pokémon com XP e evolução', () => {
  const text = present.formatCollection(collection, [], '!pk');
  assert.match(text, /Meu Time Pokémon/);
  assert.doesNotMatch(text, /Máquinas Técnicas/);
  assert.match(text, /Charmeleon.*Nv\. 34/s);
  assert.match(text, /XP 38\/76/);
  assert.match(text, /Próxima evolução: Charizard — Nv\. 36/);
  assert.match(text, /NÍVEL MÁXIMO/);
  assert.match(text, /XP \?/);
});

test('!pk tm card caption: curta sem listagem', () => {
  const caption = present.formatCollectionCardCaption(collection, [], '!pk');
  assert.match(caption, /🎮 \*Meu Time Pokémon\*/);
  assert.doesNotMatch(caption, /Máquinas Técnicas/);
  assert.doesNotMatch(caption, /Charmeleon/);
  assert.doesNotMatch(caption, /XP 38\/76/);
  assert.match(caption, /tm txt/);
  assert.match(caption, /tm csv/);
});

test('envelope card-only / txt / csv sem misturar formatos', () => {
  const caption = present.formatCollectionCardCaption(collection, [], '!pk');
  const full = present.formatCollection(collection, [], '!pk');
  const card = present.envelope('req-tm', caption, {
    image: { base64: 'abc', mimeType: 'image/png', filename: 'meu-time-pokemon.png' },
    caption,
    effects: []
  });
  assert.equal(card.messages.length, 1);
  assert.equal(card.messages[0].type, 'image');
  assert.doesNotMatch(card.messages[0].text, /Charmeleon/);

  const txt = present.envelope('req-txt', full, { effects: [] });
  assert.equal(txt.messages.length, 1);
  assert.equal(txt.messages[0].type, 'text');
  assert.match(txt.messages[0].text, /Charmeleon/);

  const csv = present.envelope('req-csv', '🎒 CSV', {
    document: {
      base64: Buffer.from('pokemon_id,nome\n1,A\n').toString('base64'),
      mimeType: 'text/csv',
      filename: 'odisseu-time-pokemon.csv'
    },
    effects: []
  });
  assert.equal(csv.messages.length, 1);
  assert.equal(csv.messages[0].type, 'document');
  assert.equal(csv.messages[0].filename, 'odisseu-time-pokemon.csv');
});

test('modelo compartilhado: mesmas entradas para card e texto', () => {
  const model = present.buildTeamPresentationModel(collection, [], '!pk');
  assert.equal(model.entries.length, 4);
  assert.equal(model.entries[0].xp.atual, 38);
  assert.equal(model.entries[0].xp.necessario, 76);
  assert.match(model.entries[0].evolucao, /Charizard/);
  assert.equal(model.entries[2].xp.max, true);
  assert.equal(model.entries[3].xp.known, false);
});

test('renderTeamGrid: PNG real com nomenclatura de time', async () => {
  fs.mkdirSync(FIXTURES, { recursive: true });
  const img = await media.renderTeamGrid(collection.collection, {
    filename: 'meu-time-pokemon.png',
    trainerLevel: 4,
    ativoIndex: 0,
    title: 'Meu Time Pokémon'
  });
  assert.ok(img);
  const buf = Buffer.from(img.base64, 'base64');
  assert.ok(buf.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
  fs.writeFileSync(path.join(FIXTURES, 'team-grid-xp.png'), buf);
  assert.ok(fs.statSync(path.join(FIXTURES, 'team-grid-xp.png')).size > 2000);
});

test('evidências txt + caption + CSV parser', () => {
  fs.mkdirSync(FIXTURES, { recursive: true });
  const text = present.formatCollection(collection, [], '!pk');
  const caption = present.formatCollectionCardCaption(collection, [], '!pk');
  fs.writeFileSync(path.join(FIXTURES, 'tm-txt-exemplo.txt'), text, 'utf8');
  fs.writeFileSync(path.join(FIXTURES, 'tm-card-caption.txt'), caption, 'utf8');
  assert.match(text, /Meu Time Pokémon/);
  assert.match(caption, /Meu Time Pokémon/);

  const raw = fs.readFileSync(
    path.join(FIXTURES, 'odisseu-time-pokemon.sample.csv'),
    'utf8'
  );
  const lines = raw.trim().split(/\n/);
  const headers = lines[0].split(',');
  assert.deepEqual(headers.slice(0, 5), [
    'pokemon_id', 'numero_pokedex', 'nome', 'especie', 'nivel'
  ]);
  assert.ok(raw.includes("'=CMD()"));
  assert.ok(raw.includes('Charizard'));
});
