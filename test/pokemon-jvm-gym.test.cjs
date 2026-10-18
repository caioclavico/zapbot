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

test('overview marca desafio bloqueado', () => {
  const text = present.formatGymsOverview({
    desafioHabilitado: false,
    desafioBloqueio: 'Combate 3×3 pendente.',
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
  assert.match(text, /bloqueado|3×3/i);
  assert.match(text, /Pedra/);
  assert.match(text, /🔒/);
});

test('desafiar/ranking bloqueados sem aceitar vitória do cliente', () => {
  assert.match(present.formatGymBlocked('desafiar'), /3×3|JVM/i);
  assert.match(present.formatGymBlocked('ranking'), /Ranking/);
  assert.match(present.formatGymBlocked('historico'), /Histórico/);
});

test('formatGymHeal e time salvo', () => {
  assert.match(present.formatGymHeal({
    gymId: 'pedra', indice: 0, antes: 88, depois: 100,
    label: 'Poção de Vida', item: 'pocao'
  }), /88% → 100%/);
  assert.match(present.formatGymTeamSaved({ indices: [0, 1, 2] }), /bloqueado/i);
});

test('formatUnavailable lista gin', () => {
  assert.match(present.formatUnavailable('batalhar'), /ginasio|gin/);
});
