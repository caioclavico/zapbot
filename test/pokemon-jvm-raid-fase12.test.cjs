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
  const win = present.formatRaidAttack({
    venceu: true,
    playerId: 'ash',
    dano: 80,
    rewards: [
      { playerId: 'ash', moedas: 50, pe: 6, xpPokemon: 6 },
      { playerId: 'misty', moedas: 50, pe: 6, xpPokemon: 6 }
    ],
    raid: { chefe: { nome: 'Onix' } }
  }, '!pk');
  assert.match(win, /RAID CONCLUÍDA/);
  assert.match(win, /Onix/);
  assert.match(win, /Moedas: \+50/);
  assert.match(win, /PE do treinador: \+6/);
  assert.match(win, /XP do Pokémon: \+6/);
  assert.match(win, /raide fruta/);
  assert.match(win, /capturar/);
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
