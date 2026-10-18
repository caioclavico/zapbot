'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('aliases loja/mochila/curar/pocao', () => {
  assert.equal(present.resolveCommand(['loja']), 'loja');
  assert.equal(present.resolveCommand(['mochila']), 'mochila');
  assert.equal(present.resolveCommand(['mch']), 'mochila');
  assert.equal(present.resolveCommand(['curar']), 'curar');
  assert.equal(present.resolveCommand(['cur']), 'curar');
  assert.equal(present.resolveCommand(['pocao']), 'pocao');
  assert.equal(present.resolveCommand(['pot']), 'pocao');
  assert.equal(present.resolveCommand(['pocao-maxima']), 'pocao-maxima');
});

test('formatShop e buy', () => {
  const text = present.formatShop({
    coins: 100,
    ocupacaoMochila: 2,
    capacidadeMochila: 50,
    livres: 48,
    itens: [{ itemId: 'pocao', nome: 'Poção de Vida', emoji: '🧪', preco: 20 }]
  });
  assert.match(text, /Loja Pokémon/);
  assert.match(text, /pocao/);
  assert.match(present.formatShopBuy({
    nome: 'Poção de Vida', emoji: '🧪', coins: 80
  }), /Comprou/);
});

test('formatInventory e item use', () => {
  assert.match(present.formatInventory({
    coins: 10,
    ocupacaoMochila: 1,
    capacidadeMochila: 50,
    itens: [{ itemId: 'fruta', nome: 'Fruta', emoji: '🍓', qty: 1 }]
  }), /Mochila/);
  assert.match(present.formatItemUse({
    kind: 'pocao', itemId: 'pocao', hpAntes: 10, hpDepois: 26, hpMax: 40,
    pokemon: { nome: 'Rattata' }
  }), /HP/);
});

test('kit/diario permanecem bloqueados na mensagem de inventário', () => {
  assert.match(present.formatInventory({ itens: [] }), /Kit\/diário|bloqueados/i);
});
