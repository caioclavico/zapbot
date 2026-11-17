'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { createShutdown } = require('../scripts/lib/a6-shutdown.cjs');

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}

test('shutdown coalesces repeated signals and awaits handlers before cleanup', async () => {
  const handler = deferred();
  const server = deferred();
  const order = [];
  let exits = [];
  let scheduled;
  const shutdown = createShutdown({
    drain: () => handler.promise,
    stopAdmissionSources: async () => order.push('sources'),
    stopResources: async () => order.push('resources'),
    updateHealth: async () => order.push('health'),
    stopPokemon: async () => order.push('platform-agent'),
    closeServer: async () => { order.push('server-start'); await server.promise; order.push('server'); },
    closeRecovery: async () => order.push('whatsapp'),
    exit: code => exits.push(code),
    schedule: fn => { scheduled = fn; return { unref() {} }; },
    cancel: () => { scheduled = null; }
  });
  const first = shutdown.request('SIGTERM');
  assert.equal(shutdown.status(), 'draining');
  assert.equal(shutdown.request('SIGINT'), first);
  assert.deepEqual(order, ['sources'], 'periodic producers stop immediately after gate closure');
  handler.resolve({ quiescent: true, durable: true, activeOperations: 0 });
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(exits, [], 'exit waits until HTTP server closure completes');
  server.resolve();
  assert.deepEqual(await first, { stopped: true });
  assert.deepEqual(order, ['sources', 'resources', 'health', 'platform-agent', 'server-start', 'server', 'whatsapp']);
  assert.deepEqual(exits, [0]);
  assert.equal(shutdown.status(), 'closed');
});

test('battle, capture, coin, raid and Datomic handlers drain through the same admission lease', async () => {
  for (const kind of ['battle', 'capture', 'coin-payment', 'automatic-raid', 'datomic-transaction']) {
    const t = require('node:fs').mkdtempSync(require('node:path').join(require('node:os').tmpdir(), 'a6-shutdown-op-'));
    try {
      const { create } = require('../scripts/lib/a6-maintenance.cjs');
      const gate = create({ file: require('node:path').join(t, 'maintenance.json') });
      assert.equal(gate.begin(), true, `${kind}: operation admitted before shutdown`);
      let cleaned = false;
      const shutdown = createShutdown({
        beforeDrain: () => gate.startShutdown(),
        drain: () => gate.enter(1000),
        closeRecovery: async () => { cleaned = true; },
        exit: () => {},
        schedule: () => ({ unref() {} }), cancel: () => {}
      });
      const pending = shutdown.request('SIGTERM');
      assert.equal(gate.begin(), false, `${kind}: no operation admitted after shutdown signal`);
      assert.equal(cleaned, false, `${kind}: browser/resources remain while accepted handler is active`);
      gate.end(); // Stand-in for the handler's final persistence/ACK boundary.
      assert.equal((await pending).stopped, true, `${kind}: cleanup follows operation completion`);
      assert.equal(cleaned, true);
    } finally {
      require('node:fs').rmSync(t, { recursive: true, force: true });
    }
  }
});

