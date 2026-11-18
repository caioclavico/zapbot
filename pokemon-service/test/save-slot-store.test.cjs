'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {SaveSlotStore, MODULES} = require('../runtime/save-slot-store.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

const copy = value => JSON.parse(JSON.stringify(value));
function pokemon(id, extra = {}) {
  return {'id-pokemon': id, nome: `P-${id}`, nivel: 1, hp: 10, 'hp-atual': 10,
    ataque: 5, defesa: 5, 'atq-esp': 5, 'def-esp': 5, veloc: 5,
    'xp-desde-nivel': 0, golpes: [], tipos: ['normal'], ...extra};
}
function account(id = 'legacy') {
  return {ativo: 0, equipe: [pokemon(id)], pc: [], enfermaria: [],
    inventario: {pokebola: 3}, moedas: 91, pokedex: {normal: [id]}};
}
function harness({beforeExecute} = {}) {
  const rows = new Map();
  let tail = Promise.resolve();
  const execute = (sql, args, options) => {
    const run = async () => {
      await beforeExecute?.(sql, args, options);
      if (sql.startsWith('SELECT ')) {
        const value = rows.get(`${args[0]}|${args[1]}`);
        const row = value === undefined ? null : {valor: value};
        return {first: () => row, rows: row ? [row] : [], wasApplied: () => true};
      }
      if (sql.startsWith('INSERT ') && sql.endsWith('IF NOT EXISTS')) {
        const key = `${args[0]}|${args[1]}`;
        const applied = !rows.has(key);
        if (applied) rows.set(key, args[2]);
        return {wasApplied: () => applied};
      }
      if (sql.startsWith('UPDATE ') && sql.includes('IF valor = ?')) {
        const key = `${args[1]}|${args[2]}`;
        const applied = rows.get(key) === args[3];
        if (applied) rows.set(key, args[0]);
        return {wasApplied: () => applied};
      }
      throw new Error('unexpected_cql');
    };
    const work = tail.then(run);
    tail = work.catch(() => {});
    return work;
  };
  const now = (() => { let tick = 0; return () => new Date(Date.UTC(2026, 9, 15, 0, 0, tick++)).toISOString(); })();
  return {rows, execute, store: () => new SaveSlotStore(execute, 'zapbot', {now})};
}
const source = {kind: 'legacy_estado_particionado', version: '1'};

test('store is restricted to its three additive modules', async () => {
  const h = harness(), store = h.store();
  for (const module of MODULES) await store.read(module, 'safe');
  await assert.rejects(store.read('treinador', 'g'), /invalid_module/);
  await assert.rejects(store.insertImmutable('pokemon-restauracao-backups', 'x', {}), /invalid_module/);
});

test('import preserves account snapshot, separates global receipts, and never touches active state', async () => {
  const h = harness(), store = h.store(), active = account('active');
  active['adventure-entregas'] = {currentHatch: 'activePokemon'};
  const activeBefore = copy(active);
  const legacy = account('historic');
  legacy.equipe[0].shiny = true;
  legacy.equipe[0]['xp-desde-nivel'] = 9;
  legacy.pc = [pokemon('stored', {item: 'restos', ataque: 31})];
  legacy.enfermaria = [{pokemon: pokemon('ward', {shiny: true}), 'pronto-em': 123}];
  legacy['adventure-entregas'] = {hatchA: 'historic'};
  const original = copy(legacy);
  const metadata = await store.importValidated({chat: 'group-A', player: 'player-A', slotId: 'legacy-v1', source, account: legacy, currentAccount: active});
  assert.deepEqual(legacy, original);
  assert.deepEqual(active, activeBefore);
  assert.equal(metadata.pokemonCount, 3);
  assert.equal(metadata.source, source.kind);
  assert.equal(metadata.sourceVersion, '1');
  assert.equal(metadata.format, 'json-account-v1');
  const identity = hash(['group-A', 'player-A']);
  const stored = JSON.parse(h.rows.get(`pokemon-save-slots|${identity}:legacy-v1`));
  assert.equal(stored.account.equipe[0].shiny, true);
  assert.equal(stored.account.equipe[0]['xp-desde-nivel'], 9);
  assert.equal(stored.account.pc[0].ataque, 31);
  assert.equal(stored.account.enfermaria[0].pokemon.shiny, true);
  assert.equal(Object.hasOwn(stored.account, 'adventure-entregas'), false);
  assert.equal(stored.globalReceipts.snapshotHash, hash(stored.globalReceipts.snapshot));
  const ledger = JSON.parse(h.rows.get(`pokemon-save-global-receipts|${identity}`));
  const ledgerWithoutHash = {...ledger}; delete ledgerWithoutHash.integrityHash;
  assert.deepEqual(ledgerWithoutHash, {schemaVersion: 1, receipts: {currentHatch: 'activePokemon', hatchA: 'historic'}});
  assert.equal(ledger.integrityHash, hash(ledgerWithoutHash));
});

test('metadata listing is read-only, scoped to exact group and player, and hides account payload', async () => {
  const h = harness(), store = h.store();
  await store.importValidated({chat: 'g1', player: 'p1', slotId: 'save-1', source, account: account()});
  await store.importValidated({chat: 'g1', player: 'p2', slotId: 'save-2', source, account: account('p2')});
  await store.importValidated({chat: 'g2', player: 'p1', slotId: 'save-3', source, account: account('g2-p1')});
  const writeCount = [...h.rows.keys()].length;
  const listed = await store.list('g1', 'p1');
  assert.equal(listed.length, 1);
  assert.equal(listed[0].slotId, 'save-1');
  assert.equal(Object.hasOwn(listed[0], 'account'), false);
  assert.deepEqual(await store.list('g1', 'unknown'), []);
  assert.equal((await store.metadata('g1', 'p2', 'save-2')).slotId, 'save-2');
  assert.equal(await store.metadata('g1', 'p1', 'save-2'), null);
  assert.equal([...h.rows.keys()].length, writeCount);
});

