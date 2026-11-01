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

test('formatPvpBattle mantém X acima e O abaixo (assentos fixos)', () => {
  const battle = {
    phase: 'active',
    active: true,
    vez: 'o',
    turnIndex: 1,
    nomes: { x: 'Ash', o: 'Misty' },
    jogadores: { x: 'ash', o: 'misty' },
    pokemonX: {
      nome: 'Pikachu', hp: 45, hpAtual: 40,
      golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    pokemonO: {
      nome: 'Squirtle', hp: 44, hpAtual: 30,
      golpes: [{ nomeExibicao: 'Jato', tipo: 'water', poder: 40, classe: 'especial' }],
      tipos: ['water']
    },
    hp: { x: 40, o: 30 }
  };
  const text = present.formatPvpBattle(battle, '!pk', { mySide: 'o', suaVez: true });
  assert.match(text, /Vez de \*Misty\*/);
  assert.match(text, /Ash.*Pikachu[\s\S]*Misty.*Squirtle/);
  assert.match(text, /Jato/);
  const idxAsh = text.indexOf('Ash');
  const idxMisty = text.indexOf('Misty', idxAsh + 1);
  assert.ok(idxAsh >= 0 && idxMisty > idxAsh, 'Ash (X) aparece antes de Misty (O)');
});

test('pvpViewForSide nunca espelha assentos (evita barras alternando)', () => {
  const battle = {
    vez: 'o',
    pokemonX: { nome: 'Pikachu' },
    pokemonO: { nome: 'Squirtle' },
    hp: { x: 40, o: 30 },
    nomes: { x: 'Ash', o: 'Misty' }
  };
  const fromO = present.pvpViewForSide(battle, 'o');
  assert.equal(fromO.swapped, false);
  assert.equal(fromO.battle.pokemonX.nome, 'Pikachu');
  assert.equal(fromO.battle.pokemonO.nome, 'Squirtle');
  assert.equal(fromO.battle.hp.x, 40);
  assert.equal(fromO.battle.vez, 'o');
});

test('formatPvpBattle vitória mostra XP e PE', () => {
  const text = present.formatPvpBattle({
    phase: 'finished', outcome: 'x',
    nomes: { x: 'Ash', o: 'Misty' },
    pokemonX: { nome: 'Pikachu', hp: 45, hpAtual: 10 },
    pokemonO: { nome: 'Squirtle', hp: 44, hpAtual: 0 },
    hp: { x: 10, o: 0 }
  }, '!pk', {
    rewards: {
      moedas: 10, pe: 1, bola: 'pokebola',
      pokemon: [{ treinador: 'Ash', nome: 'Pikachu', nocautes: 1, xp: 3, subidas: 0 }]
    }
  });
  assert.match(text, /Ash.*venceu/);
  assert.match(text, /\+10 moedas/);
  assert.match(text, /\+1 PE/);
  assert.match(text, /\+3 XP/);
});

test('formatPvpBattle com vez string tolera suaVez do DTO', () => {
  const battle = {
    phase: 'active', active: true, vez: 'o', suaVez: true,
    nomes: { x: 'Ash', o: 'Misty' },
    jogadores: { x: 'ash', o: 'misty' },
    pokemonX: {
      nome: 'Pikachu', hp: 45, hpAtual: 40,
      golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    pokemonO: {
      nome: 'Squirtle', hp: 44, hpAtual: 30,
      golpes: [{ nomeExibicao: 'Jato', tipo: 'water', poder: 40, classe: 'especial' }],
      tipos: ['water']
    },
    hp: { x: 40, o: 30 },
    lastAction: { mensagem: '⚡ *Choque* causou *12* de dano!' }
  };
  const text = present.formatPvpBattle(battle, '!pk', { mySide: 'o', suaVez: true });
  assert.match(text, /causou \*12\* de dano/);
  assert.match(text, /Vez de \*Misty\*/);
  assert.match(text, /Squirtle/);
  assert.match(text, /atacar/);
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
