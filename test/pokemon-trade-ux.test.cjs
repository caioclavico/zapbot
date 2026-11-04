'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

describe('trade UX presentation', () => {
  it('formats create with public id and mentions without internal ids', () => {
    const data = {
      id: 'false_120363@g.us_x:trade-create:lid:84:1',
      tradeId: 'false_120363@g.us_x:trade-create:lid:84:1',
      publicId: 84,
      publicLabel: '#84',
      playerA: '207700373848240@lid',
      playerB: '254816181813317@lid',
      pokemonAName: 'Pikachu',
      pokemonBName: 'Staryu',
      state: 'invited'
    };
    const text = present.formatTradeCreated(data, {
      prefix: '!pk',
      fromPlayerId: data.playerA,
      toPlayerId: data.playerB
    });
    assert.match(text, /PROPOSTA DE TROCA #84/);
    assert.match(text, /!pk troca aceitar 84/);
    assert.match(text, /!pk troca recusar 84/);
    assert.match(text, /Pikachu/);
    assert.match(text, /Staryu/);
    assert.doesNotMatch(text, /trade-create/);
    assert.doesNotMatch(text, /@g\.us/);
    assert.doesNotMatch(text, /false_/);
    const mentions = present.tradeMentions(data.playerA, data.playerB);
    assert.deepEqual(mentions, [data.playerA, data.playerB]);
  });

  it('does not invent pokemon names when absent', () => {
    const text = present.formatTradeCreated({
      publicId: 3,
      playerA: 'a@lid',
      playerB: 'b@lid',
      state: 'invited'
    }, { prefix: '!pk', fromPlayerId: 'a@lid', toPlayerId: 'b@lid' });
    assert.match(text, /#3/);
    assert.doesNotMatch(text, /Oferece:/);
    assert.doesNotMatch(text, /Recebe:/);
  });

  it('lists open trades with public labels only', () => {
    const text = present.formatTradeList([
      { publicId: 1, state: 'invited', pokemonAName: 'Bulbasaur', pokemonBName: 'Squirtle' },
      { publicId: 2, state: 'completed', pokemonAName: 'X', pokemonBName: 'Y' },
      { id: 'legacy-long-id', publicId: 9, state: 'accepted' }
    ], { prefix: '!pk' });
    assert.match(text, /#1/);
    assert.match(text, /#9/);
    assert.doesNotMatch(text, /legacy-long-id/);
    assert.doesNotMatch(text, /#2/);
  });

  it('formats accept/complete/refuse/cancel without canonical ids', () => {
    const sample = { publicId: 12, pokemonAName: 'A', pokemonBName: 'B', evolutions: ['Kadabra'] };
    assert.doesNotMatch(present.formatTradeAccepted(sample, { prefix: '!pk' }), /trade-/);
    assert.match(present.formatTradeCompleted(sample), /#12/);
    assert.match(present.formatTradeCompleted(sample), /Kadabra/);
    assert.match(present.formatTradeRefused(sample), /#12/);
    assert.match(present.formatTradeCancelled(sample), /#12/);
  });
});
