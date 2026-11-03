'use strict';

/**
 * Fase 12 — regressões críticas de raide A6.
 * Sem deploy/JVM live: present + media + contrato JSON camelCase.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

/** Espelha camel-key do bridge (json.clj) — sufixo `?` removido. */
function camelKey(k) {
  const s = String(k).replace(/\?$/, '');
  const parts = s.split('-');
  return parts[0] + parts.slice(1).map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join('');
}

function toCamelDto(obj) {
  if (Array.isArray(obj)) return obj.map(toCamelDto);
  if (!obj || typeof obj !== 'object') return obj;
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    out[camelKey(k)] = toCamelDto(v);
  }
  return out;
}

test('abrir direto por índice/id/nome sem menu prévio', () => {
  assert.equal(present.resolveRaidGym('1'), 'pedra');
  assert.equal(present.resolveRaidGym('3'), 'eletrico');
  assert.equal(present.resolveRaidGym('pedra'), 'pedra');
  assert.equal(present.resolveRaidGym('Água'), 'agua');
  assert.equal(present.resolveRaidGym('fogo'), 'fogo');
  assert.equal(present.resolveRaidGym(''), null);
  assert.equal(present.resolveRaidGym('99'), null);
  const help = present.formatRaidOpenHelp('!pk');
  assert.match(help, /5 minutos/);
  assert.match(help, /abrir 1/);
  assert.match(help, /Sem argumento/);
});

test('lobby messaging usa 5 minutos', () => {
  assert.match(present.formatRaidOpened({
    raid: { nomeGinasio: 'Pedra', liga: 'iniciante', chefe: { nome: 'Onix' } }
  }, '!pk'), /5 minutos/);
});

test('contrato JSON: sua-vez? → suaVez (incidente todos bloqueados)', () => {
  // Bug raiz: keyword :sua-vez? virava "suaVez?" e o Node lia "suaVez" → sempre false.
  assert.equal(camelKey('sua-vez?'), 'suaVez');
  assert.equal(camelKey('sua-vez'), 'suaVez');
  assert.equal(camelKey('inscrito?'), 'inscrito');
  assert.equal(camelKey('voce?'), 'voce');

  const dtoLegadoQuebrado = {
    fase: 'combate',
    vez: '111@lid',
    'suaVez?': true, // formato quebrado pré-fix
    hpChefe: 400,
    hpMax: 440,
    chefe: { nome: 'Onix' },
    participantes: [
      { nome: 'Ash', playerId: '111@lid', voce: true, hp: 100 },
      { nome: 'Misty', playerId: '222@lid', hp: 100 }
    ]
  };
  // raidFlag tolera ambos
  assert.equal(present.raidFlag(dtoLegadoQuebrado, 'suaVez'), true);

  const dtoCorrigido = toCamelDto({
    'sua-vez': true,
    inscrito: true,
    voce: true,
    vez: '111@lid',
    'vez-nome': 'Ash'
  });
  assert.equal(dtoCorrigido.suaVez, true);
  assert.equal(dtoCorrigido.inscrito, true);
  assert.equal(dtoCorrigido.voce, true);
  assert.equal(dtoCorrigido.vezNome, 'Ash');
});

test('incidente: vários jogadores — só o da vez autenticada passa', () => {
  const ordem = ['111@lid', '222@c.us', '333@lid'];
  const vez = '222@c.us';
  const attackers = [
    { playerId: '111@lid', expect: false },
    { playerId: '222@c.us', expect: true }, // identidade canônica exata
    { playerId: '333@lid', expect: false },
    { playerId: '222@lid', expect: false } // NÃO equivaler por strip de sufixo
  ];
  for (const a of attackers) {
    const suaVez = a.playerId === vez;
    assert.equal(suaVez, a.expect, `player ${a.playerId}`);
  }
  assert.ok(ordem.includes(vez));
});

test('apresentação de turno: nome, golpes, comando e próximo', () => {
  const text = present.formatRaidStatus({
    raid: {
      fase: 'combate',
      liga: 'bronze',
      nomeGinasio: 'Pedra',
      hpChefe: 300,
      hpMax: 440,
      vez: '222@c.us',
      vezNome: 'Misty',
      vezHp: 180,
      vezPokemon: {
        nome: 'Starmie',
        golpes: [
          { nomeExibicao: 'Water Gun', classe: 'especial', poder: 40 },
          { nomeExibicao: 'Psychic', classe: 'especial', poder: 80 },
          { nomeExibicao: 'Recover', classe: 'status', poder: 0 },
          { nomeExibicao: 'Swift', classe: 'especial', poder: 60 }
        ]
      },
      suaVez: false,
      proximaVezNome: 'Ash',
      chefe: { nome: 'Onix' },
      participantes: [
        { nome: 'Ash', hp: 200 },
        { nome: 'Misty', voce: true, hp: 180 }
      ]
    }
  }, '!pk');
  assert.match(text, /Vez de \*Misty\*/);
  assert.match(text, /Starmie/);
  assert.match(text, /Water Gun/);
  assert.match(text, /indisponível/);
  assert.match(text, /!pk raide atacar 1/);
  assert.match(text, /Próximo: \*Ash\*/);
  assert.doesNotMatch(text, /222@c\.us/);
});

