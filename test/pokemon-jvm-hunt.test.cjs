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
  assert.match(present.formatHuntEligibility({
    eligibilidade: {
      temTreinador: true,
      temPokemon: true,
      ativoVivo: true,
      cooldownOk: false,
      cooldownRestanteS: 1800,
      semBatalhaPropria: true,
      chatLivre: true
    }
  }), /30 minutos/);
  assert.match(present.formatError({
    code: 'HUNT_COOLDOWN',
    details: { restanteMs: 1800 * 1000 }
  }), /30 minutos/);
  assert.match(present.formatError({ code: 'BATTLE_PENDING' }), /batalha\/encontro em andamento|iniciar outra caçada/i);
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
  // Mensagens distintas: Node só retenta STALE (1x); CONFLICT não é auto-retry.
  assert.match(present.formatError({ code: 'STALE_WRITER_FENCE' }), /obsoleto|ativar|saves/i);
  assert.match(present.formatError({ code: 'WRITER_FENCE_CONFLICT' }), /Conflito ao gravar|tente de novo/i);
  assert.notEqual(
    present.formatError({ code: 'STALE_WRITER_FENCE' }),
    present.formatError({ code: 'WRITER_FENCE_CONFLICT' })
  );
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
      selvagem: {
        nome: 'Magikarp', raridade: 'comum', shiny: false,
        hp: 50, hpAtual: 50, tipos: ['water']
      },
      somenteBatalha: false,
      surto: false,
      battle: {
        hp: { x: 90, o: 50 },
        pokemonX: {
          nome: 'Squirtle', hp: 90, hpAtual: 90,
          golpes: [{ nomeExibicao: 'Jato', tipo: 'water', poder: 40, classe: 'especial', precisao: 100 }]
        },
        pokemonO: { nome: 'Magikarp', hp: 50, hpAtual: 50, raridade: 'comum', tipos: ['water'] }
      }
    }
  );
  assert.match(text, /Praia Azul/);
  assert.match(text, /Magikarp/);
  assert.match(text, /Jato/);
  assert.match(text, /90\/90/);
  const win = present.formatBattleTurn({
    aguardandoCaptura: true,
    chanceBaseCaptura: 80,
    inventory: [{ itemId: 'pokebola', qty: 1 }],
    battle: { pokemons: { o: { nome: 'Abra' } }, pokemonO: { nome: 'Abra' } }
  });
  assert.match(win, /capturar/);
  assert.match(win, /CHANCES DE CAPTURA|Capturar:|Escolha sua Poké Bola/);
  assert.doesNotMatch(win, /carimbada/i);
});

test('aliases numéricos 1–4 → atacar', () => {
  assert.equal(present.resolveCommand(['1']), 'atacar');
  assert.equal(present.resolveCommand(['4']), 'atacar');
});

test('suavizarStat espelha legado', () => {
  assert.equal(pokeapi.suavizarStat(75), 75);
  assert.equal(pokeapi.suavizarStat(45), 60);
  assert.equal(pokeapi.suavizarStat(105), 90);
});

test('pokemonDeDados expõe hp-base oficial sem alterar suavizar de atk/def', () => {
  const poke = pokeapi.pokemonDeDados({
    name: 'snorlax',
    species: { name: 'snorlax' },
    types: [{ type: { name: 'normal' } }],
    abilities: [{ ability: { name: 'immunity' }, is_hidden: false }],
    moves: [],
    stats: [
      { base_stat: 160, stat: { name: 'hp' } },
      { base_stat: 110, stat: { name: 'attack' } },
      { base_stat: 65, stat: { name: 'defense' } },
      { base_stat: 65, stat: { name: 'special-attack' } },
      { base_stat: 110, stat: { name: 'special-defense' } },
      { base_stat: 30, stat: { name: 'speed' } }
    ],
    sprites: {}
  });
  assert.equal(poke.hpBase, 160);
  assert.equal(poke['hp-base'], 160);
  assert.equal(poke.hp, pokeapi.suavizarStat(160));
  assert.equal(poke.ataque, pokeapi.suavizarStat(110));
  const dados = pokeapi.dadosEvolucao(pokeapi.escalarNivel(poke, 20));
  assert.equal(dados.hpBase, 160);
  assert.equal(dados['hp-base'], 160);
});

test('finalizeWild omite taxa-captura quando species falha', async () => {
  const poke = await pokeapi.finalizeWild({
    nome: 'Fantasma',
    species: '__species_inexistente_m961__',
    slugEspecie: '__species_inexistente_m961__',
    tipos: ['normal'],
    hp: 20, ataque: 10, defesa: 20, atqEsp: 10, defEsp: 20, veloc: 80,
    nivel: 1,
    movesBrutos: []
  });
  assert.ok(!Object.prototype.hasOwnProperty.call(poke, 'taxa-captura'));
  assert.notStrictEqual(poke['taxa-captura'], null);
  assert.equal(poke['atq-esp'], 10);
  assert.equal(poke['def-esp'], 20);
  assert.ok(Array.isArray(poke.golpes));
  assert.equal(poke.taxaCaptura, undefined);
});
