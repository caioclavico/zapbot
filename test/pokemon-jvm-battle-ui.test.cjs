'use strict';

/**
 * Regressão UI de caça/batalha (estilo legado): HP, golpes, índice 1–4, HP zero.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

const squirtle = {
  nome: 'Squirtle',
  species: 'squirtle',
  tipos: ['water'],
  hp: 100,
  hpAtual: 100,
  nivel: 5,
  golpes: [
    { nomeExibicao: "Jato d'Água", tipo: 'water', poder: 40, classe: 'especial', precisao: 100 },
    { nomeExibicao: 'Investida', tipo: 'normal', poder: 40, classe: 'fisico', precisao: 100 },
    { nomeExibicao: 'Retirada', tipo: 'water', poder: 0, classe: 'status', alvo: 'proprio' },
    { nomeExibicao: 'Bolha', tipo: 'water', poder: 40, classe: 'especial', precisao: 100 }
  ]
};

const magikarp = {
  nome: 'Magikarp',
  species: 'magikarp',
  tipos: ['water'],
  raridade: 'comum',
  hp: 80,
  hpAtual: 80,
  nivel: 3,
  golpes: [
    { nomeExibicao: 'Splash', tipo: 'normal', poder: 0, classe: 'status' }
  ]
};

const praia = { id: 'praia', nome: 'Praia Azul', emoji: '🏖️', ceu: '#7dd3fc', chao: '#eab308' };
const clima = { id: 'chuvoso', nome: 'Chuvoso', emoji: '🌧️', tipos: ['water'] };

test('resolveCommand mapeia !pk 1–4 para atacar', () => {
  assert.equal(present.resolveCommand(['1']), 'atacar');
  assert.equal(present.resolveCommand(['2']), 'atacar');
  assert.equal(present.resolveCommand(['3']), 'atacar');
  assert.equal(present.resolveCommand(['4']), 'atacar');
  assert.equal(present.resolveCommand(['5']), null);
  assert.equal(present.resolveCommand(['0']), null);
});

test('A1 aliases defender / clima e formatBattleTurn item/defend', () => {
  assert.equal(present.resolveCommand(['defender']), 'defender');
  assert.equal(present.resolveCommand(['def']), 'defender');
  assert.equal(present.resolveCommand(['esquivar']), 'defender');
  assert.equal(present.resolveCommand(['clima']), 'clima');
  assert.equal(present.resolveCommand(['areas']), 'clima');
  assert.equal(present.resolveCommand(['mapa']), 'clima');

  const defText = present.formatBattleTurn({
    battle: {
      active: true,
      turnIndex: 1,
      area: praia,
      hp: { x: 90, o: 70 },
      pokemonX: { ...squirtle, hpAtual: 90 },
      pokemonO: { ...magikarp, hpAtual: 70 },
      lastAction: { action: 'defend' }
    }
  }, '!pk');
  assert.match(defText, /defensiv/);
  assert.match(defText, /defender/);

  const potText = present.formatBattleTurn({
    battle: {
      active: true,
      turnIndex: 2,
      area: praia,
      hp: { x: 100, o: 70 },
      pokemonX: { ...squirtle, hpAtual: 100 },
      pokemonO: { ...magikarp, hpAtual: 70 },
      lastAction: { action: 'item', itemId: 'pocao', hpAntes: 60, hpDepois: 100, hpMax: 100 }
    }
  }, '!pk');
  assert.match(potText, /pocao|poção|HP/i);
  assert.match(potText, /60→100/);
});

test('formatWorldInfo lista clima e áreas', () => {
  const text = present.formatWorldInfo({
    clima: { id: 'chuvoso', nome: 'Chuvoso', emoji: '🌧️', tipos: ['water'] },
    mundo: {
      clima: { id: 'chuvoso', nome: 'Chuvoso', emoji: '🌧️', tipos: ['water'] },
      areaPadrao: { id: 'praia', nome: 'Praia Azul', emoji: '🏖️' },
      areasDisponiveis: [
        { id: 'praia', nome: 'Praia Azul', emoji: '🏖️', tipos: ['water'] }
      ],
      areasIndisponiveis: []
    }
  }, '!pk');
  assert.match(text, /Chuvoso/);
  assert.match(text, /Praia Azul/);
  assert.match(text, /cacar/);
});

test('barraHp proporcional (10 segmentos) e HP zero', () => {
  assert.equal(present.barraHp(50, 100), '[█████░░░░░] 50/100');
  assert.equal(present.barraHp(0, 100), '[░░░░░░░░░░] 0/100');
  assert.equal(present.barraHp(100, 100), '[██████████] 100/100');
  assert.equal(present.barraHp(1, 100), '[░░░░░░░░░░] 1/100');
});

test('formatHuntStart inclui área, raridade, HP e golpes numerados', () => {
  const text = present.formatHuntStart(
    { area: praia, clima, evento: null, surto: false },
    {
      selvagem: magikarp,
      somenteBatalha: false,
      area: praia,
      battle: {
        area: praia,
        clima,
        hp: { x: 100, o: 80 },
        pokemonX: squirtle,
        pokemonO: magikarp
      }
    },
    '!pk'
  );
  assert.match(text, /Praia Azul/);
  assert.match(text, /Chuvoso/);
  assert.match(text, /Magikarp/);
  assert.match(text, /Comum|⚪/);
  assert.match(text, /Squirtle/);
  assert.match(text, /\[██████████\] 100\/100/);
  assert.match(text, /\[██████████\] 80\/80/);
  assert.match(text, /1\. .*Jato/);
  assert.match(text, /poder 40/);
  assert.match(text, /precisão 100%/);
  assert.match(text, /!pk atacar <1-4>/);
  assert.match(text, /!pk <1-4>/);
});

test('formatBattleTurn atualiza HP após dano e lista golpes', () => {
  const text = present.formatBattleTurn({
    battle: {
      active: true,
      turnIndex: 1,
      area: praia,
      hp: { x: 72, o: 35 },
      pokemonX: { ...squirtle, hpAtual: 72 },
      pokemonO: { ...magikarp, hpAtual: 35 },
      lastAction: { dano: 45 }
    }
  }, '!pk');
  assert.match(text, /Turno 1/);
  assert.match(text, /45 de dano/);
  assert.match(text, /72\/100/);
  assert.match(text, /35\/80/);
  assert.match(text, /1\. .*Jato/);
  assert.match(text, /Investida/);
});

test('formatBattleTurn com HP zero no adversário (nocaute → captura)', () => {
  const text = present.formatBattleTurn({
    aguardandoCaptura: true,
    chanceBaseCaptura: 70,
    battle: {
      aguardandoCaptura: true,
      area: praia,
      hp: { x: 60, o: 0 },
      pokemonX: { ...squirtle, hpAtual: 60 },
      pokemonO: { ...magikarp, hpAtual: 0 }
    }
  }, '!pk');
  assert.match(text, /derrotado/);
  assert.match(text, /capturar/);
  assert.match(text, /60\/100/);
});

test('formatBattleState erro controlado sem golpes / sem HP', () => {
  assert.match(present.formatBattleState({
    hp: { x: 10, o: 10 },
    pokemonX: { nome: 'A', hp: 10, hpAtual: 10, golpes: [] },
    pokemonO: { nome: 'B', hp: 10, hpAtual: 10 }
  }, '!pk'), /Golpes ausentes/);

  assert.match(present.formatBattleState({
    pokemonX: { nome: 'A', golpes: squirtle.golpes },
    pokemonO: { nome: 'B' }
  }, '!pk'), /HP incompletos/);
});

test('reexibição: formatBattleState é puro (mesma entrada → mesmo texto)', () => {
  const battle = {
    area: praia,
    hp: { x: 55, o: 20 },
    pokemonX: { ...squirtle, hpAtual: 55 },
    pokemonO: { ...magikarp, hpAtual: 20 }
  };
  const a = present.formatBattleState(battle, '!pk');
  const b = present.formatBattleState(battle, '!pk');
  assert.equal(a, b);
  assert.match(a, /55\/100/);
  assert.match(a, /20\/80/);
});

test('menuGolpes marca efetividade contra tipos do rival', () => {
  const menu = present.menuGolpes(
    { golpes: [{ nomeExibicao: 'Chicote', tipo: 'grass', poder: 45, classe: 'fisico' }] },
    ['water'],
    null
  );
  assert.match(menu, /🔥/);
  assert.match(menu, /Planta/);
});

test('renderHuntArena desenha barras de HP sem mutar estado', async () => {
  const img = await media.renderHuntArena({
    area: praia,
    pokemonX: { ...squirtle, hp: 100, hpAtual: 40 },
    pokemonO: { ...magikarp, hp: 80, hpAtual: 0 },
    caption: 'turno',
    mostrarEntrada: false
  });
  assert.ok(img);
  assert.equal(img.mimeType, 'image/png');
  assert.ok(img.base64.length > 100);
  // Pokémon de entrada intacto (sem mutação colateral).
  assert.equal(squirtle.hpAtual, 100);
  assert.equal(magikarp.hpAtual, 80);
});
