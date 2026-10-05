'use strict';

const fs = require('node:fs/promises');
const os = require('node:os');
const { performance, monitorEventLoopDelay } = require('node:perf_hooks');

const DEFAULT_INTERVAL_MS = 60_000;
const MIN_INTERVAL_MS = 15_000;
const LOOP_RESOLUTION_MS = 100;
const CHROMIUM_NAME = /^(?:chrome|chromium)(?:$|[-_. ])/i;

function boundedInteger(value, fallback, min, max) {
  return Number.isFinite(value) ? Math.min(max, Math.max(min, Math.trunc(value))) : fallback;
}

function finite(value) {
  return Number.isFinite(value) && value >= 0 ? value : null;
}

function rounded(value) {
  return finite(value) === null ? null : Math.round(value * 100) / 100;
}

function safePid(value) {
  return Number.isSafeInteger(value) && value > 0 ? value : null;
}

function parseStat(text, expectedPid) {
  // comm can contain spaces and parentheses. Fields after its LAST ')' start
  // at state (3); utime/stime are (14)/(15), starttime is (22).
  const end = text.lastIndexOf(')');
  const start = text.indexOf('(');
  if (start < 1 || end <= start || Number(text.slice(0, start).trim()) !== expectedPid) return null;
  const fields = text.slice(end + 1).trim().split(/\s+/);
  const ppid = Number(fields[1]);
  const utime = Number(fields[11]);
  const stime = Number(fields[12]);
  const starttime = Number(fields[19]);
  if (!/^[A-Za-z]$/.test(fields[0]) || !Number.isSafeInteger(ppid) || ppid < 0
      || ![utime, stime, starttime].every(n => Number.isSafeInteger(n) && n >= 0)
      || !Number.isSafeInteger(utime + stime)) return null;
  return {
    pid: expectedPid,
    ppid,
    name: text.slice(start + 1, end).replace(/[^\x20-\x7e]/g, '?').slice(0, 32),
    state: fields[0],
    start_time_ticks: starttime,
    cpu_ticks: utime + stime
  };
}

function parseRss(text, expectedPid, state) {
  const pid = /^Pid:\s*(\d+)\s*$/m.exec(text);
  if (!pid || Number(pid[1]) !== expectedPid) return null;
  const rss = /^VmRSS:\s*(\d+)\s+kB\s*$/m.exec(text);
  if (!rss) return state === 'Z' ? 0 : null;
  return finite(Number(rss[1]) * 1024);
}

function parseClockTicks(buffer, arch, endian) {
  const word = ['x64', 'arm64', 'ppc64', 's390x', 'riscv64', 'loong64'].includes(arch) ? 8
    : ['ia32', 'arm', 'mips', 'mipsel'].includes(arch) ? 4 : null;
  if (!word || !Buffer.isBuffer(buffer) || buffer.length > 16_384 || buffer.length % (word * 2)) return null;
  const read = word === 8
    ? offset => Number(endian === 'BE' ? buffer.readBigUInt64BE(offset) : buffer.readBigUInt64LE(offset))
    : offset => endian === 'BE' ? buffer.readUInt32BE(offset) : buffer.readUInt32LE(offset);
  for (let offset = 0; offset < buffer.length; offset += word * 2) {
    const type = read(offset);
    if (type === 0) break;
    if (type === 17) { // AT_CLKTCK: userspace clock ticks/second, not kernel HZ.
      const value = read(offset + word);
      return Number.isSafeInteger(value) && value > 0 && value <= 1_000_000 ? value : null;
    }
  }
  return null;
}

async function mapLimited(items, concurrency, callback) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(items.length, concurrency) }, async () => {
    while (next < items.length) await callback(items[next++]);
  }));
}

function freeze(value) {
  for (const child of Object.values(value)) if (child && typeof child === 'object') freeze(child);
  return Object.freeze(value);
}

/**
 * Passive monitor. browserProcess supplies an ALREADY running Puppeteer child
 * process (or null); it must never launch/reconnect a browser. All /proc reads
 * are asynchronous and restricted to this mount's PID namespace.
 *
 * Injections are for deterministic tests: fs (promises), process, performance,
 * timers, createHistogram, endian, now and log. No shell, CDP or process signal.
 * Sources: nodejs.org/docs/latest-v22.x/api/{process,perf_hooks}.html,
 * man7.org/linux/man-pages/man5/proc_pid_stat.5.html and man3/getauxval.3.html.
 */
