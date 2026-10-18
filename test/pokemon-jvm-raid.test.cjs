'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases raide/raid e subcomandos', () => {
  assert.equal(present.resolveCommand(['raide']), 'raid');
  assert.equal(present.resolveCommand(['raid']), 'raid');
  assert.equal(present.resolveRaidSubcommand('ent'), 'entrar');
  assert.equal(present.resolveRaidSubcommand('atk'), 'atacar');
  assert.equal(present.resolveRaidSubcommand('cap'), 'capturar');
});

test('formatRaidStatus sem raid e com combate', () => {
  assert.match(present.formatRaidStatus({ raid: null }), /Nenhuma raide/);
  assert.match(present.formatRaidStatus({
    raid: {
      fase: 'combate',
      liga: 'bronze',
      nomeGinasio: 'Pedra',
      hpChefe: 200,
      hpMax: 440,
      vez: 'ash',
      suaVez: true,
      nParticipantes: 2,
      chefe: { nome: 'Snorlax' },
      participantes: [{ nome: 'Ash', voce: true, hp: 180, dano: 40 }]
    }
  }, '!pk'), /Raide/);
});

test('formatRaidAttack vitória e turno', () => {
  assert.match(present.formatRaidAttack({
    venceu: true, dano: 80, raid: {}
  }), /Vitória/);
  assert.match(present.formatRaidAttack({
    dano: 40, revide: 10, raid: { hpChefe: 100, hpMax: 440, vez: 'misty' }
  }, '!pk'), /Dano 40/);
});

test('abrir/cancelar permanecem bloqueados', () => {
  assert.match(present.formatGymBlocked('abrir'), /automática|admin/i);
  assert.match(present.formatGymBlocked('cancelar'), /Cancelar/);
});
