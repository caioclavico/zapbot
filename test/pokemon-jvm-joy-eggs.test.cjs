'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases joy e ovos', () => {
  assert.equal(present.resolveCommand(['joy']), 'joy');
  assert.equal(present.resolveCommand(['enfermeira']), 'joy');
  assert.equal(present.resolveCommand(['hospital']), 'joy');
  assert.equal(present.resolveCommand(['ovos']), 'ovos');
  assert.equal(present.resolveCommand(['ovo']), 'ovos');
  assert.equal(present.resolveCommand(['incubar']), 'incubar');
  assert.equal(present.resolveCommand(['incubadoras']), 'incubadoras');
});

test('formatJoyStatus e send/collect', () => {
  assert.match(present.formatJoyStatus({
    feridos: [{ collectionIndex: 0, nome: 'Rattata', hp: 40, hpAtual: 10 }],
    enfermaria: [],
    tempoTratamentoMinutos: 30
  }, '!pk'), /Feridos/);
  assert.match(present.formatJoySend({
    pokemon: { nome: 'Rattata' }
  }), /Joy recebeu/);
  assert.match(present.formatJoyCollect({ n: 1, curados: [{ nome: 'Rattata' }] }), /curado/);
});

test('formatAdventureStatus e hatch', () => {
  const text = present.formatAdventureStatus({
    inventarioCount: 1,
    limiteInventario: 9,
    eggs: [{ eggId: 'e1', category: 'comum', state: 'inventory', species: 'Pichu' }],
    incubators: [{ incubatorId: 'permanente', kind: 'permanente', livre: true }]
  }, '!pk');
  assert.match(text, /Adventure Sync/);
  assert.match(text, /sob demanda|notificação automática/i);
  assert.match(present.formatEggHatch({
    registro: { nome: 'Pichu', nivel: 1 }
  }), /eclodiu/);
});

test('resolveEggIndex', () => {
  const data = {
    eggs: [
      { eggId: 'a', state: 'inventory' },
      { eggId: 'b', state: 'hatched' },
      { eggId: 'c', state: 'incubating' }
    ]
  };
  assert.equal(present.resolveEggIndex(data, 1).eggId, 'a');
  assert.equal(present.resolveEggIndex(data, 2).eggId, 'c');
  assert.equal(present.resolveEggIndex(data, 9), null);
});
