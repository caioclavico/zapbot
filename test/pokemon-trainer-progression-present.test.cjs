'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('level-up mostra faixa, PE, prêmios, expansão e próximo marco', () => {
  const data = {
    trainerLevelUps: [{
      trainer: 'Ash', nivelAnterior: 4, nivelAtual: 6, peRecebido: 35,
      xpAtual: 5, xpNecessario: 35,
      items: { pokebola: 2, 'pocao-maxima': 1 },
      pending: { reviver: 1 },
      niveis: [
        { nivel: 5, marcoEspaco: 'pokemon' },
        { nivel: 6 }
      ]
    }]
  };
  const text = present.appendTrainerLevelUps('Batalha concluída.', data);
  assert.match(text, /TREINADOR SUBIU DE NÍVEL/);
  assert.match(text, /Nível 6 alcançado/);
  assert.match(text, /4 → 6/);
  assert.match(text, /\+35/);
  assert.match(text, /Ash/);
  assert.match(text, /🔴 Poké Bola ×2/);
  assert.match(text, /RECOMPENSAS DESBLOQUEADAS/);
  assert.match(text, /Sem espaço; ficou pendente: 💎 Reviver ×1/);
  assert.match(text, /Armazenamento Pokémon: \+50/);
  assert.match(text, /Próximo nível: 5\/35 PE/);
  assert.match(text, /Continue sua jornada/);
});

test('level-up aceita chaves kebab e não repete notificação em retry', () => {
  const data = { 'trainer-level-ups': [{
    'nivel-anterior': 1, 'nivel-atual': 2, 'pe-recebido': 10,
    'xp-atual': 0, 'xp-necessario': 15,
    items: { 'grande-bola': 1, reviver: 1 },
    niveis: [{ nivel: 2, 'marco-espaco': 'mochila' }]
  }] };
  assert.match(present.formatTrainerLevelUps(data), /🔵 Grande Bola ×1/);
  assert.match(present.formatTrainerLevelUps(data), /Mochila: \+50/);
  assert.equal(present.appendTrainerLevelUps('OK', { ...data, duplicate: true }), 'OK');
});

test('sem crossing ou dados repetidos o texto permanece inalterado', () => {
  assert.equal(present.appendTrainerLevelUps('OK', {}), 'OK');
  assert.equal(present.appendTrainerLevelUps('OK', { trainerLevelUps: [] }), 'OK');
});

test('level-up só lista recompensas efetivamente presentes no DTO', () => {
  const text = present.formatTrainerLevelUps({
    trainerLevelUps: [{
      nivelAnterior: 11, nivelAtual: 12, peRecebido: 8,
      xpAtual: 2, xpNecessario: 40, items: {}
    }]
  });
  assert.match(text, /Nível 12 alcançado/);
  assert.doesNotMatch(text, /RECOMPENSAS DESBLOQUEADAS/);
  assert.doesNotMatch(text, /Mochila:/);
  assert.doesNotMatch(text, /moedas/i);
});
