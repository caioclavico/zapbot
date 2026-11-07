'use strict';

// Node workflow drain. The JVM bridge owns the independent Datomic write gate.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

function create({ file = null, token = '' } = {}) {
  let active = 0;
  let state = { phase: 'open' };
  let shutdownRequested = false;
  const waiters = new Set();

  function load() {
    if (!file) return;
    try {
      const dir = path.dirname(file);
      const prefix = `${path.basename(file)}.`;
      if (fs.existsSync(dir) && fs.readdirSync(dir).some(name => name.startsWith(prefix) && name.endsWith('.tmp'))) {
        state = { phase: 'draining', reason: 'interrupted-maintenance-state-write' };
        return;
      }
    } catch (_) {
      state = { phase: 'draining', reason: 'unreadable-maintenance-state-directory' };
      return;
    }
    if (!fs.existsSync(file)) return;
    try {
      const parsed = JSON.parse(fs.readFileSync(file, 'utf8'));
      state = ['open', 'draining', 'quiescent'].includes(parsed.phase)
        ? parsed
        : { phase: 'draining', reason: 'invalid-maintenance-state' };
    } catch (_) {
      state = { phase: 'draining', reason: 'unreadable-maintenance-state' };
    }
  }
  function persist(next) {
    if (file) {
      const dir = path.dirname(file);
      fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
      const tmp = `${file}.${process.pid}.${crypto.randomUUID()}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(next), { mode: 0o600, flag: 'wx' });
      const fd = fs.openSync(tmp, 'r');
      try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
      fs.renameSync(tmp, file);
      // Persist the rename itself; successful file fsync alone does not make
      // the directory entry durable across a host crash.
      const dirFd = fs.openSync(dir, 'r');
      try { fs.fsyncSync(dirFd); } finally { fs.closeSync(dirFd); }
    }
    state = next;
    return snapshot();
  }
  function snapshot() { return { ...state, activeOperations: active, durable: !!file }; }

  load();
  return {
    begin() {
      // All Node replicas must share this file. Refreshing before admission
      // makes a marker written by another instance visible immediately.
      load();
      if (shutdownRequested || state.phase !== 'open') return false;
      active += 1;
      return true;
    },
    end() {
      active = Math.max(0, active - 1);
      if (active === 0) for (const resolve of [...waiters]) resolve();
    },
    async enter(timeoutMs = 30000) {
      if (state.phase === 'open') {
        const draining = { phase: 'draining', startedAt: new Date().toISOString() };
        // Stop admission before the durable write; on persistence failure this
        // process remains closed and no successful drain is returned.
        state = draining;
        persist(draining);
      }
      if (active > 0) {
        let timer;
        let resolver;
        const drained = new Promise(resolve => {
          resolver = resolve;
          waiters.add(resolve);
        });
        const timeout = new Promise(resolve => {
          timer = setTimeout(() => resolve(false), Math.max(0, timeoutMs));
        });
        const result = await Promise.race([drained.then(() => true), timeout]);
        clearTimeout(timer);
        waiters.delete(resolver);
        if (!result) return { quiescent: false, ...snapshot() };
      }
      return { quiescent: true, ...persist({ ...state, phase: 'quiescent', quiescentAt: new Date().toISOString() }) };
    },
    resume() {
      if (state.phase !== 'quiescent' || active !== 0) {
        const error = new Error('maintenance_resume_requires_quiescence');
        error.code = 'MAINTENANCE_RESUME_BLOCKED';
        throw error;
      }
      return persist({ phase: 'open', resumedAt: new Date().toISOString() });
    },
    snapshot,
    startShutdown() { shutdownRequested = true; return snapshot(); },
    shuttingDown() { return shutdownRequested; },
    authorized(value) {
      const a = Buffer.from(String(value || ''));
      const b = Buffer.from(token);
      return !!token && a.length === b.length && crypto.timingSafeEqual(a, b);
    },
    // `configured` is the durable local gate. `enabled` means its optional HTTP
    // operator endpoints have a separate shared-secret credential.
    configured: !!file,
    enabled: !!file && !!token
  };
}

const singleton = create({
  file: process.env.ODISSEU_NODE_MAINTENANCE_FILE || null,
  token: process.env.ODISSEU_NODE_MAINTENANCE_TOKEN || ''
});

module.exports = { ...singleton, create };
