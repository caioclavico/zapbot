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
  assert.match(text, /Ovos e incubação/);
  assert.match(text, /PI hoje/);
  assert.doesNotMatch(text, /Pichu/);
  assert.match(text, /espécie só é revelada/i);
  assert.match(present.formatEggHatch({
    registro: { nome: 'Pichu', nivel: 1, shiny: true }
  }), /eclodiu/);
  assert.match(present.formatEggHatch({
    collectionFull: true, pending: true
  }), /Coleção cheia/);
  assert.match(present.formatIncubationHelp({
    piDaily: { points: 10, cap: 150, restante: 140 },
    atividades: [{ label: 'Caça concluída', pi: 5 }]
  }, '!pk'), /Pontos de Incubação/);
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
