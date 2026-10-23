'use strict';

// Cliente HTTP da bridge JVM (M4.1 leituras + M4.2 escritas explícitas).
// Loopback only. Sem Datomic. Sem retry automático de escrita com novo operationId.
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
 * Host permitido:
 * - loopback (sempre);
 * - hostname exato `odisseu-jvm` na porta 18090 via HTTP, só com
 *   allowDockerJvmHost=true (staging Compose; desligado por padrão).
 * Não aceita IPs privados, outros hostnames nem HTTPS nesse modo Docker.
 */
function assertJvmUrl(base, { allowDockerJvmHost = false } = {}) {
  const host = (base.hostname || '').toLowerCase();
  if (['127.0.0.1', 'localhost', '::1'].includes(host)) return;
  const port = String(base.port || (base.protocol === 'https:' ? '443' : '80'));
  if (
    allowDockerJvmHost === true &&
    host === 'odisseu-jvm' &&
    port === '18090' &&
    base.protocol === 'http:'
  ) {
    return;
  }
  throw failure(
    'CONFIG',
    'POKEMON_JVM_URL deve apontar para loopback (127.0.0.1/localhost)' +
      (allowDockerJvmHost
        ? ' ou http://odisseu-jvm:18090 (Docker staging).'
        : '.')
  );
}

/**
 * Identidade confiável a partir do contexto do adaptador Node (WhatsApp/message).
 * Nunca aceita playerId/chatId vindos do texto digitado pelo usuário.
 */
function trustedIdentityFromMessage(message = {}) {
  const chatId = message.fromMe ? message.to : message.from;
  const playerId = message.author || message.from;
  const rawId = message.id;
  const requestId = typeof rawId === 'string'
    ? rawId
    : (rawId && (rawId._serialized || rawId.$1)) || null;
  if (typeof chatId !== 'string' || !chatId.trim()) {
    throw failure('IDENTITY', 'chatId confiável ausente no contexto da mensagem.');
  }
  if (typeof playerId !== 'string' || !playerId.trim()) {
    throw failure('IDENTITY', 'playerId confiável ausente no contexto da mensagem.');
  }
  return { chatId: chatId.trim(), playerId: playerId.trim(), requestId };
}

/** operationId estável — o caller deve reutilizar o mesmo valor em retry. */
function stableOperationId(requestId, action) {
  if (typeof requestId === 'string' && requestId.trim() && typeof action === 'string') {
    return `${requestId.trim()}:${action}`;
  }
  return crypto.randomUUID();
}

