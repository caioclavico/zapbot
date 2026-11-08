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
    golpe: { nomeExibicao: "Jato d'Água", tipo: 'water', poder: 40 },
    raid: {
      turnIndex: 4,
      hpChefe: 115,
      hpMax: 200,
      fase: 'combate',
      ordem: ['ash'],
      vez: 'ash'
    }
  }, '!pk');
  assert.match(text, /RAID — TURNO 4/);
  assert.match(text, /Blastoise.*Jato d'Água/);
  assert.match(text, /É super efetivo/);
  assert.match(text, /142 → 115/);
  assert.match(text, /Blastoise desmaiou/);
  assert.match(text, /Gyarados entrou/);
  assert.match(text, /A raid continua/);
});
