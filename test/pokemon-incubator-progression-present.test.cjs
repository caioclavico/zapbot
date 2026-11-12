'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');
const eggCards = require('../scripts/lib/egg-cards.cjs');

describe('incubator progression presenters', () => {
  it('lista pendentes e recompensas', () => {
    const data = {
      incubators: [
        { incubatorId: 'permanente', kind: 'permanente', uses: null, livre: true, bonusPoints: 0 },
        { incubatorId: 'incubator-reward:level:10', kind: 'extra', uses: 2, livre: false, bonusPoints: 0 }
      ],
      pendingIncubatorCount: 1,
      pendingIncubatorGrants: [
        { rewardId: 'incubator-reward:level:30', label: 'Incubadora especial (nível 30)', kind: 'especial' }
      ],
      incubatorRewardsUnlocked: [
        { rewardId: 'incubator-reward:level:10', label: 'Incubadora extra (nível 10)' }
      ],
      raidWins: 3,
      temporarySlotsFree: 1
    };
    assert.match(present.formatIncubators(data), /Recompensas pendentes: \*1\*/);
    assert.match(present.formatIncubatorRewards(data), /fila FIFO/);
    assert.match(present.formatIncubatorRewards(data), /nível 30/);
  });

  it('formata grant imediato e pendente', () => {
    const granted = present.formatIncubatorRewardEvents({
      incubatorRewards: [{
        rewardId: 'incubator-reward:level:10',
        status: 'granted',
        kind: 'extra',
        source: 'level',
        threshold: 10,
        uses: 3,
        bonusPoints: 0
      }]
    });
    assert.match(granted, /NOVA RECOMPENSA/);
    assert.match(granted, /nível 10/);
    assert.match(granted, /desbloqueou/);

    const pending = present.formatIncubatorRewardEvents({
      incubatorRewards: [{
        rewardId: 'incubator-reward:level:30',
        status: 'enqueued',
        kind: 'especial',
        source: 'level',
        threshold: 30,
        pending: true
      }]
    });
    assert.match(pending, /disponível para resgate/);
    assert.doesNotMatch(pending, /desbloqueou uma/);
  });

  it('appendTrainerLevelUps inclui incubadoras', () => {
    const text = present.appendTrainerLevelUps('Vitória!', {
      trainerLevelUps: [{
        nivelAnterior: 9,
        nivelAtual: 10,
        peRecebido: 100,
        xpAtual: 0,
        xpNecessario: 200,
        niveis: [{ nivel: 10 }]
      }],
      incubatorRewards: [{
        rewardId: 'incubator-reward:level:10',
        status: 'granted',
        kind: 'extra',
        source: 'level',
        threshold: 10,
        uses: 3
      }]
    });
    assert.match(text, /TREINADOR SUBIU/);
    assert.match(text, /NOVA RECOMPENSA/);
  });

  it('card PNG mostra pendentes sem inventar incubadoras', async () => {
    const buf = await eggCards.render('incubators', {
      incubators: [
        { incubatorId: 'permanente', kind: 'permanente', uses: null, livre: true }
      ],
      pendingIncubatorCount: 2
    });
    assert.ok(Buffer.isBuffer(buf));
    assert.ok(buf.length > 1000);
  });
});
