'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases PvP batalhar/abrir/entrar/sair', () => {
  assert.equal(present.resolveCommand(['batalhar']), 'batalhar');
  assert.equal(present.resolveCommand(['abrir']), 'batalhar');
  assert.equal(present.resolveCommand(['entrar']), 'batalhar');
  assert.equal(present.resolveCommand(['sair']), 'sair');
});

test('formatPvpChallenge espelha master', () => {
  const text = present.formatPvpChallenge({
    pokemonX: { nome: 'Pikachu', nivel: 20 },
    faixaNiveis: { min: 17, max: 23 },
    nomes: { x: 'Ash' }
  }, 'Ash', '!pk');
  assert.match(text, /Desafio Pokémon/);
  assert.match(text, /Pikachu/);
  assert.match(text, /17 a 23/);
  assert.match(text, /!pk batalhar/);
});

test('formatPvpBattle indica vez e golpes', () => {
  const battle = {
    phase: 'active',
    active: true,
    vez: 'x',
    nomes: { x: 'Ash', o: 'Misty' },
    jogadores: { x: 'ash', o: 'misty' },
    pokemonX: {
      nome: 'Pikachu', hp: 45, hpAtual: 40,
      golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    pokemonO: { nome: 'Squirtle', hp: 44, hpAtual: 44 },
    hp: { x: 40, o: 44 }
  };
  const mine = present.formatPvpBattle(battle, '!pk', { mySide: 'x' });
  assert.match(mine, /Vez de \*Ash\*/);
  assert.match(mine, /atacar/);
  const wait = present.formatPvpBattle(battle, '!pk', { mySide: 'o' });
  assert.match(wait, /Aguarde/);
});

test('formatPvpBattle vitória e forfeit', () => {
  assert.match(present.formatPvpBattle({
    phase: 'finished', outcome: 'x',
    nomes: { x: 'Ash', o: 'Misty' },
    pokemonX: { nome: 'Pikachu', hp: 45, hpAtual: 10 },
    pokemonO: { nome: 'Squirtle', hp: 44, hpAtual: 0 }
  }, '!pk'), /Ash.*venceu/);
  assert.match(present.formatPvpBattle({
    phase: 'finished', outcome: 'forfeit-o',
    nomes: { x: 'Ash', o: 'Misty' },
    pokemonX: { nome: 'Pikachu', hp: 45, hpAtual: 45 },
    pokemonO: { nome: 'Squirtle', hp: 44, hpAtual: 44 }
  }, '!pk'), /Desistência/);
});

test('erros PvP mapeados', () => {
  assert.match(present.formatError({ code: 'PVP_CHAT_BUSY' }), /desafio|batalha|PvP/i);
  assert.match(present.formatError({ code: 'PVP_OUT_OF_TURN' }), /vez/i);
  assert.match(present.formatError({ code: 'PVP_NO_CHALLENGE' }), /desafio/i);
});
