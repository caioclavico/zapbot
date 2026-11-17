'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const magikarp = {
  nome: 'Magikarp',
  tipos: ['water'],
  altura: 0.9,
  raridade: 'comum',
  shiny: false,
  hp: 80,
  hpAtual: 80
};

const squirtle = {
  nome: 'Squirtle',
  tipos: ['water'],
  altura: 0.5,
  hp: 100,
  hpAtual: 100,
  golpes: [
    { nomeExibicao: 'Jato', tipo: 'water', poder: 80, classe: 'especial', precisao: 100 },
    { nomeExibicao: 'Investida', tipo: 'normal', poder: 40, classe: 'fisico', precisao: 100 }
  ]
};

const praia = {
  id: 'praia',
  nome: 'Praia Azul',
  emoji: '🏖️',
  ceu: '#7dd3fc',
  chao: '#eab308'
};

test('idCenario e cores do bioma', () => {
  assert.equal(media.idCenario(praia), 'praia');
  assert.equal(media.idCenario({ id: 'Floresta Verde' }), 'floresta');
  assert.match(media.svgArenaCacada(praia, false), /#7dd3fc/);
  assert.match(media.svgBolaCaptura('ultra-bola', true, false), /#111827/);
});

test('arena de encontro por bioma (sem download de sprite)', async () => {
  const img = await media.renderHuntArena({
    area: praia,
    pokemonX: squirtle,
    pokemonO: magikarp,
    caption: 'Um Comum Magikarp apareceu.',
    mostrarEntrada: true
  });
  assert.ok(img);
  assert.equal(img.mimeType, 'image/png');
  assert.equal(img.filename, 'batalha-selvagem.png');
  assert.ok(img.base64.length > 800);
  // PNG signature em base64
  assert.ok(Buffer.from(img.base64, 'base64').subarray(0, 4).equals(
    Buffer.from([0x89, 0x50, 0x4e, 0x47])
  ));
});

test('turno com fuga e captura aguardando', async () => {
  const fuga = await media.renderHuntArena({
    area: praia,
    pokemonX: squirtle,
    pokemonO: magikarp,
    fugiu: true,
    caption: 'Magikarp fugiu!'
  });
  assert.equal(fuga.filename, 'fuga-selvagem.png');

  const win = await media.renderHuntArena({
    area: praia,
    pokemonX: squirtle,
    pokemonO: magikarp,
    aguardandoCaptura: true,
    caption: 'Magikarp foi derrotado!'
  });
  assert.ok(win.base64.length > 500);
});

test('imagem de captura sucesso e falha', async () => {
  const ok = await media.renderCaptureBall({
    bola: 'pokebola', capturou: true, fugiu: false
  });
  const fail = await media.renderCaptureBall({
    bola: 'grande-bola', capturou: false, fugiu: true
  });
  assert.equal(ok.filename, 'captura-concluida.png');
  assert.equal(fail.filename, 'captura-falhou.png');
  assert.ok(ok.base64.length > 400);
});

test('falha de download de sprite não quebra (placeholder)', async () => {
  const img = await media.renderHuntArena({
    area: { id: 'caverna', ceu: '#475569', chao: '#1e293b' },
    pokemonX: { ...squirtle, imagem: 'http://127.0.0.1:1/missing.png' },
    pokemonO: { ...magikarp, imagem: 'http://127.0.0.1:1/missing.png' },
    caption: 'teste'
  });
  assert.ok(img);
  assert.equal(img.mimeType, 'image/png');
});

test('cartão dos 3 iniciais (M9.6.1) — layout e fallback', async () => {
  assert.equal(media.tiposPt(['grass', 'poison']), 'Planta/Veneno');
  assert.equal(media.tiposPt(['fire']), 'Fogo');
  assert.equal(media.tiposPt(['water']), 'Água');

  const starters = [
    {
      index: 1, nome: 'Bulbasaur', species: 'bulbasaur', tipos: ['grass', 'poison'],
      imagem: 'http://127.0.0.1:1/bulba.png'
    },
    {
      index: 2, nome: 'Charmander', species: 'charmander', tipos: ['fire'],
      imagem: 'http://127.0.0.1:1/char.png'
    },
    {
      index: 3, nome: 'Squirtle', species: 'squirtle', tipos: ['water'],
      imagem: 'http://127.0.0.1:1/squirt.png'
    }
  ];
  const img = await media.renderStartersCard(starters);
  assert.ok(img);
  assert.equal(img.mimeType, 'image/png');
  assert.equal(img.filename, 'iniciais.png');
  const png = Buffer.from(img.base64, 'base64');
  assert.ok(png.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])));
  assert.ok(png.length > 4000, 'cartão composto deve ter tamanho razoável');

  const vazio = await media.renderStartersCard([]);
  assert.equal(vazio, null);
});

test('envelope de mídia não mistura efeitos com TX', () => {
  const env = present.envelope('req-media', 'legenda', {
    image: { base64: 'aaaa', mimeType: 'image/png', filename: 'x.png' },
    effects: []
  });
  assert.equal(env.messages[0].type, 'image');
  assert.deepEqual(env.effects, []);
});

test('formatBattleTurn preserva nomes/tipos/golpes', () => {
  const text = present.formatBattleTurn({
    battle: {
      turnIndex: 2,
      hp: { x: 80, o: 12 },
      area: praia,
      pokemonX: { ...squirtle, hpAtual: 80 },
      pokemonO: { ...magikarp, hpAtual: 12 },
      lastAction: { dano: 40 }
    }
  });
  assert.match(text, /Praia Azul/);
  assert.match(text, /Magikarp/);
  assert.match(text, /Jato/);
  assert.match(text, /40 de dano/);
  assert.match(text, /80\/100/);
  assert.match(text, /12\/80/);
  assert.match(text, /poder 80/);
});
