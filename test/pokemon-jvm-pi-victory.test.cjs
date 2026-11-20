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

test('raide divulga recompensas por participante e atalho de captura', () => {
  const data = {
    capturaPendente: true,
    raid: { participantes: [{ playerId: 'ash', nome: 'Ash' }, { playerId: 'misty', nome: 'Misty' }] },
    rewards: [{ playerId: 'ash' }, { playerId: 'misty' }],
    adventureActivity: [
      { playerId: 'ash', pi: 10, eggId: 'e1', eggCategory: 'raro' },
      { playerId: 'misty', pi: 30, incubatorId: 'i1', incubatorKind: 'extra' }
    ]
  };
  const { text } = present.formatRaidVictory(data);
  assert.match(text, /@Ash[^@]*\+10 PI[^@]*ovo raro/);
  assert.match(text, /@Misty[^@]*\+30 PI[^@]*incubadora extra/);
  assert.match(text, /Próximos comandos[^]*!pk raide cap/);
  assert.deepEqual(present.formatConfirmedPiLines({ ...data, duplicate: true }), []);
});

test('captura de raide resume os ganhos já creditados na vitória', () => {
  const text = present.formatRaidCapture({ capturou: true, victoryReward: {
    pe: 12, xpPokemon: 8, moedas: 5, adventureActivity: [{ pi: 30 }]
  }});
  assert.match(text, /já recebidas pela vitória[^]*\+12 PE[^]*\+30 PI[^]*\+8 XP[^]*\+5 moedas/);
  assert.doesNotMatch(present.formatRaidCapture({ capturou: true }), /PI de incubação/);
});
