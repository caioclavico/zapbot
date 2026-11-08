'use strict';
/**
 * Lote C — apresentação: mensagens canônicas, captura com qty, raid HP/efetividade.
 */
const assert = require('assert');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

// --- Raid: golpe traduzido, efetividade, troca, autodesmaio, HP antes→depois ---
const raidMsg = present.formatRaidAttack({
  dano: 42,
  revide: 10,
  multiplicador: 2,
  golpe: { nomeExibicao: 'Lança-Chamas' },
  trocou: true,
  autodesmaio: true,
  hpAtivo: 88,
  raid: { hpChefe: 100, hpMax: 200, vez: 'ash', ordem: ['ash'] }
}, '!pk');

assert.match(raidMsg, /Lança-Chamas|Lanca-Chamas/);
assert.match(raidMsg, /super efetivo/i);
assert.match(raidMsg, /próximo da party|proximo da party|party|entrou na batalha/i);
assert.match(raidMsg, /desmaiou após o golpe/i);
assert.match(raidMsg, /142\s*→\s*100|Chefe: 142/);
assert.match(raidMsg, /A raid continua/i);
assert.doesNotMatch(raidMsg, /Super Bola/);

// --- Wild turn: mensagem canônica do motor (não recalcula) ---
const turnMsg = present.formatBattleTurn({
  battle: {
    active: true,
    turnIndex: 3,
    hp: { x: 80, o: 40 },
    pokemonX: {
      nome: 'Charmander', hp: 100, hpAtual: 80,
      golpes: [{ nomeExibicao: 'Brasas', tipo: 'fire', poder: 40, classe: 'especial' }]
    },
    pokemonO: { nome: 'Oddish', hp: 100, hpAtual: 40, tipos: ['grass'] },
    lastAction: {
      action: 'attack',
      mensagem: '🔮 *Brasas* de *Charmander* causou 35 de dano em *Oddish*!\n💥 É super efetivo!\n🎯 Acerto crítico!',
      dano: 35,
      multiplicador: 2,
      critico: true,
      golpeNome: 'Brasas',
      hpAntes: 75,
      hpDepois: 40
    }
  }
}, '!pk');
assert.match(turnMsg, /Brasas/);
assert.match(turnMsg, /super efetivo/i);
assert.match(turnMsg, /crítico|critico/i);
assert.match(turnMsg, /35 de dano/);

// --- Fallback canônico sem mensagem ---
const turnFallback = present.formatBattleTurn({
  battle: {
    active: true,
    turnIndex: 1,
    hp: { x: 90, o: 50 },
    pokemonX: {
      nome: 'Pikachu', hp: 100, hpAtual: 90,
      golpes: [{ nomeExibicao: 'Choque do Trovão', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    pokemonO: { nome: 'Squirtle', hp: 100, hpAtual: 50 },
    lastAction: {
      action: 'attack',
      dano: 22,
      multiplicador: 2,
      golpeNome: 'Choque do Trovão',
      critico: true,
      hpAntes: 72,
      hpDepois: 50
    }
  }
}, '!pk');
assert.match(turnFallback, /Choque do Trovão/);
assert.match(turnFallback, /22 de dano/);
assert.match(turnFallback, /super efetivo/i);
assert.match(turnFallback, /crítico|critico/i);
assert.match(turnFallback, /72→50/);

// --- Explosion lose copy ---
const loseExp = present.formatBattleTurn({
  outcome: 'lose',
  battle: {
    outcome: 'lose',
    lastAction: { autodesmaio: true, mensagem: '💀 Seu Pokémon desmaiou após o golpe!' }
  }
}, '!pk');
assert.match(loseExp, /desmaiou após o golpe/i);

// --- Captura: qty real + chances do motor (sem Super Bola) ---
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
assert.match(menu, /Ultra Bola: 2/);
assert.doesNotMatch(menu, /Super Bola/);
assert.match(menu, /chance 70%/);
assert.match(menu, /chance 95%/);

// KO → menu recebe chances canônicas do confirm-turn
const koMenu = present.formatBattleTurn({
  aguardandoCaptura: true,
  chanceBaseCaptura: 40,
  chances: { pokebola: 40, 'grande-bola': 60, 'ultra-bola': 80 },
  inventory: [
    { itemId: 'pokebola', qty: 5 },
    { itemId: 'grande-bola', qty: 1 },
    { itemId: 'ultra-bola', qty: 0 }
  ],
  battle: {
    aguardandoCaptura: true,
    pokemonO: { nome: 'Abra', raridade: 'raro' },
    pokemonX: { nome: 'Meu', hp: 100, hpAtual: 80 },
    hp: { x: 80 }
  }
}, '!pk');
assert.match(koMenu, /Grande Bola: 1/);
assert.match(koMenu, /chance 60%/);
assert.match(koMenu, /foi derrotado/i);

// --- Team grid: sem imagem → placeholder claro (não caixa preta) ---
(async () => {
  const grid = await media.renderTeamGrid([
    { nome: 'SemSprite', species: 'zzzz-nao-existe', nivel: 5, hp: 40, hpAtual: 40,
      tipos: ['normal'], collectionIndex: 0 }
  ], { title: 'Time teste', trainerLevel: 3 });
  assert.ok(grid && grid.base64, 'renderTeamGrid deve produzir imagem');
  const buf = Buffer.from(grid.base64, 'base64');
  assert.equal(buf[0], 0x89, 'PNG');
  const sharp = require('sharp');
  const { data, info } = await sharp(buf).ensureAlpha().raw().toBuffer({ resolveWithObject: true });
  let nonBlack = 0;
  for (let i = 0; i < data.length; i += info.channels) {
    if (data[i] > 30 || data[i + 1] > 30 || data[i + 2] > 30) nonBlack++;
  }
  assert.ok(nonBlack > 100, `time grid não deve ser caixa preta (nonBlack=${nonBlack})`);
  console.log('pokemon-a6-lote-c-present.test.cjs OK', { nonBlack, gridBytes: buf.length });
})().catch((e) => { console.error(e); process.exit(1); });
