'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('INSUFFICIENT_ITEM fruta → mensagem Frambo (não cartão-presente)', () => {
  const msg = present.formatError({
    code: 'INSUFFICIENT_ITEM',
    details: { 'item-id': 'fruta', qty: 0, delta: -1 }
  });
  assert.match(msg, /Frutas Frambo/i);
  assert.match(msg, /!pk mochila/);
  assert.doesNotMatch(msg, /cartao-presente|Cartão-presente|presente/i);
});

test('INSUFFICIENT_ITEM cartao-presente permanece específico', () => {
  const msg = present.formatError({
    code: 'INSUFFICIENT_ITEM',
    details: { itemId: 'cartao-presente' }
  });
  assert.match(msg, /Cartão-presente|cartao-presente/i);
  assert.doesNotMatch(msg, /Frambo/i);
});

test('formatRaidEmpty lista comandos reais sem IDs internos', () => {
  const text = present.formatRaidEmpty('!pk');
  assert.match(text, /Nenhuma raid em andamento/);
  assert.match(text, /!pk raid abrir/);
  assert.match(text, /!pk raid entrar/);
  assert.doesNotMatch(text, /raid-id|operationId|uuid/i);
});

test('formatRaidStatus sem raid usa empty canônico', () => {
  const text = present.formatRaidStatus({ raid: null }, '!pk');
  assert.match(text, /Nenhuma raid em andamento/);
});

test('formatRaidTurnBlock: exatamente 2 barras + raridade de ambos', () => {
  const lines = present.formatRaidTurnBlock({
    fase: 'combate',
    vez: 'ash@lid',
    vezNome: 'Ash',
    suaVez: true,
    hpChefe: 200,
    hpMax: 320,
    vezHp: 67,
    chefe: { nome: 'Onix', raridade: 'raro', tipos: ['rock', 'ground'] },
    vezPokemon: {
      nome: 'Wartortle',
      raridade: 'incomum',
      hp: 100,
      golpes: [{ nomeExibicao: 'Water Gun', tipo: 'water', poder: 40, classe: 'especial' }]
    }
  }, '!pk');
  const text = lines.join('\n');
  const bars = (text.match(/[█░]/g) || []).length;
  // barraHp usa █░ — pelo menos duas linhas de barra
  const barLines = lines.filter((l) => /[█░]/.test(l));
  assert.equal(barLines.length, 2, `esperado 2 barras, veio ${barLines.length}: ${text}`);
  assert.match(text, /Onix.*Raro|Onix.*raro/i);
  assert.match(text, /Wartortle.*[Ii]ncomum|Wartortle.*incomum/i);
  assert.ok(bars > 0);
});

test('formatRaidStart não duplica barra do chefe', () => {
  const text = present.formatRaidStart({
    raid: {
      fase: 'combate',
      vez: 'ash',
      vezNome: 'Ash',
      suaVez: true,
      hpChefe: 320,
      hpMax: 320,
      vezHp: 100,
      chefe: { nome: 'Boss', raridade: 'raro', tipos: ['normal'] },
      vezPokemon: {
        nome: 'Pika', raridade: 'comum', hp: 100,
        golpes: [{ nomeExibicao: 'Thunder', tipo: 'electric', poder: 80, classe: 'especial' }]
      }
    }
  }, '!pk');
  const barLines = text.split('\n').filter((l) => /[█░]/.test(l));
  assert.equal(barLines.length, 2);
  assert.equal((text.match(/❤️ Chefe:/g) || []).length, 0);
});

test('formatRaidAttack: narrativa sem 3ª barra', () => {
  const text = present.formatRaidAttack({
    dano: 40,
    multiplicador: 1,
    hpChefeAntes: 200,
    revide: 5,
    hpAtivo: 60,
    golpe: { nomeExibicao: 'Tackle', tipo: 'normal', poder: 40, classe: 'fisico' },
    pokemonAtacante: { nome: 'Pika' },
    raid: {
      fase: 'combate',
      vez: 'ash',
      vezNome: 'Ash',
      suaVez: true,
      turnIndex: 2,
      hpChefe: 160,
      hpMax: 320,
      vezHp: 60,
      chefe: { nome: 'Boss', raridade: 'raro', tipos: ['normal'] },
      vezPokemon: {
        nome: 'Pika', raridade: 'comum', hp: 100,
        golpes: [{ nomeExibicao: 'Tackle', tipo: 'normal', poder: 40, classe: 'fisico' }]
      }
    }
  }, '!pk');
  const barLines = text.split('\n').filter((l) => /[█░]/.test(l));
  assert.equal(barLines.length, 2, text);
});

test('vitória com captura pendente orienta capturar/fruta', () => {
  const { text } = present.formatRaidVictory({
    capturaPendente: true,
    raid: {
      fase: 'vitoria',
      capturaPendente: true,
      chefe: { nome: 'Onix' },
      participantes: [{ playerId: 'ash@lid', nome: 'Ash', pokemon: { nome: 'Pika' } }]
    },
    rewards: []
  }, '!pk');
  assert.match(text, /CAPTURA DISPONÍVEL/);
  assert.match(text, /!pk raid capturar/);
  assert.match(text, /!pk raid fruta/);
});

test('vitória captura concluída não prende em capturar', () => {
  const footer = present.formatRaidVictoryFooter({
    fase: 'vitoria',
    capturaPendente: false,
    captura: { capturou: true },
    inscrito: true
  }, { agora: Date.now() }, '!pk');
  const text = footer.join('\n');
  assert.match(text, /Captura concluída/);
  assert.doesNotMatch(text, /CAPTURA DISPONÍVEL/);
});
