'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('formatConfirmedPiLines: PI + progresso before→after', () => {
  const lines = present.formatConfirmedPiLines({
    adventureActivity: [{
      pi: 15,
      eggsProgress: [{ category: 'comum', pointsBefore: 40, points: 55, goal: 100 }]
    }]
  });
  assert.ok(lines.some((l) => l.includes('+15 PI')));
  assert.ok(lines.some((l) => /40 → 55\/100/.test(l)));
});

test('formatConfirmedPiLines: ovo sem revelar espécie', () => {
  const lines = present.formatConfirmedPiLines({
    adventureActivity: [{ pi: 0, eggId: 'egg:1', eggCategory: 'raro' }]
  });
  assert.ok(lines.some((l) => /ENCONTROU UM OVO/i.test(l)));
  assert.ok(lines.some((l) => /Raridade: Raro/.test(l)));
  assert.ok(!lines.some((l) => /pikachu|species/i.test(l)));
});

test('resolveScopedCommand: cacar/raid/pvp explícitos', () => {
  assert.equal(present.resolveScopedCommand(['cacar', 'atacar', '1']).cmd, 'atacar');
  assert.equal(present.resolveScopedCommand(['cacar', 'atacar', '1']).scope, 'cacar');
  assert.equal(present.resolveScopedCommand(['raid', 'atacar', '2']).scope, 'raid');
  assert.equal(present.resolveScopedCommand(['pvp', 'defender']).cmd, 'defender');
  assert.equal(present.resolveScopedCommand(['atacar', '1']).scope, null);
});

test('ambiguousBattlePrompt lista comandos explícitos', () => {
  const text = present.ambiguousBattlePrompt('!pk', ['raid', 'cacar']);
  assert.match(text, /raid atacar/);
  assert.match(text, /cacar atacar/);
});

test('formatRaidItemUse mostra cura e revide', () => {
  const text = present.formatRaidItemUse({
    itemId: 'pocao-maxima',
    pokemonAtivo: { nome: 'Charizard' },
    hpAntes: 12,
    hpDepois: 120,
    hpMax: 120,
    hpAposRevide: 85,
    revide: 35
  }, '!pk');
  assert.match(text, /POÇÃO MÁXIMA/);
  assert.match(text, /12\/120/);
  assert.match(text, /120\/120/);
  assert.match(text, /Dano recebido: 35/);
  assert.match(text, /85\/120/);
});

test('formatCaptureResult inclui recompensas PI confirmadas', () => {
  const text = present.formatCaptureResult({
    capturou: true,
    bola: 'pokebola',
    chance: 50,
    indice: 0,
    registro: { nome: 'Pikachu' },
    recompensa: { xp: 12, pe: 8, moedas: 0, sequencia: 1 },
    adventureActivity: [{
      pi: 15,
      eggsProgress: [{ category: 'comum', pointsBefore: 40, points: 55, goal: 100 }]
    }]
  }, '!pk');
  assert.match(text, /CAPTURADO/);
  assert.match(text, /\+15 PI/);
  assert.match(text, /40 → 55\/100/);
});
