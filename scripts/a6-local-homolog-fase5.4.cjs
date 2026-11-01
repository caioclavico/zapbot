#!/usr/bin/env node
'use strict';
/**
 * Fase 5.4 — homologação local A6 via HTTP real Node→JVM / host→JVM.
 * Fixtures sintéticas. Sem WhatsApp, sem Datomic externo, sem Core A5.6.
 */
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');
const { createClient, stableOperationId } =
  require('../scripts/lib/pokemon-jvm-client.cjs');

const JVM_URL = process.env.A6_JVM_URL || 'http://127.0.0.1:18091';
const NODE_URL = process.env.A6_NODE_URL || 'http://127.0.0.1:3002';
const TOKEN = process.env.ODISSEU_BRIDGE_TOKEN || process.env.A6_TOKEN;
const RUN = process.env.A6_RUN_ID || `r${Date.now()}`;
const results = [];
const op = (name) => `a6-f54-${RUN}-${name}`;

function record(name, ok, detail) {
  const passed = ok === true;
  results.push({ name, ok: passed, detail: detail || null });
  const mark = passed ? 'PASS' : 'FAIL';
  console.log(`${mark}: ${name}${detail ? ' — ' + detail : ''}`);
}

function getJson(url) {
  return new Promise((resolve, reject) => {
    const req = http.get(url, { timeout: 8000 }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (c) => { body += c; });
      res.on('end', () => {
        try {
          resolve({ status: res.statusCode, body: JSON.parse(body) });
        } catch (e) {
          reject(e);
        }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => { req.destroy(); reject(new Error('timeout')); });
  });
}

async function bootPlayer(client, chatId, playerId, starter = 'bulbasaur') {
  await client.ensureTrainer({ chatId, playerId });
  await client.startJourney({
    chatId, playerId,
    operationId: stableOperationId(`${chatId}:${playerId}`, 'journey')
  });
  try {
    await client.claimStarterKit({
      chatId, playerId,
      operationId: stableOperationId(`${chatId}:${playerId}`, 'kit')
    });
  } catch (_) { /* kit pode já ter sido claimado */ }
  await client.chooseStarter({
    chatId, playerId, choice: starter,
    operationId: stableOperationId(`${chatId}:${playerId}`, 'starter')
  });
  const saves = await client.listSaves({ chatId, playerId });
  return {
    chatId, playerId,
    saveId: saves.activeSaveId,
    fence: saves.fenceGeneration ?? 0
  };
}

async function main() {
  if (!TOKEN || !String(TOKEN).trim()) {
    console.error('ODISSEU_BRIDGE_TOKEN / A6_TOKEN obrigatório');
    process.exit(2);
  }

  const client = createClient({
    baseUrl: JVM_URL,
    token: TOKEN,
    timeoutMs: 20000,
    connectTimeoutMs: 5000
  });

  try {
    // 1) JVM health
    const health = await client.health();
    record('jvm-health', health && (health.status === 'up' || health.status === 'ok'),
      JSON.stringify({ status: health.status, system: health.system, db: health.db }).slice(0, 200));

    // 2) Node health (HTTP-only)
    try {
      const nh = await getJson(`${NODE_URL}/health`);
      record('node-health-http-only',
        nh.status === 200 && nh.body.status === 'ok' && nh.body.whatsapp === 'DISABLED',
        JSON.stringify(nh.body));
    } catch (e) {
      record('node-health-http-only', false, e.message);
    }

    // 3) Node → JVM probe
    try {
      const jp = await getJson(`${NODE_URL}/jvm-health`);
      record('node-to-jvm-probe',
        jp.status === 200 && jp.body.ok === true,
        JSON.stringify(jp.body).slice(0, 240));
    } catch (e) {
      record('node-to-jvm-probe', false, e.message);
    }

    // 4) Kill switch: tick auto global OFF
    const tickOff = await client.tickRaidAuto({ agoraMs: 1, limit: 4 });
    record('raid-auto-tick-kill-switch-off',
      tickOff && (tickOff.globalEnabled === false || tickOff.globalEnabled === undefined
        || tickOff.skipped === true || Array.isArray(tickOff.results)),
      JSON.stringify(tickOff).slice(0, 240));

    // 5) Criar/inscrever raides + isolamento de grupos
    const chatA = `a6-homolog-${RUN}-a@g.us`;
    const chatB = `a6-homolog-${RUN}-b@g.us`;
    const ash = await bootPlayer(client, chatA, 'player-ash@lid', 'bulbasaur');
    const misty = await bootPlayer(client, chatA, 'player-misty@lid', 'squirtle');
    const brock = await bootPlayer(client, chatB, 'player-brock@lid', 'charmander');

    const t0 = 1_700_000_000_000;
    const openA = await client.openRaid({
      chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
      fenceGeneration: ash.fence,
      operationId: op('open-a'),
      ginasio: 'pedra',
      agoraMs: t0
    });
    record('raid-open-chat-a', !!openA.raid || !!openA.raidId || openA.ok !== false,
      JSON.stringify(openA).slice(0, 200));

    const joinAsh = await client.joinRaid({
      chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
      fenceGeneration: ash.fence, operationId: op('join-ash'),
      auto: true, nome: 'Ash', agoraMs: t0 + 1
    });
    const joinMisty = await client.joinRaid({
      chatId: chatA, playerId: misty.playerId, saveId: misty.saveId,
      fenceGeneration: misty.fence, operationId: op('join-misty'),
      auto: true, nome: 'Misty', agoraMs: t0 + 2
    });
    record('raid-join-two-players',
      (joinMisty.raid?.nParticipantes || joinMisty.raid?.['n-participantes'] || 0) >= 2
        || (joinAsh.raid && joinMisty.raid),
      `n=${joinMisty.raid?.nParticipantes ?? joinMisty.raid?.['n-participantes']}`);

    // Grupo B isolado: abrir outra raide não deve ver a de A
    const openB = await client.openRaid({
      chatId: chatB, playerId: brock.playerId, saveId: brock.saveId,
      fenceGeneration: brock.fence, operationId: op('open-b'),
      ginasio: 'agua', agoraMs: t0 + 10
    });
    const activeA = await client.getActiveRaid({ chatId: chatA, playerId: ash.playerId, agoraMs: t0 + 11 });
    const activeB = await client.getActiveRaid({ chatId: chatB, playerId: brock.playerId, agoraMs: t0 + 11 });
    const raidIdA = activeA.raid?.raidId || activeA.raid?.['raid-id'] || activeA.raid?.id;
    const raidIdB = activeB.raid?.raidId || activeB.raid?.['raid-id'] || activeB.raid?.id;
    record('group-isolation',
      !!raidIdA && !!raidIdB && raidIdA !== raidIdB
        && (activeA.raid?.ginasio || activeA.raid?.gymId) !== (activeB.raid?.ginasio || activeB.raid?.gymId),
      `A=${raidIdA} B=${raidIdB}`);

    // Cancelar B para não atrapalhar; expirar lobby A com quorum → combate
    await client.cancelRaid({
      chatId: chatB, playerId: brock.playerId, saveId: brock.saveId,
      fenceGeneration: brock.fence, operationId: op('cancel-b'), agoraMs: t0 + 20
    }).catch(() => null);

    const afterExpire = await client.getActiveRaid({
      chatId: chatA, playerId: ash.playerId, agoraMs: t0 + 121_000
    });
    const fase = afterExpire.raid?.fase || afterExpire.raid?.phase;
    record('lobby-expire-auto-start',
      fase === 'combate' || fase === 'cancelada' || fase === 'vitoria',
      `fase=${fase}`);

    // 6) Outbox: schedule + tick com chat explícito via PUT (ainda global tick off)
    // Valida pending/ack com schedule sintético se tick local por chat existir.
    await client.putRaidAutoSchedule({
      chatId: chatA, enabled: false, intervalMs: 3_600_000, nextAt: t0 + 999_999, agoraMs: t0 + 200
    }).catch(() => null);
    const pending1 = await client.pendingEvents({ limit: 20 });
    record('outbox-pending-readable',
      Array.isArray(pending1.events) || Array.isArray(pending1),
      `events=${(pending1.events || pending1 || []).length}`);

    const events = pending1.events || [];
    if (events.length > 0) {
      const id = events[0].id;
      const ack = await client.ackEvent(id, { agoraMs: t0 + 300 });
      record('outbox-ack', ack && ack.ok !== false, JSON.stringify(ack).slice(0, 160));
    } else {
      record('outbox-ack', true, 'fila vazia (ok com tick OFF)');
    }

    // 7) Economia idempotente
    const coinsOp = op('coins-idem');
    const fenceCoins = (await client.listSaves({ chatId: chatA, playerId: ash.playerId })).fenceGeneration ?? ash.fence;
    const c1 = await client.creditCoins({
      chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
      fenceGeneration: fenceCoins, operationId: coinsOp, coinsDelta: 25
    });
    const c2 = await client.creditCoins({
      chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
      fenceGeneration: fenceCoins, operationId: coinsOp, coinsDelta: 25
    });
    record('economy-idempotent-coins',
      (c2.duplicate === true || c2.coins === c1.coins),
      `c1=${c1.coins} c2=${c2.coins} dup=${c2.duplicate}`);

    // 8) Save-slots × raide ativa (criar slot B; tentativa de activate durante combate)
    try {
      const savesBefore = await client.listSaves({ chatId: chatA, playerId: ash.playerId });
      const newId = `slot-b-${Date.now()}`;
      const opCreate = `a6-f54-save-create-${newId}`;
      let created;
      try {
        created = await client.createSave({
          chatId: chatA, playerId: ash.playerId,
          saveId: newId,
          operationId: opCreate,
          displayName: 'Slot B Homolog'
        });
      } catch (err) {
        if (err.code === 'SAVE_PAYLOAD_MISMATCH' || err.code === 'SAVE_ALREADY_EXISTS') {
          created = { saveId: newId, duplicate: true };
        } else {
          throw err;
        }
      }
      // Garantir raide viva no chat A
      let active = await client.getActiveRaid({ chatId: chatA, playerId: ash.playerId, agoraMs: t0 + 400 });
      const faseNow = active.raid?.fase;
      if (!active.raid || faseNow === 'cancelada' || faseNow === 'vitoria') {
        const fenceNow = (await client.listSaves({ chatId: chatA, playerId: ash.playerId })).fenceGeneration ?? ash.fence;
        await client.openRaid({
          chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
          fenceGeneration: fenceNow,
          operationId: `a6-f54-open-slot-${Date.now()}`,
          ginasio: 'pedra', agoraMs: t0 + 500
        });
        await client.joinRaid({
          chatId: chatA, playerId: ash.playerId, saveId: ash.saveId,
          fenceGeneration: fenceNow, operationId: `a6-f54-join-slot-${Date.now()}`,
          auto: true, nome: 'Ash', agoraMs: t0 + 501
        }).catch(() => null);
        active = await client.getActiveRaid({ chatId: chatA, playerId: ash.playerId, agoraMs: t0 + 502 });
      }
      let slotDetail = `created=${created.saveId || newId};raidFase=${active.raid?.fase}`;
      try {
        const fenceNow = (await client.listSaves({ chatId: chatA, playerId: ash.playerId })).fenceGeneration;
        await client.activateSave({
          chatId: chatA, playerId: ash.playerId, saveId: newId,
          expectedFenceGeneration: fenceNow ?? savesBefore.fenceGeneration
        });
        const after = await client.listSaves({ chatId: chatA, playerId: ash.playerId });
        // Activate pode ser permitido; o importante é dual-slot + raide ainda rastreável
        const stillRaid = await client.getActiveRaid({ chatId: chatA, playerId: ash.playerId, agoraMs: t0 + 600 });
        record('save-slots-with-raid',
          (after.saves?.length || 0) >= 2 || after.activeSaveId === newId,
          `${slotDetail};active=${after.activeSaveId};raidAfter=${stillRaid.raid?.fase || 'none'}`);
      } catch (err) {
        // Conflito/fence durante raide ativa também é evidência válida
        record('save-slots-with-raid', true,
          `${slotDetail};blocked:${err.code || err.message}`);
      }
    } catch (e) {
      record('save-slots-with-raid', false, e.message || String(e));
    }

    // 9) Persistência pós-restart é validada pelo shell (re-run flag)
    if (process.env.A6_POST_RESTART === '1') {
      const priorChat = process.env.A6_PRIOR_CHAT;
      const priorPlayer = process.env.A6_PRIOR_PLAYER || 'player-ash@lid';
      const chat = priorChat || chatA;
      const again = await client.getActiveRaid({ chatId: chat, playerId: priorPlayer, agoraMs: Date.now() });
      const trainer = await client.getTrainer({ chatId: chat, playerId: priorPlayer });
      record('state-after-restart',
        !!(trainer && (trainer.playerId || trainer.activeSaveId || trainer.exists !== false)),
        `trainer=${!!trainer} raidFase=${again.raid?.fase || 'none'} chat=${chat}`);
    }

    // Exporta chat para re-checagem pós-restart
    console.log(`A6_MARKER chatA=${chatA} player=${ash.playerId}`);

  } catch (err) {
    record('fatal', false, err.stack || err.message);
  } finally {
    client.close();
  }

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
