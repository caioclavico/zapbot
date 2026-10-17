'use strict';

// Read-only eligibility gate for a future playable-save transition.  This
// module intentionally does not clear activities or acquire locks: it reports
// durable state before a reservation and again immediately after it.
const {RestoreError, hash} = require('./restore-protocol.cjs');

const MODULES = Object.freeze({
  gyms: 'ginasios', battles: 'pokemon-batalhas-ativas', hunts: 'pokemon-cacadas-ativas',
  raids: 'raids', trades: 'pokemon-propostas-troca', moveOffers: 'pokemon-remocoes-pendentes',
  transitions: 'pokemon-save-transition-journal'
});
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const first = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const own = (value, key) => Object.prototype.hasOwnProperty.call(value || {}, key);

function block(code, details = {}) { return {code, ...details}; }
function playerIn(value, player) {
  if (!object(value)) return false;
  if (['pid', 'player', 'jogador', 'atacante', 'defensor'].some(key => value[key] === player)) return true;
  return ['participantes', 'jogadores', 'players'].some(key => object(value[key]) && own(value[key], player));
}

const EGG_PENDING = new Set(['incubating', 'ready_to_hatch', 'ready-to-hatch', 'delivery_pending']);
const eggState = egg => String(egg?.state || egg?.['state'] || '');

function evaluate({chat, player, account, states = {}, adventure = null, now = Date.now(),
  writerCoverageVerified = false, transition = null, ignoreOperationId = null} = {}) {
  const blocks = [];
  // No caller may treat a local lock or an atom as writer coverage. Until all
  // game writers and callbacks fence their final writes, approval fails closed.
  if (writerCoverageVerified !== true) blocks.push(block('activity_writer_coverage_unverified'));
  if (!object(account)) blocks.push(block('activity_account_unavailable'));

  const gyms = states.gyms;
  if (gyms !== undefined && !object(gyms)) blocks.push(block('activity_gym_state_invalid'));
  if (object(gyms)) for (const [gymId, gym] of Object.entries(gyms)) {
    if (object(gym) && gym.pid === player) blocks.push(block('activity_gym_occupied', {gymId,
      defenders: Array.isArray(gym.time) ? gym.time.map(item => item?.nome).filter(Boolean) : []}));
  }

  for (const [name, code] of [['battles','activity_battle_active'], ['hunts','activity_hunt_active']]) {
    const state = states[name];
    if (state !== undefined && !object(state)) blocks.push(block(`${code}_state_invalid`));
    if (object(state) && Object.values(state).some(item => playerIn(item, player))) blocks.push(block(code));
  }
  const raids = states.raids;
  if (raids !== undefined && !object(raids)) blocks.push(block('activity_raid_state_invalid'));
  // Partition value may be a single raid ({fase, participantes}) or a map of raids.
  if (object(raids)) {
    const list = (object(raids.participantes) || typeof raids.fase === 'string')
      ? [raids] : Object.values(raids);
    for (const raid of list) {
      if (!object(raid) || !playerIn(raid, player)) continue;
      if (['inscricoes', 'combate'].includes(raid.fase)) blocks.push(block('activity_raid_active'));
      // captura-expira is the capture window; expira is the combat/inscription timer.
      const captureUntil = Number(raid['captura-expira'] ?? raid.capturaExpira ?? raid.expira ?? 0);
      if (raid.fase === 'vitoria' && captureUntil > now &&
          object(raid.participantes?.[player]) &&
          Number(raid.participantes[player].dano || 0) > 0 &&
          !(raid.capturas && object(raid.capturas[player]) && raid.capturas[player].capturou) &&
          Number(raid.capturas?.[player]?.tentativas || 0) < 3) {
        blocks.push(block('activity_raid_capture_pending'));
      }
    }
  }
  for (const [name, code] of [['trades','activity_trade_pending'], ['moveOffers','activity_move_offer_pending']]) {
    const state = states[name];
    if (state !== undefined && !object(state)) blocks.push(block(`${code}_state_invalid`));
    if (object(state) && Object.values(state).some(item => playerIn(item, player) ||
        (object(item) && (item.a === player || item.b === player)))) blocks.push(block(code));
  }
  if (object(account)) {
    const joy = account.enfermaria;
    if (joy !== undefined && !Array.isArray(joy)) blocks.push(block('activity_joy_state_invalid'));
    if (Array.isArray(joy) && joy.some(item => Number(item?.['pronto-em']) > now)) blocks.push(block('activity_joy_pending'));
    const events = account['adventure-eventos'];
    if (events !== undefined && !object(events)) blocks.push(block('activity_adventure_events_invalid'));
    if (object(events) && Object.keys(events).length > 0) blocks.push(block('activity_adventure_events_pending'));
    const transfer = account['transferencia-professor'];
    if (transfer !== undefined && transfer !== null && !object(transfer)) {
      blocks.push(block('activity_professor_transfer_invalid'));
    }
    if (object(transfer) && Number(transfer.expira || transfer['expira'] || 0) > now) {
      blocks.push(block('activity_professor_transfer_pending'));
    }
  }
  if (adventure !== undefined && adventure !== null) {
    if (!object(adventure)) blocks.push(block('activity_adventure_state_invalid'));
    else {
      const eggs = Array.isArray(adventure.eggs) ? adventure.eggs : [];
      if (eggs.some(egg => EGG_PENDING.has(eggState(egg)))) {
        blocks.push(block('activity_adventure_egg_pending'));
      }
      if (adventure.hasPendingHatch === true ||
          (Array.isArray(adventure.pendingDeliveries) && adventure.pendingDeliveries.length > 0)) {
        blocks.push(block('activity_adventure_delivery_pending'));
      }
    }
  }
  if (transition && transition.operationId !== ignoreOperationId && transition.complete !== true) {
    blocks.push(block('activity_transition_pending', {operationId: transition.operationId}));
  }
  return {eligible: blocks.length === 0, blocks};
}

