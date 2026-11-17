'use strict';
const assert = require('assert');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

const raidMsg = present.formatRaidAttack({
  dano: 42,
  revide: 10,
  multiplicador: 2,
  golpe: { nomeExibicao: 'Lanca-Chamas' },
  trocou: true,
  raid: { hpChefe: 100, hpMax: 200, vez: 'ash', ordem: ['ash'] }
}, '!pk');

assert.match(raidMsg, /super efetivo|🔥Super efetivo/i);
assert.match(raidMsg, /Super efetivo!🔥/);
assert.doesNotMatch(raidMsg, /🔥Super efetivo|Super efetivo! 🔥/);
assert.match(raidMsg, /desmaiou|ficou fora|party/i);

const menu = present.formatCaptureMenu({
  encounter: {
    aguardandoCaptura: true,
    pokemonO: { nome: 'Pidgey', raridade: 'comum' },
    inventory: [
      { itemId: 'pokebola', qty: 12 },
      { itemId: 'grande-bola', qty: 4 },
      { itemId: 'ultra-bola', qty: 2 },
      { itemId: 'fruta', qty: 3 }
    ],
    chanceBaseCaptura: 70,
    tentativasCaptura: 0,
    chances: { pokebola: 70, 'grande-bola': 95, 'ultra-bola': 95 }
  }
});
assert.match(menu, /Grande Bola: 4/);
assert.match(menu, /Pokébola: 12|Pokebola: 12/);
assert.doesNotMatch(menu, /Super Bola/);

const menuLegacy = present.formatCaptureMenu({
  encounter: {
    aguardandoCaptura: true,
    pokemonO: { nome: 'Pidgey', raridade: 'comum' },
    inventory: [
      { itemId: 'grande-bola', qty: 1 },
      { itemId: 'great-ball', qty: 2 },
      { itemId: 'super-bola', qty: 1 }
    ],
    chanceBaseCaptura: 50,
    tentativasCaptura: 0
  }
});
assert.match(menuLegacy, /Grande Bola: 4/, 'qtyBall soma aliases legado great/super');

console.log('pokemon-a6-gameplay-present.test.cjs OK');