function startResourceMonitor(options = {}) {
  const io = options.fs || fs;
  const proc = options.process || process;
  const clock = options.performance || performance;
  const timers = options.timers || { setTimeout, clearTimeout };
  const now = options.now || (() => new Date().toISOString());
  const log = typeof options.log === 'function' ? options.log : () => {};
  const browserProcess = typeof options.browserProcess === 'function' ? options.browserProcess : () => null;
  const intervalMs = boundedInteger(options.intervalMs, DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS, 3_600_000);
  const maxPids = boundedInteger(options.maxPids, 256, 1, 512);
  const concurrency = boundedInteger(options.concurrency, 4, 1, 8);
  const endian = options.endian || os.endianness();
  let histogram = null;
  try {
    histogram = (options.createHistogram || monitorEventLoopDelay)({ resolution: LOOP_RESOLUTION_MS });
    histogram.enable();
  } catch {
    try { histogram?.disable(); } catch { /* Failed initialization is optional. */ }
    histogram = null;
  }
  let stopped = false;
  let timer = null;
  let last = null;
  let previousNode = null;
  let previousPids = new Map();
  let ticksPromise = null;
  let commands = { count: 0, total: 0, max: 0 };

  function nodeSample(at) {
    let memory = {};
    let cpu = null;
    try { memory = proc.memoryUsage(); } catch { /* Unavailable metrics stay null. */ }
    try { cpu = proc.cpuUsage(); } catch { /* No platform-specific fallback. */ }
    let cpuPercent = null;
    const window = previousNode ? at - previousNode.at : null;
    if (window > 0 && cpu && previousNode.cpu) {
      const delta = cpu.user + cpu.system - previousNode.cpu.user - previousNode.cpu.system;
      cpuPercent = rounded(delta / (window * 1000) * 100); // 100% is ONE core.
    }
    previousNode = { at, cpu };
    return {
      window_ms: rounded(window),
      metrics: {
        pid: safePid(proc.pid), rss_bytes: finite(memory.rss),
        heap_used_bytes: finite(memory.heapUsed), heap_total_bytes: finite(memory.heapTotal),
        external_bytes: finite(memory.external), array_buffers_bytes: finite(memory.arrayBuffers),
        cpu_percent: cpuPercent
      }
    };
  }

  function loopSample() {
    if (!histogram) return { available: false, resolution_ms: LOOP_RESOLUTION_MS };
    try {
      const count = finite(histogram.count) || 0;
      const ms = value => count ? rounded(value / 1e6) : null;
      const p95 = count ? histogram.percentile(95) / 1e6 : null;
      const sample = {
        available: true, resolution_ms: LOOP_RESOLUTION_MS, samples: count,
        mean_ms: ms(histogram.mean), p95_ms: rounded(p95),
        p99_ms: count ? ms(histogram.percentile(99)) : null, max_ms: ms(histogram.max),
        // The raw timer delay includes its sampling interval. This is an
        // approximation of scheduling lag above that baseline, not CPU time.
        lag_p95_ms: p95 === null ? null : rounded(Math.max(0, p95 - LOOP_RESOLUTION_MS))
      };
      histogram.reset();
      return sample;
    } catch { return { available: false, resolution_ms: LOOP_RESOLUTION_MS }; }
  }

  async function chromiumSample() {
    let rootPid = null;
    try {
      const child = browserProcess();
      // Puppeteer may retain its old ChildProcess after closure. Never follow
      // the numeric PID once that child has exited and its PID can be reused.
      if (child && child.exitCode == null && child.signalCode == null) rootPid = safePid(child.pid);
    } catch { /* Browser may be closing. */ }
    const result = {
      available: false, scope: 'current_pid_namespace', root_pid: rootPid,
      clock_ticks_per_second: null, cpu_percent: null, cpu_status: 'no_previous_sample',
      cpu_incomplete: true, previous_processes_not_observed: 0,
      rss_bytes: null, rss_measured_processes: 0, process_count: 0, processes: [],
      orphan_candidates: 0, zombie_candidates: 0, outside_tree_candidates: 0,
      scan: { pids_considered: 0, stat_files_read: 0, status_files_read: 0,
        truncated: false, read_errors: 0, vanished_pids: 0 }
    };
    if (proc.platform !== 'linux') {
      result.reason = 'unsupported_platform';
      result.cpu_status = 'unsupported_platform';
      previousPids = new Map();
      return result;
    }
    if (!ticksPromise) ticksPromise = io.readFile('/proc/self/auxv')
      .then(bytes => parseClockTicks(bytes, proc.arch, endian)).catch(() => null);
    result.clock_ticks_per_second = await ticksPromise;
    if (stopped) return result;
    const pids = new Set(rootPid ? [rootPid] : []);
    try {
      const dir = await io.opendir('/proc');
      let entries = 0;
      for await (const entry of dir) {
        if (stopped) break;
        if (++entries > maxPids * 4 + 128) { result.scan.truncated = true; break; }
        const pid = /^\d+$/.test(entry.name) ? safePid(Number(entry.name)) : null;
        if (!pid || pids.has(pid)) continue;
        if (pids.size >= maxPids) { result.scan.truncated = true; break; }
        pids.add(pid);
      }
    } catch { result.scan.read_errors++; result.reason = 'proc_unavailable'; }
    result.scan.pids_considered = pids.size;
    const records = new Map();
    async function readStat(pid) {
      if (stopped) return null;
      result.scan.stat_files_read++;
      try {
        const text = await io.readFile(`/proc/${pid}/stat`, 'utf8');
        const record = text.length <= 8192 ? parseStat(text, pid) : null;
        if (!record) result.scan.read_errors++;
        return record;
      } catch (error) {
        if (error.code === 'ENOENT' || error.code === 'ESRCH') result.scan.vanished_pids++;
        else result.scan.read_errors++;
        return null;
      }
    }
    await mapLimited([...pids], concurrency, async pid => {
      const record = await readStat(pid);
      if (record) records.set(pid, record);
    });
    if (stopped) return result;
    const children = new Map();
    for (const record of records.values()) {
      if (!children.has(record.ppid)) children.set(record.ppid, []);
      children.get(record.ppid).push(record.pid);
    }
    const tree = new Set();
    const queue = rootPid && records.has(rootPid) ? [rootPid] : [];
    for (let i = 0; i < queue.length; i++) {
      const pid = queue[i];
      if (tree.has(pid)) continue;
      tree.add(pid);
      for (const child of children.get(pid) || []) if (!tree.has(child)) queue.push(child);
    }
    const samples = [];
    const currentPids = new Map();
    await mapLimited([...tree], concurrency, async pid => {
      if (stopped) return;
      const record = records.get(pid);
      let rss = null;
      result.scan.status_files_read++;
      try {
        const text = await io.readFile(`/proc/${pid}/status`, 'utf8');
        if (text.length <= 65_536) rss = parseRss(text, pid, record.state);
        if (rss === null) result.scan.read_errors++;
      } catch (error) {
        if (error.code === 'ENOENT' || error.code === 'ESRCH') result.scan.vanished_pids++;
        else result.scan.read_errors++;
      }
      // A PID can disappear/reappear between stat and status. Verify identity
      // again before combining RSS/counters and before computing a CPU delta.
      const confirmed = await readStat(pid);
      if (!confirmed || confirmed.start_time_ticks !== record.start_time_ticks) return;
      const at = clock.now();
      const previous = previousPids.get(pid);
      const elapsed = previous ? at - previous.at : null;
      const delta = previous ? confirmed.cpu_ticks - previous.cpu_ticks : null;
      const cpuPercent = result.clock_ticks_per_second && elapsed > 0 && delta >= 0
        && previous.start_time_ticks === confirmed.start_time_ticks
        ? rounded(delta / result.clock_ticks_per_second / (elapsed / 1000) * 100) : null;
      currentPids.set(pid, { ...confirmed, at });
      samples.push({ ...confirmed, cpu_percent: cpuPercent, rss_bytes: rss });
    });
    if (stopped) return result;
    // An exited worker need not produce ENOENT: it may already be absent when
    // /proc is enumerated. Its final CPU cannot be recovered from survivors.
    for (const previous of previousPids.values()) {
      const current = currentPids.get(previous.pid);
      if (!current || current.start_time_ticks !== previous.start_time_ticks) result.previous_processes_not_observed++;
    }
    previousPids = currentPids; // Only this bounded snapshot is retained.
    result.available = rootPid !== null && currentPids.has(rootPid);
    if (!result.available && !result.reason) result.reason = rootPid ? 'browser_process_unavailable' : 'browser_not_running';
    result.processes = samples.sort((a, b) => a.pid - b.pid);
    result.process_count = samples.length;
    const measured = samples.filter(p => p.rss_bytes !== null);
    result.rss_measured_processes = measured.length;
    result.rss_bytes = measured.length ? measured.reduce((sum, p) => sum + p.rss_bytes, 0) : null;
    const incompleteScan = result.scan.truncated || result.scan.read_errors > 0
      || result.scan.vanished_pids > 0 || samples.length !== tree.size || !result.available;
    result.rss_incomplete = measured.length !== samples.length || incompleteScan;
    const cpus = samples.map(p => p.cpu_percent);
    result.cpu_incomplete = incompleteScan || result.previous_processes_not_observed > 0
      || !cpus.length || cpus.some(cpu => cpu === null);
    if (cpus.length && cpus.every(p => p !== null)) {
      result.cpu_percent = rounded(cpus.reduce((sum, cpu) => sum + cpu, 0));
      result.cpu_status = result.cpu_incomplete ? 'partial' : 'measured';
    } else if (!result.clock_ticks_per_second) result.cpu_status = 'clock_ticks_unavailable';
    for (const record of records.values()) {
      if (tree.has(record.pid) && record.state === 'Z') result.zombie_candidates++;
      if (tree.has(record.pid) || !CHROMIUM_NAME.test(record.name)) continue;
      result.outside_tree_candidates++;
      if (record.state === 'Z') result.zombie_candidates++;
      // Reparenting to PID 1/0 only suggests an orphan. Other browsers and
      // legitimate helpers can also match: no leak diagnosis or remediation.
      if (record.ppid <= 1) result.orphan_candidates++;
    }
    return result;
  }

  async function collect() {
    if (stopped) return;
    const started = clock.now();
    const node = nodeSample(started);
    const loop = loopSample();
    const windowCommands = commands;
    commands = { count: 0, total: 0, max: 0 };
    let chromium;
    try { chromium = await chromiumSample(); }
    catch { chromium = { available: false, reason: 'collection_failed', cpu_percent: null }; }
    if (stopped) return; // Never publish a partial sample after stop().
    last = freeze({
      timestamp: now(), interval_ms: intervalMs, window_ms: node.window_ms,
      collection_ms: rounded(clock.now() - started),
      node: node.metrics, event_loop: loop,
      command_latency: { count: windowCommands.count,
        mean_ms: windowCommands.count ? rounded(windowCommands.total / windowCommands.count) : null,
        max_ms: windowCommands.count ? rounded(windowCommands.max) : null },
      chromium
    });
    try { log(last); } catch { /* Observability never changes command outcomes. */ }
  }

  async function run() {
    try { await collect(); } catch { /* Even injected metric failures must not crash the bot. */ }
    if (!stopped) {
      timer = timers.setTimeout(run, intervalMs); // Schedule only AFTER completion.
      if (timer && typeof timer.unref === 'function') timer.unref();
    }
  }
  // Leave the caller time to retain stop() before any async collection starts.
  Promise.resolve().then(run);
  return {
    stop() {
      if (stopped) return;
      stopped = true;
      if (timer !== null) timers.clearTimeout(timer);
      timer = null;
      try { histogram?.disable(); } catch { /* Already unavailable. */ }
      previousPids.clear();
      commands = { count: 0, total: 0, max: 0 };
    },
    snapshot() { return last; },
    recordCommandLatency(ms) {
      if (stopped || finite(ms) === null || commands.count >= Number.MAX_SAFE_INTEGER) return;
      const total = commands.total + ms;
      if (!Number.isFinite(total)) return;
      commands = { count: commands.count + 1, total, max: Math.max(commands.max, ms) };
    }
  };
}

module.exports = { startResourceMonitor, DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS };
