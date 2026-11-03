'use strict';

/**
 * Fase 12.1 — ciclo integrado (domínio simulado via present + contrato).
 * Não fala com produção; valida o contrato Node↔JVM e o fluxo UX.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const media = require('../scripts/lib/pokemon-jvm-media.cjs');

function camelKey(k) {
  const s = String(k).replace(/\?$/, '');
  const parts = s.split('-');
  return parts[0] + parts.slice(1).map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join('');
}

function toCamel(obj) {
  if (Array.isArray(obj)) return obj.map(toCamel);
  if (!obj || typeof obj !== 'object') return obj;
  const out = {};
  for (const [k, v] of Object.entries(obj)) out[camelKey(k)] = toCamel(v);
  return out;
}

function mon(nome, golpes) {
  return {
    nome,
    species: nome.toLowerCase(),
    nivel: 5,
    hp: 100,
    'hp-atual': 100,
    golpes: golpes || [
      { 'nome-exibicao': 'Tackle', classe: 'fisico', poder: 40 },
      { 'nome-exibicao': 'Growl', classe: 'status', poder: 0 }
    ]
  };
}

function openRaid(agora) {
  const lobbyMs = 5 * 60 * 1000;
  return {
    'raid-id': 'r-int-1',
    fase: 'inscricoes',
    ginasio: 'pedra',
    'nome-ginasio': '🪨 Pedra',
    liga: 'iniciante',
    expira: agora + lobbyMs,
    'n-participantes': 0,
    ordem: [],
    participantes: {},
    chefe: { nome: 'Onix', species: 'onix', hp: 70 },
    'hp-chefe': null,
    'hp-max': null,
    vez: null
  };
}

function join(raid, playerId, nome) {
  if (raid.participantes[playerId]) return raid;
  const pokemon = mon(nome === 'Ash' ? 'Pikachu' : 'Starmie',
    nome === 'Ash'
      ? [
        { 'nome-exibicao': 'Thunderbolt', classe: 'especial', poder: 90 },
        { 'nome-exibicao': 'Quick Attack', classe: 'fisico', poder: 40 }
      ]
      : [
        { 'nome-exibicao': 'Water Gun', classe: 'especial', poder: 40 },
        { 'nome-exibicao': 'Psychic', classe: 'especial', poder: 80 },
        { 'nome-exibicao': 'Recover', classe: 'status', poder: 0 }
      ]);
  return {
    ...raid,
    ordem: [...raid.ordem, playerId],
    'n-participantes': raid.ordem.length + 1,
    participantes: {
      ...raid.participantes,
      [playerId]: { 'player-id': playerId, nome, hp: 100, dano: 0, pokemon }
    }
  };
}

function start(raid) {
  const n = raid.ordem.length;
  const vez = raid.ordem[0];
  const vp = raid.participantes[vez];
  return {
    ...raid,
    fase: 'combate',
    'hp-chefe': 220 * n,
    'hp-max': 220 * n,
    vez,
    'vez-nome': vp.nome,
    'vez-hp': vp.hp,
    'vez-pokemon': vp.pokemon,
    'vez-valida': true,
    'turn-index': 0,
    'proxima-vez': raid.ordem[1] || null,
    'proxima-vez-nome': raid.ordem[1] ? raid.participantes[raid.ordem[1]].nome : null
  };
}

function dtoFor(raid, viewerId) {
  const sua = viewerId && raid.vez && viewerId === raid.vez;
  return toCamel({
    agora: Date.now(),
    raid: {
      ...raid,
      'sua-vez': Boolean(sua),
      inscrito: Boolean(raid.participantes[viewerId]),
      participantes: raid.ordem.map((pid) => ({
        ...raid.participantes[pid],
        voce: pid === viewerId
      }))
    }
  });
}

function precheckAttack(raidDto, playerId) {
  const r = raidDto.raid;
  return Boolean(
    r.suaVez === true
    || r['suaVez?'] === true
    || (playerId && r.vez && playerId === r.vez)
  );
}

test('ciclo integrado: abrir1 → join×2 → lobby5 → start → vez → golpes → atk → próximo → card', async () => {
  const t0 = 1_700_000_000_000;
  assert.equal(present.resolveRaidGym('1'), 'pedra');

  let raid = openRaid(t0);
  assert.equal(raid.expira - t0, 5 * 60 * 1000);

  const ash = '111@lid';
  const misty = '222@c.us';
  raid = join(raid, ash, 'Ash');
  raid = join(raid, misty, 'Misty');
  assert.equal(raid['n-participantes'], 2);

  const openedMsg = present.formatRaidOpened({ raid: toCamel(raid).raid }, '!pk');
  assert.match(openedMsg, /5 minutos|⏱️/);

  raid = start(raid);
  assert.equal(raid.vez, ash);
  assert.equal(raid['vez-nome'], 'Ash');

  const statusAsh = present.formatRaidStatus(dtoFor(raid, ash), '!pk');
  assert.match(statusAsh, /Vez de \*Ash\*/);
  assert.match(statusAsh, /Thunderbolt/);
  assert.match(statusAsh, /!pk raid atacar 1/);
  assert.match(statusAsh, /É a sua vez/);
  assert.doesNotMatch(statusAsh, /111@lid/);

  const dtoAsh = dtoFor(raid, ash);
  const dtoMisty = dtoFor(raid, misty);
  assert.equal(precheckAttack(dtoAsh, ash), true);
  assert.equal(precheckAttack(dtoMisty, misty), false);
  // identidade canônica: @c.us ≠ @lid mesmo com mesmo "número"
  assert.equal(precheckAttack(dtoFor(raid, '111@c.us'), '111@c.us'), false);

  // primeiro ataque: transfere vez
  const after = {
    ...raid,
    vez: misty,
    'vez-nome': 'Misty',
    'vez-hp': 92,
    'vez-pokemon': raid.participantes[misty].pokemon,
    'hp-chefe': raid['hp-chefe'] - 40,
    'turn-index': 1,
    'proxima-vez': ash,
    'proxima-vez-nome': 'Ash',
    participantes: {
      ...raid.participantes,
      [ash]: { ...raid.participantes[ash], dano: 40, hp: 100 },
      [misty]: { ...raid.participantes[misty], hp: 92 }
    }
  };
  const atkMsg = present.formatRaidAttack({
    dano: 40,
    revide: 8,
    raid: toCamel(after)
  }, '!pk');
  assert.match(atkMsg, /Misty/);
  assert.match(atkMsg, /Water Gun|Psychic/);
  assert.match(atkMsg, /Próximo: \*Ash\*/);

  assert.equal(precheckAttack(dtoFor(after, misty), misty), true);
  assert.equal(precheckAttack(dtoFor(after, ash), ash), false);

  const card = await media.renderRaidBattleCard({
    ginasio: after['nome-ginasio'],
    chefe: after.chefe,
    atacante: after['vez-pokemon'],
    treinador: after['vez-nome'],
    hpChefe: after['hp-chefe'],
    hpMax: after['hp-max'],
    hpAtacante: after['vez-hp'],
    fase: after.fase,
    turnIndex: after['turn-index']
  });
  if (card) {
    assert.equal(card.mimeType, 'image/png');
    assert.ok(card.base64.length > 80);
  }

  // economia/saves: sem mutação neste harness — só garante que DTO não inventa rewards
  const camel = toCamel(after);
  assert.equal(camel.rewardsPaid, undefined);
});