function createClient(config = {}) {
  let base;
  try { base = new URL(config.baseUrl); } catch (_) {
    throw failure('CONFIG', 'POKEMON_JVM_URL inválida.');
  }
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
    throw failure('CONFIG', 'POKEMON_JVM_URL deve conter apenas a origem HTTP(S).');
  }
  assertJvmUrl(base, { allowDockerJvmHost: !!config.allowDockerJvmHost });
  if (typeof config.token !== 'string' || !config.token.trim()) {
    throw failure('CONFIG', 'POKEMON_JVM_TOKEN não configurado.');
  }
  const transport = base.protocol === 'https:' ? https : http;
  const agent = new transport.Agent({ keepAlive: true, maxSockets: 4, maxFreeSockets: 2 });
  const connectTimeoutMs = positive(config.connectTimeoutMs, 3000);
  const timeoutMs = positive(config.timeoutMs, 8000);
  const maxJsonBytes = positive(config.maxJsonBytes, 1024 * 1024);
  const prefix = base.pathname.replace(/\/$/, '');

  function request(method, path, payload, extraHeaders = {}) {
    return new Promise((resolve, reject) => {
      const body = payload === undefined ? null : Buffer.from(JSON.stringify(payload));
      if (body && body.length > maxJsonBytes) {
        reject(failure('LIMIT', 'Requisição da bridge JVM excedeu o limite.'));
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
        path: prefix + path, method, agent,
        headers: {
          Authorization: `Bearer ${config.token}`,
          Accept: 'application/json',
          ...extraHeaders,
          ...(body ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {})
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
          let parsed;
          try { parsed = JSON.parse(buffer.toString('utf8')); }
          catch (_) {
            finish(failure('INVALID_RESPONSE', 'Resposta da bridge JVM não contém JSON válido.', res.statusCode));
            return;
          }
          if (res.statusCode < 200 || res.statusCode >= 300) {
            const errCode = parsed && parsed.error && parsed.error.code
              ? String(parsed.error.code).toUpperCase()
              : 'HTTP';
            const message = (parsed && parsed.error && parsed.error.message)
              || `Bridge JVM respondeu HTTP ${res.statusCode}.`;
            finish(failure(errCode, message, res.statusCode, parsed && parsed.error && parsed.error.details));
            return;
          }
          if (!parsed || parsed.ok !== true || parsed.data === undefined) {
            finish(failure('INVALID_RESPONSE', 'Envelope JVM inválido.', res.statusCode));
            return;
          }
          finish(null, parsed.data);
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
      req.end(body);
    });
  }

  function q(params) {
    const sp = new URLSearchParams();
    for (const [k, v] of Object.entries(params || {})) {
      if (v !== undefined && v !== null && v !== '') sp.set(k, String(v));
    }
    const s = sp.toString();
    return s ? `?${s}` : '';
  }

  function idPath(id) {
    if (typeof id !== 'string' || !id.trim() || id.includes('/')) {
      throw failure('INVALID_RESPONSE', 'Identificador de operação inválido.');
    }
    return encodeURIComponent(id.trim());
  }

  /**
   * Reconcilia após TIMEOUT: consulta recibo; se existir, retorna; senão reenvia
   * o MESMO body (mesmo operationId). Nunca gera operationId novo.
   */
  async function mutateOnce(path, body, extraHeaders = {}) {
    try {
      return await request('POST', path, body, extraHeaders);
    } catch (err) {
      if (err.code !== 'TIMEOUT' && err.code !== 'CONNECT_TIMEOUT') throw err;
      if (!body || !body.operationId || !body.chatId || !body.playerId) throw err;
      try {
        const receipt = await request(
          'GET',
          `/v1/operations/${idPath(body.operationId)}${q({ chatId: body.chatId, playerId: body.playerId })}`
        );
        return {
          operationId: receipt.operationId,
          duplicate: true,
          reconciled: true,
          persisted: true,
          channel: receipt.channel,
          payloadHash: receipt.payloadHash,
          saveId: receipt.saveId,
          summary: receipt.summary
        };
      } catch (lookupErr) {
        if (lookupErr.code === 'OPERATION_NOT_FOUND') {
          // Commit pode não ter ocorrido — reenvia o mesmo operationId (idempotente).
          return request('POST', path, body, extraHeaders);
        }
        throw err;
      }
    }
  }

  return {
    health: () => request('GET', '/health'),
    getTrainer: ({ chatId, playerId }) =>
      request('GET', `/v1/trainer${q({ chatId, playerId })}`),
    getCollection: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/collection${q({ chatId, playerId, saveId })}`),
    getPokedex: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/pokedex${q({ chatId, playerId, saveId })}`),
    getPokemonFicha: ({ chatId, playerId, saveId, collectionIndex }) =>
      request('GET', `/v1/pokemon/ficha${q({ chatId, playerId, saveId, collectionIndex })}`),
    getActiveSave: ({ chatId, playerId }) =>
      request('GET', `/v1/active-save${q({ chatId, playerId })}`),
    listSaves: ({ chatId, playerId }) =>
      request('GET', `/v1/saves${q({ chatId, playerId })}`),
    ensureTrainer: ({ chatId, playerId }) =>
      request('POST', '/v1/trainer/ensure', { chatId, playerId }),
    getOnboarding: ({ chatId, playerId }) =>
      request('GET', `/v1/onboarding${q({ chatId, playerId })}`),
    startJourney: (body) => mutateOnce('/v1/onboarding/start', body),
    claimStarterKit: (body) => mutateOnce('/v1/onboarding/kit', body),
    chooseStarter: (body) => mutateOnce('/v1/onboarding/starter', body),
    activateSave: ({ chatId, playerId, saveId, expectedFenceGeneration }) =>
      request('POST', '/v1/saves/activate', {
        chatId, playerId, saveId,
        ...(expectedFenceGeneration === undefined ? {} : { expectedFenceGeneration })
      }),
    creditCoins: (body) => mutateOnce('/v1/rewards/coins', body),
    getOperation: ({ operationId, chatId, playerId }) =>
      request('GET', `/v1/operations/${idPath(operationId)}${q({ chatId, playerId })}`),
    listTrades: ({ chatId, playerId }) =>
      request('GET', `/v1/trades${q({ chatId, playerId })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    createTrade: (body) => mutateOnce('/v1/trades', body,
      { 'X-Actor-Player-Id': body.playerId }),
    acceptTrade: (body) => mutateOnce('/v1/trades/accept', body,
      { 'X-Actor-Player-Id': body.playerId }),
    completeTrade: (body) => mutateOnce('/v1/trades/complete', body,
      { 'X-Actor-Player-Id': body.playerId }),
    refuseTrade: (body) => mutateOnce('/v1/trades/refuse', body,
      { 'X-Actor-Player-Id': body.playerId }),
    cancelTrade: (body) => mutateOnce('/v1/trades/cancel', body,
      { 'X-Actor-Player-Id': body.playerId }),
    listTeams: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/teams${q({ chatId, playerId, saveId })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    getTeamCsv: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/teams/csv${q({ chatId, playerId, saveId })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    saveTeam: (body) => mutateOnce('/v1/teams/save', body,
      { 'X-Actor-Player-Id': body.playerId }),
    useTeam: (body) => mutateOnce('/v1/teams/use', body,
      { 'X-Actor-Player-Id': body.playerId }),
    deleteTeam: (body) => mutateOnce('/v1/teams/delete', body,
      { 'X-Actor-Player-Id': body.playerId }),
    selectLeague: (body) => mutateOnce('/v1/teams/league', body,
      { 'X-Actor-Player-Id': body.playerId }),
    getFriendship: ({ chatId, playerId, saveId, collectionIndex }) =>
      request('GET', `/v1/profile/friendship${q({ chatId, playerId, saveId, collectionIndex })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    getTitles: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/profile/titles${q({ chatId, playerId, saveId })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    selectTitle: (body) => mutateOnce('/v1/profile/title', body, { 'X-Actor-Player-Id': body.playerId }),
    getProfessorCards: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/professor/cards${q({ chatId, playerId, saveId })}`, undefined,
        { 'X-Actor-Player-Id': playerId }),
    professorSend: (body) => mutateOnce('/v1/professor/send', body, { 'X-Actor-Player-Id': body.playerId }),
    professorCancel: (body) => mutateOnce('/v1/professor/cancel', body, { 'X-Actor-Player-Id': body.playerId }),
    professorConfirm: (body) => mutateOnce('/v1/professor/confirm', body, { 'X-Actor-Player-Id': body.playerId }),
    professorUse: (body) => mutateOnce('/v1/professor/use', body, { 'X-Actor-Player-Id': body.playerId }),
    getMissions: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/missions${q({ chatId, playerId, saveId })}`),
    getMissionRewards: ({ chatId, playerId, saveId }) =>
      request('GET', `/v1/missions/rewards${q({ chatId, playerId, saveId })}`),
    claimDailyMissions: (body) => mutateOnce('/v1/missions/claim/daily', body),
    claimWeeklyMissions: (body) => mutateOnce('/v1/missions/claim/weekly', body),
    sendGift: (body) => mutateOnce('/v1/gifts/send', body),
    collectMissionPending: (body) => mutateOnce('/v1/missions/pending/collect', body),
    getEncounter: ({ chatId, playerId }) =>
      request('GET', `/v1/encounter${q({ chatId, playerId })}`),
    attemptEncounterCapture: (body) => mutateOnce('/v1/encounter/capture', body),
    startBattle: (body) => mutateOnce('/v1/battles/start', body),
    confirmBattleTurn: (body) => mutateOnce('/v1/battles/turn', body),
    getHuntPlan: ({ chatId, playerId, areaId, agoraMs, rollTipos, rollSurto }) =>
      request('GET', `/v1/hunt/plan${q({ chatId, playerId, areaId, agoraMs, rollTipos, rollSurto })}`),
    startHunt: (body) => mutateOnce('/v1/hunt/start', body),
    listGyms: ({ chatId, playerId, agoraMs }) =>
      request('GET', `/v1/gyms${q({ chatId, playerId, agoraMs })}`),
    getGym: ({ chatId, playerId, gymId, agoraMs }) =>
      request('GET', `/v1/gyms/${encodeURIComponent(gymId)}${q({ chatId, playerId, agoraMs })}`),
    getGymRanking: ({ chatId, playerId, actorPlayerId = playerId, gymId, agoraMs }) =>
      request('GET', `/v1/gyms/ranking${q({ chatId, playerId, gymId, agoraMs })}`, undefined,
        { 'X-Actor-Player-Id': actorPlayerId }),
    getGymHistory: ({ chatId, playerId, actorPlayerId = playerId, gymId }) =>
      request('GET', `/v1/gyms/history${q({ chatId, playerId, gymId })}`, undefined,
        { 'X-Actor-Player-Id': actorPlayerId }),
    getGymTeam: ({ chatId, playerId }) =>
      request('GET', `/v1/gyms/team${q({ chatId, playerId })}`),
    setGymTeam: (body) => mutateOnce('/v1/gyms/team', body),
    healGym: (body) => mutateOnce('/v1/gyms/heal', body),
    startGymChallenge: (body) => mutateOnce('/v1/gyms/challenge', body),
    getActiveRaid: ({ chatId, playerId, agoraMs }) =>
      request('GET', `/v1/raids/active${q({ chatId, playerId, agoraMs })}`),
    getRaidEligible: ({ chatId, playerId }) =>
      request('GET', `/v1/raids/eligible${q({ chatId, playerId })}`),
    openRaid: (body) => mutateOnce('/v1/raids/open', body),
    joinRaid: (body) => mutateOnce('/v1/raids/join', body),
    leaveRaid: (body) => mutateOnce('/v1/raids/leave', body),
    startRaid: (body) => mutateOnce('/v1/raids/start', body),
    raidAttack: (body) => mutateOnce('/v1/raids/attack', body),
    raidCapture: (body) => mutateOnce('/v1/raids/capture', body),
    getJoyStatus: ({ chatId, playerId, agoraMs }) =>
      request('GET', `/v1/joy${q({ chatId, playerId, agoraMs })}`),
    joySend: (body) => mutateOnce('/v1/joy/send', body),
    joyCollect: (body) => mutateOnce('/v1/joy/collect', body),
    getAdventure: ({ chatId, playerId, agoraMs }) =>
      request('GET', `/v1/adventure${q({ chatId, playerId, agoraMs })}`),
    grantEgg: (body) => mutateOnce('/v1/adventure/eggs/grant', body),
    incubateEgg: (body) => mutateOnce('/v1/adventure/eggs/incubate', body),
    hatchEgg: (body) => mutateOnce('/v1/adventure/eggs/hatch', body),
    getShop: ({ chatId, playerId }) =>
      request('GET', `/v1/shop${q({ chatId, playerId })}`),
    getInventory: ({ chatId, playerId }) =>
      request('GET', `/v1/inventory${q({ chatId, playerId })}`),
    buyItem: (body) => mutateOnce('/v1/shop/buy', body),
    useItem: (body) => mutateOnce('/v1/items/use', body),
    setActivePokemon: (body) => mutateOnce('/v1/pokemon/active', body),
    setFavoritePokemon: (body) => mutateOnce('/v1/pokemon/favorite', body),
    evolvePokemon: (body) => mutateOnce('/v1/pokemon/evolve', body),
    learnMove: (body) => mutateOnce('/v1/pokemon/learn-move', body),
    forgetMove: (body) => mutateOnce('/v1/pokemon/forget-move', body),
    offerMove: (body) => mutateOnce('/v1/pokemon/offer-move', body),
    refuseOffer: (body) => mutateOnce('/v1/pokemon/refuse-offer', body),
    relearnMove: (body) => mutateOnce('/v1/pokemon/relearn-move', body),
    getActivePvp: ({ chatId }) =>
      request('GET', `/v1/pvp/active${q({ chatId })}`),
    openPvp: (body) => mutateOnce('/v1/pvp/open', body),
    acceptPvp: (body) => mutateOnce('/v1/pvp/accept', body),
    cancelPvp: (body) => mutateOnce('/v1/pvp/cancel', body),
    confirmPvpTurn: (body) => mutateOnce('/v1/pvp/turn', body),
    close: () => agent.destroy()
  };
}

module.exports = {
  createClient,
  trustedIdentityFromMessage,
  stableOperationId,
  assertJvmUrl
};
