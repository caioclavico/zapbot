'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { startResourceMonitor } = require('../scripts/lib/odisseu-resource-monitor.cjs');

function stat(pid, { name = 'chrome', ppid = 1, state = 'S', start = 100, ticks = 10 } = {}) {
  const fields = Array(50).fill('0');
  fields[0] = state; fields[1] = ppid; fields[11] = ticks; fields[12] = 0; fields[19] = start;
  return `${pid} (${name}) ${fields.join(' ')}\n`;
}

function status(pid, kib = 1024) {
  // Deliberately include unrelated status metadata. It must not escape output.
  return `Name:\tchrome\nPid:\t${pid}\nUid:\t123 123 123 123\nVmRSS:\t${kib} kB\nGroups:\t999\n`;
}

function auxv(hz = 100, word = 8, endian = 'LE') {
  const bytes = Buffer.alloc(word * 6);
  const write = (value, offset) => word === 8
    ? bytes[endian === 'BE' ? 'writeBigUInt64BE' : 'writeBigUInt64LE'](BigInt(value), offset)
    : bytes[endian === 'BE' ? 'writeUInt32BE' : 'writeUInt32LE'](value, offset);
  write(6, 0); write(4096, word); // AT_PAGESZ must not be confused with HZ.
  write(17, word * 2); write(hz, word * 3);
  return bytes;
}

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}

function fixture(options = {}) {
  const files = new Map([['/proc/self/auxv', auxv()], ...(options.files || [])]);
  const entries = options.entries || [];
  const calls = [];
  const logged = [];
  const waiters = [];
  const state = { now: 0, activeReads: 0, maxReads: 0, entriesRead: 0, directoriesClosed: 0,
    cpu: { user: 1_000_000, system: 1_000_000 }, browserPid: options.browserPid ?? null,
    memory: { rss: 16_384, heapUsed: 8192, heapTotal: 12_288, external: 4096, arrayBuffers: 2048 } };
  const io = {
    async readFile(path, encoding) {
      assert.match(path, /^\/proc\/(?:self\/auxv|\d+\/(?:stat|status))$/);
      calls.push(path);
      state.maxReads = Math.max(state.maxReads, ++state.activeReads);
      try {
        await Promise.resolve();
        if (options.onRead) await options.onRead(path);
        const value = files.get(path);
        if (value instanceof Error) throw value;
        if (value === undefined) throw Object.assign(new Error('private detail never logged'), { code: 'ENOENT' });
        const resolved = typeof value === 'function' ? await value() : value;
        return encoding ? String(resolved) : resolved;
      } finally { state.activeReads--; }
    },
    async opendir(path) {
      assert.equal(path, '/proc');
      if (options.directoryError) throw options.directoryError;
      return { async *[Symbol.asyncIterator]() {
        try { for (const name of entries) { state.entriesRead++; yield { name: String(name) }; } }
        finally { state.directoriesClosed++; }
      } };
    }
  };
  const pending = new Set();
  const timerState = { unrefs: 0, clears: 0 };
  const timers = {
    setTimeout(fn, ms) {
      const timer = { fn, ms, unref() { timerState.unrefs++; } };
      pending.add(timer);
      return timer;
    },
    clearTimeout(timer) { timerState.clears++; pending.delete(timer); }
  };
  const histogram = {
    count: 8, mean: 123e6, max: 180e6, enables: 0, disables: 0, resets: 0,
    percentile(percentile) { return percentile === 95 ? 140e6 : 160e6; },
    enable() { this.enables++; }, disable() { this.disables++; }, reset() { this.resets++; }
  };
  const proc = {
    platform: options.platform || 'linux', arch: options.arch || 'x64', pid: 7,
    memoryUsage() { return state.memory; }, cpuUsage() { return { ...state.cpu }; }
  };
  const monitor = startResourceMonitor({
    fs: io, process: proc, performance: { now: () => state.now }, timers,
    createHistogram(config) { assert.deepEqual(config, { resolution: 100 }); return histogram; },
    endian: options.endian || 'LE', now: () => '2026-10-05T00:00:00.000Z',
    browserProcess: options.browserProcess || (() => state.browserPid ? { pid: state.browserPid } : null),
    intervalMs: options.intervalMs, maxPids: options.maxPids, concurrency: options.concurrency,
    log(value) {
      logged.push(value);
      for (const waiter of waiters) if (logged.length >= waiter.count) waiter.resolve(value);
      if (options.logError) throw new Error('private logger failure');
    }
  });
  return {
    monitor, files, calls, logged, state, pending, timerState, histogram,
    sample(count = logged.length + 1) {
      if (logged.length >= count) return Promise.resolve(logged[count - 1]);
      return new Promise(resolve => waiters.push({ count, resolve }));
    },
    async step(ms = 60_000) {
      // Logging happens immediately before the next timer is scheduled.
      await new Promise(setImmediate);
      assert.equal(pending.size, 1);
      const timer = [...pending][0];
      pending.delete(timer);
      state.now += ms;
      await timer.fn();
      return monitor.snapshot();
    }
  };
}

