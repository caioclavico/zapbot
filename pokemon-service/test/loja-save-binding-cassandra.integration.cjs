'use strict';

// Opt-in: disposable Cassandra 5.0.9 proves the loja save-bound envelope
// survives restart in estado_particionado. Does not enable production flags.
// Domain gate logic lives in loja_save_test.cljs (npm run test:domain).
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');

const HOST = 'zapbot-loja-save-cassandra-test';
const KEYSPACE = 'loja_save_binding_validation';
const host = process.env.LOJA_SAVE_CASSANDRA_HOST;
const phase = process.env.LOJA_SAVE_CASSANDRA_PHASE || 'seed';

if (require.main === module && (process.env.LOJA_SAVE_CASSANDRA_ISOLATED !== 'true' ||
    host !== HOST || !['seed', 'verify-restart'].includes(phase))) {
  throw new Error('Refusing loja-save Cassandra integration without the isolated test harness.');
}

const ENVELOPE = {
  g: {
    ash: {
      'schema-version-loja': 'save-bound-v1',
      'legacy-claim': { 'save-id': 'save-A', status: 'confirmed', 'fence-generation': 1 },
      saves: {
        'save-A': { moedas: 150, inventario: { pocao: 2 } },
        'save-B': { moedas: 0, inventario: {} }
      }
    },
    peer: {
      'schema-version-loja': 'save-bound-v1',
      'legacy-claim': { 'save-id': 'peer-A', status: 'confirmed', 'fence-generation': 1 },
      saves: { 'peer-A': { moedas: 80, inventario: {} } }
    }
  }
};

async function connectReady(client) {
  const deadline = Date.now() + 180000;
  let lastError;
  while (Date.now() < deadline) {
    try {
      await client.connect();
      const row = (await client.execute('SELECT release_version FROM system.local')).first();
      if (row?.release_version !== '5.0.9') {
        throw new Error('Disposable Cassandra must be exactly version 5.0.9.');
      }
      return row.release_version;
    } catch (error) {
      lastError = error;
      try { await client.shutdown(); } catch {}
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  throw new Error(`Cassandra CQL readiness timeout: ${lastError?.name || 'unavailable'}`);
}

async function setup(client) {
  await client.execute(
    `CREATE KEYSPACE IF NOT EXISTS ${KEYSPACE} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
  await client.execute(
    `CREATE TABLE IF NOT EXISTS ${KEYSPACE}.estado_particionado (
      modulo text, particao text, valor text, PRIMARY KEY ((modulo, particao)))`);
}

async function writeLoja(client, valor) {
  const result = await client.execute(
    `INSERT INTO ${KEYSPACE}.estado_particionado (modulo, particao, valor)
     VALUES (?, ?, ?) IF NOT EXISTS`,
    ['loja', '__valor__', JSON.stringify(valor)],
    {
      prepare: true,
      consistency: cassandra.types.consistencies.quorum,
      serialConsistency: cassandra.types.consistencies.serial
    });
  const row = result.first();
  if (row && row['[applied]'] === false) {
    // Idempotent re-stamp after seed restart path.
    await client.execute(
      `UPDATE ${KEYSPACE}.estado_particionado SET valor = ? WHERE modulo = ? AND particao = ? IF EXISTS`,
      [JSON.stringify(valor), 'loja', '__valor__'],
      {
        prepare: true,
        consistency: cassandra.types.consistencies.quorum,
        serialConsistency: cassandra.types.consistencies.serial
      });
  }
}

async function readLoja(client) {
  const result = await client.execute(
    `SELECT valor FROM ${KEYSPACE}.estado_particionado WHERE modulo = ? AND particao = ?`,
    ['loja', '__valor__'],
    {
      prepare: true,
      consistency: cassandra.types.consistencies.serial
    });
  const row = result.first();
  assert.ok(row?.valor, 'loja row must exist after durable write');
  return JSON.parse(row.valor);
}

async function seed(client) {
  await setup(client);
  await writeLoja(client, ENVELOPE);
  const lido = await readLoja(client);
  assert.equal(lido.g.ash.saves['save-A'].moedas, 150);
  assert.equal(lido.g.ash.saves['save-B'].moedas, 0);
  assert.equal(lido.g.ash['legacy-claim']['save-id'], 'save-A',
    'envelope de A não pode ser reinterpretado como B');
  assert.equal(lido.g.peer.saves['peer-A'].moedas, 80, 'peer preservado');
  console.log('PASS seed: loja save-bound envelope escrito com LWT.');
}

async function verifyRestart(client) {
  const lido = await readLoja(client);
  assert.equal(lido.g.ash['schema-version-loja'], 'save-bound-v1');
  assert.equal(lido.g.ash.saves['save-A'].moedas, 150);
  assert.equal(lido.g.ash.saves['save-B'].moedas, 0);
  assert.equal(lido.g.ash['legacy-claim']['save-id'], 'save-A');
  assert.equal(lido.g.peer.saves['peer-A'].moedas, 80);
  // Sem compra posterior: snapshot sobrevive só com o que foi confirmado.
  console.log('PASS verify-restart: envelope A/B e peer intactos após reinício.');
}

async function main() {
  const client = new cassandra.Client({
    contactPoints: [host],
    localDataCenter: 'datacenter1',
    protocolOptions: { port: 9042 },
    credentials: { username: 'cassandra', password: 'cassandra' },
    socketOptions: { connectTimeout: 10000, readTimeout: 15000 }
  });
  try {
    await connectReady(client);
    if (phase === 'seed') await seed(client);
    else await verifyRestart(client);
  } finally {
    await client.shutdown();
  }
}

if (require.main === module) {
  main().catch(err => {
    console.error(err);
    process.exit(1);
  });
}

module.exports = { ENVELOPE, KEYSPACE, HOST };
