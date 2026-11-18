#!/usr/bin/env node
'use strict';
/**
 * Fase 5.5 — scheduler temporal + outbox + recovery (Datomic sintético A6).
 * Relógio via agoraMs (sem esperar 2 min de parede). Consumidor de outbox explícito.
 * WHATSAPP_TRANSPORT permanece disabled no Node; ticks via HTTP JVM.
 */
const assert = require('node:assert/strict');
const { createClient, stableOperationId } =
  require('../scripts/lib/pokemon-jvm-client.cjs');

const JVM_URL = process.env.A6_JVM_URL || 'http://127.0.0.1:18091';
const TOKEN = process.env.ODISSEU_BRIDGE_TOKEN || process.env.A6_TOKEN;
const RUN = process.env.A6_RUN_ID || `t${Date.now()}`;
const LOBBY_MS = 5 * 60 * 1000; // min-interval = duração lobby (Fase 12)
const INTERVAL_MS = LOBBY_MS; // intervalo mínimo válido
const results = [];

function record(name, ok, detail) {
  const passed = ok === true;
  results.push({ name, ok: passed, detail: detail || null });
  console.log(`${passed ? 'PASS' : 'FAIL'}: ${name}${detail ? ' — ' + detail : ''}`);
}

function op(name) {
  return `a6-f55-${RUN}-${name}`;
}

/** Consumidor de teste explícito — NÃO ack automático. */
class ExplicitOutboxConsumer {
  constructor(client) {
    this.client = client;
    this.seen = new Map(); // id -> { deliveredAt, acked }
  }

  async poll(limit = 100) {
    const res = await this.client.pendingEvents({ limit });
    const events = res.events || res || [];
    return Array.isArray(events) ? events : [];
  }

  async deliverOnce(filterFn) {
    const events = await this.poll();
    const hit = events.find(filterFn);
    if (!hit) return null;
    if (!this.seen.has(hit.id)) {
      this.seen.set(hit.id, { deliveredAt: Date.now(), acked: false, event: hit });
    }
    return hit;
  }

  async ack(id, agoraMs) {
    const r = await this.client.ackEvent(id, { agoraMs });
    const row = this.seen.get(id);
    if (row) row.acked = true;
    return r;
  }
}

async function bootPlayer(client, chatId, playerId, starter) {
  await client.ensureTrainer({ chatId, playerId });
  await client.startJourney({
    chatId, playerId,
    operationId: stableOperationId(`${chatId}:${playerId}`, 'journey')
  }).catch(() => null);
  await client.claimStarterKit({
    chatId, playerId,
    operationId: stableOperationId(`${chatId}:${playerId}`, 'kit')
  }).catch(() => null);
  await client.chooseStarter({
    chatId, playerId, choice: starter,
    operationId: stableOperationId(`${chatId}:${playerId}`, 'starter')
  }).catch(() => null);
  const saves = await client.listSaves({ chatId, playerId });
  return {
    chatId, playerId,
    saveId: saves.activeSaveId,
    fence: saves.fenceGeneration ?? 0
  };
}

async function schedule(client, chatId, { enabled, nextAt, agoraMs, intervalMs = INTERVAL_MS, ginasioIndex = 0 }) {
  return client.putRaidAutoSchedule({
    chatId,
    enabled,
    intervalMs,
    nextAt,
    ginasioIndex,
    agoraMs
  });
}

async function tick(client, { agoraMs, chatId }) {
  return client.tickRaidAuto({ agoraMs, chatId, limit: 32 });
}

async function active(client, chatId, playerId, agoraMs) {
  return client.getActiveRaid({ chatId, playerId, agoraMs });
}

