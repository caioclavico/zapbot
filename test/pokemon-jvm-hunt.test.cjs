'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const pokeapi = require('../scripts/lib/pokemon-pokeapi.cjs');

test('aliases cacar/atacar e elegibilidade', () => {
  assert.equal(present.resolveCommand(['cacar']), 'cacar');
  assert.equal(present.resolveCommand(['cac']), 'cacar');
  assert.equal(present.resolveCommand(['atacar']), 'atacar');
  assert.equal(present.resolveCommand(['atk']), 'atacar');
  const blocked = present.formatHuntEligibility({
    eligibilidade: {
      temTreinador: true,
      temPokemon: true,
      ativoVivo: true,
      cooldownOk: false,
      cooldownRestanteS: 40,
      semBatalhaPropria: true,
      chatLivre: true
    }
  });
  assert.match(blocked, /40s/);
  assert.equal(present.formatHuntEligibility({
    eligibilidade: {
      temTreinador: true,
      temPokemon: true,
      ativoVivo: true,
      cooldownOk: true,
      semBatalhaPropria: true,
      chatLivre: true
    }
  }), null);
});

test('formatHuntStart e formatBattleTurn', () => {
  const text = present.formatHuntStart(
    {
      area: { id: 'praia', nome: 'Praia Azul', emoji: '🏖️' },
      clima: { id: 'chuvoso', nome: 'Chuvoso', emoji: '🌧️', tipos: ['water'] },
      evento: { nome: 'Festival' },
      surto: false
    },
    {
      selvagem: { nome: 'Magikarp', raridade: 'comum', shiny: false },
      somenteBatalha: false,
      surto: false
    }
  );
  assert.match(text, /Praia Azul/);
  assert.match(text, /Magikarp/);
  assert.match(present.formatBattleTurn({
    aguardandoCaptura: true,
    chanceBaseCaptura: 80,
    battle: { pokemons: { o: { nome: 'Abra' } } }
  }), /capturar/);
});

test('suavizarStat espelha legado', () => {
  assert.equal(pokeapi.suavizarStat(75), 75);
  assert.equal(pokeapi.suavizarStat(45), 60);
  assert.equal(pokeapi.suavizarStat(105), 90);
});
