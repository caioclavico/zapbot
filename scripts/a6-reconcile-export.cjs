'use strict';

/**
 * Export SELECT-only para reconciliação A6.4.
 * Nunca executa INSERT/UPDATE/DELETE/DDL. Destino local privado (mode 0600).
 *
 * Uso:
 *   CASSANDRA_CONTACT_POINTS=127.0.0.1:19045 \
 *   CASSANDRA_KEYSPACE=zapbot \
 *   CASSANDRA_LOCAL_DC=datacenter1 \
 *   RECONCILE_OUT=~/odisseu-recovery/a64-reconcile \
 *   RECONCILE_PLAYERS_JSON=~/odisseu-recovery/a64-preflight/list-players-….json \
 *   node scripts/a6-reconcile-export.cjs
 */

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cassandra = require('cassandra-driver');
const {toEdn} = require('../pokemon-service/scripts/export-historical-saves.cjs');

const READ_CONSISTENCY = cassandra.types.consistencies.localOne;
const object = (v) => Boolean(v) && typeof v === 'object' && !Array.isArray(v);

function rowsOf(result) {
  return Array.isArray(result?.rows)
    ? result.rows
    : (typeof result?.first === 'function' ? (result.first() ? [result.first()] : []) : []);
}

function parseJson(raw, label) {
  try {
    return JSON.parse(raw);
  } catch {
    throw new Error(`invalid_json_${label}`);
  }
}

function idKind(id) {
  const s = String(id || '');
  if (s.includes('@g.us')) return 'g.us';
  if (s.includes('@lid')) return 'lid';
  if (s.includes('@c.us')) return 'c.us';
  return 'other';
}

function shortHash(id) {
  return crypto.createHash('sha256').update(String(id)).digest('hex').slice(0, 12);
}

function pokemonStats(account) {
  const locs = ['equipe', 'pc', 'enfermaria'];
  const all = [];
  for (const loc of locs) {
    const arr = Array.isArray(account?.[loc]) ? account[loc] : [];
    for (const entry of arr) {
      const poke = entry && typeof entry === 'object' && entry.pokemon && !entry['id-pokemon'] && !entry.idPokemon
        ? entry.pokemon
        : entry;
      if (!object(poke)) continue;
      all.push({
        loc,
        nivel: Number(poke.nivel || poke.level || 1),
        hp: Number(poke['hp-atual'] || poke.hp || 0),
        nome: String(poke.nome || poke.especie || poke.species || ''),
        id: poke['id-pokemon'] || poke.idPokemon || null
      });
    }
  }
  const levels = all.map((p) => p.nivel);
  const species = all.map((p) => p.nome.trim()).filter(Boolean).sort();
  const ids = all.map((p) => String(p.id || '')).filter(Boolean).sort();
  const digest = (obj) => crypto.createHash('sha256').update(JSON.stringify(obj)).digest('hex').slice(0, 16);
  return {
    pokemonCount: all.length,
    locationCounts: {
      equipe: all.filter((p) => p.loc === 'equipe').length,
      pc: all.filter((p) => p.loc === 'pc').length,
      enfermaria: all.filter((p) => p.loc === 'enfermaria').length,
      total: all.length
    },
    levelSum: levels.reduce((a, b) => a + b, 0),
    levelMax: levels.length ? Math.max(...levels) : 0,
    levelMin: levels.length ? Math.min(...levels) : 0,
    hpSum: all.reduce((a, p) => a + p.hp, 0),
    speciesCount: new Set(species).size,
    speciesDigest: digest({species}),
    idDigest: digest({ids})
  };
}

function inventoryStats(account) {
  const loja = object(account?.loja) ? account.loja : {};
  const inv = object(loja.inventario) ? loja.inventario
    : (object(account?.inventario) ? account.inventario : {});
  const coins = Number(loja.moedas ?? account?.moedas ?? 0);
  const skus = Object.entries(inv)
    .map(([k, v]) => [String(k), Number(v) || 0])
    .filter(([, n]) => n > 0)
    .sort((a, b) => a[0].localeCompare(b[0]));
  const digest = crypto.createHash('sha256').update(JSON.stringify({skus})).digest('hex').slice(0, 16);
  return {
    coins,
    inventorySkuCount: skus.length,
    inventoryQtySum: skus.reduce((a, [, n]) => a + n, 0),
    inventoryDigest: digest
  };
}

function gymStats(account) {
  const g = object(account?.ginasios) ? account.ginasios : {};
  const keys = Object.keys(g).map(String).sort();
  return {
    gymCount: keys.length,
    gymDigest: crypto.createHash('sha256').update(JSON.stringify({keys})).digest('hex').slice(0, 16)
  };
}

function fingerprint(account) {
  if (!object(account)) return null;
  const body = {
    ...pokemonStats(account),
    ...inventoryStats(account),
    ...gymStats(account),
    fieldKeys: Object.keys(account).map(String).sort()
  };
  return {
    ...body,
    fingerprint: crypto.createHash('sha256').update(JSON.stringify(body)).digest('hex').slice(0, 32)
  };
}

