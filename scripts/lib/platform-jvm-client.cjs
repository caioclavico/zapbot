'use strict';

// Cliente HTTP da bridge JVM para bloqueio/admins/enquetes/quiz (M7.1–M7.3).
// Fail-closed: sem fallback Cassandra. Staging pode usar host privado Docker.
const http = require('node:http');
const https = require('node:https');
const crypto = require('node:crypto');

function failure(code, message, status, details) {
  return Object.assign(new Error(message), { code, status, details });
}

function positive(value, fallback) {
  return Number.isSafeInteger(value) && value > 0 ? value : fallback;
}

/**
 * Mesma política do cliente Pokémon (M9.5.2):
 * loopback sempre; `http://odisseu-jvm:18090` só com allowPrivateHost
 * (Compose staging; desligado por padrão). Sem IPs/domínios arbitrários.
 */
function assertHost(base, allowPrivate) {
  const host = (base.hostname || '').toLowerCase();
  if (['127.0.0.1', 'localhost', '::1'].includes(host)) return;
  const port = String(base.port || (base.protocol === 'https:' ? '443' : '80'));
  if (
    allowPrivate === true &&
    host === 'odisseu-jvm' &&
    port === '18090' &&
    base.protocol === 'http:'
  ) {
    return;
  }
  throw failure(
    'CONFIG',
    'PLATFORM_JVM_URL deve ser loopback ou http://odisseu-jvm:18090 (Docker staging).'
  );
}

