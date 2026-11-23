'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('AREA_UNKNOWN mensagem educativa com pedido', () => {
  const text = present.formatError({
    code: 'AREA_UNKNOWN',
    details: { pedido: 'vulcao azul' }
  });
  assert.match(text, /Bioma não encontrado/i);
  assert.match(text, /vulcao azul/);
  assert.match(text, /biomas/);
});

test('AREA_UNAVAILABLE distingue período', () => {
  const text = present.formatError({
    code: 'AREA_UNAVAILABLE',
    details: { areaNome: 'Vulcão Rubro', periodo: 'Noite' }
  });
  assert.match(text, /não está disponível/i);
  assert.match(text, /Vulcão Rubro/);
  assert.match(text, /Noite/);
});

test('formatBattleTurn anuncia XP de derrota quando persistido', () => {
  const text = present.formatBattleTurn({
    outcome: 'lose',
    reward: { pokemonXp: 1 },
    battle: { outcome: 'lose', pokemons: { o: { nome: 'Magikarp' }, x: { nome: 'Squirtle' } } }
  });
  assert.match(text, /desmaiou/i);
  assert.match(text, /\+1 XP/);
});

test('formatRaidDefeat não mente XP quando rewards têm 0', () => {
  const out = present.formatRaidDefeat({
    rewards: [{ playerId: 'ash@lid', pe: 3, xpPokemon: 0, moedas: 0 }],
    raid: { chefe: { nome: 'Boss' }, participantes: [{ playerId: 'ash@lid', nome: 'Ash' }] }
  });
  const text = typeof out === 'string' ? out : out.text;
  assert.match(text, /Sem XP de Pokémon liquidado/i);
  assert.doesNotMatch(text, /ganharam experiência pela batalha/);
});

test('formatConfirmedPiLines tip de incubação no ganho de ovo', () => {
  const lines = present.formatConfirmedPiLines({
    adventureActivity: { pi: 0, eggId: 'egg:1', eggCategory: 'comum' }
  });
  const text = lines.join('\n');
  assert.match(text, /VOCÊ GANHOU UM OVO/);
  assert.match(text, /incubar/);
  assert.match(text, /!pk ovos/);
});

test('shouldRenderEggGrant só quando ovo efetivo', () => {
  assert.equal(present.shouldRenderEggGrant({
    adventureActivity: { eggId: 'e1', deferred: true }
  }), false);
  assert.equal(present.shouldRenderEggGrant({
    adventureActivity: { eggId: 'e1', eggCategory: 'raro' }
  }), true);
});

test('ALIASES biomas resolve para biomas', () => {
  assert.equal(present.resolveCommand(['biomas']), 'biomas');
  assert.equal(present.resolveCommand(['bioma']), 'biomas');
});

test('formatBiomesCatalog lista biomas do plano', () => {
  const text = present.formatBiomesCatalog({
    periodo: { nome: 'Dia' },
    mundo: {
      areasDisponiveis: [{ id: 'vulcao', nome: 'Vulcão Rubro', emoji: '🌋' }],
      areasIndisponiveis: [{ id: 'floresta', nome: 'Floresta Verde', emoji: '🌳' }]
    }
  }, '!pk');
  assert.match(text, /Biomas do Odisseu/);
  assert.match(text, /Vulcão Rubro/);
  assert.match(text, /Floresta Verde/);
  assert.match(text, /cacar/);
});

const fs = require('node:fs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

test('formatHuntStart separa período Noite do clima Céu limpo', () => {
  const text = present.formatHuntStart({
    periodo: { nome: 'Noite', inicio: 19, fim: 24 },
    clima: { id: 'ceu-limpo', nome: 'Céu limpo', emoji: '🌙', tipos: ['fire', 'grass', 'ground'] },
    area: { id: 'cidade', nome: 'Cidade Pokémon', emoji: '🏙️' }
  }, {
    selvagem: { nome: 'Rattata', raridade: 'comum', hp: 30, hpAtual: 30, tipos: ['normal'] },
    battle: {
      hp: { x: 40, o: 30 },
      pokemonX: {
        nome: 'Pikachu', hp: 40, hpAtual: 40,
        golpes: [{ nomeExibicao: 'Choque', tipo: 'electric', poder: 40, classe: 'especial', precisao: 100 }]
      },
      pokemonO: { nome: 'Rattata', hp: 30, hpAtual: 30, tipos: ['normal'] }
    }
  });
  assert.match(text, /🕒 \*Noite\*/);
  assert.match(text, /Clima \*Céu limpo\*/);
  assert.doesNotMatch(text, /Ensolarado/);
});

test('formatWorldInfo nomeia período separado do clima', () => {
  const text = present.formatWorldInfo({
    periodo: { nome: 'Noite', inicio: 19, fim: 24 },
    clima: { nome: 'Céu limpo', emoji: '🌙', tipos: ['fire', 'grass'] },
    mundo: { areaPadrao: { id: 'cidade', emoji: '🏙️' } }
  });
  assert.match(text, /\*Período \(horário\):\* Noite/);
  assert.match(text, /Clima de hoje: Céu limpo/);
  assert.doesNotMatch(text, /Ensolarado/);
});

test('confirmedEggGrant ignora ovo adiado e aceita concessão efetiva', () => {
  assert.equal(present.confirmedEggGrant({
    adventureActivity: { eggId: 'e1', deferred: true, eggCategory: 'raro' }
  }), null);
  assert.deepEqual(present.confirmedEggGrant({
    adventureActivity: { eggId: 'e1', eggCategory: 'raro' }
  }), { eggId: 'e1', category: 'raro' });
});

test('renderEggGrantCard devolve PNG sem espécie', async () => {
  const out = await media.renderEggGrantCard({ eggId: 'e1', category: 'raro' }, { filename: 'ovo.png' });
  assert.ok(out);
  assert.equal(out.mimeType, 'image/png');
  assert.equal(out.filename, 'ovo.png');
  const buf = Buffer.from(out.base64, 'base64');
  assert.equal(buf.subarray(0, 4).toString('hex'), '89504e47');
});

test('!pk 1 e !pk atk 1 sem escopo não são ataque de raid', () => {
  const bare = present.resolveScopedCommand(['1']);
  const atk = present.resolveScopedCommand(['atk', '1']);
  const raid = present.resolveScopedCommand(['raid', 'atk', '1']);
  assert.equal(bare.cmd, 'atacar');
  assert.equal(bare.scope, null);
  assert.equal(atk.cmd, 'atacar');
  assert.equal(atk.scope, null);
  assert.equal(raid.cmd, 'raid');
  assert.equal(raid.scope, 'raid');
  const msg = present.formatRaidBareAttackBlocked('!pk');
  assert.match(msg, /Você está participando de uma raid/);
  assert.match(msg, /!pk raid atk 1/);
  assert.match(msg, /Comandos sem `raid` não executam ações nessa batalha/);
  const src = fs.readFileSync('src/zapbot/pokemon_http.cljs', 'utf8');
  assert.match(src, /!pk 1 \/ !pk atk 1 sem scope NÃO avançam a raid/);
  assert.match(src, /Comandos sem `raid` não executam ações nessa batalha/);
  assert.match(src, /shouldRenderHatchReveal/);
  assert.match(src, /renderHatchRevealCard/);
  assert.match(src, /renderEggGrantCard/);
});
