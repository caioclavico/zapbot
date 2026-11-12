'use strict';
/**
 * Lote A — moedas 💰, efetividade sem espaço, central !pk ajuda.
 */
const assert = require('assert');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

// --- Moedas: só 💰 ---
assert.strictEqual(present.COIN_EMOJI, '\u{1F4B0}');
assert.strictEqual(present.COIN_EMOJI, '💰');
const coinLine = present.textoMoedas(50);
assert.strictEqual(coinLine, '💰 +50 moedas');
assert.ok(!coinLine.includes('🪙'));
assert.ok(!coinLine.includes('🌙'));
assert.ok(!coinLine.includes('🌕'));
assert.strictEqual([...coinLine][0].codePointAt(0), 0x1f4b0);

const victoryCoins = present.formatGymDefenderReturn({
  pokemonNome: 'Onix',
  tempoDefesa: '2h',
  moedasRecebidas: 40
});
assert.match(victoryCoins, /💰 Moedas recebidas: 40/);
assert.doesNotMatch(victoryCoins, /🪙|🌙|🌕/);

const raidDefeat = present.formatRaidDefeat({
  raid: { chefe: { nome: 'Boss' }, liga: 'bronze' },
  rewards: [{ playerId: 'p1', moedas: 0, xp: 0 }],
  participantes: [{ playerId: 'p1', nome: 'Ash' }]
}, '!pk');
const defeatText = typeof raidDefeat === 'string' ? raidDefeat : (raidDefeat && raidDefeat.text) || '';
if (defeatText.includes('Moedas')) {
  assert.match(defeatText, /💰/);
  assert.doesNotMatch(defeatText, /🪙/);
}

// --- Efetividade: emoji colado ---
assert.strictEqual(present.effectivenessLine(2), 'Super efetivo!🔥');
assert.strictEqual(present.effectivenessLine(0.5), 'Pouco efetivo!🛡️');
assert.strictEqual(present.effectivenessLine(0), 'Sem efeito!🚫');
assert.strictEqual(present.effectivenessLine(1), null);
for (const s of ['Super efetivo!🔥', 'Pouco efetivo!🛡️', 'Sem efeito!🚫']) {
  assert.ok(!s.includes('! '), `espaço antes do emoji em ${s}`);
  assert.ok(/[!]$|[!🔥🛡️🚫]$/.test(s) || /!(🔥|🛡️|🚫)$/.test(s), `emoji deve fechar a frase: ${s}`);
  assert.ok(!s.startsWith('🔥') && !s.startsWith('🛡️') && !s.startsWith('🚫'));
}

const raidMsg = present.formatRaidAttack({
  dano: 42,
  multiplicador: 2,
  golpe: { nomeExibicao: 'Lança-Chamas', classe: 'especial' },
  pokemonAtacante: { nome: 'Charizard' },
  raid: {
    fase: 'combate',
    hpChefe: 100, hpMax: 200, vez: 'ash', ordem: ['ash'],
    chefe: { nome: 'Boss' },
    vezPokemon: { nome: 'Charizard', hp: 80 }
  }
}, '!pk');
assert.match(raidMsg, /Super efetivo!🔥/);
assert.doesNotMatch(raidMsg, /🔥 Super|💥 É super/);

const turnFallback = present.formatBattleTurn({
  battle: {
    active: true,
    turnIndex: 1,
    hp: { x: 90, o: 50 },
    pokemonX: {
      nome: 'Pikachu', hp: 100, hpAtual: 90,
      golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial' }]
    },
    pokemonO: { nome: 'Squirtle', hp: 100, hpAtual: 50 },
    lastAction: {
      action: 'attack',
      dano: 22,
      multiplicador: 2,
      golpeNome: 'Choque',
      critico: true,
      hpAntes: 72,
      hpDepois: 50
    }
  }
}, '!pk');
assert.match(turnFallback, /Super efetivo!🔥/);

// --- Ajuda ---
const index = present.formatHelp(null, '!pk');
assert.match(index, /ODISSEU — CENTRAL DE AJUDA/);
assert.match(index, /!pk ajuda cacar/);
assert.match(index, /!pk ajuda raid/);
assert.match(index, /!pk ajuda ginasio/);
assert.match(index, /!pk ajuda mt/);
assert.match(index, /!pk ajuda loja/);
assert.match(index, /!pk ajuda ovos/);
assert.match(index, /!pk ajuda incubadoras/);
assert.doesNotMatch(index, /JID|datomic|cassandra|operationId/i);
assert.doesNotMatch(index, /nível 10|10 raids|incubadoras resgatar/i);

for (const topic of [
  'cacar', 'raid', 'ginasio', 'pokemon', 'mochila', 'evoluir', 'mt',
  'loja', 'ovos', 'incubadoras', 'batalha', 'pvp',
  'troca', 'presentes', 'eventos', 'treinador', 'recompensas',
  'caçar', 'raide', 'ginásio', 'missões', 'mts'
]) {
  const text = present.formatHelp(topic, '!pk');
  assert.ok(text && text.length > 80, `ajuda curta demais: ${topic}`);
  assert.doesNotMatch(text, /JID|datomic|cassandra|fenceGeneration/i);
  assert.doesNotMatch(text, /nível 10|10 raids vencidas|incubadoras resgatar/i);
}

const mtHelp = present.formatHelp('mt', '!pk');
assert.match(mtHelp, /mt confirmar/);
assert.match(mtHelp, /loja comprar mt/);
assert.match(mtHelp, /ID canônico|id canônico/i);

const shop = present.formatShop({
  coins: 50,
  ocupacaoMochila: 1,
  capacidadeMochila: 50,
  livres: 49,
  itens: [{
    emoji: '💿',
    nome: 'MT de Ataque',
    preco: 200,
    itemId: 'mt',
    descricao: 'Sorteia um ataque compatível.'
  }]
});
assert.match(shop, /!loja comprar mt/);
assert.match(shop, /Incubadoras \*não\* se compram/);

assert.deepStrictEqual(present.resolveHelpRequest(['ajuda']), { topic: null });
assert.deepStrictEqual(present.resolveHelpRequest(['ajuda', 'raid']), { topic: 'raid' });
assert.deepStrictEqual(present.resolveHelpRequest(['ginasio', 'ajuda']), { topic: 'ginasio' });
assert.deepStrictEqual(present.resolveHelpRequest(['mochila', 'help']), { topic: 'mochila' });
assert.deepStrictEqual(present.resolveHelpRequest(['ajd']), { topic: null });
assert.strictEqual(present.resolveHelpRequest(['cacar', 'floresta']), null);

assert.strictEqual(present.resolveCommand(['ajuda']), 'ajuda');
assert.strictEqual(present.resolveCommand(['help']), 'ajuda');
assert.strictEqual(present.resolveCommand(['ajd']), 'ajuda');

const unknown = present.formatUnavailable('raid xyz');
assert.match(unknown, /ajuda raid/);
assert.match(unknown, /não reconhecido/i);

const raidHelp = present.formatHelp('raid', '!pk');
assert.match(raidHelp, /Um Pokémon por treinador/);
assert.match(raidHelp, /raid entrar/);
assert.match(raidHelp, /frambo|Frambo/);

console.log('pokemon-a6-ux-help-coins.test.cjs OK');
