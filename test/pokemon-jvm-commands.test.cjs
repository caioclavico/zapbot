'use strict';

/**
 * Simula entrada de comandos !pk no adaptador JVM (sem WhatsApp real):
 * identidade confiável + present + cliente mock → envelope {messages,effects}.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {
  createClient,
  trustedIdentityFromMessage
} = require('../scripts/lib/pokemon-jvm-client.cjs');
const present = require('../scripts/lib/pokemon-jvm-present.cjs');

async function withJvm(handler, fn) {
  const server = http.createServer(handler);
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const client = createClient({
    baseUrl: `http://127.0.0.1:${server.address().port}`,
    token: 'test-token',
    timeoutMs: 2000
  });
  try {
    await fn(client);
  } finally {
    client.close();
    server.closeAllConnections();
    await new Promise(r => server.close(r));
  }
}

function fakeMessage({ chatId, playerId, requestId, args }) {
  return {
    fromMe: false,
    from: chatId,
    author: playerId,
    id: { _serialized: requestId },
    body: `!pk ${args}`
  };
}

function dispatch(client, message, argsTokens) {
  const identity = trustedIdentityFromMessage(message);
  const cmd = present.resolveCommand(argsTokens);
  if (!cmd) {
    return Promise.resolve(
      present.envelope(identity.requestId, present.formatUnavailable(argsTokens.join(' ')))
    );
  }
  const run = async () => {
    switch (cmd) {
      case 'treinador': {
        const data = await client.getTrainer(identity);
        return present.envelope(identity.requestId, present.formatTrainer(data, 'Misty'));
      }
      case 'time': {
        const data = await client.getCollection(identity);
        return present.envelope(
          identity.requestId,
          present.formatCollection(data, argsTokens.slice(1), '!pk')
        );
      }
      case 'saves': {
        const data = await client.listSaves(identity);
        return present.envelope(identity.requestId, present.formatSavesList(data));
      }
      case 'ativar': {
        const slot = argsTokens[1];
        const before = await client.listSaves(identity);
        const data = await client.activateSave({
          ...identity,
          saveId: slot,
          expectedFenceGeneration: before.fenceGeneration
        });
        return present.envelope(identity.requestId, present.formatActivate(data));
      }
      case 'iniciar': {
        const data = await client.ensureTrainer(identity);
        return present.envelope(identity.requestId, present.formatEnsure(data));
      }
      case 'cacar': {
        const plan = await client.getHuntPlan(identity);
        const block = present.formatHuntEligibility(plan);
        if (block) {
          return present.envelope(identity.requestId, block);
        }
        return present.envelope(
          identity.requestId,
          present.formatHuntStart(plan, {
            selvagem: { nome: 'Magikarp', raridade: 'comum' },
            somenteBatalha: false
          })
        );
      }
      case 'capturar': {
        const enc = await client.getEncounter(identity);
        if (argsTokens.length < 2) {
          return present.envelope(
            identity.requestId,
            present.formatCaptureMenu(enc, '!pk')
          );
        }
        const ativo = await client.getActiveSave(identity);
        const { stableOperationId } = require('../scripts/lib/pokemon-jvm-client.cjs');
        const data = await client.attemptEncounterCapture({
          ...identity,
          saveId: ativo.activeSaveId,
          fenceGeneration: ativo.fenceGeneration,
          operationId: stableOperationId(identity.requestId, 'encounter-capture'),
          bola: argsTokens.slice(1).join(' ')
        });
        return present.envelope(
          identity.requestId,
          present.formatCaptureResult(data, '!pk')
        );
      }
      default:
        return present.envelope(identity.requestId, present.formatUnavailable(cmd));
    }
  };
  return run();
}

test('comandos simulados: treinador e coleção formatados', async () => {
  await withJvm((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url.startsWith('/v1/trainer')) {
      return res.end(JSON.stringify({
        ok: true,
        data: {
          activeSaveId: 'a', fenceGeneration: 0, collectionCount: 2, ativoIndex: 0,
          profile: { nivel: 2, xp: 6, xpAtual: 1, xpNecessario: 7, coins: 10 },
          ativo: { nome: 'Squirtle', nivel: 8, shiny: true }
        }
      }));
    }
    if (req.url.startsWith('/v1/collection')) {
      return res.end(JSON.stringify({
        ok: true,
        data: {
          saveId: 'a', count: 2, trainerLevel: 2, ativoIndex: 0,
          collection: [
            { collectionIndex: 0, nome: 'Squirtle', nivel: 8, hp: 44, hpAtual: 40, raridade: 'comum', shiny: true },
            { collectionIndex: 1, nome: 'Pidgey', nivel: 4, hp: 30, hpAtual: 30, raridade: 'comum' }
          ]
        }
      }));
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ ok: false, error: { code: 'not_found', message: 'x' } }));
  }, async client => {
    const msg = fakeMessage({
      chatId: 'g@g.us', playerId: 'p@c.us', requestId: 'req-t1', args: 'treinador'
    });
    const env = await dispatch(client, msg, ['treinador']);
    assert.equal(env.messages[0].type, 'text');
    assert.match(env.messages[0].text, /Misty/);
    assert.match(env.messages[0].text, /Squirtle/);

    const env2 = await dispatch(client, msg, ['tm']);
    assert.match(env2.messages[0].text, /seu time/i);
    assert.match(env2.messages[0].text, /Pidgey/);
  });
});

test('A→B→A por comando com isolamento de jogador na identidade', async () => {
  const state = {
    'g@g.us|ash': { active: 'a', fence: 0 },
    'g@g.us|misty': { active: 'a', fence: 0 }
  };
  await withJvm((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const chatId = url.searchParams.get('chatId') || '';
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      const payload = body ? JSON.parse(body) : {};
      const playerId = url.searchParams.get('playerId') || payload.playerId;
      const key = `${chatId || payload.chatId}|${playerId}`;
      res.setHeader('Content-Type', 'application/json');
      if (req.method === 'GET' && url.pathname === '/v1/saves') {
        const s = state[key];
        return res.end(JSON.stringify({
          ok: true,
          data: {
            activeSaveId: s.active, fenceGeneration: s.fence,
            saves: [
              { saveId: 'a', coins: playerId === 'ash' ? 50 : 0, collectionCount: 1 },
              { saveId: 'b', coins: playerId === 'ash' ? 0 : 7, collectionCount: 0 }
            ]
          }
        }));
      }
      if (req.method === 'POST' && url.pathname === '/v1/saves/activate') {
        const s = state[key];
        s.fence += 1;
        s.active = payload.saveId;
        return res.end(JSON.stringify({
          ok: true,
          data: { activeSaveId: s.active, fenceGeneration: s.fence, save: { coins: 0 } }
        }));
      }
      res.statusCode = 404;
      res.end(JSON.stringify({ ok: false, error: { code: 'not_found', message: 'x' } }));
    });
  }, async client => {
    const ash = fakeMessage({ chatId: 'g@g.us', playerId: 'ash', requestId: 'r-ash', args: 'ativar b' });
    const misty = fakeMessage({ chatId: 'g@g.us', playerId: 'misty', requestId: 'r-misty', args: 'saves' });
    const a1 = await dispatch(client, ash, ['ativar', 'b']);
    assert.match(a1.messages[0].text, /\*b\*/);
    const a2 = await dispatch(client, ash, ['ativar', 'a']);
    assert.match(a2.messages[0].text, /\*a\*/);
    const m = await dispatch(client, misty, ['saves']);
    assert.match(m.messages[0].text, /ativo=a/);
    assert.match(m.messages[0].text, /\*b\*: 7 moedas/);
    // identidade não veio do texto do comando
    const id = trustedIdentityFromMessage({
      ...ash, body: '!pk ativar b playerId=hacker'
    });
    assert.equal(id.playerId, 'ash');
  });
});

