'use strict';

const fs = require('node:fs');
const { idempotencyKey } = require('./release-announcer.cjs');
const { ReleaseAnnouncerRuntime } = require('./release-runtime.cjs');
const { createReleaseTransport } = require('./whatsapp-release-transport.cjs');

function readIntent(path) {
  if (typeof path !== 'string' || !path.trim()) return null;
  try { return JSON.parse(fs.readFileSync(path, 'utf8')); }
  catch (_) { return null; }
}

function validIntent(intent) {
  const release = intent?.release;
  return intent?.status === 'confirmed' && intent.rollbackComplete === true &&
    Boolean(release && typeof release.version === 'string' && release.version.trim() &&
      typeof release.contentHash === 'string' && release.contentHash.trim() &&
      Array.isArray(release.highlights) && release.highlights.every(x => typeof x === 'string')) &&
    intent.deployedVersion === release.version && intent.confirmedVersion === release.version;
}

function createReleaseBootstrap({
  enabled = false, intentPath = '', pilotChatId = '', isChatAuthorized = () => false,
  client, platform, checkJvmHealth = async () => false, isWhatsAppReady = () => false,
  isNodeHealthy = () => true, workerId = `release-node-${process.pid}`,
  logger = () => {}
} = {}) {
  const runtime = new ReleaseAnnouncerRuntime({ enabled, pilotChatId, isChatAuthorized, logger });
  const transport = enabled && client && platform
    ? createReleaseTransport({ client, platform, logger })
    : null;
  let inFlight = null;

  async function onWhatsAppReady() {
    if (!enabled) return { status: 'disabled' };
    if (inFlight) return inFlight;
    inFlight = (async () => {
      const intent = readIntent(intentPath);
      if (!intent) return { status: 'no-release-intent' };
      if (!validIntent(intent)) return { status: 'invalid-release-intent' };
      const release = intent.release;
      if (release.announcementPolicy?.enabled === false) return { status: 'release-disabled' };
      const chatId = String(intent.chatId || '');
      if (!chatId || chatId !== String(pilotChatId) || !isChatAuthorized(chatId)) {
        return { status: 'group-not-authorized' };
      }
      if (!client || !platform || !transport) return { status: 'delivery-unavailable' };

      let jvmHealthy = false;
      try { jvmHealthy = (await checkJvmHealth()) === true; } catch (_) {}
      const whatsappReady = isWhatsAppReady() === true;
      const nodeHealthy = isNodeHealthy() === true;
      if (!jvmHealthy || !whatsappReady || !nodeHealthy) return { status: 'not-ready' };

      const key = idempotencyKey({ version: release.version, contentHash: release.contentHash, chatId });
      let found;
      try { found = (await platform.getReleaseAnnouncement(key)).announcement; }
      catch (error) {
        logger({ event: 'release_store_unavailable', code: error.code || 'bridge_error' });
        return { status: 'not-ready' };
      }
      if (found?.status === 'completed') return { status: 'already-completed' };

      await transport.reconcileBuffered();
      const result = await runtime.publishConfirmed({
        intent,
        gates: { jvmHealthy, nodeHealthy, whatsappReady, datomicAccessible: true,
          versionConfirmed: true, rollbackComplete: true },
        platform, transport, workerId, operationId: `release:${key}`
      });
      logger({ event: 'release_announcement_attempt', status: result.status });
      return result;
    })().catch(error => {
      logger({ event: 'release_announcer_error', code: error.code || 'announcement_error' });
      return { status: 'isolated-error' };
    }).finally(() => { inFlight = null; });
    return inFlight;
  }

  return { onWhatsAppReady, transport };
}

module.exports = { createReleaseBootstrap, readIntent, validIntent };
