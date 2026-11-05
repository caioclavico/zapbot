'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const FIXTURES = path.join(__dirname, 'fixtures', 'a6-tm-xp');

const collection = {
  collection: [
    {
      collectionIndex: 0,
      idPokemon: 'id-charm',
      nome: 'Charmeleon',
      species: 'charmeleon',
      nivel: 34,
      xpDesdeNivel: 5,
      xpNivelAtual: 5,
      xpProximoNivel: 9,
      xpNecessario: 9,
      xpConhecido: true,
      progressoXpPercentual: 55.6,
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
      xpDesdeNivel: 3,
      xpNivelAtual: 3,
      xpProximoNivel: 9,
      xpNecessario: 9,
      xpConhecido: true,
      hp: 60,
      hpAtual: 60,
      tipos: ['electric'],
      raridade: 'comum'
      // sem evolução por nível (pedra) — campo omitido
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

test('pokemonXpProgress: parcial, máximo e ausente', () => {
  const a = present.pokemonXpProgress(collection.collection[0]);
  assert.equal(a.known, true);
  assert.equal(a.atual, 5);
  assert.equal(a.necessario, 9);
  assert.equal(a.pct, 55.6);

  const max = present.pokemonXpProgress(collection.collection[2]);
  assert.equal(max.max, true);
  assert.equal(max.label, 'NÍVEL MÁXIMO');

  const unk = present.pokemonXpProgress(collection.collection[3]);
  assert.equal(unk.known, false);
  assert.equal(unk.label, 'XP ?');

  // não inventa zero
  assert.equal(present.pokemonXpProgress({ nivel: 5 }).known, false);
});

test('!pk tm txt: texto equivalente ao card com XP e evolução', () => {
  const text = present.formatCollection(collection, [], '!pk');
  assert.match(text, /Máquinas Técnicas — Meus Pokémon/);
  assert.match(text, /Charmeleon.*Nv\. 34/s);
  assert.match(text, /XP 5\/9/);
  assert.match(text, /55,6%/);
  assert.match(text, /Próxima evolução: Charizard — Nv\. 36/);
  assert.match(text, /Pikachu/);
  assert.match(text, /NÍVEL MÁXIMO/);
  assert.match(text, /XP \?/);
  assert.match(text, /Card visual: !pk tm/);
  assert.match(text, /CSV: !pk tm csv/);
});

test('!pk tm card caption: curta, sem listagem de Pokémon/XP', () => {
  const caption = present.formatCollectionCardCaption(collection, [], '!pk');
  assert.match(caption, /Máquinas Técnicas/);
  assert.doesNotMatch(caption, /Charmeleon/);
  assert.doesNotMatch(caption, /XP 5\/9/);
  assert.doesNotMatch(caption, /Próxima evolução/);
  assert.match(caption, /tm txt/);
  assert.match(caption, /tm csv/);
});

test('envelope card-only: uma imagem com caption curta (sem segunda mensagem)', () => {
  const caption = present.formatCollectionCardCaption(collection, [], '!pk');
  const full = present.formatCollection(collection, [], '!pk');
  const env = present.envelope('req-tm', caption, {
    image: { base64: 'abc', mimeType: 'image/png', filename: 'meu-time-pokemon.png' },
    caption,
    effects: []
  });
  assert.equal(env.messages.length, 1);
  assert.equal(env.messages[0].type, 'image');
  assert.equal(env.messages[0].text, caption);
  assert.doesNotMatch(env.messages[0].text, /Charmeleon/);

  const txtEnv = present.envelope('req-txt', full, { effects: [] });
  assert.equal(txtEnv.messages.length, 1);
  assert.equal(txtEnv.messages[0].type, 'text');
  assert.match(txtEnv.messages[0].text, /Charmeleon/);
});

test('envelope CSV: documento sem PNG', () => {
  const env = present.envelope('req-csv', '🎒 CSV', {
    document: {
      base64: Buffer.from('pokemon_id,nome\n1,A\n').toString('base64'),
      mimeType: 'text/csv',
      filename: 'odisseu-mt-pokemon.csv'
    },
    effects: []
  });
  assert.equal(env.messages.length, 1);
  assert.equal(env.messages[0].type, 'document');
  assert.equal(env.messages[0].filename, 'odisseu-mt-pokemon.csv');
  assert.equal(env.messages[0].mimeType, 'text/csv');
});

test('renderTeamGrid: PNG real com XP e evolução', async () => {
  fs.mkdirSync(FIXTURES, { recursive: true });
  const img = await media.renderTeamGrid(collection.collection, {
    filename: 'meu-time-pokemon.png',
    trainerLevel: 4,
    ativoIndex: 0,
    title: 'Máquinas Técnicas'
  });
  assert.ok(img);
  assert.equal(img.mimeType, 'image/png');
  const buf = Buffer.from(img.base64, 'base64');
  assert.ok(buf.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
  const out = path.join(FIXTURES, 'team-grid-xp.png');
  fs.writeFileSync(out, buf);
  assert.ok(fs.statSync(out).size > 2000);
});

test('modelo compartilhado: card e texto usam as mesmas entradas', () => {
  const model = present.buildTeamPresentationModel(collection, [], '!pk');
  assert.equal(model.entries.length, 4);
  assert.equal(model.entries[0].xp.atual, 5);
  assert.match(model.entries[0].evolucao, /Charizard/);
  assert.equal(model.entries[2].xp.max, true);
  assert.equal(model.entries[3].xp.known, false);
});

test('evidência textual !pk tm txt', () => {
  fs.mkdirSync(FIXTURES, { recursive: true });
  const text = present.formatCollection(collection, [], '!pk');
  fs.writeFileSync(path.join(FIXTURES, 'tm-txt-exemplo.txt'), text, 'utf8');
  assert.ok(text.includes('Máquinas Técnicas'));
});

test('CSV fixture: parser independente + formula injection', () => {
  const raw = fs.readFileSync(
    path.join(FIXTURES, 'odisseu-mt-pokemon.sample.csv'),
    'utf8'
  );
  const lines = raw.trim().split(/\n/);
  const headers = lines[0].split(',');
  assert.deepEqual(headers.slice(0, 5), [
    'pokemon_id', 'numero_pokedex', 'nome', 'especie', 'nivel'
  ]);
  assert.ok(headers.includes('xp_nivel_atual'));
  assert.ok(headers.includes('proxima_evolucao'));
  assert.ok(raw.includes("'=CMD()"));
  assert.ok(raw.includes('Charizard'));
  // parse simples (sem aspas aninhadas além do prefixo ')
  const row1 = lines[1].split(',');
  assert.equal(row1[headers.indexOf('nome')], 'Charmeleon');
  assert.equal(row1[headers.indexOf('xp_nivel_atual')], '5');
  assert.equal(row1[headers.indexOf('xp_proximo_nivel')], '9');
});
