'use strict';

// Exportador operacional somente leitura. Este módulo nunca executa INSERT,
// UPDATE, DELETE, DDL ou operações de schema no Cassandra.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const {hash} = require('../runtime/restore-protocol.cjs');

const VERSION_SOURCES = Object.freeze({
  v1: {kind: 'legacy_estado_particionado', version: '1'},
  v2: {kind: 'legacy_estado', version: '2'}
});
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const validId = value => typeof value === 'string' && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value);
const requestedVersions = value => {
  const raw = String(value || 'both').trim().toLowerCase();
  const versions = raw === 'both' ? ['v1', 'v2'] : raw.split(',').map(v => v.trim()).filter(Boolean);
  if (!versions.length || versions.some(v => !Object.hasOwn(VERSION_SOURCES, v)) || new Set(versions).size !== versions.length) {
    throw new Error('invalid_versions');
  }
  return versions;
};

function parseAccount(raw, version, chat, player) {
  let value;
  try { value = JSON.parse(raw); } catch { throw new Error(`invalid_json_${version}`); }
  const account = version === 'v1' ? value?.[player] : value?.[chat]?.[player];
  if (!object(account)) throw new Error(`historical_save_not_found_${version}`);
  const bytes = Buffer.byteLength(JSON.stringify(account));
  if (bytes > 8 * 1024 * 1024) throw new Error(`historical_save_too_large_${version}`);
  return account;
}

async function readOne(execute, query, params, version, chat, player) {
  const result = await execute(
    query,
    params,
    {prepare: true, consistency: 'SERIAL'}
  );
  const rows = Array.isArray(result?.rows) ? result.rows : (typeof result?.first === 'function' ? (result.first() ? [result.first()] : []) : []);
  if (rows.length > 1) throw new Error(`historical_save_ambiguous_${version}`);
  if (!rows.length || !rows[0]?.valor) throw new Error(`historical_save_not_found_${version}`);
  return parseAccount(rows[0].valor, version, chat, player);
}

function ednString(value) { return JSON.stringify(value); }
function ednKey(key) { return `:${String(key).replace(/[^A-Za-z0-9_.*+!?_-]/g, '_')}`; }
// Structural maps use keyword keys; account payloads retain their original
// string keys so transform-export can distinguish legacy fields faithfully.
function toEdn(value, structural = false) {
  if (value === null || value === undefined) return 'nil';
  if (typeof value === 'string') return ednString(value);
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return `[${value.map(v => toEdn(v, structural)).join(' ')}]`;
  if (object(value)) return `{${Object.entries(value).map(([k, v]) => {
    const childStructural = structural && !['treinador', 'loja'].includes(k);
    return `${structural ? ednKey(k) : ednString(k)} ${toEdn(v, childStructural)}`;
  }).join(' ')}}`;
  throw new Error('unsupported_export_value');
}

function snapshotFor(version, chat, player, account) {
  const source = VERSION_SOURCES[version];
  const sourceRecordHash = hash(account);
  const sourceRecordId = `${source.kind}:${chat}:${player}`;
  const snapshotId = `${version}-${sourceRecordHash}`;
  return {
    'snapshot-id': snapshotId,
    'legacy-version': version,
    'chat-id': chat,
    'player-id': player,
    'target-slot': `recovery-${version}`,
    'created-at': new Date().toISOString(),
    source: {
      module: source.kind,
      kind: source.kind,
      sourceVersion: source.version,
      originalRecordId: sourceRecordId,
      originalSaveId: account['save-id'] || account.saveId || account.slotId || null,
      sourceHash: sourceRecordHash
    },
    treinador: account,
    loja: account.loja || account['loja'] || {
      moedas: account.moedas || 0,
      inventario: account.inventario || {}
    }
  };
}