test('Node metrics, CPU delta, loop units and constant-memory command windows are correct', { timeout: 5000 }, async t => {
  const f = fixture({ platform: 'darwin', intervalMs: 1 });
  t.after(() => f.monitor.stop());
  assert.equal(f.monitor.snapshot(), null);
  const initial = await f.sample();
  assert.equal(initial.interval_ms, 15_000);
  assert.equal(initial.node.cpu_percent, null);
  assert.equal(initial.window_ms, null);
  assert.deepEqual(initial.node, { pid: 7, rss_bytes: 16_384, heap_used_bytes: 8192,
    heap_total_bytes: 12_288, external_bytes: 4096, array_buffers_bytes: 2048, cpu_percent: null });
  assert.deepEqual(initial.event_loop, { available: true, resolution_ms: 100, samples: 8,
    mean_ms: 123, p95_ms: 140, p99_ms: 160, max_ms: 180, lag_p95_ms: 40 });
  assert.deepEqual(initial.command_latency, { count: 0, mean_ms: null, max_ms: null });
  assert.equal(initial.chromium.reason, 'unsupported_platform');
  assert.deepEqual(f.calls, []);
  for (const ms of [20, 30, 70, -10, NaN, Infinity, '123', null]) f.monitor.recordCommandLatency(ms);
  f.state.cpu.user += 20_000_000;
  f.state.cpu.system += 10_000_000;
  const second = await f.step();
  assert.equal(second.node.cpu_percent, 50);
  assert.equal(second.window_ms, 60_000);
  assert.deepEqual(second.command_latency, { count: 3, mean_ms: 40, max_ms: 70 });
  const third = await f.step();
  assert.equal(third.node.cpu_percent, 0);
  assert.deepEqual(third.command_latency, { count: 0, mean_ms: null, max_ms: null });
  assert.ok(Object.isFrozen(third));
  assert.ok(Object.isFrozen(third.node));
  assert.equal(f.histogram.resets, 3);
});