test('formatRaidStart e Attack usam bloco de turno', () => {
  const start = present.formatRaidStart({
    raid: {
      fase: 'combate', hpChefe: 440, hpMax: 440,
      vez: 'a@lid', vezNome: 'Ash', vezHp: 200,
      vezPokemon: {
        nome: 'Pikachu',
        golpes: [{ nomeExibicao: 'Thunderbolt', classe: 'especial', poder: 90 }]
      },
      suaVez: true
    }
  }, '!pk');
  assert.match(start, /Ash/);
  assert.match(start, /Thunderbolt/);
  assert.match(start, /É a sua vez/);

  const atk = present.formatRaidAttack({
    dano: 40, revide: 8,
    raid: {
      fase: 'combate', hpChefe: 400, hpMax: 440,
      vez: 'b@lid', vezNome: 'Brock', vezHp: 150,
      vezPokemon: {
        nome: 'Onix',
        golpes: [{ nomeExibicao: 'Rock Throw', classe: 'fisico', poder: 50 }]
      }
    }
  }, '!pk');
  assert.match(atk, /Brock/);
  assert.match(atk, /Rock Throw/);
});

test('renderRaidBattleCard retorna png ou null (fallback textual)', async () => {
  const card = await media.renderRaidBattleCard({
    ginasio: 'Pedra',
    chefe: { nome: 'Onix', species: 'onix' },
    atacante: { nome: 'Pikachu', species: 'pikachu', hp: 120 },
    treinador: 'Ash',
    hpChefe: 400,
    hpMax: 440,
    hpAtacante: 100,
    fase: 'combate',
    turnIndex: 0
  });
  // Sem rede: sprites podem falhar → null é ok; se renderizar, é PNG.
  if (card) {
    assert.equal(card.mimeType, 'image/png');
    assert.ok(card.base64 && card.base64.length > 100);
    assert.match(card.filename, /raid/);
  } else {
    assert.equal(card, null);
  }
});

test('resolveCommand não engole raid abrir N', () => {
  assert.equal(present.resolveCommand(['raid', 'abrir', '1']), 'raid');
  assert.equal(present.resolveCommand(['raide', 'abrir', 'pedra']), 'raid');
  assert.equal(present.resolveRaidSubcommand('abrir'), 'abrir');
  // bare 1 continua atacar (sem menu de ginásio pendente)
  assert.equal(present.resolveCommand(['1']), 'atacar');
});

test('vitória lista recompensas reais e captura; fruta aliases', () => {
  assert.equal(present.resolveRaidSubcommand('fruta'), 'fruta');
  assert.equal(present.resolveRaidSubcommand('frambo'), 'frambo');
  const winRaw = present.formatRaidAttack({
    venceu: true,
    playerId: '121212@lid',
    dano: 80,
    rewards: [
      { playerId: '121212@lid', moedas: 50, pe: 6, xpPokemon: 6 },
      { playerId: '5511999999999@c.us', moedas: 50, pe: 6, xpPokemon: 6 }
    ],
    raid: {
      chefe: { nome: 'Onix' },
      participantes: [
        { playerId: '121212@lid', nome: 'Jogador1', pokemon: { nome: 'Charizard' } },
        { playerId: '5511999999999@c.us', nome: 'Jogador2', pokemon: { nome: 'Blastoise' } }
      ]
    }
  }, '!pk');
  const win = present.coerceRaidPresent(winRaw);
  assert.match(win.text, /RAID CONCLUÍDA/);
  assert.match(win.text, /Onix/);
  assert.match(win.text, /@Jogador1 — Pokémon: Charizard/);
  assert.match(win.text, /@Jogador2 — Pokémon: Blastoise/);
  assert.match(win.text, /@Jogador1\n💰 \+50 moedas/);
  assert.match(win.text, /🏅 \+6 PE/);
  assert.match(win.text, /⭐ \+6 XP/);
  assert.doesNotMatch(win.text, /121212@lid/);
  assert.doesNotMatch(win.text, /5511999999999/);
  assert.deepEqual(win.mentions, ['121212@lid', '5511999999999@c.us']);
  assert.match(win.text, /raide fruta/);
  assert.match(win.text, /capturar/);
  const fruta = present.formatRaidFrambo({}, '!pk');
  assert.match(fruta, /Fruta Frambo utilizada/);
  const ui = present.formatRaidCaptureUi({
    capturaUi: {
      chefe: 'Onix',
      tentativas: 0,
      maxTentativas: 3,
      bolas: { pokebola: 10, 'grande-bola': 5, 'ultra-bola': 3 },
      frambo: 2,
      framboPreparada: true,
      chances: { pokebola: 35, 'grande-bola': 53, 'ultra-bola': 95 }
    },
    raid: { chefe: { nome: 'Onix' }, captura: {} }
  }, '!pk');
  assert.match(ui, /CAPTURA DO CHEFE/);
  assert.match(ui, /Ultra Bola: 3/);
  assert.match(ui, /bônus ativo/);
  assert.match(ui, /raide fruta/);
});