async function selectValor(client, query, params) {
  const result = await client.execute(query, params, {prepare: true, consistency: READ_CONSISTENCY});
  const rows = rowsOf(result);
  if (rows.length > 1) throw new Error(`ambiguous_rows:${query}`);
  return rows[0]?.valor || null;
}

async function loadV1Partition(client, keyspace, chat) {
  const raw = await selectValor(
    client,
    `SELECT valor FROM ${keyspace}.legacy_estado_particionado WHERE modulo = ? AND particao = ?`,
    ['treinador', chat]
  );
  return raw ? parseJson(raw, 'v1') : {};
}

async function loadV2Treinador(client, keyspace) {
  const raw = await selectValor(
    client,
    `SELECT valor FROM ${keyspace}.legacy_estado WHERE chave = ?`,
    ['treinador']
  );
  return raw ? parseJson(raw, 'v2') : {};
}

async function loadOperationalTreinador(client, keyspace) {
  // estado_particionado operacional: pode haver 1+ partições
  const result = await client.execute(
    `SELECT particao, valor FROM ${keyspace}.estado_particionado WHERE modulo = ? ALLOW FILTERING`,
    ['treinador'],
    {prepare: true, consistency: READ_CONSISTENCY}
  );
  const rows = rowsOf(result);
  const byPartition = {};
  for (const row of rows) {
    const part = row.particao;
    const value = row.valor ? parseJson(row.valor, `op:${part}`) : {};
    byPartition[part] = value;
  }
  return byPartition;
}

function findOperationalAccount(byPartition, chat, player) {
  // Formatos observados: particao=chat → {player→account}
  // ou particao global → {chat→{player→account}} / {player→account}
  const direct = byPartition[chat];
  if (object(direct) && object(direct[player])) {
    return {account: direct[player], partition: chat, shape: 'partition-is-chat'};
  }
  for (const [part, value] of Object.entries(byPartition)) {
    if (!object(value)) continue;
    if (object(value[chat]) && object(value[chat][player])) {
      return {account: value[chat][player], partition: part, shape: 'nested-chat-player'};
    }
    if (object(value[player]) && !String(player).includes('@g.us')) {
      // só aceitar se a partição for o chat alvo ou se houver um único candidato
      if (part === chat || Object.keys(byPartition).length === 1) {
        return {account: value[player], partition: part, shape: 'flat-player'};
      }
    }
  }
  return null;
}

function loadPlayersList(file) {
  const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (!raw?.ok || !Array.isArray(raw.players)) throw new Error('invalid_players_json');
  return raw.players;
}