test('Chromium descendants, RSS and CPU use stat identity and AT_CLKTCK rather than a guessed HZ', { timeout: 5000 }, async t => {
  const f = fixture({ browserPid: 20, entries: ['self', 1, 7, 20, 21, 22, 30, 31, 32, 99], files: [
    ['/proc/self/auxv', auxv(250)],
    ['/proc/1/stat', stat(1, { name: 'init' })],
    ['/proc/7/stat', stat(7, { name: 'node', ppid: 1 })],
    ['/proc/20/stat', stat(20, { ppid: 7, ticks: 100 })],
    ['/proc/21/stat', stat(21, { name: 'chrome (worker)', ppid: 20, ticks: 200 })],
    ['/proc/22/stat', stat(22, { name: 'utility', ppid: 21, ticks: 300 })],
    ['/proc/30/stat', stat(30, { ppid: 1 })],
    ['/proc/31/stat', stat(31, { name: 'chrome_crashpad', ppid: 1, state: 'Z' })],
    ['/proc/32/stat', stat(32, { name: 'chromium', ppid: 20, state: 'Z' })],
    ['/proc/20/status', status(20, 1024)], ['/proc/21/status', status(21, 2048)],
    ['/proc/22/status', status(22, 512)], ['/proc/32/status', 'Pid:\t32\n']
  ] });
  t.after(() => f.monitor.stop());
  const first = await f.sample();
  assert.equal(first.chromium.available, true);
  assert.equal(first.chromium.scope, 'current_pid_namespace');
  assert.equal(first.chromium.clock_ticks_per_second, 250);
  assert.equal(first.chromium.cpu_percent, null);
  assert.equal(first.chromium.process_count, 4);
  assert.equal(first.chromium.rss_bytes, 3584 * 1024);
  assert.equal(first.chromium.rss_incomplete, true); // The vanished PID makes scan coverage uncertain.
  assert.deepEqual(first.chromium.processes.map(p => p.pid), [20, 21, 22, 32]);
  assert.equal(first.chromium.processes[1].name, 'chrome (worker)');
  assert.equal(first.chromium.outside_tree_candidates, 2);
  assert.equal(first.chromium.orphan_candidates, 2);
  assert.equal(first.chromium.zombie_candidates, 2);
  assert.equal(first.chromium.scan.vanished_pids, 1);
  for (const [pid, ppid, ticks] of [[20, 7, 350], [21, 20, 700], [22, 21, 300]]) {
    f.files.set(`/proc/${pid}/stat`, stat(pid, { ppid, ticks }));
  }
  const second = await f.step(10_000);
  assert.deepEqual(second.chromium.processes.map(p => p.cpu_percent), [10, 20, 0, 0]);
  assert.equal(second.chromium.cpu_percent, 30);
  assert.equal(second.chromium.cpu_status, 'partial');
  assert.equal(second.chromium.cpu_incomplete, true);
  assert.equal(f.calls.filter(path => path === '/proc/self/auxv').length, 1);
  assert.ok(f.state.maxReads <= 4);
  const json = JSON.stringify(second);
  assert.ok(!json.includes('Uid') && !json.includes('Groups') && !json.includes('private detail'));
  assert.ok(!json.includes('leak'));
});

test('PID reuse resets CPU deltas and a stat/status identity race is not mixed into a sample', { timeout: 5000 }, async t => {
  const f = fixture({ browserPid: 20, entries: [20, 21], files: [
    ['/proc/20/stat', stat(20, { ticks: 400 })], ['/proc/20/status', status(20)],
    ['/proc/21/stat', stat(21, { ppid: 20, ticks: 900 })], ['/proc/21/status', status(21)]
  ] });
  t.after(() => f.monitor.stop());
  await f.sample();
  f.files.set('/proc/21/stat', stat(21, { ppid: 20, ticks: 1000, start: 200 }));
  const second = await f.step();
  assert.equal(second.chromium.processes.find(p => p.pid === 21).cpu_percent, null);
  assert.equal(second.chromium.cpu_percent, null);
  let reads = 0;
  f.files.set('/proc/21/stat', () => stat(21, { ppid: 20, start: ++reads === 1 ? 200 : 300, ticks: 1000 }));
  const third = await f.step();
  assert.deepEqual(third.chromium.processes.map(p => p.pid), [20]);
  assert.equal(third.chromium.rss_bytes, 1024 * 1024);
  assert.equal(third.chromium.rss_incomplete, true);
  assert.equal(third.chromium.cpu_status, 'partial');
});

test('a complete Chromium scan measures core-based CPU, including values over 100 percent', { timeout: 5000 }, async t => {
  const f = fixture({ browserPid: 20, entries: [20, 21], files: [
    ['/proc/20/stat', stat(20, { ticks: 100 })], ['/proc/20/status', status(20)],
    ['/proc/21/stat', stat(21, { ppid: 20, ticks: 100 })], ['/proc/21/status', status(21)]
  ] });
  t.after(() => f.monitor.stop());
  const initial = await f.sample();
  assert.equal(initial.chromium.rss_incomplete, false);
  f.files.set('/proc/20/stat', stat(20, { ticks: 6100 }));
  f.files.set('/proc/21/stat', stat(21, { ppid: 20, ticks: 3100 }));
  f.state.cpu.user += 90_000_000;
  const second = await f.step();
  assert.equal(second.chromium.cpu_percent, 150);
  assert.equal(second.node.cpu_percent, 150);
  assert.equal(second.chromium.cpu_status, 'measured');
  assert.equal(second.chromium.cpu_incomplete, false);
  assert.equal(second.collection_ms, 0);
});