test('physical Node SIGTERM waits for accepted work, persists state and restarts fail-closed', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a6-node-sigterm-'));
  const marker = path.join(dir, 'maintenance.json');
  const persistence = path.join(dir, 'simulated-commit.log');
  const source = `
    const fs=require('node:fs');
    const {create}=require(${JSON.stringify(require.resolve('../scripts/lib/a6-maintenance.cjs'))});
    const {createShutdown}=require(${JSON.stringify(require.resolve('../scripts/lib/a6-shutdown.cjs'))});
    const gate=create({file:process.env.MARKER});
    gate.begin();
    setTimeout(()=>{fs.writeFileSync(process.env.PERSISTENCE,'committed\\n');gate.end();},150);
    const shutdown=createShutdown({beforeDrain:()=>gate.startShutdown(),drain:()=>gate.enter(2000),
      stopResources:async()=>console.log('resources_closed'),closeServer:async()=>console.log('server_closed'),
      closeRecovery:async()=>console.log('whatsapp_closed'),exit:code=>{console.log('exit_'+code);process.exit(code);},
      schedule:fn=>{const t=setTimeout(fn,3000);t.unref();return t;}});
    process.on('SIGTERM',()=>shutdown.request('SIGTERM'));
    process.on('SIGINT',()=>shutdown.request('SIGINT'));
    console.log('READY');
  `;
  const child = spawn(process.execPath, ['-e', source], {
    env: { ...process.env, MARKER: marker, PERSISTENCE: persistence },
    stdio: ['ignore', 'pipe', 'pipe']
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', chunk => { stdout += chunk.toString(); });
  child.stderr.on('data', chunk => { stderr += chunk.toString(); });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('child did not become ready')), 3000);
      child.stdout.on('data', () => {
        if (stdout.includes('READY')) { clearTimeout(timer); resolve(); }
      });
      child.once('error', reject);
    });
    child.kill('SIGTERM');
    setTimeout(() => { if (child.exitCode === null) child.kill('SIGTERM'); }, 10);
    const exitCode = await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('child failed to finish graceful shutdown')), 4000);
      child.once('exit', code => { clearTimeout(timer); resolve(code); });
    });
    assert.equal(exitCode, 0, stderr);
    assert.equal(fs.readFileSync(persistence, 'utf8'), 'committed\n');
    assert.match(fs.readFileSync(marker, 'utf8'), /"phase":"quiescent"/);
    assert.ok(stdout.indexOf('server_closed') < stdout.indexOf('whatsapp_closed'));
    const restarted = require('../scripts/lib/a6-maintenance.cjs').create({ file: marker });
    assert.equal(restarted.snapshot().phase, 'quiescent');
    assert.equal(restarted.begin(), false);
  } finally {
    if (child.exitCode === null) child.kill('SIGKILL');
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

test('drain timeout keeps resources alive and can be retried after operations finish', async () => {
  const cleanup = [];
  const outcomes = [
    { quiescent: false, durable: true, activeOperations: 1 },
    { quiescent: true, durable: true, activeOperations: 0 }
  ];
  const shutdown = createShutdown({
    drain: async () => outcomes.shift(),
    stopResources: async () => cleanup.push('resources'),
    closeServer: async () => cleanup.push('server'),
    closeRecovery: async () => cleanup.push('whatsapp'),
    exit: code => cleanup.push(`exit:${code}`),
    schedule: () => ({ unref() {} }), cancel: () => {}
  });
  assert.equal((await shutdown.request()).stopped, false);
  assert.deepEqual(cleanup, []);
  assert.equal(shutdown.status(), 'blocked');
  assert.equal((await shutdown.request('SIGTERM')).stopped, true);
  assert.deepEqual(cleanup, ['resources', 'server', 'whatsapp', 'exit:0']);
});

test('undurable or rejected drain never reports success or closes resources', async () => {
  for (const drain of [
    async () => ({ quiescent: true, durable: false }),
    async () => { throw Object.assign(new Error('persist failed'), { code: 'PERSIST' }); }
  ]) {
    const effects = [];
    const shutdown = createShutdown({
      drain,
      closeRecovery: async () => effects.push('closed'),
      exit: code => effects.push(`exit:${code}`),
      schedule: () => ({ unref() {} }), cancel: () => {}
    });
    const result = await shutdown.request();
    assert.equal(result.stopped, false);
    assert.deepEqual(effects, []);
  }
});

test('post-drain cleanup failures are logged and exit nonzero after all cleanup attempts', async () => {
  const effects = [];
  const logs = [];
  const shutdown = createShutdown({
    drain: async () => ({ quiescent: true, durable: true }),
    stopResources: async () => { effects.push('resources'); throw new Error('resource'); },
    closeServer: async () => effects.push('server'),
    closeRecovery: async () => effects.push('whatsapp'),
    exit: code => effects.push(`exit:${code}`),
    log: (event, data) => logs.push({ event, data }),
    schedule: () => ({ unref() {} }), cancel: () => {}
  });
  const result = await shutdown.request();
  assert.equal(result.reason, 'cleanup_incomplete');
  assert.deepEqual(effects, ['resources', 'server', 'whatsapp', 'exit:1']);
  assert.equal(logs.some(x => x.event === 'shutdown_cleanup_incomplete'), true);
});