async function main() {
  const required = ['CASSANDRA_CONTACT_POINTS', 'CASSANDRA_KEYSPACE', 'RECONCILE_OUT', 'RECONCILE_PLAYERS_JSON'];
  for (const k of required) {
    if (!process.env[k]) throw new Error(`missing_${k}`);
  }
  if (process.env.CASSANDRA_KEYSPACE !== 'zapbot') {
    throw new Error('reconcile_export_requires_zapbot_keyspace');
  }

  const outDir = path.resolve(process.env.RECONCILE_OUT.replace(/^~/, process.env.HOME));
  fs.mkdirSync(outDir, {recursive: true, mode: 0o700});

  const playersList = loadPlayersList(
    path.resolve(process.env.RECONCILE_PLAYERS_JSON.replace(/^~/, process.env.HOME))
  );

  const auth = process.env.CASSANDRA_USERNAME && process.env.CASSANDRA_PASSWORD
    ? {username: process.env.CASSANDRA_USERNAME, password: process.env.CASSANDRA_PASSWORD}
    : undefined;
  const client = new cassandra.Client({
    contactPoints: process.env.CASSANDRA_CONTACT_POINTS.split(',').map((v) => v.trim()),
    localDataCenter: process.env.CASSANDRA_LOCAL_DC || 'datacenter1',
    keyspace: process.env.CASSANDRA_KEYSPACE,
    ...(auth ? {authProvider: new cassandra.auth.PlainTextAuthProvider(auth.username, auth.password)} : {})
  });

  const appearances = [];
  const sourceCounts = {
    'cassandra-v1': 0,
    'cassandra-v2': 0,
    'cassandra-operational': 0
  };
  const privateAccounts = [];

  try {
    await client.connect();
    const keyspace = process.env.CASSANDRA_KEYSPACE;
    const v2 = await loadV2Treinador(client, keyspace);
    const operational = await loadOperationalTreinador(client, keyspace);

    const v1Cache = new Map();
    for (const {chatId, playerIds} of playersList) {
      if (!v1Cache.has(chatId)) {
        v1Cache.set(chatId, await loadV1Partition(client, keyspace, chatId));
      }
      const v1 = v1Cache.get(chatId);
      for (const playerId of playerIds) {
        const base = {
          chatId,
          playerId,
          chatHash: shortHash(chatId),
          playerHash: shortHash(playerId),
          chatKind: idKind(chatId),
          playerKind: idKind(playerId)
        };

        if (object(v1?.[playerId])) {
          const fp = fingerprint(v1[playerId]);
          sourceCounts['cassandra-v1'] += 1;
          appearances.push({
            ...base,
            source: 'cassandra-v1',
            fingerprint: fp
          });
          privateAccounts.push({
            source: 'cassandra-v1',
            chatId,
            playerId,
            account: v1[playerId]
          });
        }

        if (object(v2?.[chatId]?.[playerId])) {
          const fp = fingerprint(v2[chatId][playerId]);
          sourceCounts['cassandra-v2'] += 1;
          appearances.push({
            ...base,
            source: 'cassandra-v2',
            fingerprint: fp
          });
          privateAccounts.push({
            source: 'cassandra-v2',
            chatId,
            playerId,
            account: v2[chatId][playerId]
          });
        }

        const op = findOperationalAccount(operational, chatId, playerId);
        if (op) {
          const fp = fingerprint(op.account);
          sourceCounts['cassandra-operational'] += 1;
          appearances.push({
            ...base,
            source: 'cassandra-operational',
            fingerprint: fp,
            operationalShape: op.shape,
            operationalPartitionHash: shortHash(op.partition)
          });
          privateAccounts.push({
            source: 'cassandra-operational',
            chatId,
            playerId,
            account: op.account
          });
        }
      }
    }

    // Dump autorizado no formato A6.3 para ensaio discover (privado)
    const snapshots = privateAccounts
      .filter((a) => a.source === 'cassandra-v1' || a.source === 'cassandra-v2')
      .map((a) => {
        const version = a.source === 'cassandra-v1' ? 'v1' : 'v2';
        const kind = version === 'v1' ? 'legacy_estado_particionado' : 'legacy_estado';
        const sourceHash = crypto.createHash('sha256').update(JSON.stringify(a.account)).digest('hex');
        return {
          'snapshot-id': `${version}-${sourceHash.slice(0, 16)}`,
          'legacy-version': version,
          'chat-id': a.chatId,
          'player-id': a.playerId,
          'target-slot': `recovery-${version}`,
          'created-at': new Date().toISOString(),
          source: {
            module: kind,
            kind,
            sourceVersion: version === 'v1' ? '1' : '2',
            originalRecordId: `${kind}:${a.chatId}:${a.playerId}`,
            sourceHash
          },
          treinador: a.account,
          loja: a.account.loja || {moedas: a.account.moedas || 0, inventario: a.account.inventario || {}}
        };
      });

    const dump = {
      meta: {
        'authorized-offline-copy': true,
        'read-only-cassandra-export': true,
        exporter: 'a6.4-reconcile',
        exportedAt: new Date().toISOString(),
        keyspace,
        purpose: 'reconcile-matrix-and-discover-rehearsal'
      },
      historical_snapshots: snapshots,
      source: 'cassandra-select-only'
    };

    const appearancesPath = path.join(outDir, 'appearances.json');
    const dumpJsonPath = path.join(outDir, 'authorized-dump.json');
    const dumpEdnPath = path.join(outDir, 'authorized-dump.edn');
    const summaryPath = path.join(outDir, 'export-summary.json');
    const privatePath = path.join(outDir, 'private-accounts.json');
    const privateEdnPath = path.join(outDir, 'private-accounts.edn');

    fs.writeFileSync(appearancesPath, JSON.stringify({
      ok: true,
      at: new Date().toISOString(),
      sourceCounts,
      appearanceCount: appearances.length,
      appearances
    }, null, 2), {mode: 0o600});

    fs.writeFileSync(dumpJsonPath, JSON.stringify(dump), {mode: 0o600});
    fs.writeFileSync(dumpEdnPath, toEdn(dump, true) + '\n', {mode: 0o600});
    fs.writeFileSync(privatePath, JSON.stringify({ok: true, accounts: privateAccounts}), {mode: 0o600});
    fs.writeFileSync(privateEdnPath, toEdn({ok: true, accounts: privateAccounts}, true) + '\n', {mode: 0o600});
    fs.writeFileSync(summaryPath, JSON.stringify({
      ok: true,
      at: new Date().toISOString(),
      sourceCounts,
      appearanceCount: appearances.length,
      snapshotCount: snapshots.length,
      uniquePlayers: new Set(appearances.map((a) => a.playerHash)).size,
      uniqueChats: new Set(appearances.map((a) => a.chatHash)).size,
      operationalPartitions: Object.keys(operational).map(shortHash),
      paths: {
        appearances: appearancesPath,
        dumpJson: dumpJsonPath,
        dumpEdn: dumpEdnPath,
        privateAccounts: privatePath,
        privateAccountsEdn: privateEdnPath
      },
      writeOps: 'none-on-cassandra'
    }, null, 2), {mode: 0o600});

    process.stdout.write(JSON.stringify({
      ok: true,
      outDir,
      sourceCounts,
      appearanceCount: appearances.length,
      snapshotCount: snapshots.length
    }) + '\n');
  } finally {
    await client.shutdown();
  }
}

main().catch((err) => {
  console.error(`reconcile_export_failed:${err.message}`);
  process.exitCode = 1;
});
