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

test('formatRaidAttack vitória, derrota e turno', () => {
  const win = present.coerceRaidPresent(present.formatRaidAttack({
    venceu: true, dano: 80, raid: {}
  }));
  assert.match(win.text, /RAID CONCLUÍDA/);
  const lose = present.coerceRaidPresent(present.formatRaidAttack({
    derrota: true,
    rewards: [{ playerId: 'ash@lid', moedas: 0, pe: 3, xpPokemon: 3 }],
    raid: { chefe: { nome: 'Onix' }, participantes: [{ playerId: 'ash@lid', nome: 'Ash' }] }
  }));
  assert.match(lose.text, /RAID ENCERRADA — DERROTA/);
  assert.match(lose.text, /\+3 PE/);
  assert.match(lose.text, /Moedas: 0/);
  assert.match(present.formatRaidAttack({
    dano: 40, revide: 10, raid: { hpChefe: 100, hpMax: 440, vez: 'misty' }
  }, '!pk'), /Dano 40/);
});

test('abrir/cancelar de raide saíram do formatGymBlocked (Fase 2)', () => {
  // ranking/historico de ginásio seguem bloqueados; abrir/cancelar não.
  assert.match(present.formatGymBlocked('ranking'), /Ranking/);
  assert.match(present.formatGymBlocked('historico'), /Histórico/);
  assert.doesNotMatch(present.formatGymBlocked('abrir'), /automática|admin/i);
  assert.doesNotMatch(present.formatGymBlocked('cancelar'), /Cancelar raide/);
  assert.match(present.formatRaidOpenHelp('!pk'), /Abrir raide/);
  assert.match(present.resolveRaidSubcommand('abrir'), /abrir/);
  assert.match(present.resolveRaidSubcommand('cancelar'), /cancelar/);
});
