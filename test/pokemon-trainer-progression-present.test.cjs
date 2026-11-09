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
  assert.match(text, /Ash subiu de nível/);
  assert.match(text, /Nível 4 → 6/);
  assert.match(text, /\+35 PE/);
  assert.match(text, /🔴 Poké Bola ×2/);
  assert.match(text, /Sem espaço; ficou pendente: 💎 Reviver ×1/);
  assert.match(text, /📦 \+50 espaços Pokémon/);
  assert.match(text, /Próximo nível: 5\/35 PE/);
});

test('level-up aceita chaves kebab e não repete notificação em retry', () => {
  const data = { 'trainer-level-ups': [{
    'nivel-anterior': 1, 'nivel-atual': 2, 'pe-recebido': 10,
    'xp-atual': 0, 'xp-necessario': 15,
    items: { 'grande-bola': 1, reviver: 1 },
    niveis: [{ nivel: 2, 'marco-espaco': 'mochila' }]
  }] };
  assert.match(present.formatTrainerLevelUps(data), /🔵 Grande Bola ×1/);
  assert.match(present.formatTrainerLevelUps(data), /🎒 \+50 espaços na mochila/);
  assert.equal(present.appendTrainerLevelUps('OK', { ...data, duplicate: true }), 'OK');
});

test('sem crossing ou dados repetidos o texto permanece inalterado', () => {
  assert.equal(present.appendTrainerLevelUps('OK', {}), 'OK');
  assert.equal(present.appendTrainerLevelUps('OK', { trainerLevelUps: [] }), 'OK');
});
