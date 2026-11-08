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

test('raid UI: efetividade + desmaio com reserva continua', () => {
  const text = present.formatRaidAttack({
    dano: 27,
    multiplicador: 2,
    revide: 10,
    trocou: true,
    hpChefeAntes: 142,
    pokemonAtacante: { nome: 'Blastoise' },
    pokemonAtivo: { nome: 'Gyarados' },
    golpe: { nomeExibicao: "Jato d'Água", tipo: 'water', poder: 40, classe: 'especial' },
    raid: {
      turnIndex: 4,
      hpChefe: 115,
      hpMax: 200,
      fase: 'combate',
      ordem: ['ash'],
      vez: 'ash',
      chefe: { nome: 'Charizard', tipos: ['fire'] }
    }
  }, '!pk');
  assert.match(text, /RAID — TURNO 4/);
  assert.match(text, /Jato d'Água.*Blastoise.*causou 27 de dano/);
  assert.match(text, /🔮/); // emoji classe especial (caça)
  assert.match(text, /É super efetivo/);
  assert.match(text, /142→115/);
  assert.match(text, /Blastoise desmaiou/);
  assert.match(text, /Gyarados.*entrou/);
  assert.match(text, /A raid continua/);
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