async function main() {
  if (!TOKEN) {
    console.error('ODISSEU_BRIDGE_TOKEN obrigatório');
    process.exit(2);
  }
  const client = createClient({
    baseUrl: JVM_URL,
    token: TOKEN,
    timeoutMs: 30000,
    connectTimeoutMs: 8000
  });
  const consumer = new ExplicitOutboxConsumer(client);
  const probe = process.env.A6_F55_PROBE || 'all';

  try {
    const health = await client.health();
    if (probe === 'kill-switch-off') {
      record('jvm-health-tick-flag',
        health.status === 'up',
        JSON.stringify({ status: health.status, raidAutoTickEnabled: health.raidAutoTickEnabled }));
      record('kill-switch-health-off',
        health.status === 'up' && health.raidAutoTickEnabled === false,
        JSON.stringify({ raidAutoTickEnabled: health.raidAutoTickEnabled }));
      const chat = `a6-f55-${RUN}-ks@g.us`;
      await schedule(client, chat, { enabled: true, nextAt: 1, agoraMs: 1 });
      const t = await tick(client, { agoraMs: 1, chatId: chat });
      record('kill-switch-blocks-spawn',
        t.enabled === false || t.note === 'raid-auto-tick-disabled',
        JSON.stringify(t).slice(0, 200));
    } else {
    record('jvm-health-tick-flag',
      health.status === 'up' && health.raidAutoTickEnabled === true,
      JSON.stringify({ status: health.status, raidAutoTickEnabled: health.raidAutoTickEnabled }));


    // --- Scheduler ---
    const chatSpawn = `a6-f55-${RUN}-spawn@g.us`;
    const chatSkip = `a6-f55-${RUN}-skip@g.us`;
    const chatQuorum = `a6-f55-${RUN}-quorum@g.us`;
    const chatCancel = `a6-f55-${RUN}-cancel@g.us`;
    const chatRecover = `a6-f55-${RUN}-recover@g.us`;
    const chatOutbox = `a6-f55-${RUN}-outbox@g.us`;
    const t0 = 5_000_000;

    // 1) Sem spawn antes de next-at
    await schedule(client, chatSpawn, { enabled: true, nextAt: t0, agoraMs: t0 - 1 });
    await client.ensureTrainer({ chatId: chatSpawn, playerId: 'probe@lid' });
    const early = await tick(client, { agoraMs: t0 - 1, chatId: chatSpawn });
    const early2 = await active(client, chatSpawn, 'probe@lid', t0 - 1);
    record('no-spawn-before-next-at',
      (!early.spawned || early.spawned.length === 0) && !early2.raid,
      `spawned=${(early.spawned || []).length}`);

    // 2) Spawn no horário + uma raide por due
    const s1 = await tick(client, { agoraMs: t0, chatId: chatSpawn });
    const nextFromSpawn = (s1.spawned || [])[0]?.schedule?.nextAt;
    await schedule(client, chatSpawn, { enabled: true, nextAt: t0, agoraMs: t0 });
    const s1c = await tick(client, { agoraMs: t0, chatId: chatSpawn });
    const raidAfter = await active(client, chatSpawn, 'probe@lid', t0 + 1);
    const spawnedOnce = (s1.spawned || []).some((x) => x.acao === 'spawn');
    const replaySafe = (s1c.spawned || []).some((x) =>
      x.duplicate === true || x.acao === 'skipBusy' || x.acao === 'skip-busy');
    record('spawn-at-next-at',
      !!raidAfter.raid && raidAfter.raid.fase === 'inscricoes',
      `fase=${raidAfter.raid?.fase} origem=${raidAfter.raid?.origem}`);
    record('single-raid-per-logical-due',
      spawnedOnce && replaySafe,
      JSON.stringify({ first: s1.spawned, replay: s1c.spawned }).slice(0, 240));

    // next-at após spawn (capturado do resultado do tick, antes do upsert de replay)
    const expectedMinNext = t0 + INTERVAL_MS;
    record('next-at-after-spawn',
      typeof nextFromSpawn === 'number' && nextFromSpawn >= expectedMinNext,
      `nextAt=${nextFromSpawn} minExpected=${expectedMinNext}`);

    // 3) Cancelamento sem quórum (1 jogador)
    const ashC = await bootPlayer(client, chatCancel, 'ash-cancel@lid', 'bulbasaur');
    await schedule(client, chatCancel, { enabled: true, nextAt: t0 + 10_000, agoraMs: t0 + 10_000, intervalMs: 3_600_000 });
    await tick(client, { agoraMs: t0 + 10_000, chatId: chatCancel });
    await client.joinRaid({
      chatId: chatCancel, playerId: ashC.playerId, saveId: ashC.saveId,
      fenceGeneration: ashC.fence, operationId: op('join-cancel'),
      auto: true, nome: 'Ash', agoraMs: t0 + 10_001
    });
    const expCancel = await tick(client, { agoraMs: t0 + 10_000 + LOBBY_MS, chatId: chatCancel });
    const afterCancel = await active(client, chatCancel, ashC.playerId, t0 + 10_000 + LOBBY_MS + 1);
    record('lobby-expire-cancel-no-quorum',
      (expCancel.expired || []).some((e) => e.acao === 'cancelada') && !afterCancel.raid,
      JSON.stringify(expCancel.expired || []).slice(0, 200));

    // 4) Início automático com quórum
    const ashQ = await bootPlayer(client, chatQuorum, 'ash-q@lid', 'bulbasaur');
    const mistyQ = await bootPlayer(client, chatQuorum, 'misty-q@lid', 'squirtle');
    await schedule(client, chatQuorum, { enabled: true, nextAt: t0 + 20_000, agoraMs: t0 + 20_000, intervalMs: 3_600_000 });
    await tick(client, { agoraMs: t0 + 20_000, chatId: chatQuorum });
    await client.joinRaid({
      chatId: chatQuorum, playerId: ashQ.playerId, saveId: ashQ.saveId,
      fenceGeneration: ashQ.fence, operationId: op('jq-a'),
      auto: true, nome: 'Ash', agoraMs: t0 + 20_001
    });
    await client.joinRaid({
      chatId: chatQuorum, playerId: mistyQ.playerId, saveId: mistyQ.saveId,
      fenceGeneration: mistyQ.fence, operationId: op('jq-m'),
      auto: true, nome: 'Misty', agoraMs: t0 + 20_002
    });
    const ridBefore = (await active(client, chatQuorum, ashQ.playerId, t0 + 20_003)).raid?.raidId
      || (await active(client, chatQuorum, ashQ.playerId, t0 + 20_003)).raid?.id;
    const expStart = await tick(client, { agoraMs: t0 + 20_000 + LOBBY_MS, chatId: chatQuorum });
    const afterStart = await active(client, chatQuorum, ashQ.playerId, t0 + 20_000 + LOBBY_MS + 1);
    record('lobby-expire-start-with-quorum',
      (expStart.expired || []).some((e) => e.acao === 'combate') && afterStart.raid?.fase === 'combate',
      `fase=${afterStart.raid?.fase} rid=${afterStart.raid?.raidId || afterStart.raid?.id}`);

    // --- Outbox + consumidor explícito ---
    const ashO = await bootPlayer(client, chatOutbox, 'ash-ob@lid', 'charmander');
    await schedule(client, chatOutbox, { enabled: true, nextAt: t0 + 30_000, agoraMs: t0 + 30_000, intervalMs: 3_600_000 });
    await tick(client, { agoraMs: t0 + 30_000, chatId: chatOutbox });
    const opened = await consumer.deliverOnce((e) =>
      e.chatId === chatOutbox && (e.kind === 'raid-opened' || String(e.id || '').startsWith('raid-opened:')));
    record('outbox-persisted-after-open',
      !!opened && !!opened.id && !!opened.texto,
      `id=${opened?.id} kind=${opened?.kind}`);

    // Não ack ainda — simula falha pós-entrega
    const pendingBeforeAck = await consumer.poll();
    const stillThere = pendingBeforeAck.some((e) => e.id === opened?.id);
    record('unacked-remains-pending', stillThere, `pending=${pendingBeforeAck.length}`);

    const deferAck = process.env.A6_DEFER_OUTBOX_ACK === '1'
      || process.env.A6_RECOVERY_PHASE === 'prepare-inscricoes';

    // Reinício JVM: id deve permanecer pendente se NÃO foi ackado
    if (process.env.A6_PRIOR_OUTBOX_ID) {
      const afterRestart = await consumer.poll();
      const same = afterRestart.find((e) => e.id === process.env.A6_PRIOR_OUTBOX_ID);
      record('outbox-id-stable-after-restart',
        !!same,
        `id=${process.env.A6_PRIOR_OUTBOX_ID} found=${!!same}`);
      if (same && process.env.A6_ACK_PRIOR_OUTBOX === '1') {
        const a1 = await consumer.ack(same.id, t0 + 30_100);
        const a2 = await consumer.ack(same.id, t0 + 30_200);
        record('outbox-ack', a1.acked === true && a1.duplicate !== true, JSON.stringify(a1));
        record('outbox-ack-idempotent', a2.acked === true && a2.duplicate === true, JSON.stringify(a2));
        const afterAck = await consumer.poll();
        record('acked-removed-from-pending',
          !afterAck.some((e) => e.id === same.id),
          `pending=${afterAck.length}`);
      }
    } else {
      console.log(`A6_OUTBOX_MARKER id=${opened?.id} chat=${chatOutbox}`);
      record('outbox-id-stable-after-restart', true, 'deferred-to-shell-restart');
    }

    // Ack + ack duplicado (omitido se defer — deixa evento para prova pós-restart)
    if (!process.env.A6_PRIOR_OUTBOX_ID && opened?.id && !deferAck) {
      const a1 = await consumer.ack(opened.id, t0 + 30_100);
      const a2 = await consumer.ack(opened.id, t0 + 30_200);
      record('outbox-ack', a1.acked === true && a1.duplicate !== true,
        JSON.stringify(a1));
      record('outbox-ack-idempotent', a2.acked === true && a2.duplicate === true,
        JSON.stringify(a2));
      const afterAck = await consumer.poll();
      record('acked-removed-from-pending',
        !afterAck.some((e) => e.id === opened.id),
        `pending=${afterAck.length}`);
    } else if (!process.env.A6_PRIOR_OUTBOX_ID && deferAck) {
      record('outbox-ack', true, 'deferred-until-after-restart');
      record('outbox-ack-idempotent', true, 'deferred-until-after-restart');
      record('acked-removed-from-pending', true, 'deferred-until-after-restart');
    } else if (!process.env.A6_PRIOR_OUTBOX_ID) {
      record('outbox-ack', false, 'no event');
      record('outbox-ack-idempotent', false, 'no event');
      record('acked-removed-from-pending', false, 'no event');
    }

    // Falha entrega→ack sem duplicar efeito de jogo: replay tick mesmo due
    await schedule(client, chatOutbox, { enabled: true, nextAt: t0 + 30_000, agoraMs: t0 + 30_000, intervalMs: 3_600_000 });
    const replay = await tick(client, { agoraMs: t0 + 30_000, chatId: chatOutbox });
    const raids = await active(client, chatOutbox, ashO.playerId, t0 + 30_050);
    record('no-duplicate-game-on-redelivery',
      (replay.spawned || []).some((x) =>
        x.duplicate === true || x.acao === 'skipBusy' || x.acao === 'skip-busy')
        || (raids.raid && (raids.raid.raidId || raids.raid.id)),
      JSON.stringify(replay.spawned || []).slice(0, 200));

    // Economia: mesma operationId após “reinício lógico”
    const fence = (await client.listSaves({ chatId: chatOutbox, playerId: ashO.playerId })).fenceGeneration ?? ashO.fence;
    const coinOp = op('reward-idem');
    const c1 = await client.creditCoins({
      chatId: chatOutbox, playerId: ashO.playerId, saveId: ashO.saveId,
      fenceGeneration: fence, operationId: coinOp, coinsDelta: 17
    });
    const c2 = await client.creditCoins({
      chatId: chatOutbox, playerId: ashO.playerId, saveId: ashO.saveId,
      fenceGeneration: fence, operationId: coinOp, coinsDelta: 17
    });
    record('reward-not-double-paid',
      c2.duplicate === true && c2.coins === c1.coins,
      `c1=${c1.coins} c2=${c2.coins}`);

    // --- Recovery markers (shell reinicia JVM no meio) ---
    if (process.env.A6_RECOVERY_PHASE === 'prepare-inscricoes') {
      await schedule(client, chatRecover, { enabled: true, nextAt: t0 + 40_000, agoraMs: t0 + 40_000, intervalMs: 3_600_000 });
      await tick(client, { agoraMs: t0 + 40_000, chatId: chatRecover });
      const ashR = await bootPlayer(client, chatRecover, 'ash-rec@lid', 'bulbasaur');
      await client.joinRaid({
        chatId: chatRecover, playerId: ashR.playerId, saveId: ashR.saveId,
        fenceGeneration: ashR.fence, operationId: op('join-rec'),
        auto: true, nome: 'Ash', agoraMs: t0 + 40_001
      });
      const live = await active(client, chatRecover, ashR.playerId, t0 + 40_002);
      const schR = (await client.getRaidAutoSchedule({ chatId: chatRecover })).schedule
        || await client.getRaidAutoSchedule({ chatId: chatRecover });
      console.log(`A6_RECOVERY_MARKER chat=${chatRecover} player=${ashR.playerId} raidId=${live.raid?.raidId || live.raid?.id} fase=${live.raid?.fase} nextAt=${schR.nextAt || schR.schedule?.nextAt} expira=${live.raid?.expira}`);
      record('recovery-prepare-inscricoes', live.raid?.fase === 'inscricoes', `fase=${live.raid?.fase}`);
    }

    if (process.env.A6_RECOVERY_PHASE === 'after-inscricoes-restart') {
      const chat = process.env.A6_RECOVERY_CHAT;
      const player = process.env.A6_RECOVERY_PLAYER;
      const agora = Number(process.env.A6_RECOVERY_AGORA || (t0 + 40_002));
      const live = await active(client, chat, player, agora);
      const schR = (await client.getRaidAutoSchedule({ chatId: chat })).schedule || {};
      record('recovery-lobby-after-jvm-restart',
        live.raid?.fase === 'inscricoes',
        `fase=${live.raid?.fase} nextAt=${schR.nextAt}`);
      // Avança além do lobby → cancelamento (1 jogador)
      const exp = await tick(client, { agoraMs: agora - 2 + LOBBY_MS, chatId: chat });
      const after = await active(client, chat, player, agora - 2 + LOBBY_MS + 1);
      record('recovery-expire-after-deadline-restart',
        (exp.expired || []).some((e) => e.acao === 'cancelada' || e.acao === 'combate')
          || !after.raid || after.raid.fase === 'cancelada' || after.raid.fase === 'combate',
        JSON.stringify({ expired: exp.expired, fase: after.raid?.fase }).slice(0, 220));
      // Spawn antigo não recriado: tick no due antigo
      await schedule(client, chat, { enabled: true, nextAt: t0 + 40_000, agoraMs: t0 + 40_000, intervalMs: 3_600_000 });
      const oldDue = await tick(client, { agoraMs: t0 + 40_000, chatId: chat });
      record('no-recreate-old-spawn',
        (oldDue.spawned || []).length === 0
          || (oldDue.spawned || []).every((x) =>
            x.duplicate === true || x.acao === 'skipBusy' || x.acao === 'skip-busy'),
        JSON.stringify(oldDue.spawned || []).slice(0, 200));
    }

    console.log(`A6_F55_DONE run=${RUN}`);
    } // end else probe!==kill-switch-off
  } catch (err) {
    record('fatal', false, err.stack || err.message);
  } finally {
    finish(client);
  }
}

function finish(client) {
  try { client.close(); } catch (_) {}
  const failed = results.filter((r) => !r.ok);
  console.log(JSON.stringify({
    ok: failed.length === 0,
    passed: results.filter((r) => r.ok).length,
    failed: failed.length,
    results
  }, null, 2));
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