class SaveActivityEligibility {
  constructor(execute, keyspace, {
    now = () => Date.now(), writerCoverageVerified = false, accountReader, adventureReader = null
  } = {}) {
    if (typeof execute !== 'function' || !/^[a-z][a-z0-9_]*$/.test(keyspace) || typeof accountReader !== 'function') {
      throw new RestoreError('save_activity_invalid_config');
    }
    if (adventureReader != null && typeof adventureReader !== 'function') {
      throw new RestoreError('save_activity_invalid_config');
    }
    this.execute = execute; this.table = `${keyspace}.estado_particionado`; this.now = now;
    this.writerCoverageVerified = writerCoverageVerified === true;
    this.accountReader = accountReader;
    this.adventureReader = adventureReader;
  }
  async read(module, partition) {
    const result = await this.execute(`SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,
      [module, partition], {prepare:true, consistency:'SERIAL'});
    const value = first(result)?.valor;
    if (value == null) return undefined;
    try { return JSON.parse(value); } catch { throw new RestoreError('save_activity_state_corrupt', {module}); }
  }
  async transition(chat, player) {
    const identity = hash([chat, player]);
    const journal = await this.read(MODULES.transitions, identity);
    if (!object(journal?.operations)) return null;
    const pending = Object.entries(journal.operations).find(([, operation]) => object(operation) && operation.phase !== 'conclusao');
    return pending ? {operationId:pending[0], complete:false} : null;
  }
  async readAdventure(chat, player) {
    if (!this.adventureReader) return null;
    try {
      return await this.adventureReader(chat, player);
    } catch (error) {
      // Ambiguous Adventure state must not approve a save switch.
      throw new RestoreError('save_activity_adventure_unavailable', {
        cause: error?.code || error?.message || 'adventure_read_failed'
      });
    }
  }
  async check({chat, player, ignoreOperationId = null} = {}) {
    const [account, gyms, battles, hunts, raids, trades, moveOffers, transition, adventure] = await Promise.all([
      this.accountReader(chat, player), this.read(MODULES.gyms, chat), this.read(MODULES.battles, chat),
      this.read(MODULES.hunts, chat), this.read(MODULES.raids, chat), this.read(MODULES.trades, chat),
      this.read(MODULES.moveOffers, chat), this.transition(chat, player), this.readAdventure(chat, player)
    ]);
    return evaluate({chat, player, account:account?.state || account, adventure, now:this.now(),
      writerCoverageVerified:this.writerCoverageVerified, transition, ignoreOperationId,
      states:{battles, hunts, raids, trades, moveOffers, gyms}});
  }
  async assertEligible(args) {
    const report = await this.check(args);
    if (!report.eligible) throw new RestoreError('save_transition_activity_blocked', {blocks:report.blocks});
    return report;
  }
  async assertActivityMayStart({chat, player, coordinator, ignoreOperationId = null} = {}) {
    if (!coordinator || typeof coordinator.resourcePartition !== 'function') {
      throw new RestoreError('save_transition_activity_reserved');
    }
    const resource = {type:'account', id:player};
    const lock = await coordinator.read('pokemon-save-transition-locks', coordinator.resourcePartition(chat, resource));
    const owner = lock?.value?.owner;
    if (owner && owner !== ignoreOperationId) {
      throw new RestoreError('save_transition_activity_reserved', {operationId:owner});
    }
    const account = await this.accountReader(chat, player);
    const reserved = account?.transitionOwner || account?.state?.transitionOwner;
    if (reserved && reserved !== ignoreOperationId) {
      throw new RestoreError('save_transition_activity_reserved', {operationId:reserved});
    }
    return true;
  }
}

module.exports = {MODULES, SaveActivityEligibility, evaluate};
