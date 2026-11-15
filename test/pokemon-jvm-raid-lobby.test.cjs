'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

test('resolveRaidGym numérico e por id', () => {
  assert.equal(present.resolveRaidGym('1'), 'pedra');
  assert.equal(present.resolveRaidGym('5'), 'fogo');
  assert.equal(present.resolveRaidGym('agua'), 'agua');
  assert.equal(present.resolveRaidGym('nope'), null);
  assert.equal(present.resolveRaidGym('Lt. Surge'), 'eletrico');
  assert.equal(present.resolveRaidGym('Blaine'), 'fogo');
});

test('formatRaidOpenHelp lista ginásios', () => {
  const t = present.formatRaidOpenHelp('!pk');
  assert.match(t, /Abrir raid/);
  assert.match(t, /1\. Brock .*Pedra/);
  assert.match(t, /5\. Blaine .*nv\. 40/);
  assert.match(t, /!pk raid abrir/);
});

test('formatRaidOpened e cancelada', () => {
  assert.match(present.formatRaidOpened({
    raid: { ginasio: 'pedra', nomeGinasio: '🪨 Pedra', liga: 'iniciante',
      chefe: { nome: 'Onix' } }
  }, '!pk'), /Onix/);
  assert.match(present.formatRaidCancelled({
    raid: { ginasio: 'pedra', nomeGinasio: 'Pedra' }
  }, '!pk'), /cancelada/);
});

test('status mostra máx 5, chefe, ginásio, participantes e prazo', () => {
  const t = present.formatRaidStatus({
    agora: 1000,
    raid: {
      fase: 'inscricoes', liga: 'iniciante', nParticipantes: 2,
      chefe: { nome: 'Onix' }, ginasio: 'pedra', nomeGinasio: '🪨 Pedra',
      expira: 121000,
      participantes: [
        { nome: 'Ash', voce: true },
        { nome: 'Misty' }
      ]
    }
  }, '!pk');
  assert.match(t, /Onix/);
  assert.match(t, /Pedra/);
  assert.match(t, /2\/5/);
  assert.match(t, /Ash/);
  assert.match(t, /Misty/);
  assert.match(t, /⏱️/);
});

test('mensagem de turno menciona o próximo jogador com o ID do WhatsApp', () => {
  const raid = { fase: 'combate', vez: '123456789@lid', vezNome: 'Misty' };
  const text = present.formatRaidStart({ raid });
  assert.deepEqual(present.withRaidTurnMention(text, raid), {
    text: text.replace('👉 Vez de *Misty*', '👉 Vez de @123456789'),
    mentions: ['123456789@lid']
  });

  const terminal = { text: 'Raide encerrada', mentions: ['987654321@c.us'] };
  assert.deepEqual(present.withRaidTurnMention(terminal, { ...raid, fase: 'vitoria' }), terminal);
});