test('reinício: DTO reidratado preserva vez canônica e suaVez', () => {
  // DTO é por viewer: sua-vez só true para quem bate com :vez (igualdade literal).
  const base = {
    fase: 'combate',
    vez: '222@c.us',
    'vez-nome': 'Misty',
    'vez-valida': true,
    'hp-chefe': 300,
    'hp-max': 440,
    chefe: { nome: 'Onix' },
    'vez-pokemon': mon('Starmie'),
    participantes: [
      { nome: 'Ash', 'player-id': '111@lid' },
      { nome: 'Misty', 'player-id': '222@c.us', voce: true }
    ]
  };
  const dtoMisty = toCamel({ raid: { ...base, 'sua-vez': true } });
  const dtoAsh = toCamel({ raid: { ...base, 'sua-vez': false } });
  const dtoLidAlias = toCamel({ raid: { ...base, 'sua-vez': false } });
  assert.equal(dtoMisty.raid.suaVez, true);
  assert.equal(dtoMisty.raid.vez, '222@c.us');
  assert.equal(precheckAttack(dtoMisty, '222@c.us'), true);
  assert.equal(precheckAttack(dtoAsh, '111@lid'), false);
  // mesmo "número" com @lid ≠ @c.us canônico da vez
  assert.equal(precheckAttack(dtoLidAlias, '222@lid'), false);
  const text = present.formatRaidStatus(dtoMisty, '!pk');
  assert.match(text, /Misty/);
  assert.doesNotMatch(text, /222@c\.us/);
});

test('contrato JSON: keywords com ? não quebram pré-check', () => {
  assert.equal(camelKey('sua-vez?'), 'suaVez');
  const legado = { raid: { fase: 'combate', vez: 'a@lid', 'suaVez?': true } };
  assert.equal(present.raidFlag(legado.raid, 'suaVez'), true);
  assert.equal(precheckAttack(legado, 'a@lid'), true);
});
