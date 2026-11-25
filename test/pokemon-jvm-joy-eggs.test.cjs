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
  assert.equal(present.resolveCommand(['incubacao']), 'incubacao');
  assert.equal(present.resolveCommand(['chocar']), 'chocar');
});

test('formatJoyStatus e send/collect', () => {
  assert.match(present.formatJoyStatus({
    feridos: [{ collectionIndex: 0, nome: 'Rattata', hp: 40, hpAtual: 10 }],
    enfermaria: [],
    tempoTratamentoMinutos: 30
  }, '!pk'), /Feridos/);
  assert.match(present.formatJoySend({
    pokemon: { nome: 'Rattata' },
    tempoTratamentoSegundos: 1800
  }), /30 minutos/);
  assert.match(present.formatJoyStatus({
    enfermaria: [{
      pokemon: { nome: 'Rattata' },
      pronto: false,
      restanteMs: 25 * 60 * 1000
    }],
    prontos: 0,
    tempoTratamentoSegundos: 1800
  }, '!pk'), /25 minutos/);
  assert.match(present.formatJoyCollect({ n: 1, curados: [{ nome: 'Rattata' }] }), /curado/);
  assert.equal(present.formatDurationPt(1800), '30 minutos');
  assert.equal(present.formatDurationPt(90), '1m 30s');
});

test('formatAdventureStatus e hatch', () => {
  const text = present.formatAdventureStatus({
    inventarioCount: 1,
    limiteInventario: 9,
    piDaily: { points: 20, cap: 150, restante: 130 },
    eggs: [{ eggId: 'e1', category: 'comum', state: 'inventory', pointsGoal: 100, progressPct: 0 }],
    incubators: [{ incubatorId: 'permanente', kind: 'permanente', livre: true }]
  }, '!pk');
  assert.match(text, /Ovos disponíveis/);
  assert.match(text, /Para incubar agora/);
  assert.doesNotMatch(text, /Pichu|PI hoje/);
  assert.match(text, /espécie só é revelada/i);
  assert.match(present.formatEggHatch({
    registro: { nome: 'Pichu', nivel: 1, shiny: true }
  }), /SEU OVO CHOCOU/);
  assert.match(present.formatEggHatch({
    collectionFull: true, pending: true
  }), /Coleção cheia/);
  assert.match(present.formatIncubationHelp({
    piDaily: { points: 10, cap: 150, restante: 140 },
    atividades: [{ label: 'Captura selvagem', pi: 15 }]
  }, '!pk'), /Pontos de Incubação/);
});

test('resolveEggIndex', () => {
  const data = {
    eggs: [
      { eggId: 'a', state: 'inventory' },
      { eggId: 'b', state: 'hatched' },
      { eggId: 'c', state: 'incubating' },
      { eggId: 'd', state: 'inventory' }
    ]
  };
  assert.equal(present.resolveEggIndex(data, 1).eggId, 'a');
  assert.equal(present.resolveEggIndex(data, 2).eggId, 'd');
  assert.equal(present.resolveEggIndex(data, 3), null);
});

test('resolveHatchEggIndex só lista prontos', () => {
  const data = {
    eggs: [
      { eggId: 'inv', state: 'inventory' },
      { eggId: 'ready', state: 'ready-to-hatch' },
      { eggId: 'pending', state: 'delivery-pending' },
      { eggId: 'inc', state: 'incubating' }
    ]
  };
  assert.equal(present.resolveHatchEggIndex(data, 1).eggId, 'ready');
  assert.equal(present.resolveHatchEggIndex(data, 2).eggId, 'pending');
  assert.equal(present.resolveHatchEggIndex(data, 3), null);
  assert.equal(present.resolveEggIndex(data, 1).eggId, 'inv');
});
