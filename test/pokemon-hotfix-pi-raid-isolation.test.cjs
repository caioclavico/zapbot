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

/**
 * Regressão roteador→handler: resolveScopedCommand decide cmd/scope
 * que pokemon_http usa para despachar (raid → executar-raid-jvm!).
 */
test('resolveScopedCommand: raid NUNCA promove entrar/abrir/iniciar/capturar', () => {
  const cases = [
    ['raid', 'entrar'],
    ['raide', 'abrir'],
    ['raid', 'iniciar'],
    ['raid', 'capturar', '1'],
    ['raid', 'fruta'],
    ['raid', 'sair'],
    ['raid', 'atacar', '1'],
    ['raid', 'pocao'],
    ['raid', 'pocao-maxima'],
    ['raide', 'pot'],
  ];
  for (const tokens of cases) {
    const r = present.resolveScopedCommand(tokens);
    assert.equal(r.cmd, 'raid', `cmd for ${tokens.join(' ')}`);
    assert.equal(r.scope, 'raid', `scope for ${tokens.join(' ')}`);
    assert.equal(r.resto.length, tokens.length - 1, `resto len for ${tokens.join(' ')}`);
    assert.ok(r.resto[0], `resto[0] for ${tokens.join(' ')}`);
    // Handler canônico: cmd=raid → executar-raid-jvm! (não PvP/caça).
    assert.notEqual(r.cmd, 'batalhar');
    assert.notEqual(r.cmd, 'capturar');
    assert.notEqual(r.cmd, 'atacar');
  }
});

test('resolveScopedCommand: cacar/pvp explícitos preservam ação', () => {
  assert.equal(present.resolveScopedCommand(['cacar', 'atacar', '1']).cmd, 'atacar');
  assert.equal(present.resolveScopedCommand(['cacar', 'atacar', '1']).scope, 'cacar');
  assert.deepEqual(present.resolveScopedCommand(['cacar', 'atacar', '1']).resto, ['1']);
  assert.equal(present.resolveScopedCommand(['cacar', 'defender']).cmd, 'defender');
  assert.equal(present.resolveScopedCommand(['cacar', 'pocao']).cmd, 'pocao');
  assert.equal(present.resolveScopedCommand(['cacar', 'pocao']).scope, 'cacar');
  assert.equal(present.resolveScopedCommand(['cacar', 'pocao-maxima']).cmd, 'pocao-maxima');
  assert.equal(present.resolveScopedCommand(['pvp', 'defender']).cmd, 'defender');
  assert.equal(present.resolveScopedCommand(['pvp', 'pocao-maxima']).cmd, 'pocao-maxima');
  assert.equal(present.resolveScopedCommand(['pvp', 'pocao']).scope, 'pvp');
  assert.equal(present.resolveScopedCommand(['atacar', '1']).scope, null);
  assert.equal(present.resolveScopedCommand(['raid', 'atacar', '2']).cmd, 'raid');
});

test('resolveRaidSubcommand: poção aliases', () => {
  assert.equal(present.resolveRaidSubcommand('pocao'), 'pocao');
  assert.equal(present.resolveRaidSubcommand('poção'), 'pocao');
  assert.equal(present.resolveRaidSubcommand('pot'), 'pocao');
  assert.equal(present.resolveRaidSubcommand('pocao-maxima'), 'pocao-maxima');
  assert.equal(present.resolveRaidSubcommand('pmax'), 'pocao-maxima');
  assert.equal(present.resolveRaidSubcommand('entrar'), 'entrar');
});

test('ambiguousBattlePrompt lista comandos explícitos', () => {
  const text = present.ambiguousBattlePrompt('!pk', ['raid', 'cacar']);
  assert.match(text, /Você possui mais de um combate ativo/);
  assert.match(text, /raid atacar/);
  assert.match(text, /cacar atacar/);
  assert.match(text, /Nenhuma ação foi executada/);
});

test('ambiguousBattlePrompt: kind potion', () => {
  const text = present.ambiguousBattlePrompt('!pk', ['raid', 'cacar', 'pvp'], { kind: 'potion' });
  assert.match(text, /raid pocao/);
  assert.match(text, /cacar pocao-maxima/);
  assert.match(text, /pvp pocao/);
});

test('raidCombatEligibleForPlayer: só participante em combate com HP', () => {
  assert.equal(present.raidCombatEligibleForPlayer(null, 'ash'), false);
  assert.equal(present.raidCombatEligibleForPlayer({ fase: 'inscricoes', inscrito: true }, 'ash'), false);
  assert.equal(present.raidCombatEligibleForPlayer({
    fase: 'combate', inscrito: false, participantes: []
  }, 'ash'), false);
  assert.equal(present.raidCombatEligibleForPlayer({
    fase: 'combate', inscrito: true, participantes: [{ playerId: 'ash', hp: 0, voce: true }]
  }, 'ash'), false);
  assert.equal(present.raidCombatEligibleForPlayer({
    fase: 'combate', inscrito: true, participantes: [{ playerId: 'ash', hp: 40, voce: true }]
  }, 'ash'), true);
  // Outro jogador no chat: inscrito=false → não elege
  assert.equal(present.raidCombatEligibleForPlayer({
    fase: 'combate', inscrito: false,
    participantes: [{ playerId: 'misty', hp: 100 }]
  }, 'ash'), false);
});

test('formatRaidItemUse mostra cura e revide', () => {
  const text = present.formatRaidItemUse({
    itemId: 'pocao-maxima',
    pokemonAtivo: { nome: 'Charizard' },
    hpAntes: 12,
    hpDepois: 120,
    hpMax: 120,
    hpAposRevide: 96,
    revide: 24
  }, '!pk');
  assert.match(text, /POÇÃO MÁXIMA/);
  assert.match(text, /12\/120/);
  assert.match(text, /120\/120/);
  assert.match(text, /Dano recebido: 24/);
  assert.match(text, /96\/120/);
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
