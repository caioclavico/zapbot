'use strict';

// Adapters for the first real vertical slice of a gym battle: the challenger's
// persisted HP/status and the defender occupancy wear.  The journal owns the
// operation identity; each destination records that identity in the same LWT
// as its mutation.  Rewards, occupancy changes and statistics deliberately do
// not use this adapter yet.
const {hash} = require('./restore-protocol.cjs');

const fail = code => Object.assign(new Error(code), {code});
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const validTargetId = value => typeof value === 'string' && value.length > 0 && value.length <= 200 &&
  !/[\u0000-\u001f\u007f]/.test(value);

function receipt(record, effectId) {
  const receipts = record?.operationReceipts;
  return Boolean(receipts?.[effectId] || receipts?.has?.(effectId) || receipts?.includes?.(effectId));
}

function trainerPayload({record, activeIndex, hp, status}) {
  if (!record || !Number.isSafeInteger(activeIndex) || activeIndex < 0 ||
      !Number.isFinite(hp) || !object(record.state)) throw fail('invalid_real_battle_trainer_plan');
  return {
    expectedGeneration: record.generation,
    expectedHash: record.stateHash,
    writerContext: {identityHash:record.identityHash,fenceGeneration:record.fenceGeneration,
      activeSaveId:record.activeSaveId,transitionOwner:record.transitionOwner},
    activeIndex,
    hp,
    status: status == null ? null : status
  };
}

function gymWearPayload({record, targetId, nextState}) {
  if (!record || !validTargetId(targetId) || !object(nextState)) throw fail('invalid_real_battle_gym_plan');
  return {
    module: 'ginasios', targetId,
    expectedGeneration: record.generation,
    expectedHash: record.stateHash,
    nextState
  };
}

function applyTrainerState(state, payload) {
  const team = state?.equipe;
  if (!Array.isArray(team) || !object(team[payload.activeIndex])) throw fail('real_battle_trainer_active_missing');
  const next = JSON.parse(JSON.stringify(state));
  next.equipe[payload.activeIndex]['hp-atual'] = payload.hp;
  next.equipe[payload.activeIndex].status = payload.status;
  return next;
}

function createRealGymBattleAdapters({trainerStore, gymStore}) {
  if (!trainerStore || !gymStore) throw fail('real_battle_adapter_unavailable');
  return {
    'trainer-account': {
      async hasReceipt({chat, player, effectId}) {
        return receipt(await trainerStore.readAccount(chat, player), effectId);
      },
      async applyOnce({chat, player, effectId, payload}) {
        return trainerStore.applyOnce(chat, player, {
          effectId,
          intentHash: hash(payload),
          expectedGeneration: payload.expectedGeneration,
          expectedStateHash: payload.expectedHash,
          writerContext:payload.writerContext,
          transform: state => applyTrainerState(state, payload)
        });
      }
    },
    'gym-occupancy': {
      async hasReceipt({chat, effectId, payload}) {
        const record = await gymStore.readRecord(chat, payload.module, payload.targetId);
        return receipt(record, effectId);
      },
      async applyOnce({chat, effectId, payload}) {
        const current = await gymStore.readRecord(chat, payload.module, payload.targetId);
        if (receipt(current, effectId)) return current;
        if (!current || current.generation !== payload.expectedGeneration || current.stateHash !== payload.expectedHash) {
          throw fail('real_battle_gym_stale_generation');
        }
        return gymStore.writeRecordRecovering(chat, payload.module, payload.targetId,
          current.state, payload.nextState, null, effectId);
      }
    }
  };
}

// The production-facing finalizer for the currently certified slice.  It is
// intentionally narrow: callers must not append XP, rewards or occupancy
// replacement and then call it as a complete battle settlement.
async function finalizeRealGymRound({journal, trainerStore, gymStore, chat, player, battleId,
  activeIndex, hp, status, gymId = null, wornOccupancy = null}) {
  if (!journal || !trainerStore || !gymStore || !validTargetId(chat) || !validTargetId(player) ||
      !validTargetId(battleId)) throw fail('invalid_real_battle_finalizer_input');
  // A retry must use the exact plan captured by the original round.  Rebuilding
  // a plan from a state already changed by that round would turn an idempotent
  // retry into a different plan and incorrectly report a plan conflict.
  const existing = await journal.read(chat, battleId);
  if (existing) {
    return journal.apply(chat, battleId, createRealGymBattleAdapters({trainerStore, gymStore}));
  }
  const account = await trainerStore.readAccount(chat, player);
  const gym = wornOccupancy == null ? null : await gymStore.readRecord(chat, 'ginasios', gymId);
  const hpEffect = trainerPayload({record:account, activeIndex, hp, status});
  const wearEffect = wornOccupancy == null ? null : gymWearPayload({record:gym, targetId:gymId, nextState:wornOccupancy});
  const effects = [
    {id:`${battleId}_hp`, type:'trainer-account', payload:hpEffect},
    ...(wearEffect ? [{id:`${battleId}_wear`, type:'gym-occupancy', payload:wearEffect}] : [])
  ];
  await journal.begin({chat, player, battleId, effects});
  return journal.apply(chat, battleId, createRealGymBattleAdapters({trainerStore, gymStore}));
}

module.exports = {createRealGymBattleAdapters, trainerPayload, gymWearPayload, applyTrainerState, finalizeRealGymRound};
