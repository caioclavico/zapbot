'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {saveGroup} = require('../runtime/trainer-state-store.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

function database(initial = {}) {
  const rows = new Map([['treinador|group-A', JSON.stringify(initial)]]);
  let tail = Promise.resolve();
  let loseWrite = false;
  const execute = (sql, args) => {
    const run = async () => {
      if (sql.startsWith('SELECT ')) {
        const value = rows.get(`${args[0]}|${args[1]}`);
        const first = value === undefined ? null : {valor: value};
        return {first: () => first, rows: first ? [first] : []};
      }
      if (sql.startsWith('UPDATE ') && sql.includes('IF valor = ?')) {
        const key = `${args[1]}|${args[2]}`;
        const applied = rows.get(key) === args[3];
        if (applied) rows.set(key, args[0]);
        if (applied && loseWrite) { loseWrite = false; throw new Error('lost_response_after_lwt'); }
        return {wasApplied: () => applied};
      }
      if (sql.startsWith('INSERT ') && sql.endsWith('IF NOT EXISTS')) {
        const key = `${args[0]}|${args[1]}`;
        const applied = !rows.has(key);
        if (applied) rows.set(key, args[2]);
        return {wasApplied: () => applied};
      }
      throw new Error('unexpected_query');
    };
    const pending = tail.then(run);
    tail = pending.catch(() => {});
    return pending;
  };
  return {rows, execute, loseResponse() { loseWrite = true; }};
}

const table = 'zapbot.estado_particionado';
const parseGroup = h => JSON.parse(h.rows.get('treinador|group-A'));

test('CAS reaplica apenas as contas alteradas e preserva o jogador atualizado em outra instância', async () => {
  const before = {p1: {coins: 1}, p2: {coins: 2}}, h = database(before);
  h.rows.set('treinador|group-A', JSON.stringify({p1: before.p1, p2: {coins: 20}}));
  await saveGroup(h.execute, table, 'group-A', before, {p1: {coins: 10}, p2: before.p2});
  assert.deepEqual(parseGroup(h), {p1: {coins: 10}, p2: {coins: 20}});
});

test('CAS rejects a stale update to the same player and never overwrites the concurrent account', async () => {
  const before = {p1: {coins: 1}}, h = database(before);
  h.rows.set('treinador|group-A', JSON.stringify({p1: {coins: 7}}));
  await assert.rejects(saveGroup(h.execute, table, 'group-A', before, {p1: {coins: 9}}), /trainer_cas_conflict/);
  assert.deepEqual(parseGroup(h), {p1: {coins: 7}});
});

test('durable player transition lock rejects ordinary trainer persistence', async () => {
  const before = {p1: {coins: 1}}, h = database(before);
  h.rows.set(`pokemon-save-transition-locks|${hash(['group-A', 'account', 'p1'])}`,
    JSON.stringify({schemaVersion: 1, generation: 2, owner: 'pending-op', resourceHash: hash({type: 'account', id: 'p1'})}));
  await assert.rejects(saveGroup(h.execute, table, 'group-A', before, {p1: {coins: 2}}), /save_transition_pending/);
  assert.deepEqual(parseGroup(h), before);
});

test('two different player writers converge through Cassandra-row CAS without losing either update', async () => {
  const before = {p1: {coins: 1}, p2: {coins: 2}}, h = database(before);
  const [a, b] = await Promise.all([
    saveGroup(h.execute, table, 'group-A', before, {p1: {coins: 11}, p2: before.p2}),
    saveGroup(h.execute, table, 'group-A', before, {p1: before.p1, p2: {coins: 22}})
  ]);
  assert.deepEqual(parseGroup(h), {p1: {coins: 11}, p2: {coins: 22}});
  assert.equal([a, b].every(Boolean), true);
});

test('lost LWT response is resolved by SERIAL read and not reported as an unconfirmed write', async () => {
  const before = {p1: {coins: 1}}, h = database(before);
  h.loseResponse();
  const result = await saveGroup(h.execute, table, 'group-A', before, {p1: {coins: 2}});
  assert.deepEqual(result, {p1: {coins: 2}});
  assert.deepEqual(parseGroup(h), {p1: {coins: 2}});
});
