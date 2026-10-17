'use strict';

// Proposed minimal close for assert→INSERT TOCTOU on the loja partition.
//
// WHY AN EXTRA SELECT IS NOT A FIX
// -------------------------------
// assertLateralIntentActive then INSERT spans two partitions (trainer row vs
// estado_particionado/loja). A second SERIAL read before INSERT still leaves a
// window; timeouts/lease TTL do not prove a paused writer stopped.
//
// MINIMAL ARCHITECTURAL CHANGE
// ----------------------------
// Put the write lease on the SAME row as the loja valor and condition the
// mutation with LWT:
//
//   ALTER TABLE ...estado_particionado ADD IF NOT EXISTS write_lease text;
//
//   -- acquire (after trainer begin succeeded):
//   UPDATE ... SET write_lease = ? WHERE modulo='loja' AND particao='__valor__'
//     IF write_lease = '' OR write_lease = ?
//
//   -- durable economy write bound to the lease:
//   UPDATE ... SET valor = ?, write_lease = ? WHERE ...
//     IF write_lease = ?
//
//   -- release after trainer confirm+reconcile (or on abort):
//   UPDATE ... SET write_lease = '' WHERE ... IF write_lease = ?
//
// Consequences:
// - Loja writers must go through acquire→writeIF→release (no bare INSERT).
// - Other modules on estado_particionado ignore write_lease (null/'').
// - Raids/ginasios stay unchanged until they adopt the same pattern.
// - Trainer LateralIntent remains the A→B barrier; the lease stops a stale
//   INSERT after the intent was reconciled/released.
// - Still not cross-partition atomic with the trainer begin LWT: acquire can
//   fail after begin (intent stays active → retry or mark uncertain). That is
//   acceptable fail-closed behaviour.
//
// This module is the protocol skeleton + in-memory/Cassandra executor adapter.
// Production armazenamento is NOT switched over in this change set.
const fail = (code, message = code) => Object.assign(new Error(message), {code});

function createMemoryLojaLeaseStore() {
  let row = {valor: null, writeLease: ''};
  return {
    snapshot() { return {...row, valor: row.valor ? structuredClone(row.valor) : null}; },
    async acquire(operationId) {
      if (!operationId) throw fail('invalid_loja_lease');
      if (row.writeLease && row.writeLease !== operationId) {
        throw fail('loja_write_lease_held');
      }
      row = {...row, writeLease: operationId};
      return row.writeLease;
    },
    async writeIfLease(operationId, valor) {
      if (row.writeLease !== operationId) {
        throw fail('loja_write_lease_mismatch');
      }
      row = {valor: structuredClone(valor), writeLease: operationId};
      return row.valor;
    },
    async release(operationId) {
      if (row.writeLease !== operationId) {
        throw fail('loja_write_lease_mismatch');
      }
      row = {...row, writeLease: ''};
      return true;
    },
    // Unconditional write — models today's estado_particionado INSERT/UPDATE.
    async writeUnconditional(valor) {
      row = {...row, valor: structuredClone(valor)};
      return row.valor;
    }
  };
}

/**
 * Cassandra adapter for the harness table (not production DDL):
 *   (modulo, particao) PK with valor text, write_lease text
 */
function createCassandraLojaLeaseStore(execute, {keyspace, modulo = 'loja', particao = '__valor__'}) {
  const table = `${keyspace}.loja_lease_partition`;
  return {
    table,
    async ensureSchema() {
      await execute(
        `CREATE TABLE IF NOT EXISTS ${table} (
          modulo text, particao text, valor text, write_lease text,
          PRIMARY KEY ((modulo), particao))`);
      await execute(
        `INSERT INTO ${table} (modulo, particao, valor, write_lease)
         VALUES (?, ?, ?, ?) IF NOT EXISTS`,
        [modulo, particao, null, '']);
    },
    async acquire(operationId) {
      const read = await execute(
        `SELECT write_lease FROM ${table} WHERE modulo = ? AND particao = ?`,
        [modulo, particao], {consistency: 'SERIAL'});
      const current = read?.first?.()?.write_lease || read?.rows?.[0]?.write_lease || '';
      if (current && current !== operationId) throw fail('loja_write_lease_held');
      const result = await execute(
        `UPDATE ${table} SET write_lease = ? WHERE modulo = ? AND particao = ?
         IF write_lease = ?`,
        [operationId, modulo, particao, current || ''],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (!result?.wasApplied?.()) throw fail('loja_write_lease_held');
      return operationId;
    },
    async writeIfLease(operationId, valor) {
      const encoded = typeof valor === 'string' ? valor : JSON.stringify(valor);
      const result = await execute(
        `UPDATE ${table} SET valor = ?, write_lease = ? WHERE modulo = ? AND particao = ?
         IF write_lease = ?`,
        [encoded, operationId, modulo, particao, operationId],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (!result?.wasApplied?.()) throw fail('loja_write_lease_mismatch');
      return valor;
    },
    async release(operationId) {
      const result = await execute(
        `UPDATE ${table} SET write_lease = ? WHERE modulo = ? AND particao = ?
         IF write_lease = ?`,
        ['', modulo, particao, operationId],
        {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
      if (!result?.wasApplied?.()) throw fail('loja_write_lease_mismatch');
      return true;
    },
    async writeUnconditional(valor) {
      const encoded = typeof valor === 'string' ? valor : JSON.stringify(valor);
      await execute(
        `UPDATE ${table} SET valor = ? WHERE modulo = ? AND particao = ?`,
        [encoded, modulo, particao],
        {consistency: 'QUORUM'});
      return valor;
    },
    async readValor() {
      const result = await execute(
        `SELECT valor, write_lease FROM ${table} WHERE modulo = ? AND particao = ?`,
        [modulo, particao], {consistency: 'SERIAL'});
      const row = result?.first?.() || result?.rows?.[0];
      if (!row?.valor) return {valor: null, writeLease: row?.write_lease || ''};
      return {valor: JSON.parse(row.valor), writeLease: row.write_lease || ''};
    }
  };
}

module.exports = {
  createMemoryLojaLeaseStore,
  createCassandraLojaLeaseStore
};