test('same immutable import is idempotent; conflicting content or provenance cannot overwrite a slot', async () => {
  const h = harness(), store = h.store();
  const input = {chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: account()};
  const first = await store.importValidated(input);
  const replay = await store.importValidated(input);
  assert.equal(replay.createdAt, first.createdAt);
  const changed = account(); changed.moedas++;
  await assert.rejects(store.importValidated({...input, account: changed}), /slot_conflict/);
  await assert.rejects(store.importValidated({...input, source: {kind: 'legacy_estado', version: '2'}}), /slot_conflict/);
  assert.equal((await store.list('g', 'p')).length, 1);
});

test('concurrent imports retain distinct slots and coalesce duplicate same-slot imports', async () => {
  const h = harness(), storeA = h.store(), storeB = h.store();
  const same = {chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: account()};
  const results = await Promise.allSettled([
    storeA.importValidated(same), storeB.importValidated(same),
    storeA.importValidated({...same, slotId: 'legacy-v2', source: {kind: 'legacy_estado', version: '2'}})
  ]);
  assert.equal(results.filter(item => item.status === 'fulfilled').length, 3);
  assert.deepEqual((await storeA.list('g', 'p')).map(item => item.slotId).sort(), ['legacy-v1', 'legacy-v2']);
  const competing = await Promise.allSettled([
    storeA.importValidated({...same, account: account('different')}),
    storeB.importValidated({...same, account: account('also-different')})
  ]);
  assert.equal(competing.filter(item => item.status === 'rejected').length, 2);
  assert.equal((await storeA.list('g', 'p')).length, 2);
});

test('a new store instance sees durable slots after restart and verifies integrity', async () => {
  const h = harness(), firstProcess = h.store();
  await firstProcess.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: account()});
  const afterRestart = h.store();
  assert.equal((await afterRestart.list('g', 'p')).length, 1);
  const identity = hash(['g', 'p']);
  const key = `pokemon-save-slots|${identity}:legacy-v1`;
  const slot = JSON.parse(h.rows.get(key)); slot.account.moedas = 999;
  h.rows.set(key, JSON.stringify(slot));
  await assert.rejects(afterRestart.metadata('g', 'p', 'legacy-v1'), /slot_corrupt/);
});

test('persistence failure never changes active account and retry completes partial import', async () => {
  let failModule = 'pokemon-save-slots';
  const h = harness({beforeExecute: (sql, args) => {
    const module = sql.startsWith('UPDATE ') ? args[1] : args[0];
    if (failModule && module === failModule && !sql.startsWith('SELECT ')) throw new Error('synthetic_cassandra_failure');
  }});
  const store = h.store(), historical = account(); historical['adventure-entregas'] = {claimed: 'pokemon'};
  const activeBefore = account('active');
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: historical}), /synthetic_cassandra_failure|durability_unconfirmed/);
  assert.deepEqual(activeBefore, account('active'));
  assert.equal((await store.list('g', 'p')).length, 0);
  // The receipt union can safely have committed first; retrying is monotonic.
  failModule = null;
  await store.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: historical});
  assert.equal((await store.list('g', 'p')).length, 1);
});

test('receipt conflicts and invalid source records are rejected before slot publication', async () => {
  const h = harness(), store = h.store(), one = account();
  one['adventure-entregas'] = {event: 'pokemon-A'};
  await store.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v1', source, account: one});
  const two = account('other'); two['adventure-entregas'] = {event: 'pokemon-B'};
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v2', source: {kind: 'legacy_estado', version: '2'}, account: two}), /global_receipt_conflict/);
  const currentConflict = account('current'); currentConflict['adventure-entregas'] = {newEvent: 'different-pokemon'};
  const legacyConflict = account('legacy'); legacyConflict['adventure-entregas'] = {newEvent: 'legacy-pokemon'};
  await assert.rejects(store.importValidated({chat: 'g', player: 'p2', slotId: 'legacy-v1', source, account: legacyConflict, currentAccount: currentConflict}), /global_receipt_conflict/);
  const bad = account('bad'); bad.equipe[0].shiny = 'yes';
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'bad-slot', source, account: bad}), /invalid_shiny/);
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'legacy-v2', source: {kind: 'legacy_estado', version: '1'}, account: account()}), /invalid_slot_source/);
  assert.deepEqual((await store.list('g', 'p')).map(item => item.slotId), ['legacy-v1']);
});

test('invalid slot IDs, oversized records, unknown fields with operational semantics, and active receipts fail closed', async () => {
  const store = harness().store();
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: '../escape', source, account: account()}), /invalid_slot_id/);
  const pending = account(); pending['adventure-eventos'] = {evt: {foo: 'pending'}};
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'pending', source, account: pending}), /operational_review_required/);
  const internalReceipt = account(); internalReceipt['restore-receipt'] = {operation: 'restore'};
  await assert.rejects(store.importValidated({chat: 'g', player: 'p', slotId: 'receipt', source, account: internalReceipt}), /invalid_slot_source/);
});
