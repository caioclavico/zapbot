'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { create } = require('../scripts/lib/a6-maintenance.cjs');

function temporary() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'a6-maintenance-'));
  return { dir, file: path.join(dir, 'maintenance.json'), dispose: () => fs.rmSync(dir, { recursive: true, force: true }) };
}

test('drain waits for active operation and rejects new work', async () => {
  const t = temporary();
  try {
    const gate = create({ file: t.file, token: 'operator-test' });
    assert.equal(gate.begin(), true);
    const pending = gate.enter(1000);
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(gate.snapshot().phase, 'draining');
    assert.equal(gate.begin(), false);
    gate.end();
    const result = await pending;
    assert.equal(result.quiescent, true);
    assert.equal(result.activeOperations, 0);
  } finally { t.dispose(); }
});

test('timeout leaves durable draining state and can finish later', async () => {
  const t = temporary();
  try {
    const gate = create({ file: t.file, token: 'operator-test' });
    gate.begin();
    const timedOut = await gate.enter(1);
    assert.equal(timedOut.quiescent, false);
    assert.equal(gate.snapshot().phase, 'draining');
    gate.end();
    assert.equal((await gate.enter(100)).quiescent, true);
  } finally { t.dispose(); }
});

test('restart remains fail-closed; resume requires persisted quiescence', async () => {
  const t = temporary();
  try {
    const first = create({ file: t.file, token: 'operator-test' });
    first.begin();
    await first.enter(1);
    const restarted = create({ file: t.file, token: 'operator-test' });
    assert.equal(restarted.snapshot().phase, 'draining');
    assert.throws(() => restarted.resume(), { code: 'MAINTENANCE_RESUME_BLOCKED' });
    const child = spawnSync(process.execPath, ['-e',
      `const g=require(${JSON.stringify(require.resolve('../scripts/lib/a6-maintenance.cjs'))}).create({file:process.env.MAINT_FILE,token:'operator-test'}); console.log(JSON.stringify({phase:g.snapshot().phase,accepted:g.begin()}));`
    ], { encoding: 'utf8', env: { ...process.env, MAINT_FILE: t.file } });
    assert.equal(child.status, 0);
    assert.deepEqual(JSON.parse(child.stdout.trim()), { phase: 'draining', accepted: false });
    first.end();
    await first.enter(100);
    const quiescentRestart = create({ file: t.file, token: 'operator-test' });
    assert.equal(quiescentRestart.snapshot().phase, 'quiescent');
    assert.equal(quiescentRestart.begin(), false);
    assert.equal(restarted.authorized('wrong'), false);
    assert.equal(restarted.authorized('operator-test'), true);
    quiescentRestart.resume();
    assert.equal(quiescentRestart.begin(), true);
    quiescentRestart.end();
  } finally { t.dispose(); }
});

test('malformed persisted marker fails closed', () => {
  const t = temporary();
  try {
    fs.writeFileSync(t.file, '{bad');
    const gate = create({ file: t.file, token: 'operator-test' });
    assert.equal(gate.snapshot().phase, 'draining');
    assert.equal(gate.begin(), false);
  } finally { t.dispose(); }
});

test('shutdown closes local admission even when the persistent marker is unavailable', () => {
  const gate = create();
  assert.equal(gate.configured, false);
  gate.startShutdown();
  assert.equal(gate.begin(), false);
  assert.equal(gate.shuttingDown(), true);
});

test('interrupted atomic marker write is recovered as draining', () => {
  const t = temporary();
  try {
    fs.writeFileSync(`${t.file}.deadbeef.tmp`, '{"phase":"draining"}', { mode: 0o600 });
    const gate = create({ file: t.file, token: 'operator-test' });
    assert.equal(gate.snapshot().phase, 'draining');
    assert.equal(gate.begin(), false);
  } finally { t.dispose(); }
});

test('a shared marker blocks new work in another Node instance', async () => {
  const t = temporary();
  try {
    const first = create({ file: t.file, token: 'operator-test' });
    const second = create({ file: t.file, token: 'operator-test' });
    await first.enter(100);
    assert.equal(second.begin(), false);
  } finally { t.dispose(); }
});
