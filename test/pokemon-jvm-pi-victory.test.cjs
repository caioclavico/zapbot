'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('formatConfirmedPiLines só usa PI confirmado', () => {
  assert.deepEqual(present.formatConfirmedPiLines({}), []);
  assert.deepEqual(present.formatConfirmedPiLines({
    adventureActivity: [{ activity: 'wild-victory', pi: 0, duplicate: false }]
  }), []);
  assert.deepEqual(present.formatConfirmedPiLines({
    adventureActivity: [{ activity: 'pvp-victory', pi: 20, eventId: 'egg-act:pvp:1:ash' }]
  }), ['🥚 +20 PI de incubação']);
  assert.deepEqual(present.formatConfirmedPiLines({
    adventureActivity: [
      { playerId: 'ash', pi: 20 },
      { playerId: 'misty', pi: 5 }
    ]
  }, { playerId: 'ash' }), ['🥚 +20 PI de incubação']);
});

test('vitória de caça anuncia PI confirmado', () => {
  const text = present.formatBattleTurn({
    aguardandoCaptura: true,
    adventureActivity: [{ activity: 'wild-victory', pi: 15 }],
    battle: {
      kind: 'wild',
      active: false,
      aguardandoCaptura: true,
      pokemonX: { nome: 'Pikachu', hp: 45, hpAtual: 30 },
      pokemonO: { nome: 'Onix', hp: 80, hpAtual: 0 },
      hp: { x: 30, o: 0 },
      inventory: [{ itemId: 'pokebola', qty: 3 }]
    },
    reward: { pokemonXp: 3, pe: 1 }
  });
  assert.match(text, /VITÓRIA NA CAÇADA/);
  assert.match(text, /\+15 PI de incubação/);
});

test('não inventa PI sem adventure-activity', () => {
  const text = present.formatBattleTurn({
    aguardandoCaptura: true,
    battle: {
      kind: 'wild',
      active: false,
      aguardandoCaptura: true,
      pokemonX: { nome: 'Pikachu', hp: 45, hpAtual: 30 },
      pokemonO: { nome: 'Onix', hp: 80, hpAtual: 0 },
      hp: { x: 30, o: 0 },
      inventory: [{ itemId: 'pokebola', qty: 3 }]
    },
    reward: { pokemonXp: 3, pe: 1 }
  });
  assert.doesNotMatch(text, /PI de incubação/);
});