test('escrita não integrada, cacar elegível e JVM indisponível', async () => {
  const msg = fakeMessage({
    chatId: 'g@g.us', playerId: 'p', requestId: 'r-x', args: 'batalhar'
  });
  const denied = await dispatch(
    { /* unused */ },
    msg,
    ['batalhar']
  );
  assert.match(denied.messages[0].text, /não está disponível/);

  await withJvm((req, res) => {
    res.setHeader('Content-Type', 'application/json');
    if (req.url.startsWith('/v1/hunt/plan')) {
      return res.end(JSON.stringify({
        ok: true,
        data: {
          area: { id: 'praia', nome: 'Praia Azul', emoji: '🏖️' },
          clima: { id: 'chuvoso', nome: 'Chuvoso', emoji: '🌧️', tipos: ['water'] },
          eligibilidade: {
            temTreinador: true, temPokemon: true, ativoVivo: true,
            cooldownOk: true, semBatalhaPropria: true, chatLivre: true
          }
        }
      }));
    }
    res.statusCode = 404;
    res.end(JSON.stringify({ ok: false, error: { code: 'not_found' } }));
  }, async client => {
    const hunt = await dispatch(client, msg, ['cacar']);
    assert.match(hunt.messages[0].text, /Praia Azul/);
    assert.match(hunt.messages[0].text, /Magikarp/);
  });

  const dead = createClient({
    baseUrl: 'http://127.0.0.1:1',
    token: 'x',
    connectTimeoutMs: 150,
    timeoutMs: 300
  });
  try {
    await assert.rejects(
      () => dead.getTrainer({ chatId: 'c', playerId: 'p' }),
      err => ['NETWORK', 'CONNECT_TIMEOUT', 'TIMEOUT', 'ECONNREFUSED'].includes(err.code)
        || /indispon/i.test(err.message)
    );
    assert.match(present.formatError({ code: 'ECONNREFUSED' }), /indisponível/);
  } finally {
    dead.close();
  }
});