test('a closed browser ChildProcess never anchors its reused PID into the Chromium tree', { timeout: 5000 }, async t => {
  for (const ended of [{ exitCode: 0 }, { signalCode: 'SIGTERM' }]) {
    const f = fixture({ entries: [20], browserProcess: () => ({ pid: 20, ...ended }), files: [
      ['/proc/20/stat', stat(20)], ['/proc/20/status', status(20)]
    ] });
    t.after(() => f.monitor.stop());
    const sample = await f.sample();
    assert.equal(sample.chromium.root_pid, null);
    assert.equal(sample.chromium.available, false);
    assert.equal(sample.chromium.reason, 'browser_not_running');
    assert.equal(sample.chromium.process_count, 0);
    assert.equal(sample.chromium.outside_tree_candidates, 1);
    assert.equal(sample.chromium.orphan_candidates, 1);
    assert.ok(!f.calls.some(path => path.endsWith('/status')));
  }
});

test('a worker absent before proc enumeration marks surviving CPU as partial', { timeout: 5000 }, async t => {
  const entries = [20, 21];
  const f = fixture({ browserPid: 20, entries, files: [
    ['/proc/20/stat', stat(20, { ticks: 100 })], ['/proc/20/status', status(20)],
    ['/proc/21/stat', stat(21, { ppid: 20, ticks: 100 })], ['/proc/21/status', status(21)]
  ] });
  t.after(() => f.monitor.stop());
  await f.sample();
  entries.pop();
  f.files.delete('/proc/21/stat');
  f.files.delete('/proc/21/status');
  f.files.set('/proc/20/stat', stat(20, { ticks: 3100 }));
  const sample = await f.step();
  assert.equal(sample.chromium.scan.vanished_pids, 0);
  assert.equal(sample.chromium.scan.read_errors, 0);
  assert.equal(sample.chromium.cpu_percent, 50);
  assert.equal(sample.chromium.previous_processes_not_observed, 1);
  assert.equal(sample.chromium.cpu_status, 'partial');
  assert.equal(sample.chromium.cpu_incomplete, true);
  assert.equal(sample.chromium.rss_incomplete, false); // Current RSS coverage is still complete.
});

test('proc scan is bounded, prioritizes the browser PID and enforces read concurrency', { timeout: 5000 }, async t => {
  const files = [];
  for (let pid = 1; pid <= 100; pid++) {
    files.push([`/proc/${pid}/stat`, stat(pid, { name: pid === 99 ? 'chrome' : 'node', ppid: 0 })]);
  }
  files.push(['/proc/99/status', status(99)]);
  const f = fixture({ browserPid: 99, entries: Array.from({ length: 100 }, (_, i) => i + 1),
    files, maxPids: 5, concurrency: 2 });
  t.after(() => f.monitor.stop());
  const sample = await f.sample();
  assert.equal(sample.chromium.available, true);
  assert.equal(sample.chromium.scan.pids_considered, 5);
  assert.equal(sample.chromium.scan.stat_files_read, 6); // Five scanned + one identity confirmation.
  assert.equal(sample.chromium.scan.status_files_read, 1);
  assert.equal(sample.chromium.scan.truncated, true);
  assert.equal(sample.chromium.rss_incomplete, true);
  assert.ok(f.state.entriesRead <= 5);
  assert.equal(f.state.directoriesClosed, 1);
  assert.equal(f.state.maxReads, 2);
});

test('unreadable proc, vanished processes and absent clock ticks degrade without exposing errors', { timeout: 5000 }, async t => {
  const privateError = Object.assign(new Error('secret diagnostic error /session/profile'), { code: 'EACCES' });
  const f = fixture({ browserPid: 20, entries: [20, 21], files: [
    ['/proc/self/auxv', privateError], ['/proc/20/stat', stat(20)], ['/proc/20/status', privateError]
  ], logError: true });
  t.after(() => f.monitor.stop());
  const sample = await f.sample();
  assert.equal(sample.chromium.available, true);
  assert.equal(sample.chromium.rss_bytes, null);
  assert.equal(sample.chromium.rss_incomplete, true);
  assert.equal(sample.chromium.cpu_percent, null);
  assert.equal(sample.chromium.cpu_status, 'clock_ticks_unavailable');
  assert.equal(sample.chromium.scan.read_errors, 1);
  assert.equal(sample.chromium.scan.vanished_pids, 1);
  assert.ok(!JSON.stringify(sample).includes('secret'));
  await f.step(); // A logger failure must not prevent the next sample.
  assert.equal(f.logged.length, 2);
});

