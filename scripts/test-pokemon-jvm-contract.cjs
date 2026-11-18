'use strict';

/**
 * Contrato + e2e M4.1: sobe bridge JVM (clj), consulta via pokemon-jvm-client,
 * mede latência e RSS de Node + JVM. Sem Chromium neste script.
 *
 * Uso: node scripts/test-pokemon-jvm-contract.cjs
 */
const { spawn } = require('node:child_process');
const { createClient } = require('./lib/pokemon-jvm-client.cjs');
const fs = require('node:fs');
const path = require('node:path');

const TOKEN = process.env.ODISSEU_BRIDGE_TOKEN || 'm41-contract-token';
const PORT = Number(process.env.ODISSEU_BRIDGE_PORT || 18091);
const CHAT = 'chat-m61-staging';
const PLAYER = 'player-m61';
const ROOT = path.resolve(__dirname, '..');

function resolveClojure() {
  if (process.env.CLOJURE_BIN && fs.existsSync(process.env.CLOJURE_BIN)) {
    return process.env.CLOJURE_BIN;
  }
  const vendored = path.join(ROOT, '.tools/clojure/bin/clojure');
  if (fs.existsSync(vendored)) return vendored;
  return 'clojure';
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function rssMb(pid) {
  try {
    const out = require('node:child_process')
      .execFileSync('ps', ['-o', 'rss=', '-p', String(pid)], { encoding: 'utf8' })
      .trim();
    if (/^\d+$/.test(out)) return Math.round(Number(out) / 1024);
  } catch (_) { /* ignore */ }
  return null;
}

function timed(label, fn) {
  const t0 = process.hrtime.bigint();
  return Promise.resolve()
    .then(fn)
    .then(value => {
      const ms = Number(process.hrtime.bigint() - t0) / 1e6;
      return { label, ms, value };
    });
}

async function waitHealthy(client, attempts = 40) {
  let last;
  for (let i = 0; i < attempts; i++) {
    try {
      const h = await client.health();
      if (h && h.status === 'up') return h;
      last = h;
    } catch (err) {
      last = err;
    }
    await sleep(250);
  }
  throw new Error(`Bridge não ficou healthy: ${last && last.message ? last.message : last}`);
}

async function main() {
  const env = {
    ...process.env,
    ODISSEU_BRIDGE_HOST: '127.0.0.1',
    ODISSEU_BRIDGE_PORT: String(PORT),
    ODISSEU_BRIDGE_TOKEN: TOKEN,
    ODISSEU_BRIDGE_SEED: 'true',
    ODISSEU_BRIDGE_SYSTEM: 'odisseu-m41-contract',
    ODISSEU_BRIDGE_STORAGE: '.datomic-local/m41-contract',
    ODISSEU_BRIDGE_DB: 'bridge-contract'
  };

  const clj = resolveClojure();
  env.CLJ_CONFIG = env.CLJ_CONFIG || path.join(ROOT, '.tools/clj-config');
  env.GITLIBS = env.GITLIBS || path.join(ROOT, '.tools/gitlibs');

  const jvm = spawn(clj, ['-M:bridge-http'], {
    cwd: ROOT,
    env,
    stdio: ['ignore', 'pipe', 'pipe']
  });

  let jvmLog = '';
  jvm.stdout.on('data', d => { jvmLog += d.toString(); process.stdout.write(d); });
  jvm.stderr.on('data', d => { jvmLog += d.toString(); process.stderr.write(d); });

  const client = createClient({
    baseUrl: `http://127.0.0.1:${PORT}`,
    token: TOKEN,
    connectTimeoutMs: 3000,
    timeoutMs: 8000
  });

  const results = { ok: false };
  try {
    await waitHealthy(client);
    const trainer = await timed('trainer', () => client.getTrainer({ chatId: CHAT, playerId: PLAYER }));
    const collection = await timed('collection', () => client.getCollection({ chatId: CHAT, playerId: PLAYER }));
    const active = await timed('active-save', () => client.getActiveSave({ chatId: CHAT, playerId: PLAYER }));

    if (trainer.value.activeSaveId !== 'a') throw new Error('activeSaveId esperado a');
    if (collection.value.count < 1) throw new Error('coleção vazia');
    if (collection.value.collection[0].species !== 'Bulbasaur') throw new Error('species');
    if (active.value.save.coins !== 120) throw new Error('coins');

    // Erro de negócio estruturado
    try {
      await client.getTrainer({ chatId: CHAT, playerId: 'inexistente' });
      throw new Error('deveria falhar player_not_found');
    } catch (err) {
      if (err.code !== 'PLAYER_NOT_FOUND' || err.status !== 404) {
        throw new Error(`erro inesperado: ${err.code}/${err.status}`);
      }
    }

    results.ok = true;
    results.latenciesMs = {
      trainer: Math.round(trainer.ms * 10) / 10,
      collection: Math.round(collection.ms * 10) / 10,
      activeSave: Math.round(active.ms * 10) / 10
    };
    results.rssMb = {
      node: rssMb(process.pid),
      jvm: rssMb(jvm.pid)
    };
    results.sample = {
      trainer: trainer.value,
      collectionCount: collection.value.count,
      coins: active.value.save.coins
    };
    console.log('=== M4.1 Node↔JVM contract ===');
    console.log(JSON.stringify(results, null, 2));
  } catch (err) {
    results.ok = false;
    results.error = err.message;
    results.jvmLogTail = jvmLog.slice(-2000);
    console.error('=== M4.1 contract FAILED ===');
    console.error(JSON.stringify(results, null, 2));
    process.exitCode = 1;
  } finally {
    client.close();
    if (jvm.pid) {
      try { process.kill(jvm.pid, 'SIGTERM'); } catch (_) { /* ignore */ }
      await sleep(500);
      try { process.kill(jvm.pid, 'SIGKILL'); } catch (_) { /* ignore */ }
    }
    const outFile = path.join(require('node:os').tmpdir(), 'm4-1-contract-result.json');
    fs.writeFileSync(outFile, JSON.stringify(results, null, 2));
    console.log('resultado em', outFile);
  }
}

main();
