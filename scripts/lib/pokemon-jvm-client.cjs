'use strict';

// Cliente HTTP da bridge JVM (M4.1): somente leituras em loopback.
// Sem acesso a Datomic, sem escritas, sem retries de mutação.
const http = require('node:http');
const https = require('node:https');

function failure(code, message, status, details) {
  return Object.assign(new Error(message), { code, status, details });
}

function positive(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

function assertLoopback(base) {
  const host = (base.hostname || '').toLowerCase();
  if (!['127.0.0.1', 'localhost', '::1'].includes(host)) {
    throw failure('CONFIG', 'POKEMON_JVM_URL deve apontar para loopback (127.0.0.1/localhost).');
  }
}

function createClient(config = {}) {
  let base;
  try { base = new URL(config.baseUrl); } catch (_) {
    throw failure('CONFIG', 'POKEMON_JVM_URL inválida.');
  }
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
    throw failure('CONFIG', 'POKEMON_JVM_URL deve conter apenas a origem HTTP(S).');
  }
  assertLoopback(base);
  if (typeof config.token !== 'string' || !config.token.trim()) {
    throw failure('CONFIG', 'POKEMON_JVM_TOKEN não configurado.');
  }
  const transport = base.protocol === 'https:' ? https : http;
  const agent = new transport.Agent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 2 });
  const connectTimeoutMs = positive(config.connectTimeoutMs, 3000);
  const timeoutMs = positive(config.timeoutMs, 8000);
  const maxJsonBytes = positive(config.maxJsonBytes, 1024 * 1024);
  const prefix = base.pathname.replace(/\/$/, '');

  function request(path) {
    return new Promise((resolve, reject) => {
      let finished = false;
      let connectionTimer;
      let totalTimer;
      const finish = (error, value) => {
        if (finished) return;
        finished = true;
        clearTimeout(connectionTimer);
        clearTimeout(totalTimer);
        if (error) reject(error); else resolve(value);
      };
      const req = transport.request({
        protocol: base.protocol, hostname: base.hostname, port: base.port || undefined,
        path: prefix + path, method: 'GET', agent,
        headers: {
          Authorization: `Bearer ${config.token}`,
          Accept: 'application/json'
        }
      }, res => {
        if (finished) { res.destroy(); return; }
        clearTimeout(connectionTimer);
        const chunks = [];
        let size = 0;
        res.on('data', chunk => {
          if (finished) return;
          size += chunk.length;
          if (size > maxJsonBytes) {
            finish(failure('LIMIT', 'Resposta da bridge JVM excedeu o limite.'));
            res.destroy();
            return;
          }
          chunks.push(chunk);
        });
        res.on('aborted', () => finish(failure('NETWORK', 'Resposta da bridge JVM interrompida.')));
        res.on('error', () => finish(failure('NETWORK', 'Falha ao receber resposta da bridge JVM.')));
        res.on('end', () => {
          if (finished) return;
          const buffer = chunks.length === 1 ? chunks[0] : Buffer.concat(chunks, size);
          let body;
          try { body = JSON.parse(buffer.toString('utf8')); }
          catch (_) {
            finish(failure('INVALID_RESPONSE', 'Resposta da bridge JVM não contém JSON válido.', res.statusCode));
            return;
          }
          if (res.statusCode < 200 || res.statusCode >= 300) {
            const errCode = body && body.error && body.error.code
              ? String(body.error.code).toUpperCase()
              : 'HTTP';
            const message = (body && body.error && body.error.message)
              || `Bridge JVM respondeu HTTP ${res.statusCode}.`;
            finish(failure(errCode, message, res.statusCode, body && body.error && body.error.details));
            return;
          }
          if (!body || body.ok !== true || body.data === undefined) {
            finish(failure('INVALID_RESPONSE', 'Envelope de leitura JVM inválido.', res.statusCode));
            return;
          }
          finish(null, body.data);
        });
      });
      req.on('socket', socket => {
        if (!socket.connecting) return;
        connectionTimer = setTimeout(() => {
          const error = failure('CONNECT_TIMEOUT', 'Tempo de conexão com a bridge JVM esgotado.');
          finish(error); req.destroy(error);
        }, connectTimeoutMs);
        socket.once(base.protocol === 'https:' ? 'secureConnect' : 'connect', () => clearTimeout(connectionTimer));
      });
      totalTimer = setTimeout(() => {
        const error = failure('TIMEOUT', 'Tempo de resposta da bridge JVM esgotado.');
        finish(error); req.destroy(error);
      }, timeoutMs);
      req.on('error', error => finish(failure(error.code || 'NETWORK', 'Bridge JVM indisponível.')));
      req.end();
    });
  }

  function q(params) {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(params)) {
      if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
    }
    const s = sp.toString();
    return s ? `?${s}` : '';
  }

  return {
    health: () => request('/health'),
    getTrainer: ({ chatId, playerId }) =>
      request(`/v1/trainer${q({ chatId, playerId })}`),
    getCollection: ({ chatId, playerId, saveId }) =>
      request(`/v1/collection${q({ chatId, playerId, saveId })}`),
    getActiveSave: ({ chatId, playerId }) =>
      request(`/v1/active-save${q({ chatId, playerId })}`),
    close: () => agent.destroy()
  };
}

module.exports = { createClient };
