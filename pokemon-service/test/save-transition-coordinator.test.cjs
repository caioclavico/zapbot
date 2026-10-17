'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {SaveTransitionCoordinator} = require('../runtime/save-transition-coordinator.cjs');
const WRITER_COVERAGE = {writerProtocolCoverageVerified: true};

function harness() {
  const rows = new Map();
  let tail = Promise.resolve();
  let loseNextTrainerWrite = false;
  const execute = (sql, args) => {
    const run = async () => {
      if (sql.startsWith('SELECT ')) {
        const value = rows.get(`${args[0]}|${args[1]}`);
        const row = value === undefined ? null : {valor: value};
        return {first: () => row, rows: row ? [row] : [], wasApplied: () => true};
      }
      if (sql.startsWith('INSERT ') && sql.endsWith('IF NOT EXISTS')) {
        const key = `${args[0]}|${args[1]}`;
        const applied = !rows.has(key);
        if (applied) rows.set(key, args[2]);
        if (applied && loseNextTrainerWrite && args[0] === 'treinador') {
          loseNextTrainerWrite = false;
          throw new Error('synthetic_lost_response_after_lwt');
        }
        return {wasApplied: () => applied};
      }
      if (sql.startsWith('INSERT ')) {
        rows.set(`${args[0]}|${args[1]}`, args[2]);
        return {wasApplied: () => true};
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
  const coordinator = (enabled = true) => new SaveTransitionCoordinator(execute, 'zapbot', {
    enabled, legacyJournalCoverageVerified: true, ...WRITER_COVERAGE
  });
  return {rows, execute, coordinator, loseNextWrite() { loseNextTrainerWrite = true; }};
}

const resources = player => [{type: 'account', id: player}, {type: 'gym', id: 'gym-A'}];
async function reserve(h, player, operationId) {
  const c = h.coordinator();
  await c.begin({chat: 'group-A', player, operationId, resources: resources(player)});
  return {c, snapshot: await c.reserve('group-A', player, operationId)};
}
async function toConclusion(c, player, operationId) {
  for (const phase of ['backup', 'encerramento', 'ativacao', 'verificacao', 'conclusao']) {
    await c.advance('group-A', player, operationId, phase);
  }
}

test('save transition coordination is disabled unless explicitly enabled', async () => {
  const h = harness(), c = h.coordinator(false);
  await assert.rejects(c.begin({chat: 'g', player: 'p', operationId: 'disabled-01', resources: resources('p')}), /save_transition_disabled/);
  assert.equal(h.rows.size, 0);
});

test('enabling transitions alone cannot bypass uncertified domain-writer coverage', async () => {
  const h = harness();
  const c = new SaveTransitionCoordinator(h.execute, 'zapbot', {
    enabled: true, legacyJournalCoverageVerified: true
  });
  await assert.rejects(c.begin({chat: 'group-A', player: 'p1', operationId: 'coverage-01', resources: resources('p1')}),
    /save_transition_writer_coverage_unverified/);
  assert.equal(h.rows.size, 0);
});

test('concurrent reservations serialize the shared gym and isolate account locks by group/player', async () => {
  const h = harness();
  const a = h.coordinator(), b = h.coordinator();
  await Promise.all([
    a.reconcileStartup(), b.reconcileStartup()
  ]);
  await a.begin({chat: 'group-A', player: 'p1', operationId: 'operation-A1', resources: resources('p1')});
  await b.begin({chat: 'group-A', player: 'p2', operationId: 'operation-B1', resources: resources('p2')});
  const results = await Promise.allSettled([
    a.reserve('group-A', 'p1', 'operation-A1'),
    b.reserve('group-A', 'p2', 'operation-B1')
  ]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
  assert.equal(results.filter(result => result.status === 'rejected').length, 1);
  await a.begin({chat: 'group-B', player: 'p2', operationId: 'operation-C1', resources: resources('p2')});
  assert.equal((await a.reserve('group-B', 'p2', 'operation-C1')).resources.every(item => item.owned), true);
});

test('effect intent is durable before LWT and a lost response is reconciled by reading the exact value', async () => {
  const h = harness();
  const {c, snapshot} = await reserve(h, 'p1', 'operation-effect-1');
  await c.advance('group-A', 'p1', 'operation-effect-1', 'backup');
  const accountFence = snapshot.resources.find(item => item.resource.type === 'account').fence;
  h.loseNextWrite();
  assert.equal(await c.writeFenced({chat: 'group-A', operationId: 'operation-effect-1', fence: accountFence,
    effectId: 'trainer-effect-1', module: 'treinador', partition: 'synthetic-p1',
    expectedValue: null, nextValue: {synthetic: true, pokemon: ['synthetic-id']}}), true);
  const state = await c.inspect('group-A', 'p1', 'operation-effect-1');
  assert.equal(state.effects[0].status, 'applied');
  assert.deepEqual(JSON.parse(h.rows.get('treinador|synthetic-p1')), {synthetic: true, pokemon: ['synthetic-id']});
  await toConclusion(c, 'p1', 'operation-effect-1');
  await c.release('group-A', 'p1', 'operation-effect-1');
});

test('old fencing generation is rejected after release and a later reservation', async () => {
  const h = harness();
  const first = await reserve(h, 'p1', 'operation-old-1');
  const staleFence = first.snapshot.resources.find(item => item.resource.type === 'account').fence;
  await toConclusion(first.c, 'p1', 'operation-old-1');
  await first.c.release('group-A', 'p1', 'operation-old-1');
  const second = await reserve(h, 'p1', 'operation-new-1');
  assert.ok(second.snapshot.resources.find(item => item.resource.type === 'account').generation > staleFence.generation);
  await second.c.advance('group-A', 'p1', 'operation-new-1', 'backup');
  await assert.rejects(second.c.writeFenced({chat: 'group-A', operationId: 'operation-old-1', fence: staleFence,
    effectId: 'trainer-stale-1', module: 'treinador', partition: 'synthetic-p1',
    expectedValue: null, nextValue: {old: true}}), /stale_transition_fence|transition_phase_invalid/);
});

test('a plain legacy upsert bypasses the coordination lock, documenting the integration blocker', async () => {
  const h = harness();
  await reserve(h, 'p1', 'operation-legacy-1');
  // This models current armazenamento/salvar!'s unconditional INSERT. The
  // durable lock cannot constrain an already deployed writer that bypasses it.
  await h.execute('INSERT INTO zapbot.estado_particionado (modulo, particao, valor) VALUES (?, ?, ?)',
    ['treinador', 'legacy-writer-p1', JSON.stringify({staleSnapshot: true})]);
  assert.deepEqual(JSON.parse(h.rows.get('treinador|legacy-writer-p1')), {staleSnapshot: true});
});

test('startup index refuses uncertified legacy coverage and blocks unresolved operations after restart', async () => {
  const h = harness();
  const unverified = new SaveTransitionCoordinator(h.execute, 'zapbot', {enabled: true, ...WRITER_COVERAGE});
  await assert.rejects(unverified.begin({chat: 'g', player: 'p', operationId: 'unverified-1', resources: resources('p')}),
    /transition_index_coverage_unverified/);
  assert.equal(h.rows.size, 0);

  const c = h.coordinator();
  await c.begin({chat: 'group-A', player: 'p1', operationId: 'pending-index-1', resources: resources('p1')});
  await c.reserve('group-A', 'p1', 'pending-index-1');
  const restarted = h.coordinator();
  await assert.rejects(restarted.reconcileStartup(), /transition_startup_unresolved/);
  assert.equal(JSON.parse(h.rows.get('pokemon-save-transition-index|active')).operations !== undefined, true);
});

test('startup reconciliation releases only a concluded operation and removes its index entry idempotently', async () => {
  const h = harness(), c = h.coordinator();
  await c.begin({chat: 'group-A', player: 'p1', operationId: 'completed-index-1', resources: resources('p1')});
  const state = await c.reserve('group-A', 'p1', 'completed-index-1');
  await c.advance('group-A', 'p1', 'completed-index-1', 'backup');
  await toConclusion(c, 'p1', 'completed-index-1');
  const restarted = h.coordinator();
  assert.deepEqual(await restarted.reconcileStartup(), {ready: true, pending: 0});
  assert.deepEqual(JSON.parse(h.rows.get('pokemon-save-transition-index|active')).operations, {});
  const account = state.resources.find(item => item.resource.type === 'account');
  assert.equal(JSON.parse(h.rows.get(`pokemon-save-transition-locks|${account.fence.resourcePartition}`)).owner, null);
  assert.deepEqual(await restarted.reconcileStartup(), {ready: true, pending: 0});
});
