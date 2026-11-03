'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('resolveRaidGym numérico e por id', () => {
  assert.equal(present.resolveRaidGym('1'), 'pedra');
  assert.equal(present.resolveRaidGym('5'), 'fogo');
  assert.equal(present.resolveRaidGym('agua'), 'agua');
  assert.equal(present.resolveRaidGym('nope'), null);
});

test('formatRaidOpenHelp lista ginásios', () => {
  const t = present.formatRaidOpenHelp('!pk');
  assert.match(t, /Abrir raid/);
  assert.match(t, /1\. .*Pedra/);
  assert.match(t, /!pk raid abrir/);
});

test('formatRaidOpened e cancelada', () => {
  assert.match(present.formatRaidOpened({
    raid: { ginasio: 'pedra', nomeGinasio: '🪨 Pedra', liga: 'iniciante',
      chefe: { nome: 'Onix' } }
  }, '!pk'), /Onix/);
  assert.match(present.formatRaidCancelled({
    raid: { ginasio: 'pedra', nomeGinasio: 'Pedra' }
  }, '!pk'), /cancelada/);
});

test('status mostra máx 5, chefe, ginásio, participantes e prazo', () => {
  const t = present.formatRaidStatus({
    agora: 1000,
    raid: {
      fase: 'inscricoes', liga: 'iniciante', nParticipantes: 2,
      chefe: { nome: 'Onix' }, ginasio: 'pedra', nomeGinasio: '🪨 Pedra',
      expira: 121000,
      participantes: [
        { nome: 'Ash', voce: true },
        { nome: 'Misty' }
      ]
    }
  }, '!pk');
  assert.match(t, /Onix/);
  assert.match(t, /Pedra/);
  assert.match(t, /2\/5/);
  assert.match(t, /Ash/);
  assert.match(t, /Misty/);
  assert.match(t, /⏱️/);
});