test('32-bit big-endian auxv is decoded and malformed auxv never fabricates CPU', { timeout: 5000 }, async t => {
  for (const [bytes, expected] of [[auxv(128, 4, 'BE'), 128], [Buffer.from([1, 2, 3]), null]]) {
    const f = fixture({ arch: 'arm', endian: 'BE', files: [['/proc/self/auxv', bytes]] });
    t.after(() => f.monitor.stop());
    assert.equal((await f.sample()).chromium.clock_ticks_per_second, expected);
  }
});

test('stop clears the unref timer and histogram, retaining only the last complete snapshot', { timeout: 5000 }, async () => {
  const f = fixture({ platform: 'win32' });
  const complete = await f.sample();
  await new Promise(setImmediate);
  assert.equal(f.pending.size, 1);
  assert.equal(f.timerState.unrefs, 1);
  assert.equal([...f.pending][0].ms, 60_000);
  f.monitor.stop();
  f.monitor.stop();
  f.monitor.recordCommandLatency(123);
  assert.equal(f.pending.size, 0);
  assert.equal(f.timerState.clears, 1);
  assert.equal(f.histogram.disables, 1);
  assert.strictEqual(f.monitor.snapshot(), complete);
});

test('an empty delay histogram reports null rather than sentinel values or invented lag', { timeout: 5000 }, async t => {
  const f = fixture({ platform: 'darwin' });
  t.after(() => f.monitor.stop());
  f.histogram.count = 0;
  f.histogram.mean = NaN;
  f.histogram.max = 0;
  const sample = await f.sample();
  assert.deepEqual(sample.event_loop, { available: true, resolution_ms: 100, samples: 0,
    mean_ms: null, p95_ms: null, p99_ms: null, max_ms: null, lag_p95_ms: null });
});

test('slow async proc reads never overlap scans and stopping suppresses partial publication', { timeout: 5000 }, async () => {
  const gate = deferred();
  const reached = deferred();
  const f = fixture({ browserPid: 20, entries: [20], files: [
    ['/proc/20/stat', stat(20)], ['/proc/20/status', status(20)]
  ], onRead(path) { if (path === '/proc/20/stat') { reached.resolve(); return gate.promise; } } });
  await reached.promise;
  assert.equal(f.pending.size, 0); // No interval scheduled while the first scan is in flight.
  assert.equal(f.monitor.snapshot(), null);
  f.monitor.stop();
  gate.resolve();
  await new Promise(setImmediate);
  assert.deepEqual(f.logged, []);
  assert.equal(f.pending.size, 0);
  assert.equal(f.histogram.disables, 1);
  assert.equal(f.calls.filter(path => path.endsWith('/status')).length, 0);
});

test('unavailable histogram and proc directory leave Node sampling active', { timeout: 5000 }, async t => {
  const f = fixture({ directoryError: new Error('private proc failure') });
  t.after(() => f.monitor.stop());
  const sample = await f.sample();
  assert.equal(sample.chromium.reason, 'proc_unavailable');
  assert.equal(sample.chromium.scan.read_errors, 1);
  assert.equal(sample.node.rss_bytes, 16_384);
  const logs = [];
  const stoppedHistogram = startResourceMonitor({ process: { platform: 'darwin', pid: 3,
    memoryUsage: () => ({}), cpuUsage: () => ({ user: 0, system: 0 }) },
    createHistogram() { throw new Error('private histogram failure'); }, log: value => logs.push(value) });
  t.after(() => stoppedHistogram.stop());
  await new Promise(setImmediate);
  assert.equal(logs.length, 1);
  assert.equal(logs[0].event_loop.available, false);
  assert.equal(logs[0].node.rss_bytes, null);
  assert.ok(!JSON.stringify(logs).includes('private'));
});
