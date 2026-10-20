'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases ginasio/gin e subcomandos', () => {
  assert.equal(present.resolveCommand(['ginasio']), 'ginasio');
  assert.equal(present.resolveCommand(['gin']), 'ginasio');
  assert.equal(present.resolveGymSubcommand('des'), 'desafiar');
  assert.equal(present.resolveGymSubcommand('tm'), 'time');
  assert.equal(present.resolveGymSubcommand('pot'), 'pocao');
});

test('overview marca desafio habilitado no M4.8', () => {
  const text = present.formatGymsOverview({
    desafioHabilitado: true,
    gyms: [
      {
        id: 'pedra', nome: '🪨 Pedra', nivel: 10, desbloqueado: true,
        liderNpc: 'Brock', ocupacao: null
      },
      {
        id: 'agua', nome: '💧 Água', nivel: 20, desbloqueado: false,
        liderNpc: 'Misty'
      }
    ]
  });
  assert.match(text, /Desafiar/);
  assert.match(text, /Pedra/);
  assert.match(text, /🔒/);
});

test('ranking/historico ainda bloqueados; desafiar não usa formatGymBlocked', () => {
  assert.match(present.formatGymBlocked('ranking'), /Ranking/);
  assert.match(present.formatGymBlocked('historico'), /Histórico/);
  assert.equal(present.formatGymBlocked('desafiar').includes('3×3'), false);
});

test('formatGymChallengeStart e resolve vitória', () => {
  assert.match(present.formatGymChallengeStart({
    battle: {
      liderNome: 'Brock',
      pokemonX: {
        nome: 'Pikachu', hp: 80, hpAtual: 80,
        golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial', precisao: 100 }]
      },
      pokemonO: { nome: 'Geodude', hp: 70, hpAtual: 70, tipos: ['rock', 'ground'] },
      hp: { x: 80, o: 70 },
      reservasX: 2,
      reservasO: 2
    }
  }, '!pk'), /Desafio contra/);

  assert.match(present.formatGymResolve({
    venceu: true, premio: 'primeira', peDelta: 5, occupied: true,
    reward: { moedas: 100 }
  }, { gymNome: '🪨 Pedra' }), /venceu o ginásio/);
});

test('formatBattleTurn ginásio em andamento', () => {
  const text = present.formatBattleTurn({
    battle: {
      kind: 'gym',
      gymId: 'pedra',
      gymNome: '🪨 Pedra',
      liderNome: 'Brock',
      active: true,
      turnIndex: 2,
      hp: { x: 40, o: 12 },
      reservasX: 1,
      reservasO: 0,
      pokemonX: {
        nome: 'A', hp: 80, hpAtual: 40,
        golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
      },
      pokemonO: { nome: 'Onix', hp: 90, hpAtual: 12, tipos: ['rock', 'ground'] },
      lastAction: { dano: 30 }
    }
  }, '!pk');
  assert.match(text, /Ginásio|Pedra/);
  assert.match(text, /Reservas/);
  assert.match(text, /40\/80/);
  assert.match(text, /Choque/);
});

test('formatGymHeal e time salvo apontam desafiar', () => {
  assert.match(present.formatGymHeal({
    gymId: 'pedra', indice: 0, antes: 88, depois: 100,
    label: 'Poção de Vida', item: 'pocao'
  }), /88% → 100%/);
  assert.match(present.formatGymTeamSaved({ indices: [0, 1, 2] }), /desafiar/i);
});

test('formatUnavailable lista gin', () => {
  assert.match(present.formatUnavailable('batalhar'), /ginasio|gin/);
});
