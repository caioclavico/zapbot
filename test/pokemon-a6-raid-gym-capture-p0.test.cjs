'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const RARIDADES = ['comum', 'incomum', 'raro', 'epico', 'lendario', 'mitico'];

test('contrato captura: raridade canônica → label PT (sem ?)', () => {
  const labels = {
    comum: 'Comum',
    incomum: 'Incomum',
    raro: 'Raro',
    epico: 'Épico',
    lendario: 'Lendário',
    mitico: 'Mítico'
  };
  for (const r of RARIDADES) {
    const text = present.formatCaptureMenu({
      encounter: {
        aguardandoCaptura: true,
        pokemonO: { nome: 'Mon', species: 'mon', raridade: r },
        inventory: [],
        chanceBaseCaptura: 40,
        tentativasCaptura: 0,
        chances: { pokebola: 40, 'grande-bola': 60, 'ultra-bola': 80 }
      }
    }, '!pk');
    assert.match(text, new RegExp(`Raridade: ${labels[r]}`));
    assert.equal(text.includes('Raridade: ?'), false);
  }
});

test('contrato captura: raridade ausente → fallback explícito (não ?)', () => {
  const errs = [];
  const prev = console.error;
  console.error = (...args) => errs.push(args.join(' '));
  try {
    const text = present.formatCaptureMenu({
      encounter: {
        aguardandoCaptura: true,
        pokemonO: { nome: 'Pidgey', species: 'pidgey' },
        inventory: [],
        chanceBaseCaptura: 50,
        tentativasCaptura: 0,
        chances: { pokebola: 50 }
      }
    }, '!pk');
    assert.match(text, /Raridade: indisponível/);
    assert.equal(text.includes('Raridade: ?'), false);
    assert.ok(errs.some((e) => /missing canonical raridade/.test(e)));
  } finally {
    console.error = prev;
  }
});

