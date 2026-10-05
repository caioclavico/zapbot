'use strict';

// Transport only: no game state, WhatsApp objects, image processing or retries
// of commands. The service deduplicates requests by requestId.
const http = require('node:http');
const https = require('node:https');

function failure(code, message, status) {
  return Object.assign(new Error(message), { code, status });
}

function positive(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

function createClient(config = {}) {
  let base;
  try { base = new URL(config.baseUrl); } catch (_) {
    throw failure('CONFIG', 'POKEMON_SERVICE_URL inválida.');
  }
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
    throw failure('CONFIG', 'POKEMON_SERVICE_URL deve conter apenas a origem e o caminho HTTP(S).');
  }
  if (typeof config.token !== 'string' || !config.token.trim()) {
    throw failure('CONFIG', 'POKEMON_SERVICE_TOKEN não configurado.');
  }
  const transport = base.protocol === 'https:' ? https : http;
  const agent = new transport.Agent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 2 });
  const connectTimeoutMs = positive(config.connectTimeoutMs, 5000);
  const timeoutMs = positive(config.timeoutMs, 45000);
  const maxMediaBytes = positive(config.maxMediaBytes, 10 * 1024 * 1024);
  const maxJsonBytes = positive(config.maxJsonBytes, 1024 * 1024);
  const prefix = base.pathname.replace(/\/$/, '');

  function request(method, path, payload, binary = false) {
    return new Promise((resolve, reject) => {
      const body = payload === undefined ? null : Buffer.from(JSON.stringify(payload));
      if (body && body.length > maxJsonBytes) {
        reject(failure('LIMIT', 'Requisição Pokémon excedeu o limite.'));
        return;
      }
      const limit = binary ? maxMediaBytes : maxJsonBytes;
      let finished = false;
      let connectionTimer;
      let totalTimer;
      let releaseBody;
      const finish = (error, value) => {
        if (finished) return;
        finished = true;
        clearTimeout(connectionTimer);
        clearTimeout(totalTimer);
        if (releaseBody) {
          releaseBody();
          releaseBody = null;
        }
        if (error) reject(error); else resolve(value);
      };
      const req = transport.request({
        protocol: base.protocol, hostname: base.hostname, port: base.port || undefined,
        path: prefix + path, method, agent,
        headers: {
          Authorization: `Bearer ${config.token}`,
          Accept: binary ? 'application/octet-stream' : 'application/json',
          ...(body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {})
        }
      }, res => {
        if (finished) { res.destroy(); return; }
        clearTimeout(connectionTimer);
        if (res.statusCode < 200 || res.statusCode >= 300) {
          finish(failure('HTTP', `Serviço Pokémon respondeu HTTP ${res.statusCode}.`, res.statusCode));
          res.destroy();
          return;
        }
        const contentLength = res.headers['content-length'];
        const length = Number(contentLength);
        if (length > limit) {
          finish(failure('LIMIT', 'Resposta Pokémon excedeu o limite.'));
          res.destroy();
          return;
        }
        // Binary responses with a bounded length need only their final buffer.
        const expectedLength = binary && typeof contentLength === 'string'
          && /^\d+$/.test(contentLength) && Number.isSafeInteger(length) && length >= 0
          ? length : null;
        let target = expectedLength === null ? null : Buffer.allocUnsafe(expectedLength);
        const chunks = [];
        let size = 0;
        releaseBody = () => { chunks.length = 0; target = null; };
        res.on('data', chunk => {
          if (finished) return;
          const nextSize = size + chunk.length;
          if (nextSize > limit) {
            finish(failure('LIMIT', 'Resposta Pokémon excedeu o limite.'));
            res.destroy();
          } else if (expectedLength !== null && nextSize > expectedLength) {
            finish(failure('NETWORK', 'Resposta Pokémon interrompida.'));
            res.destroy();
          } else {
            if (target) chunk.copy(target, size);
            else chunks.push(chunk);
            size = nextSize;
          }
        });
        res.on('aborted', () => finish(failure('NETWORK', 'Resposta Pokémon interrompida.')));
        res.on('error', () => finish(failure('NETWORK', 'Falha ao receber resposta Pokémon.')));
        res.on('end', () => {
          if (finished) return;
          // Never expose bytes from an incomplete preallocated response.
          if (expectedLength !== null && size !== expectedLength) {
            finish(failure('NETWORK', 'Resposta Pokémon interrompida.'));
            return;
          }
          const buffer = target || (chunks.length === 1 ? chunks[0] : Buffer.concat(chunks, size));
          if (binary) return finish(null, buffer);
          try { finish(null, JSON.parse(buffer.toString('utf8'))); }
          catch (_) { finish(failure('INVALID_RESPONSE', 'Resposta Pokémon não contém JSON válido.')); }
        });
      });
      req.on('socket', socket => {
        if (!socket.connecting) return;
        connectionTimer = setTimeout(() => {
          const error = failure('CONNECT_TIMEOUT', 'Tempo de conexão com Pokémon esgotado.');
          finish(error); req.destroy(error);
        }, connectTimeoutMs);
        socket.once(base.protocol === 'https:' ? 'secureConnect' : 'connect', () => clearTimeout(connectionTimer));
      });
      totalTimer = setTimeout(() => {
        const error = failure('TIMEOUT', 'Tempo de resposta do serviço Pokémon esgotado.');
        finish(error); req.destroy(error);
      }, timeoutMs);
      req.on('error', error => finish(failure(error.code || 'NETWORK', 'Serviço Pokémon indisponível.')));
      req.end(body);
    });
  }

  function idPath(id) {
    if (typeof id !== 'string' || !/^[a-zA-Z0-9_-]{1,256}$/.test(id)) {
      throw failure('INVALID_RESPONSE', 'Identificador de recurso Pokémon inválido.');
    }
    return encodeURIComponent(id);
  }

  return {
    command: value => request('POST', '/commands', value),
    media: id => request('GET', `/media/${idPath(id)}`, undefined, true),
    pendingEvents: () => request('GET', '/events/pending'),
    ack: id => request('POST', `/events/${idPath(id)}/ack`, {}),
    health: () => request('GET', '/health'),
    close: () => agent.destroy()
  };
}

module.exports = { createClient };