async function exportHistorical({execute, keyspace, chat, player, versions = 'both', now = () => new Date().toISOString()}) {
  if (!validId(chat) || !validId(player)) throw new Error('invalid_identity');
  if (!/^[a-z][a-z0-9_]*$/.test(keyspace)) throw new Error('invalid_keyspace');
  const selected = requestedVersions(versions);
  const table = `${keyspace}.legacy_estado_particionado`;
  const snapshots = [];
  const missingVersions = [];
  for (const version of selected) {
    const source = VERSION_SOURCES[version];
    let account;
    try {
      const query = version === 'v1'
        ? `SELECT valor FROM ${table} WHERE modulo = ? AND particao = ?`
        : `SELECT valor FROM ${keyspace}.legacy_estado WHERE chave = ?`;
      const params = version === 'v1' ? ['treinador', chat] : ['treinador'];
      account = await readOne(
        execute,
        query,
        params,
        version,
        chat,
        player
      );
      // SERIAL não fornece snapshot multi-tabela. Uma segunda leitura da mesma
      // origem detecta alteração concorrente durante a exportação.
      const confirmed = await readOne(execute, query, params, version, chat, player);
      if (hash(account) !== hash(confirmed)) throw new Error(`historical_save_changed_during_export_${version}`);
    } catch (error) {
      if (selected.length > 1 && /^historical_save_not_found_/.test(error.message)) {
        missingVersions.push(version);
        continue;
      }
      throw error;
    }
    const snapshot = snapshotFor(version, chat, player, account);
    snapshot['created-at'] = now();
    snapshots.push(snapshot);
  }
  return {
    meta: {
      'authorized-offline-copy': true,
      'read-only-cassandra-export': true,
      exporter: 'a6.3.1',
      exportedAt: now(),
      keyspace,
      requestedVersions: selected,
      missingVersions
    },
    historical_snapshots: snapshots,
    group: {'chat-id': chat},
    source: 'cassandra-select-only'
  };
}

async function runCli() {
  const required = ['CASSANDRA_CONTACT_POINTS', 'CASSANDRA_KEYSPACE', 'RECOVERY_CHAT_ID', 'RECOVERY_PLAYER_ID'];
  for (const key of required) if (!process.env[key]) throw new Error(`missing_${key}`);
  const cassandra = require('cassandra-driver');
  const auth = process.env.CASSANDRA_USERNAME && process.env.CASSANDRA_PASSWORD
    ? {username: process.env.CASSANDRA_USERNAME, password: process.env.CASSANDRA_PASSWORD} : undefined;
  const client = new cassandra.Client({
    contactPoints: process.env.CASSANDRA_CONTACT_POINTS.split(',').map(v => v.trim()),
    localDataCenter: process.env.CASSANDRA_LOCAL_DC || 'datacenter1',
    keyspace: process.env.CASSANDRA_KEYSPACE,
    ...(auth ? {authProvider: new cassandra.auth.PlainTextAuthProvider(auth.username, auth.password)} : {})
  });
  const output = process.env.RECOVERY_OUTPUT;
  if (!output) throw new Error('missing_RECOVERY_OUTPUT');
  try {
    await client.connect();
    const result = await exportHistorical({
      execute: (query, params, options) => client.execute(query, params, options),
      keyspace: process.env.CASSANDRA_KEYSPACE,
      chat: process.env.RECOVERY_CHAT_ID,
      player: process.env.RECOVERY_PLAYER_ID,
      versions: process.env.RECOVERY_VERSIONS || 'both'
    });
    fs.mkdirSync(path.dirname(path.resolve(output)), {recursive: true});
    fs.writeFileSync(output, toEdn(result, true) + '\n', {mode: 0o600});
    process.stdout.write(JSON.stringify({ok: true, output, versions: result.meta.requestedVersions,
      snapshots: result.historical_snapshots.map(s => ({version: s['legacy-version'], snapshotId: s['snapshot-id']}))}) + '\n');
  } finally {
    await client.shutdown();
  }
}

module.exports = {exportHistorical, toEdn, VERSION_SOURCES};
if (require.main === module) runCli().catch(error => { console.error(`historical_export_failed:${error.message}`); process.exitCode = 1; });
