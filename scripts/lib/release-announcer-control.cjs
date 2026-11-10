'use strict';

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const VERSION = 1;
const POLL_MS = 50;

function controlError(code) {
  const error = new Error(code);
  error.code = code;
  return error;
}

function safeReason(value) {
  const reason = String(value || 'maintenance');
  if (!/^[a-zA-Z0-9._-]{1,60}$/.test(reason)) throw controlError('reason_invalid');
  return reason;
}

function syncDirectory(directory) {
  const fd = fs.openSync(directory, fs.constants.O_RDONLY);
  try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}

function validatePath(file) {
  if (typeof file !== 'string' || !path.isAbsolute(file)) throw controlError('control_path_must_be_absolute');
  const resolved = path.resolve(file);
  const parent = path.dirname(resolved);
  const parentStat = fs.lstatSync(parent);
  if (!parentStat.isDirectory() || parentStat.isSymbolicLink()) throw controlError('control_parent_not_directory');
  // Resolve aliases such as macOS /var -> /private/var instead of rejecting
  // harmless platform paths. The runtime uses the canonical parent thereafter.
  return path.join(fs.realpathSync(parent), path.basename(resolved));
}

function makeControl(file, { now = () => new Date().toISOString(), sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) } = {}) {
  const controlPath = validatePath(file);
  const leasePath = `${controlPath}.publication-inflight`;

  function read() {
    let stat;
    try { stat = fs.lstatSync(controlPath); }
    catch (error) {
      if (error.code === 'ENOENT') throw controlError('control_missing');
      throw controlError('control_unavailable');
    }
    if (!stat.isFile() || stat.isSymbolicLink() || (stat.mode & 0o022) !== 0 || stat.uid !== process.getuid?.()) {
      throw controlError('control_permissions_invalid');
    }
    let state;
    try { state = JSON.parse(fs.readFileSync(controlPath, 'utf8')); }
    catch (_) { throw controlError('control_unreadable'); }
    if (!state || state.version !== VERSION || !['open', 'suspended'].includes(state.state) ||
        typeof state.updatedAt !== 'string' || typeof state.reason !== 'string' ||
        !/^[a-zA-Z0-9._-]{1,60}$/.test(state.reason)) {
      throw controlError('control_invalid');
    }
    return { state: state.state, updatedAt: state.updatedAt, reason: state.reason };
  }

  function atomicWrite(state, reason) {
    const payload = Buffer.from(`${JSON.stringify({ version: VERSION, state, updatedAt: now(), reason })}\n`);
    const temp = `${controlPath}.${process.pid}.${crypto.randomUUID()}.tmp`;
    let fd;
    try {
      fd = fs.openSync(temp, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY, 0o600);
      fs.writeFileSync(fd, payload);
      fs.fsyncSync(fd);
      fs.closeSync(fd);
      fd = undefined;
      const existing = (() => { try { return fs.lstatSync(controlPath); } catch (e) { if (e.code === 'ENOENT') return null; throw e; } })();
      if (existing && (!existing.isFile() || existing.isSymbolicLink() || (existing.mode & 0o022) !== 0)) {
        throw controlError('control_permissions_invalid');
      }
      fs.renameSync(temp, controlPath);
      fs.chmodSync(controlPath, 0o600);
      syncDirectory(path.dirname(controlPath));
    } catch (error) {
      if (fd !== undefined) { try { fs.closeSync(fd); } catch (_) {} }
      try { fs.unlinkSync(temp); } catch (_) {}
      if (error.code) throw error;
      throw controlError('control_write_failed');
    }
  }

  function initializeOpen(reason = 'explicit-initialization') {
    reason = safeReason(reason);
    let fd;
    try {
      fd = fs.openSync(controlPath, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY, 0o600);
      fs.writeFileSync(fd, `${JSON.stringify({ version: VERSION, state: 'open', updatedAt: now(), reason })}\n`);
      fs.fsyncSync(fd);
      fs.closeSync(fd);
      syncDirectory(path.dirname(controlPath));
      return read();
    } catch (error) {
      if (fd !== undefined) { try { fs.closeSync(fd); } catch (_) {} }
      if (error.code === 'EEXIST') throw controlError('control_already_exists');
      if (error.code) throw error;
      throw controlError('control_write_failed');
    }
  }

  function assertOpen() {
    const current = read();
    if (current.state !== 'open') throw controlError('publication_suspended');
    return current;
  }

  function acquireLease(fingerprint) {
    let fd;
    try {
      fd = fs.openSync(leasePath, fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_WRONLY, 0o600);
    } catch (error) {
      if (error.code === 'EEXIST') throw controlError('publication_inflight');
      throw controlError('publication_lock_unavailable');
    }
    try {
      const safeFingerprint = crypto.createHash('sha256').update(String(fingerprint)).digest('hex');
      fs.writeFileSync(fd, `${JSON.stringify({ version: VERSION, pid: process.pid, startedAt: now(), fingerprint: safeFingerprint })}\n`);
      fs.fsyncSync(fd);
      syncDirectory(path.dirname(leasePath));
    } catch (_) {
      try { fs.closeSync(fd); } catch (_) {}
      try { fs.unlinkSync(leasePath); } catch (_) {}
      throw controlError('publication_lock_unavailable');
    }
    return fd;
  }

  async function withPublication(fn, fingerprint = 'release') {
    // State must be readable/open before and after taking the cross-process lease.
    assertOpen();
    const fd = acquireLease(fingerprint);
    try {
      assertOpen();
      return await fn({ assertOpen });
    } finally {
      try { fs.closeSync(fd); } catch (_) {}
      try { fs.unlinkSync(leasePath); syncDirectory(path.dirname(leasePath)); }
      catch (_) { throw controlError('publication_lock_release_failed'); }
    }
  }

  async function suspendAndWait({ reason = 'maintenance', timeoutMs = 30000 } = {}) {
    if (!Number.isFinite(timeoutMs) || timeoutMs < 0) throw controlError('invalid_timeout');
    atomicWrite('suspended', safeReason(reason));
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      let active = false;
      try { fs.lstatSync(leasePath); active = true; }
      catch (error) { if (error.code !== 'ENOENT') throw controlError('publication_lock_unavailable'); }
      if (!active) return { ...read(), quiescent: true, publicationInFlight: false };
      if (Date.now() >= deadline) return { ...read(), quiescent: false, publicationInFlight: true };
      await sleep(Math.min(POLL_MS, Math.max(1, deadline - Date.now())));
    }
  }

  function resume(reason = 'maintenance-complete') {
    reason = safeReason(reason);
    const current = read();
    if (current.state !== 'suspended') throw controlError('control_not_suspended');
    try { fs.lstatSync(leasePath); throw controlError('publication_inflight'); }
    catch (error) { if (error.code !== 'ENOENT') throw error.code ? error : controlError('publication_lock_unavailable'); }
    atomicWrite('open', String(reason).slice(0, 120));
    return read();
  }

  function status() {
    const current = read();
    let publicationInFlight = false;
    try { fs.lstatSync(leasePath); publicationInFlight = true; }
    catch (error) { if (error.code !== 'ENOENT') throw controlError('publication_lock_unavailable'); }
    return { ...current, publicationInFlight };
  }

  return { read, status, assertOpen, withPublication, initializeOpen, suspendAndWait, resume };
}

async function cli(argv) {
  const [command, file, ...rest] = argv;
  if (!command || !file) throw controlError('usage');
  const control = makeControl(file);
  if (command === 'initialize-open') return control.initializeOpen();
  if (command === 'status') return control.status();
  if (command === 'suspend') {
    const reason = rest[0] || 'maintenance';
    const timeoutMs = Number(rest[1] || 30000);
    return control.suspendAndWait({ reason, timeoutMs });
  }
  if (command === 'resume') return control.resume(rest[0] || 'maintenance-complete');
  throw controlError('unknown_command');
}

if (require.main === module) {
  cli(process.argv.slice(2)).then(result => {
    console.log(JSON.stringify(result));
    if (result.quiescent === false) process.exitCode = 1;
  }).catch(error => {
    console.error(JSON.stringify({ status: 'unavailable', code: error.code || 'control_error' }));
    process.exitCode = 1;
  });
}

module.exports = { makeControl, VERSION };
