'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const maintenance = require('/app/scripts/lib/a6-maintenance.cjs');

(async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'a6-node-drain-'));
  try {
    const marker = path.join(root, 'maintenance.json');
    const gate = maintenance.create({ file: marker, token: 'disposable-only' });
    assert.equal(gate.begin(), true, 'open gate admits operation');
    const drain = gate.enter(2000);
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(gate.snapshot().phase, 'draining');
    assert.equal(gate.begin(), false, 'draining gate rejects new mutation');
    gate.end();
    const result = await drain;
    assert.equal(result.quiescent, true);
    assert.equal(result.activeOperations, 0);
    assert.equal(JSON.parse(fs.readFileSync(marker, 'utf8')).phase, 'quiescent');

    const restarted = maintenance.create({ file: marker, token: 'disposable-only' });
    assert.equal(restarted.begin(), false, 'restart preserves maintenance');
    restarted.resume();
    assert.equal(restarted.begin(), true, 'explicit resume reopens admission');
    restarted.end();

    const timeout = maintenance.create({ file: path.join(root, 'timeout.json') });
    timeout.begin();
    assert.equal((await timeout.enter(0)).quiescent, false, 'timeout never claims drain');
    timeout.end();
    assert.equal((await timeout.enter(100)).quiescent, true, 'drain can be retried safely');

    const malformed = path.join(root, 'malformed.json');
    fs.writeFileSync(malformed, '{');
    assert.equal(maintenance.create({ file: malformed }).begin(), false,
      'malformed marker fails closed');
    console.log('A6_NODE_DRAIN_DISPOSABLE_PASS scenarios=4');
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
})().catch(error => {
  console.error('A6_NODE_DRAIN_DISPOSABLE_FAIL', error.code || error.name);
  process.exitCode = 1;
});
