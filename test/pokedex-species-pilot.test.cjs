'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const pilot = require('../scripts/lib/pokedex-species-pilot.cjs');

const PILOT = '120363427684548487@g.us';
const OTHER = '120363999999999999@g.us';

test('pilot-only: só o chat piloto autorizado', () => {
  assert.equal(pilot.pilotAuthorized(PILOT, { pilotOnly: true, pilotChatId: PILOT }), true);
  assert.equal(pilot.pilotAuthorized(OTHER, { pilotOnly: true, pilotChatId: PILOT }), false);
  assert.equal(pilot.pilotAuthorized('', { pilotOnly: true, pilotChatId: PILOT }), false);
  assert.equal(pilot.pilotAuthorized(PILOT, { pilotOnly: true, pilotChatId: '' }), false);
});

test('pilot-only=false: GA — qualquer chat', () => {
  assert.equal(pilot.pilotAuthorized(OTHER, { pilotOnly: false, pilotChatId: PILOT }), true);
  assert.equal(pilot.pilotAuthorized(OTHER, { pilotOnly: false, pilotChatId: '' }), true);
});

test('mensagem fora do piloto menciona espécie vs coleção', () => {
  const msg = pilot.foraDoPilotoMessage('!');
  assert.match(msg, /homologação/);
  assert.match(msg, /!pokedex/);
  assert.match(msg, /!pk pokedex/);
  assert.match(msg, /piloto/);
});
