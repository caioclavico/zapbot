'use strict';

// E2E explícito: Node -> HTTP bridge -> Datomic Local.
// A fixture deve provisionar os IDs abaixo no mesmo banco temporário.
const { spawn } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { createClient, stableOperationId } = require('./lib/pokemon-jvm-client.cjs');

const root = path.resolve(__dirname, '..');
const storage = fs.mkdtempSync(path.join(os.tmpdir(), 'odisseu-trade-e2e-'));
const token = process.env.ODISSEU_BRIDGE_TOKEN || 'trade-e2e-token';
const port = Number(process.env.ODISSEU_BRIDGE_PORT || 18093);
const env = {
  ...process.env,
  ODISSEU_BRIDGE_HOST: '127.0.0.1', ODISSEU_BRIDGE_PORT: String(port),
  ODISSEU_BRIDGE_TOKEN: token, ODISSEU_BRIDGE_SEED: 'false',
  ODISSEU_BRIDGE_SYSTEM: `trade-e2e-${path.basename(storage)}`,
  ODISSEU_BRIDGE_STORAGE: storage, ODISSEU_BRIDGE_DB: 'trade-e2e',
  CLOJURE_BIN: process.env.CLOJURE_BIN || '/usr/local/bin/clojure'
};

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function main() {
  const jvm = spawn(env.CLOJURE_BIN, ['-M:bridge-http'], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
  const client = createClient({ baseUrl: `http://127.0.0.1:${port}`, token, timeoutMs: 8000 });
  const requestId = `trade-e2e-${Date.now()}`;
  const base = { chatId: process.env.TRADE_CHAT_ID || '', playerId: process.env.TRADE_PLAYER_A || '' };
  let stage = 'start';
  try {
    const check = (ok, label) => { if (!ok) throw new Error(label); };
    let healthy = false;
    for (let i = 0; i < 60 && !healthy; i++) {
      try { healthy = (await client.health()).status === 'up'; } catch (_) { await sleep(250); }
    }
    if (!healthy) throw new Error('bridge JVM não ficou healthy');

    // A criação exige uma única menção real; zero/múltiplas são rejeitadas antes do HTTP.
    const chatId = process.env.TRADE_CHAT_ID || `trade-e2e-chat-${path.basename(storage)}`;
    const playerA = process.env.TRADE_PLAYER_A || 'trade-e2e-a@c.us';
    const playerB = process.env.TRADE_PLAYER_B || 'trade-e2e-b@c.us';
    const a = { chatId, playerId: playerA };
    const b = { chatId, playerId: playerB };
    stage = 'ensure-trainers'; await client.ensureTrainer(a);
    await client.ensureTrainer(b);
    async function ensurePokemon(identity, choice) {
      let collection = await client.getCollection(identity);
      const items = collection.collection || collection.items || collection.list || [];
      if (Array.isArray(items) && items.length) return collection;
      await client.startJourney({ ...identity, operationId: stableOperationId(requestId, `start:${identity.playerId}`) });
      await client.chooseStarter({ ...identity, choice,
        operationId: stableOperationId(requestId, `starter:${identity.playerId}`) });
      collection = await client.getCollection(identity);
      const after = collection.collection || collection.items || collection.list || [];
      if (!Array.isArray(after) || !after.length) {
        throw new Error(`fixture sem Pokémon após provisionamento para ${identity.playerId}`);
      }
      return collection;
    }
    const savesA = await client.listSaves(a);
    const savesB = await client.listSaves(b);
    const colA = await ensurePokemon({ ...a, saveId: savesA.activeSaveId }, 'bulbasaur');
    const colB = await ensurePokemon({ ...b, saveId: savesB.activeSaveId }, 'charmander');
    const itemsA = colA.collection || colA.items || colA.list || [];
    const itemsB = colB.collection || colB.items || colB.list || [];
    const pokemonA = process.env.TRADE_POKEMON_A || itemsA[0].idPokemon || itemsA[0].pokemonId || itemsA[0].id;
    const pokemonB = process.env.TRADE_POKEMON_B || itemsB[0].idPokemon || itemsB[0].pokemonId || itemsB[0].id;
    check(pokemonA && pokemonB, 'fixture sem Pokémon');
    const snapshotA = itemsA.find(p => String(p.idPokemon || p.pokemonId || p.id) === String(pokemonA));
    const snapshotB = itemsB.find(p => String(p.idPokemon || p.pokemonId || p.id) === String(pokemonB));
    check(snapshotA && snapshotB, 'snapshot ausente');
    const mentionedIds = [playerB];
    if (mentionedIds.length !== 1) throw new Error('menção ambígua');
    stage = 'trade-create'; const create = await client.createTrade({
      ...a, playerA, playerB,
      saveA: process.env.TRADE_SAVE_A || savesA.activeSaveId,
      saveB: process.env.TRADE_SAVE_B || savesB.activeSaveId,
      fenceA: Number(process.env.TRADE_FENCE_A ?? savesA.fenceGeneration),
      fenceB: Number(process.env.TRADE_FENCE_B ?? savesB.fenceGeneration),
      fenceGeneration: Number(process.env.TRADE_FENCE_A ?? savesA.fenceGeneration),
      pokemonA, pokemonB,
      operationId: stableOperationId(requestId, 'trade-create')
    });
    const tradeId = create.tradeId || create.id;
    if (!tradeId) throw new Error('bridge não retornou tradeId');
    stage = 'trade-list'; const listed = await client.listTrades(a);
    if (!listed || !Array.isArray(listed.trades)) throw new Error('listagem inválida');
    stage = 'trade-accept'; const accepted = await client.acceptTrade({
      ...b, tradeId,
      saveId: process.env.TRADE_SAVE_B || savesB.activeSaveId,
      fenceGeneration: Number(process.env.TRADE_FENCE_B ?? savesB.fenceGeneration),
      operationId: stableOperationId(requestId, 'trade-accept')
    });
    if (!accepted) throw new Error('aceite ausente');
    const completeBody = {
      ...a, tradeId, saveId: process.env.TRADE_SAVE_A || savesA.activeSaveId,
      fenceGeneration: Number(process.env.TRADE_FENCE_A ?? savesA.fenceGeneration), pokemonA, pokemonB,
      now: 1700000000000,
      operationId: stableOperationId(requestId, 'trade-complete')
    };
    stage = 'trade-complete'; const completed = await client.completeTrade(completeBody);
    stage = 'trade-complete-retry'; const retry = await client.completeTrade(completeBody);
    const finalAData = await client.getCollection({ ...a, saveId: savesA.activeSaveId });
    const finalBData = await client.getCollection({ ...b, saveId: savesB.activeSaveId });
    const finalA = finalAData.collection || finalAData.items || finalAData.list || [];
    const finalB = finalBData.collection || finalBData.items || finalBData.list || [];
    const idOf = p => String(p.idPokemon || p.pokemonId || p.id);
    const movedA = finalB.find(p => idOf(p) === String(pokemonA));
    const movedB = finalA.find(p => idOf(p) === String(pokemonB));
    check(movedA, 'Bulbasaur não foi transferido para B');
    check(movedB, 'Charmander não foi transferido para A');
    check(!finalA.some(p => idOf(p) === String(pokemonA)), 'Bulbasaur duplicado ou retido em A');
    check(!finalB.some(p => idOf(p) === String(pokemonB)), 'Charmander duplicado ou retido em B');
    for (const key of ['idPokemon', 'pokemonId', 'id', 'species', 'nome', 'nivel', 'hp', 'hpAtual', 'golpes', 'shiny', 'iv']) {
      if (snapshotA[key] !== undefined) check(JSON.stringify(movedA[key]) === JSON.stringify(snapshotA[key]), `atributo A alterado: ${key}`);
      if (snapshotB[key] !== undefined) check(JSON.stringify(movedB[key]) === JSON.stringify(snapshotB[key]), `atributo B alterado: ${key}`);
    }
    async function independentPair(tag, firstChoice, secondChoice) {
      const pa = { chatId, playerId: `trade-e2e-${tag}-a@c.us` };
      const pb = { chatId, playerId: `trade-e2e-${tag}-b@c.us` };
      await client.ensureTrainer(pa); await client.ensureTrainer(pb);
      const sa = await client.listSaves(pa); const sb = await client.listSaves(pb);
      const ca = await ensurePokemon({ ...pa, saveId: sa.activeSaveId }, firstChoice);
      const cb = await ensurePokemon({ ...pb, saveId: sb.activeSaveId }, secondChoice);
      const ia = ca.collection || ca.items || ca.list || [];
      const ib = cb.collection || cb.items || cb.list || [];
      const idA = ia[0].idPokemon || ia[0].pokemonId || ia[0].id;
      const idB = ib[0].idPokemon || ib[0].pokemonId || ib[0].id;
      check(idA && idB, `fixture ${tag} sem Pokémon`);
      return { pa, pb, sa, sb, idA, idB };
    }
    async function proposal(pair, tag) {
      const operationId = stableOperationId(requestId, `trade-${tag}-create`);
      const result = await client.createTrade({
        ...pair.pa, playerA: pair.pa.playerId, playerB: pair.pb.playerId,
        saveA: pair.sa.activeSaveId, saveB: pair.sb.activeSaveId,
        fenceA: pair.sa.fenceGeneration, fenceB: pair.sb.fenceGeneration,
        fenceGeneration: pair.sa.fenceGeneration, pokemonA: pair.idA, pokemonB: pair.idB,
        operationId, now: 1700000000000
      });
      return result.tradeId || result.id;
    }
    const refusePair = await independentPair('refuse', 'bulbasaur', 'squirtle');
    const refuseId = await proposal(refusePair, 'refuse');
    stage = 'trade-refuse';
    const refused = await client.refuseTrade({ ...refusePair.pb, tradeId: refuseId,
      saveId: refusePair.sb.activeSaveId, fenceGeneration: refusePair.sb.fenceGeneration,
      operationId: stableOperationId(requestId, 'trade-refuse-action'), now: 1700000000001 });
    check(refused && refused.state === 'refused', 'recusa não persistida');
    try {
      await client.acceptTrade({ ...refusePair.pb, tradeId: refuseId,
        saveId: refusePair.sb.activeSaveId, fenceGeneration: refusePair.sb.fenceGeneration,
        operationId: stableOperationId(requestId, 'trade-refuse-after-accept'), now: 1700000000002 });
      throw new Error('proposta recusada foi aceita');
    } catch (err) { check(err.code === 'INVALID_TRADE_TRANSITION' || err.status === 409, `aceite após recusa: ${err.code}`); }
    const cancelPair = await independentPair('cancel', 'charmander', 'squirtle');
    const cancelId = await proposal(cancelPair, 'cancel');
    stage = 'trade-cancel';
    const cancelled = await client.cancelTrade({ ...cancelPair.pa, tradeId: cancelId,
      saveId: cancelPair.sa.activeSaveId, fenceGeneration: cancelPair.sa.fenceGeneration,
      operationId: stableOperationId(requestId, 'trade-cancel-action'), now: 1700000000001 });
    check(cancelled && cancelled.state === 'cancelled', 'cancelamento não persistido');
    try {
      await client.acceptTrade({ ...cancelPair.pb, tradeId: cancelId,
        saveId: cancelPair.sb.activeSaveId, fenceGeneration: cancelPair.sb.fenceGeneration,
        operationId: stableOperationId(requestId, 'trade-cancel-after-accept'), now: 1700000000002 });
      throw new Error('proposta cancelada foi aceita');
    } catch (err) { check(err.code === 'INVALID_TRADE_TRANSITION' || err.status === 409, `aceite após cancelamento: ${err.code}`); }
    const expirePair = await independentPair('expire', 'bulbasaur', 'squirtle');
    const expireId = await proposal(expirePair, 'expire');
    stage = 'trade-expire';
    try {
      await client.acceptTrade({ ...expirePair.pb, tradeId: expireId,
        saveId: expirePair.sb.activeSaveId, fenceGeneration: expirePair.sb.fenceGeneration,
        operationId: stableOperationId(requestId, 'trade-expire-accept'), now: 1700000000000 + 301000 });
      throw new Error('proposta expirada foi aceita');
    } catch (err) { check(err.code === 'TRADE_EXPIRED', `expiração não rejeitada (${err.code})`); }
    const unauthorizedPair = await independentPair('unauthorized', 'charmander', 'squirtle');
    const unauthorized = { chatId, playerId: 'trade-e2e-unauthorized@c.us' };
    await client.ensureTrainer(unauthorized);
    const unauthorizedSaves = await client.listSaves(unauthorized);
    const unauthorizedOps = [
      ['accept', async () => client.acceptTrade({ ...unauthorized, tradeId: await proposal(unauthorizedPair, 'unauthorized'), saveId: unauthorizedSaves.activeSaveId, fenceGeneration: unauthorizedSaves.fenceGeneration, operationId: stableOperationId(requestId, 'unauthorized-accept'), now: 1700000000001 })],
      ['cancel', async () => client.cancelTrade({ ...unauthorized, tradeId: await proposal(unauthorizedPair, 'unauthorized-cancel'), saveId: unauthorizedSaves.activeSaveId, fenceGeneration: unauthorizedSaves.fenceGeneration, operationId: stableOperationId(requestId, 'unauthorized-cancel'), now: 1700000000001 })]
    ];
    for (const [label, action] of unauthorizedOps) {
      try { await action(); throw new Error(`terceiro conseguiu ${label}`); }
      catch (err) { check(err.code === 'TRADE_FORBIDDEN', `terceiro ${label}: ${err.code}`); }
    }
    const completeUnauthorizedId = await proposal(unauthorizedPair, 'unauthorized-complete');
    await client.acceptTrade({ ...unauthorizedPair.pb, tradeId: completeUnauthorizedId,
      saveId: unauthorizedPair.sb.activeSaveId, fenceGeneration: unauthorizedPair.sb.fenceGeneration,
      operationId: stableOperationId(requestId, 'unauthorized-complete-accept'), now: 1700000000001 });
    try {
      await client.completeTrade({ ...unauthorized, tradeId: completeUnauthorizedId,
        saveId: unauthorizedSaves.activeSaveId, fenceGeneration: unauthorizedSaves.fenceGeneration,
        pokemonA: unauthorizedPair.idA, pokemonB: unauthorizedPair.idB,
        operationId: stableOperationId(requestId, 'unauthorized-complete'), now: 1700000000002 });
      throw new Error('terceiro conseguiu concluir');
    } catch (err) { check(err.code === 'TRADE_FORBIDDEN' || err.code === 'INVALID_TRADE', `terceiro complete: ${err.code}`); }
    stage = 'payload-mismatch';
    try {
      await client.completeTrade({ ...completeBody, pokemonB: `${pokemonB}-different` });
      throw new Error('payload divergente foi aceito');
    } catch (err) {
      check(err.code === 'RECEIPT_PAYLOAD_MISMATCH' || err.status === 409,
        `payload divergente não rejeitado (${err.code || err.status})`);
    }
    stage = 'jvm-unavailable';
    const dead = createClient({ baseUrl: 'http://127.0.0.1:1', token, connectTimeoutMs: 200, timeoutMs: 400 });
    try {
      await dead.listTrades(a);
      throw new Error('JVM indisponível foi tratada como sucesso');
    } catch (err) {
      check(['NETWORK', 'CONNECT_TIMEOUT', 'TIMEOUT', 'ECONNREFUSED'].includes(err.code),
        `erro inesperado com JVM indisponível (${err.code})`);
    } finally { dead.close(); }
    console.log(JSON.stringify({ ok: true, tradeId, listed: listed.trades.length,
      completed: !!completed, idempotentRetry: !!retry,
      refuse: { state: refused.state, rejectedAfter: true },
      cancel: { state: cancelled.state, rejectedAfter: true },
      expire: { rejected: true }, unauthorized: { rejected: true } }, null, 2));
  } catch (err) {
    err.stage = stage;
    throw err;
  } finally {
    client.close();
    if (jvm.pid) { try { jvm.kill('SIGTERM'); } catch (_) {} await sleep(500); try { jvm.kill('SIGKILL'); } catch (_) {} }
    fs.rmSync(storage, { recursive: true, force: true });
  }
}
main().catch(err => {
  console.error(JSON.stringify({ stage: err.stage || 'unknown', code: err.code, message: err.message,
    stack: err.stack }, null, 2));
  process.exitCode = 1;
});
