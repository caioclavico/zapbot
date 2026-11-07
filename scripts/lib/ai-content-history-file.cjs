'use strict';

const fsDefault = require('node:fs/promises');
const path = require('node:path');
const {isDuplicate} = require('./ai-content-generator.cjs');

const KINDS = new Set(['piada', 'curiosidade']);
const queuesByPath = new Map();
const ENTRY_KEYS = ['category', 'subtopic', 'summary', 'fingerprint', 'createdAt', 'source'];
const EMPTY = () => ({version: 1, piada: {cursor: 0, items: []}, curiosidade: {cursor: 0, items: []}});

function normalizeEntry(entry) {
  if (!entry || typeof entry !== 'object' || Array.isArray(entry)) throw new Error('invalid history entry');
  if (Object.keys(entry).some(key => !ENTRY_KEYS.includes(key))) throw new Error('unexpected history field');
  const normalized = {};
  for (const key of ENTRY_KEYS) {
    const value = entry[key];
    if (typeof value !== 'string' || value.length > (key === 'summary' ? 220 : 120))
      throw new Error('invalid history metadata');
    normalized[key] = value;
  }
  for (const key of ['subtopic', 'summary']) {
    normalized[key] = normalized[key]
      .replace(/[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/g, '[dado removido]')
      .replace(/https?:\/\/\S+/gi, '[link removido]')
      .replace(/\b\+?\d[\d .()/-]{7,}\d\b/g, '[número removido]')
      .replace(/[\u0000-\u001f\u007f]/g, ' ')
      .replace(/\s+/g, ' ').trim();
  }
  if (!/^[a-z0-9_-]{1,80}$/.test(normalized.category)) throw new Error('invalid history category');
  if (!['gemini', 'groq', 'local'].includes(normalized.source)) throw new Error('invalid history source');
  if (!/^[a-f0-9]{64}$/.test(normalized.fingerprint)) throw new Error('invalid history fingerprint');
  if (!Number.isFinite(Date.parse(normalized.createdAt))) throw new Error('invalid history timestamp');
  return normalized;
}

function normalizeState(raw, windowSize) {
  if (!raw || raw.version !== 1) throw new Error('unsupported history format');
  const state = EMPTY();
  for (const kind of KINDS) {
    const entry = raw[kind];
    if (!entry || !Array.isArray(entry.items) || !Number.isSafeInteger(entry.cursor) || entry.cursor < 0)
      throw new Error('corrupt history state');
    state[kind] = {cursor: entry.cursor, items: entry.items.map(normalizeEntry).slice(-windowSize)};
  }
  return state;
}

function createStore(options = {}) {
  const fs = options.fs || fsDefault;
  const filePath = path.resolve(options.filePath || process.env.AI_CONTENT_HISTORY_PATH || '/app/data/ai-content-history.json');
  const windowSize = Number.isSafeInteger(options.windowSize) ? Math.min(200, Math.max(20, options.windowSize)) : 120;
  if (!queuesByPath.has(filePath)) queuesByPath.set(filePath, Promise.resolve());

  function serialized(operation) {
    const pending = queuesByPath.get(filePath).catch(() => undefined).then(operation);
    queuesByPath.set(filePath, pending.catch(() => undefined));
    return pending;
  }

  async function readUnlocked() {
    const dir = path.dirname(filePath);
    const prefix = `.${path.basename(filePath)}.`;
    try {
      const names = await fs.readdir(dir);
      await Promise.all(names.filter(name => name.startsWith(prefix) && name.endsWith('.tmp'))
        .map(async name => {
          const candidate = path.join(dir, name);
          const stat = await fs.lstat(candidate);
          if (stat.isFile()) await fs.unlink(candidate);
        }));
    } catch (error) {
      if (error && error.code !== 'ENOENT') throw error;
    }
    try {
      const contents = await fs.readFile(filePath, 'utf8');
      return normalizeState(JSON.parse(contents), windowSize);
    } catch (error) {
      if (error && error.code === 'ENOENT') return EMPTY();
      throw error;
    }
  }

  async function writeUnlocked(raw) {
    const state = normalizeState({...raw, version: 1}, windowSize);
    const dir = path.dirname(filePath);
    await fs.mkdir(dir, {recursive: true, mode: 0o700});
    const tempPath = path.join(dir, `.${path.basename(filePath)}.${process.pid}.${require('node:crypto').randomUUID()}.tmp`);
    let handle;
    try {
      handle = await fs.open(tempPath, 'wx', 0o600);
      await handle.writeFile(`${JSON.stringify(state)}\n`, 'utf8');
      await handle.sync();
      await handle.close();
      handle = null;
      await fs.rename(tempPath, filePath);
      const dirHandle = await fs.open(dir, 'r');
      try { await dirHandle.sync(); } finally { await dirHandle.close(); }
      return state;
    } catch (error) {
      if (handle) await handle.close().catch(() => undefined);
      await fs.unlink(tempPath).catch(() => undefined);
      throw error;
    }
  }

  return Object.freeze({
    filePath,
    load: () => serialized(readUnlocked),
    save: state => serialized(() => writeUnlocked(state)),
    recentes: (kind, limit = windowSize) => serialized(async () => {
      if (!KINDS.has(kind)) throw new Error('invalid history kind');
      const count = Number.isSafeInteger(limit) ? Math.max(0, Math.min(windowSize, limit)) : windowSize;
      return (await readUnlocked())[kind].items.slice(-count);
    }),
    existeSimilar: (kind, content) => serialized(async () => {
      if (!KINDS.has(kind)) throw new Error('invalid history kind');
      return isDuplicate(content, (await readUnlocked())[kind].items).duplicate;
    }),
    registrar: (kind, entry) => serialized(async () => {
      if (!KINDS.has(kind)) throw new Error('invalid history kind');
      const state = await readUnlocked();
      const next = {...state, [kind]: {
        cursor: state[kind].cursor + 1,
        items: [...state[kind].items, normalizeEntry(entry)].slice(-windowSize),
      }};
      return writeUnlocked(next);
    }),
    limparExcedentes: () => serialized(async () => writeUnlocked(await readUnlocked())),
  });
}

module.exports = {createStore, normalizeEntry, normalizeState};