function createClient(config = {}) {
  let base;
  try { base = new URL(config.baseUrl); } catch (_) {
    throw failure('CONFIG', 'PLATFORM_JVM_URL inválida.');
  }
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
    throw failure('CONFIG', 'PLATFORM_JVM_URL deve conter apenas a origem HTTP(S).');
  }
  assertHost(base, !!config.allowPrivateHost);
  if (typeof config.token !== 'string' || !config.token.trim()) {
    throw failure('CONFIG', 'PLATFORM_JVM_TOKEN não configurado.');
  }
  const transport = base.protocol === 'https:' ? https : http;
  const agent = new transport.Agent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 2 });
  const connectTimeoutMs = positive(config.connectTimeoutMs, 3000);
  const timeoutMs = positive(config.timeoutMs, 8000);
  const maxJsonBytes = positive(config.maxJsonBytes, 256 * 1024);
  const prefix = base.pathname.replace(/\/$/, '');

  function request(method, path, payload) {
    return new Promise((resolve, reject) => {
      const body = payload === undefined ? null : Buffer.from(JSON.stringify(payload));
      if (body && body.length > maxJsonBytes) {
        reject(failure('LIMIT', 'Requisição platform JVM excedeu o limite.'));
        return;
      }
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
        path: `${prefix}${path}`, method,
        headers: Object.assign({
          Authorization: `Bearer ${config.token.trim()}`,
          Accept: 'application/json',
          Connection: 'keep-alive'
        }, body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {}),
        agent
      }, (res) => {
        clearTimeout(connectionTimer);
        const chunks = [];
        let size = 0;
        res.on('data', (c) => {
          size += c.length;
          if (size > maxJsonBytes) {
            finish(failure('LIMIT', 'Resposta platform JVM excedeu o limite.', res.statusCode));
            res.destroy();
            return;
          }
          chunks.push(c);
        });
        res.on('end', () => {
          const raw = Buffer.concat(chunks).toString('utf8');
          let parsed;
          try { parsed = raw ? JSON.parse(raw) : {}; } catch (_) {
            finish(failure('INVALID_RESPONSE', 'JSON inválido da bridge platform.', res.statusCode));
            return;
          }
          if (res.statusCode >= 200 && res.statusCode < 300 && parsed && parsed.ok === true) {
            finish(null, parsed.data || {});
            return;
          }
          const code = (parsed && parsed.error && parsed.error.code) || 'UPSTREAM';
          const msg = (parsed && parsed.error && parsed.error.message) || `HTTP ${res.statusCode}`;
          finish(failure(code, msg, res.statusCode, parsed && parsed.error && parsed.error.details));
        });
        res.on('error', (err) => finish(failure('NETWORK', err.message)));
      });
      connectionTimer = setTimeout(() => {
        req.destroy(failure('TIMEOUT', 'Timeout de conexão com a bridge platform.', undefined, { connectTimeoutMs }));
      }, connectTimeoutMs);
      totalTimer = setTimeout(() => {
        req.destroy(failure('TIMEOUT', 'Timeout total da bridge platform.', undefined, { timeoutMs }));
      }, timeoutMs);
      req.on('error', (err) => finish(failure('NETWORK', err.message || 'Falha de rede na bridge platform.')));
      if (body) req.write(body);
      req.end();
    });
  }

  function blocksToMap(entries) {
    const out = {};
    for (const e of entries || []) {
      if (e && typeof e.chatId === 'string') {
        out[e.chatId] = { 'bot?': !!e['bot?'], comandos: e.comandos || [] };
      }
    }
    return out;
  }

  function adminsToMap(entries) {
    const out = {};
    for (const e of entries || []) {
      if (e && typeof e.chatId === 'string') {
        out[e.chatId] = e.playerIds || [];
      }
    }
    return out;
  }

  function pollsToMap(entries) {
    const out = {};
    for (const e of entries || []) {
      if (e && typeof e.chatId === 'string') {
        out[e.chatId] = {
          pergunta: e.pergunta || '',
          opcoes: e.opcoes || [],
          votos: e.votos || {},
          prefix: e.prefix || '!'
        };
      }
    }
    return out;
  }

  function pollEntry(poll) {
    if (!poll || typeof poll.chatId !== 'string') return null;
    return {
      pergunta: poll.pergunta || '',
      opcoes: poll.opcoes || [],
      votos: poll.votos || {},
      prefix: poll.prefix || '!'
    };
  }

  return {
    getBlocks() {
      return request('GET', '/v1/platform/blocks').then((data) => ({
        blocks: blocksToMap(data.blocks)
      }));
    },
    getAdmins() {
      return request('GET', '/v1/platform/admins').then((data) => ({
        admins: adminsToMap(data.admins)
      }));
    },
    setBot(chatId, blocked, operationId) {
      return request('POST', '/v1/platform/blocks/bot', { chatId, blocked: !!blocked, operationId })
        .then((data) => Object.assign({}, data, { blocks: blocksToMap(data.blocks) }));
    },
    blockCommands(chatId, commands, operationId) {
      return request('POST', '/v1/platform/blocks/commands', {
        chatId, action: 'block', commands, operationId
      }).then((data) => Object.assign({}, data, { blocks: blocksToMap(data.blocks) }));
    },
    unblockCommands(chatId, commands, operationId) {
      return request('POST', '/v1/platform/blocks/commands', {
        chatId, action: 'unblock', commands, operationId
      }).then((data) => Object.assign({}, data, { blocks: blocksToMap(data.blocks) }));
    },
    rememberAdmin(chatId, playerId, operationId) {
      return request('POST', '/v1/platform/admins/remember', { chatId, playerId, operationId })
        .then((data) => Object.assign({}, data, { admins: adminsToMap(data.admins) }));
    },
    forgetAdmin(chatId, playerId, operationId) {
      return request('POST', '/v1/platform/admins/forget', { chatId, playerId, operationId })
        .then((data) => Object.assign({}, data, { admins: adminsToMap(data.admins) }));
    },
    knownAdmin(chatId, playerIds) {
      return request('POST', '/v1/platform/admins/known', { chatId, playerIds });
    },
    getPolls() {
      return request('GET', '/v1/platform/polls').then((data) => ({
        polls: pollsToMap(data.polls)
      }));
    },
    getPoll(chatId) {
      const q = encodeURIComponent(chatId);
      return request('GET', `/v1/platform/polls?chatId=${q}`).then((data) => ({
        poll: pollEntry(data.poll)
      }));
    },
    createPoll(chatId, poll, operationId) {
      return request('POST', '/v1/platform/polls/create', {
        chatId,
        pergunta: poll.pergunta,
        opcoes: poll.opcoes || [],
        prefix: poll.prefix || '!',
        operationId
      }).then((data) => Object.assign({}, data, {
        poll: pollEntry(data.poll),
        polls: pollsToMap(data.polls)
      }));
    },
    castVote(chatId, voterId, choice, operationId) {
      return request('POST', '/v1/platform/polls/vote', {
        chatId, voterId, choice, operationId
      }).then((data) => Object.assign({}, data, {
        poll: pollEntry(data.poll),
        polls: pollsToMap(data.polls)
      }));
    },
    closePoll(chatId, operationId) {
      return request('POST', '/v1/platform/polls/close', { chatId, operationId })
        .then((data) => Object.assign({}, data, {
          poll: pollEntry(data.poll),
          polls: pollsToMap(data.polls)
        }));
    },
    getQuizHistories() {
      return request('GET', '/v1/platform/quiz-history').then((data) => {
        const out = {};
        for (const e of data.histories || []) {
          if (e && typeof e.chatId === 'string') out[e.chatId] = e.questions || [];
        }
        return { histories: out };
      });
    },
    appendQuizQuestion(chatId, question, operationId) {
      return request('POST', '/v1/platform/quiz-history/append', {
        chatId, question, operationId
      }).then((data) => {
        const out = {};
        for (const e of data.histories || []) {
          if (e && typeof e.chatId === 'string') out[e.chatId] = e.questions || [];
        }
        return Object.assign({}, data, { histories: out });
      });
    },
    getRank() {
      return request('GET', '/v1/platform/rank').then((data) => {
        const chats = {};
        for (const c of data.chats || []) {
          if (!c || typeof c.chatId !== 'string') continue;
          const players = {};
          for (const p of c.players || []) {
            if (p && typeof p.playerId === 'string') {
              const copy = Object.assign({}, p);
              delete copy.playerId;
              players[p.playerId] = copy;
            }
          }
          chats[c.chatId] = players;
        }
        return { chats };
      });
    },
    getRankChat(chatId) {
      const q = encodeURIComponent(chatId);
      return request('GET', `/v1/platform/rank?chatId=${q}`).then((data) => ({
        players: data.players || [],
        top: data.top || []
      }));
    },
    rankScore(chatId, playerId, name, game, operationId) {
      return request('POST', '/v1/platform/rank/score', {
        chatId, playerId, name, game, operationId
      });
    },
    rankPenalize(chatId, playerId, operationId) {
      return request('POST', '/v1/platform/rank/penalize', {
        chatId, playerId, operationId
      });
    },
    rankEffect(effect) {
      return request('POST', '/v1/platform/rank/effect', effect);
    },
    getReminders(chatId) {
      const path = chatId
        ? `/v1/platform/reminders?chatId=${encodeURIComponent(chatId)}`
        : '/v1/platform/reminders';
      return request('GET', path).then((data) => ({
        reminders: data.reminders || [],
        chatId: data.chatId
      }));
    },
    getReminder(id) {
      return request('GET', `/v1/platform/reminders?id=${encodeURIComponent(id)}`)
        .then((data) => ({ reminder: data.reminder || null }));
    },
    createReminder(reminder, operationId) {
      return request('POST', '/v1/platform/reminders/create', Object.assign({}, reminder, {
        operationId
      }));
    },
    cancelReminder(id, chatId, operationId) {
      return request('POST', '/v1/platform/reminders/cancel', { id, chatId, operationId });
    },
    dueReminders(now, limit) {
      const q = new URLSearchParams();
      if (now != null) q.set('now', String(now));
      if (limit != null) q.set('limit', String(limit));
      const qs = q.toString();
      return request('GET', `/v1/platform/reminders/due${qs ? `?${qs}` : ''}`)
        .then((data) => ({
          now: data.now,
          reminders: data.reminders || []
        }));
    },
    claimReminder(id, claimant, leaseMs, now) {
      return request('POST', '/v1/platform/reminders/claim', {
        id, claimant, leaseMs, now
      });
    },
    completeReminder(id, claimant, fence) {
      return request('POST', '/v1/platform/reminders/complete', { id, claimant, fence });
    },
    failReminder(id, claimant, fence, error) {
      return request('POST', '/v1/platform/reminders/fail', { id, claimant, fence, error });
    },
    getBugs(version) {
      const qs = version ? `?version=${encodeURIComponent(version)}` : '';
      return request('GET', `/v1/platform/bugs${qs}`).then((data) => ({
        bugs: data.bugs || []
      }));
    },
    getBug(id) {
      return request('GET', `/v1/platform/bugs?id=${encodeURIComponent(id)}`)
        .then((data) => ({ bug: data.bug || null }));
    },
    createBug(bug, operationId) {
      return request('POST', '/v1/platform/bugs/create', Object.assign({}, bug, { operationId }));
    },
    resolveBug(id, operationId, resolvedAt) {
      return request('POST', '/v1/platform/bugs/resolve', {
        id, operationId, resolvedAt
      });
    },
    getParticipants(chatId) {
      const path = chatId
        ? `/v1/platform/participants?chatId=${encodeURIComponent(chatId)}`
        : '/v1/platform/participants';
      return request('GET', path).then((data) => {
        if (chatId) {
          const players = {};
          for (const p of data.players || []) {
            if (p && typeof p.playerId === 'string') players[p.playerId] = p.name;
          }
          return { chatId: data.chatId, players };
        }
        const chats = {};
        for (const c of data.chats || []) {
          if (!c || typeof c.chatId !== 'string') continue;
          const players = {};
          for (const p of c.players || []) {
            if (p && typeof p.playerId === 'string') players[p.playerId] = p.name;
          }
          chats[c.chatId] = players;
        }
        return { chats };
      });
    },
    upsertParticipant(chatId, playerId, name, operationId) {
      return request('POST', '/v1/platform/participants/upsert', {
        chatId, playerId, name, operationId
      });
    },
    newOperationId(prefix) {
      return `${prefix || 'op'}:${crypto.randomUUID()}`;
    }
  };
}

module.exports = { createClient, failure, assertHost };
