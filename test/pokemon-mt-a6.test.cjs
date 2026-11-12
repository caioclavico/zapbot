'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const pokeapi = require('../scripts/lib/pokemon-pokeapi.cjs');

test('resolveCommand distingue mt de tm(time)', () => {
  assert.equal(present.resolveCommand(['tm']), 'time');
  assert.equal(present.resolveCommand(['mt']), 'mt');
  assert.equal(present.resolveCommand(['mts']), 'mt');
});

test('formatMtHelp e formatMtUsed usam valores persistidos', () => {
  const help = present.formatMtHelp('!pk', 2);
  assert.match(help, /Máquinas Técnicas/);
  assert.match(help, /MT disponíveis: \*2\*/);
  assert.match(help, /mt confirmar/);
  assert.match(help, /mt cancelar/);

  const used = present.formatMtUsed({
    pokemon: { nome: 'Wartortle', idPokemon: 'pk-wart-1', nivel: 16 },
    golpeAnterior: { slug: 'tackle', nomeExibicao: 'Investida' },
    golpeNovo: { slug: 'ice-beam', 'nome-exibicao': 'Raio de Gelo' },
    itemQtyAfter: 1,
    consumeItemId: 'mt'
  }, '!pk');
  assert.match(used, /MÁQUINA TÉCNICA UTILIZADA/);
  assert.match(used, /Wartortle/);
  assert.match(used, /Golpe anterior: Investida/);
  assert.match(used, /Novo golpe: Raio de Gelo/);
  assert.match(used, /MT consumida: 1/);
  assert.match(used, /MT restante: 1/);
});

test('formatMtPreview e pending não revelam golpe antes da confirmação', () => {
  const poke = {
    nome: 'Wartortle',
    idPokemon: 'id-w',
    nivel: 16,
    golpes: [
      { slug: 'tackle', nomeExibicao: 'Investida' },
      { slug: 'water-gun', nomeExibicao: 'Jato de Água' }
    ]
  };
  const preview = present.formatMtPreview(poke, {
    mtQty: 1, candidatos: 5, prefix: '!pk', idx: 2
  });
  assert.match(preview, /#3/);
  assert.match(preview, /id-w/);
  assert.match(preview, /mt usar 3/);

  const pending = present.formatMtPending(poke, 0, {
    mtQty: 1, candidatos: 5, prefix: '!pk'
  });
  assert.match(pending, /pendente/i);
  assert.match(pending, /Slot 1: \*Investida\*/);
  assert.match(pending, /sorteio/);
  assert.doesNotMatch(pending, /Raio de Gelo|Ice Beam/i);
});

test('INSUFFICIENT_ITEM de MT aponta para a loja', () => {
  const msg = present.formatError({
    code: 'INSUFFICIENT_ITEM',
    details: { itemId: 'mt' }
  });
  assert.match(msg, /MT/);
  assert.match(msg, /loja comprar mt/);
});

test('RNG canônico de MT é estável no mesmo seed', () => {
  const cands = [
    { slug: 'a', poder: 1 },
    { slug: 'b', poder: 2 },
    { slug: 'c', poder: 3 },
    { slug: 'd', poder: 4 }
  ];
  const s1 = pokeapi.escolherGolpeMtAleatorio(cands, 'seed-fixed-1');
  const s2 = pokeapi.escolherGolpeMtAleatorio(cands, 'seed-fixed-1');
  const s3 = pokeapi.escolherGolpeMtAleatorio(cands, 'seed-fixed-2');
  assert.equal(s1.slug, s2.slug);
  assert.ok(s3);
  assert.equal(pokeapi.escolherGolpeMtAleatorio([], 'x'), null);
});

test('ataqueDoTipo e filtro de STAB básico', () => {
  assert.equal(
    pokeapi.ataqueDoTipo({ tipo: 'water', classe: 'especial', poder: 40 }, ['water']),
    true
  );
  assert.equal(
    pokeapi.ataqueDoTipo({ tipo: 'normal', classe: 'fisico', poder: 40 }, ['water']),
    false
  );
});

test('listarCandidatosMt usa catálogo local versionado (sem HTTP)', () => {
  assert.equal(pokeapi.mtCatalogVersion(), 'mt-learnset-v1-2026-11');
  const cands = pokeapi.listarCandidatosMt('wartortle', {
    nivel: 16,
    tipos: ['water'],
    golpesAtuais: [
      { slug: 'tackle', tipo: 'normal', classe: 'fisico', poder: 40 },
      { slug: 'water-gun', tipo: 'water', classe: 'especial', poder: 40 },
      { slug: 'bite', tipo: 'dark', classe: 'fisico', poder: 60 },
      { slug: 'rapid-spin', tipo: 'normal', classe: 'fisico', poder: 50 }
    ],
    slot: 0
  });
  assert.ok(cands.length >= 1);
  assert.ok(cands.every((g) => g.slug !== 'tackle'));
  assert.ok(cands.some((g) => g.slug === 'water-pulse' || g.slug === 'bubble'));
  assert.throws(
    () => pokeapi.listarCandidatosMt('missingno-xyz', { nivel: 10 }),
    (err) => err && err.code === 'MT_LEARNSET_UNAVAILABLE'
  );
});
