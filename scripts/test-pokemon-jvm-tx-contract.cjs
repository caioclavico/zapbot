'use strict';

/**
 * Contrato M4.2 Node → JVM → Datomic → Node (dados fictícios).
 * Cobre ensure, A↔B, coins, idempotência, mismatch, reconcile, JVM down, admin.
 */
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {
  createClient,
  trustedIdentityFromMessage,
  stableOperationId
} = require('./lib/pokemon-jvm-client.cjs');

const TOKEN = process.env.ODISSEU_BRIDGE_TOKEN || 'm42-contract-token';
const PORT = Number(process.env.ODISSEU_BRIDGE_PORT || 18092);
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

async function waitHealthy(client, attempts = 50) {
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
  throw new Error(`Bridge unhealthy: ${last && last.message ? last.message : last}`);
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

async function main() {
  const identity = trustedIdentityFromMessage({
    fromMe: false,
    from: 'chat-m42-node@g.us',
    author: 'player-m42-node@c.us',
    id: { _serialized: 'msg-m42-contract-1' }
  });

  const env = {
    ...process.env,
    ODISSEU_BRIDGE_HOST: '127.0.0.1',
    ODISSEU_BRIDGE_PORT: String(PORT),
    ODISSEU_BRIDGE_TOKEN: TOKEN,
    ODISSEU_BRIDGE_SEED: 'true',
    ODISSEU_BRIDGE_SYSTEM: 'odisseu-m42-contract',
    ODISSEU_BRIDGE_STORAGE: '.datomic-local/m42-contract',
    ODISSEU_BRIDGE_DB: 'bridge-tx-contract',
    CLJ_CONFIG: process.env.CLJ_CONFIG || path.join(ROOT, '.tools/clj-config'),
    GITLIBS: process.env.GITLIBS || path.join(ROOT, '.tools/gitlibs')
  };

  const jvm = spawn(resolveClojure(), ['-M:bridge-http'], {
    cwd: ROOT, env, stdio: ['ignore', 'pipe', 'pipe']
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

  const results = { ok: false, checks: [] };
  try {
    await waitHealthy(client);

    const ensured = await client.ensureTrainer(identity);
    assert(ensured.created === true, 'created');
    results.checks.push('ensure');

    const saves = await client.listSaves(identity);
    assert(saves.activeSaveId === 'a', 'active a');
    assert(saves.saves.length === 2, '2 saves');
    results.checks.push('list-saves');

    const op1 = stableOperationId(identity.requestId, 'coins:25');
    const write1 = await client.creditCoins({
      chatId: identity.chatId,
      playerId: identity.playerId,
      saveId: saves.activeSaveId,
      fenceGeneration: saves.fenceGeneration,
      operationId: op1,
      coinsDelta: 25
    });
    assert(write1.duplicate === false && write1.coins === 25, 'write1');
    const write1b = await client.creditCoins({
      chatId: identity.chatId,
      playerId: identity.playerId,
      saveId: saves.activeSaveId,
      fenceGeneration: saves.fenceGeneration,
      operationId: op1,
      coinsDelta: 25
    });
    assert(write1b.duplicate === true && write1b.coins === 25, 'idempotent');
    results.checks.push('coins-idempotent');

    try {
      await client.creditCoins({
        chatId: identity.chatId,
        playerId: identity.playerId,
        saveId: 'a',
        fenceGeneration: saves.fenceGeneration,
        operationId: op1,
        coinsDelta: 99
      });
      throw new Error('mismatch should throw');
    } catch (err) {
      assert(err.code === 'RECEIPT_PAYLOAD_MISMATCH' && err.status === 409, 'mismatch');
    }
    results.checks.push('payload-mismatch');

    const actB = await client.activateSave({
      ...identity, saveId: 'b', expectedFenceGeneration: saves.fenceGeneration
    });
    assert(actB.activeSaveId === 'b', 'activated b');

    try {
      await client.creditCoins({
        chatId: identity.chatId,
        playerId: identity.playerId,
        saveId: 'a',
        fenceGeneration: saves.fenceGeneration,
        operationId: 'op-stale-from-node',
        coinsDelta: 5
      });
      throw new Error('stale should throw');
    } catch (err) {
      assert(err.code === 'STALE_WRITER_FENCE' && err.status === 409, 'stale');
    }

    const opB = stableOperationId(identity.requestId, 'coins-b:9');
    const onB = await client.creditCoins({
      chatId: identity.chatId,
      playerId: identity.playerId,
      saveId: 'b',
      fenceGeneration: actB.fenceGeneration,
      operationId: opB,
      coinsDelta: 9
    });
    assert(onB.coins === 9, 'coins on b');

    await client.activateSave({
      ...identity, saveId: 'a', expectedFenceGeneration: actB.fenceGeneration
    });
    const after = await client.listSaves(identity);
    const byId = Object.fromEntries(after.saves.map(s => [s.saveId, s]));
    assert(byId.a.coins === 25 && byId.b.coins === 9, 'A/B independent');
    results.checks.push('a-b-a');

    const receipt = await client.getOperation(op1);
    assert(receipt.operationId === op1, 'reconcile get');
    results.checks.push('reconcile');

    // Timeout reconcile path: operation already committed → getOperation works
    // (simula timeout pós-commit sem delay artificial).
    const reconciled = await client.creditCoins({
      chatId: identity.chatId,
      playerId: identity.playerId,
      saveId: 'a',
      fenceGeneration: after.fenceGeneration,
      operationId: op1,
      coinsDelta: 25
    });
    assert(reconciled.duplicate === true, 'post-timeout same op');
    results.checks.push('timeout-reconcile');

    // JVM indisponível → fail closed
    const dead = createClient({
      baseUrl: 'http://127.0.0.1:1',
      token: TOKEN,
      connectTimeoutMs: 200,
      timeoutMs: 400
    });
    try {
      await dead.creditCoins({
        chatId: identity.chatId,
        playerId: identity.playerId,
        saveId: 'a',
        fenceGeneration: 0,
        operationId: 'should-not-apply',
        coinsDelta: 1
      });
      throw new Error('dead should fail');
    } catch (err) {
      assert(['NETWORK', 'CONNECT_TIMEOUT', 'TIMEOUT', 'ECONNREFUSED'].includes(err.code)
        || /indispon/i.test(err.message), `jvm down got ${err.code}`);
    } finally {
      dead.close();
    }
    const still = await client.listSaves(identity);
    assert(still.saves.find(s => s.saveId === 'a').coins === 25, 'no silent write');
    results.checks.push('jvm-down');

    // Admin bloqueado
    try {
      await client.getTrainer({ chatId: '/admin/seed', playerId: 'x' });
    } catch (_) { /* ignore */ }
    const http = require('node:http');
    const adminStatus = await new Promise((resolve, reject) => {
      const req = http.request({
        hostname: '127.0.0.1', port: PORT, path: '/admin/seed', method: 'GET',
        headers: { Authorization: `Bearer ${TOKEN}` }
      }, res => { res.resume(); resolve(res.statusCode); });
      req.on('error', reject);
      req.end();
    });
    assert(adminStatus === 404, 'admin 404');
    results.checks.push('admin-blocked');

    results.ok = true;
    console.log('=== M4.2 Node↔JVM tx contract ===');
    console.log(JSON.stringify(results, null, 2));
  } catch (err) {
    results.ok = false;
    results.error = err.message;
    results.jvmLogTail = jvmLog.slice(-2000);
    console.error('=== M4.2 contract FAILED ===');
    console.error(JSON.stringify(results, null, 2));
    process.exitCode = 1;
  } finally {
    client.close();
    if (jvm.pid) {
      try { process.kill(jvm.pid, 'SIGTERM'); } catch (_) { /* ignore */ }
      await sleep(400);
      try { process.kill(jvm.pid, 'SIGKILL'); } catch (_) { /* ignore */ }
    }
    fs.writeFileSync(
      path.join(os.tmpdir(), 'm4-2-contract-result.json'),
      JSON.stringify(results, null, 2)
    );
  }
}

main();
