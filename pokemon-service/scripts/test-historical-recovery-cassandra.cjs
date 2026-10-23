'use strict';

// Integração local: escreve somente no Cassandra descartável criado por este
// processo, então chama o exportador operacional, que é SELECT-only.
const crypto = require('node:crypto');
const fs = require('node:fs');
const cassandra = require('cassandra-driver');
const {exportHistorical, toEdn} = require('./export-historical-saves.cjs');

const host = process.env.CASSANDRA_CONTACT_POINTS || '127.0.0.1';
const dc = process.env.CASSANDRA_LOCAL_DC || 'datacenter1';
const chat = process.env.RECOVERY_CHAT_ID || 'a63-chat';
const player = process.env.RECOVERY_PLAYER_ID || 'a63-player@c.us';
const output = process.env.RECOVERY_OUTPUT || '/work/target/a63/recovery.edn';
const keyspace = `a63_recovery_${crypto.randomUUID().replaceAll('-', '')}`;
const account = (id, species, coins) => ({
  ativo: 0, moedas: coins, inventario: {pocao: 2},
  equipe: [{ 'id-pokemon': id, nome: species, especie: species, nivel: 12,
    'xp-desde-nivel': 7, hp: 50, 'hp-atual': 45, shiny: false,
    golpes: [{nome: 'Investida', tipo: 'normal', poder: 40}], tipos: ['normal']}],
  pc: [], enfermaria: [], ginasios: {pedra: '2024-01-01'},
  'vitorias-treinador': 2, 'pe-ginasios': 3
});

async function waitForCassandra(client) {
  let last;
  for (let i = 0; i < 60; i++) {
    try { await client.connect(); return; } catch (error) { last = error; await new Promise(r => setTimeout(r, 1000)); }
  }
  throw last || new Error('cassandra_unavailable');
}

(async () => {
  const client = new cassandra.Client({contactPoints: host.split(',').map(v => v.trim()), localDataCenter: dc});
  try {
    await waitForCassandra(client);
    await client.execute(`CREATE KEYSPACE ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
    await client.execute(`CREATE TABLE ${keyspace}.legacy_estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`);
    await client.execute(`CREATE TABLE ${keyspace}.legacy_estado (chave text PRIMARY KEY, valor text)`);
    const v1 = {[player]: account('a63-v1-pokemon', 'Bulbasaur', 101)};
    const v2 = {[chat]: {[player]: account('a63-v2-pokemon', 'Charmander', 202)}};
    await client.execute(`INSERT INTO ${keyspace}.legacy_estado_particionado (modulo,particao,valor) VALUES (?,?,?)`, ['treinador', chat, JSON.stringify(v1)]);
    await client.execute(`INSERT INTO ${keyspace}.legacy_estado (chave,valor) VALUES (?,?)`, ['treinador', JSON.stringify(v2)]);
    const result = await exportHistorical({
      execute: (query, params, options) => {
        if (!/^SELECT /.test(query)) throw new Error('export_query_not_read_only');
        return client.execute(query, params, options);
      }, keyspace, chat, player, versions: process.env.RECOVERY_VERSIONS || 'both'
    });
    fs.mkdirSync(require('node:path').dirname(output), {recursive: true});
    fs.writeFileSync(output, toEdn(result, true), {mode: 0o600});
    process.stdout.write(JSON.stringify({ok: true, keyspace, output, snapshots: result.historical_snapshots.map(s => ({version: s['legacy-version'], id: s['snapshot-id']}))}) + '\n');
  } finally { await client.shutdown(); }
})().catch(error => { console.error(`historical_fixture_failed:${error.message}`); process.exitCode = 1; });