test('menções: nome ausente e IDs técnicos viram Treinador N', () => {
  const win = present.coerceRaidPresent(present.formatRaidAttack({
    venceu: true,
    rewards: [
      { playerId: 'aaa@lid', moedas: 50, pe: 6, xpPokemon: 6 },
      { playerId: 'bbb@c.us', moedas: 50, pe: 6, xpPokemon: 6 }
    ],
    raid: {
      chefe: { nome: 'Onix' },
      participantes: [
        { playerId: 'aaa@lid', nome: '121212@lid', pokemon: { nome: 'Pikachu' } },
        { playerId: 'bbb@c.us', pokemon: { nome: 'Squirtle' } }
      ]
    }
  }, '!pk'));
  assert.match(win.text, /@Treinador 1 — Pokémon: Pikachu/);
  assert.match(win.text, /@Treinador 2 — Pokémon: Squirtle/);
  assert.doesNotMatch(win.text, /@lid/);
  assert.deepEqual(win.mentions, ['aaa@lid', 'bbb@c.us']);
});

test('captura malsucedida distingue falha de bola vs tentativas esgotadas', () => {
  const fail = present.formatRaidCapture({
    capturou: false, bola: 'ultra-bola', frambo: true, chance: 95,
    tentativas: 1, chefe: { nome: 'Onix' }
  }, '!pk');
  assert.match(fail, /escapou da Ultra Bola/);
  assert.match(fail, /ainda pode tentar/);
  assert.doesNotMatch(fail, /fugiu!/);

  const done = present.formatRaidCapture({
    capturou: false, bola: 'ultra-bola', chance: 95,
    tentativas: 3, chefe: { nome: 'Onix' }
  }, '!pk');
  assert.match(done, /Tentativas esgotadas/);
  assert.match(done, /recompensas pela vitória foram mantidas/);
  assert.doesNotMatch(done, /fugiu!/);
});

test('derrota lista PE/XP persistidos sem captura (12.8.3)', () => {
  const raw = present.formatRaidAttack({
    derrota: true,
    playerId: '121212@lid',
    rewards: [
      { playerId: '121212@lid', moedas: 0, pe: 3, xpPokemon: 3, outcome: 'derrota' },
      { playerId: '5511999999999@c.us', moedas: 0, pe: 3, xpPokemon: 3, outcome: 'derrota' }
    ],
    raid: {
      chefe: { nome: 'Onix' },
      participantes: [
        { playerId: '121212@lid', nome: 'Ash', pokemon: { nome: 'Magikarp' } },
        { playerId: '5511999999999@c.us', nome: 'Misty', pokemon: { nome: 'Feebas' } }
      ]
    }
  }, '!pk');
  const msg = present.coerceRaidPresent(raw);
  assert.match(msg.text, /RAID ENCERRADA — DERROTA/);
  assert.match(msg.text, /Onix/);
  assert.match(msg.text, /Recompensas de participação/);
  assert.match(msg.text, /@Ash\n👤 Treinador: \+3 PE/);
  assert.match(msg.text, /⚔️ Pokémon participante: \+3 XP/);
  assert.match(msg.text, /🪙 Moedas: 0/);
  assert.match(msg.text, /@Misty\n👤 Treinador: \+3 PE/);
  assert.doesNotMatch(msg.text, /CAPTURA DO CHEFE/);
  assert.doesNotMatch(msg.text, /Sem recompensas/);
  assert.doesNotMatch(msg.text, /\+50/);
  assert.deepEqual(msg.mentions, ['121212@lid', '5511999999999@c.us']);
});