test('raid UI: efetividade + desmaio 1-mon (sem reserva)', () => {
  const text = present.formatRaidAttack({
    dano: 27,
    multiplicador: 2,
    revide: 10,
    trocou: false,
    eliminado: true,
    hpAtivo: 0,
    hpChefeAntes: 142,
    pokemonAtacante: { nome: 'Blastoise' },
    golpe: { nomeExibicao: "Jato d'Água", tipo: 'water', poder: 40, classe: 'especial' },
    raid: {
      turnIndex: 4,
      hpChefe: 115,
      hpMax: 200,
      fase: 'combate',
      ordem: ['ash', 'misty'],
      vez: 'misty',
      vezNome: 'Misty',
      suaVez: false,
      vezHp: 90,
      chefe: { nome: 'Charizard', tipos: ['fire'], raridade: 'raro' },
      vezPokemon: {
        nome: 'Starmie', raridade: 'raro', hp: 100,
        golpes: [{ nomeExibicao: 'Water Gun', tipo: 'water', poder: 40, classe: 'especial' }]
      }
    }
  }, '!pk');
  assert.match(text, /RAID — TURNO 4/);
  assert.match(text, /Jato d'Água.*Blastoise.*causou 27 de dano/);
  assert.match(text, /🔮/);
  assert.match(text, /É super efetivo/);
  assert.match(text, /142→115/);
  assert.match(text, /Blastoise desmaiou/);
  assert.match(text, /ficou fora|raid continua/i);
  assert.doesNotMatch(text, /entrou na batalha/);
  const barLines = text.split('\n').filter((l) => /[█░]/.test(l));
  assert.equal(barLines.length, 2);
});

test('raid captura UI: camelCase wire + aliases de estoque', () => {
  // JVM json/camel-key: grande-bola → grandeBola; estoque via capturaUi.bolas ou inventory.
  const text = present.formatRaidCaptureUi({
    capturaUi: {
      bolas: { pokebola: 5, grandeBola: 3, ultraBola: 1 },
      chances: { pokebola: 35, grandeBola: 52, ultraBola: 70 },
      frambo: 2,
      tentativas: 0,
      maxTentativas: 3,
      chefe: 'Onix'
    }
  }, '!pk');
  assert.match(text, /Grande Bola — 52% · estoque 3/);
  assert.match(text, /Ultra Bola — 70% · estoque 1/);
  assert.match(text, /Poké Bola — 35% · estoque 5/);
  assert.doesNotMatch(text, /\?%/);

  const viaInv = present.formatRaidCaptureUi({
    capturaUi: {
      bolas: {},
      chances: {},
      chanceBase: 35,
      tentativas: 0,
      maxTentativas: 3,
      chefe: 'Onix'
    },
    inventory: [
      { itemId: 'pokebola', qty: 2 },
      { itemId: 'great-ball', qty: 4 },
      { itemId: 'ultra-bola', qty: 1 }
    ]
  }, '!pk');
  assert.match(viaInv, /Grande Bola — 53% · estoque 4/);
  assert.match(viaInv, /Ultra Bola — 70% · estoque 1/);
});

test('caça: raridade só no adversário; barras HP nos dois', () => {
  const text = present.formatBattleState({
    pokemonX: {
      nome: 'Wartortle', species: 'wartortle', hp: 100, hpAtual: 80,
      raridade: 'raro',
      golpes: [{ nomeExibicao: 'Water Gun', tipo: 'water', poder: 40, classe: 'especial' }]
    },
    pokemonO: {
      nome: 'Charmander', species: 'charmander', hp: 90, hpAtual: 90,
      raridade: 'incomum', tipos: ['fire']
    },
    hp: { x: 80, o: 90 },
    status: {}
  }, '!pk');
  assert.match(text, /Charmander.*Incomum/);
  assert.match(text, /Wartortle/);
  // Raridade do jogador não aparece junto do nome/barra.
  assert.doesNotMatch(text, /Wartortle[^\n]*Raro/);
  assert.match(text, /\[█+░*\] 80\/100/);
  assert.match(text, /\[█+░*\] 90\/90/);
});

test('raid UI: exatamente 2 barras + raridade chefe e jogador', () => {
  const text = present.formatRaidTurnBlock({
    fase: 'combate',
    vez: 'ash',
    vezNome: 'Ash',
    suaVez: true,
    vezHp: 67,
    hpChefe: 140,
    hpMax: 220,
    chefe: { nome: 'Onix', raridade: 'raro', tipos: ['rock', 'ground'] },
    vezPokemon: {
      nome: 'Wartortle',
      raridade: 'incomum',
      hp: 100,
      hpAtual: 67,
      golpes: [
        { nome: 'Water Gun', tipo: 'water', poder: 40, classe: 'especial' },
        { classe: 'status', poder: 0, tipo: 'normal' }
      ]
    }
  }, '!pk').join('\n');
  assert.match(text, /Onix.*Raro/);
  assert.match(text, /Wartortle.*Incomum/);
  assert.match(text, /\[█+░*\] 140\/220/);
  assert.match(text, /\[█+░*\] 67\/100/);
  const barLines = text.split('\n').filter((l) => /[█░]/.test(l));
  assert.equal(barLines.length, 2);
  assert.match(text, /Water Gun/);
  assert.doesNotMatch(text, /\*\\?\*/);
  assert.match(text, /Golpe Normal|indisponível na raid/);
});

test('vitória: rodapé captura pendente vs resolvida', () => {
  const pend = present.formatRaidVictory({
    capturaPendente: true,
    raid: {
      fase: 'vitoria',
      chefe: { nome: 'Onix' },
      participantes: [{ playerId: 'ash@lid', nome: 'Ash', pokemon: { nome: 'Wartortle' } }]
    },
    rewards: [{ playerId: 'ash@lid', nome: 'Ash', moedas: 50, pe: 6, xpPokemon: 6 }]
  }, '!pk');
  assert.match(pend.text, /RAID CONCLUÍDA/);
  assert.match(pend.text, /CAPTURA DISPONÍVEL/);
  assert.match(pend.text, /raid capturar/);
  assert.match(pend.text, /Próximos comandos/);

  const done = present.formatRaidVictory({
    raid: {
      fase: 'vitoria',
      chefe: { nome: 'Onix' },
      participantes: []
    },
    rewards: []
  }, '!pk');
  assert.match(done.text, /Próximos comandos/);
  assert.match(done.text, /!pk raid/);
  assert.doesNotMatch(done.text, /CAPTURA DISPONÍVEL/);
});

test('status sem raid: mensagem limpa (não vitória presa)', () => {
  const text = present.formatRaidStatus({ raid: null }, '!pk');
  assert.match(text, /Nenhuma raid em andamento/);
  assert.match(text, /raid abrir/);
});
