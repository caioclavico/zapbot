'use strict';

const { idempotencyKey, ready, render } = require('./release-announcer.cjs');

class ReleaseAnnouncerRuntime {
  constructor({ enabled = false, pilotChatId = '', isChatAuthorized = () => false, logger = () => {} } = {}) {
    this.enabled = enabled === true;
    this.pilotChatId = String(pilotChatId || '');
    this.isChatAuthorized = isChatAuthorized;
    this.logger = logger;
  }

  async publishConfirmed({ intent, gates = {}, platform, transport, workerId, operationId } = {}) {
    if (!this.enabled) return { status: 'disabled' };
    if (!intent || intent.status !== 'confirmed' || intent.rollbackComplete !== true) return { status: 'no-confirmed-release' };
    const release = intent.release;
    if (!release || release.announcementPolicy?.enabled === false) return { status: 'release-disabled' };
    const chatId = String(intent.chatId || '');
    const versionConfirmed = Boolean(release.version && release.version === intent.deployedVersion && release.version === intent.confirmedVersion);
    if (!versionConfirmed) return { status: 'version-mismatch' };
    if (!chatId || chatId !== this.pilotChatId || !this.isChatAuthorized(chatId)) return { status: 'group-not-authorized' };
    if (!platform || !transport || !workerId || !ready({ ...gates, versionConfirmed, rollbackComplete: intent.rollbackComplete })) {
      return { status: 'not-ready' };
    }

    const key = idempotencyKey({ version: release.version, contentHash: release.contentHash, chatId });
    try {
      await platform.createReleaseAnnouncement({ version: release.version, contentHash: release.contentHash,
        chatId, idempotencyKey: key }, operationId || `release:${key}`);
      const claim = await platform.claimReleaseAnnouncement(key, workerId, 30000, Date.now());
      if (!claim.claimed) return { status: 'not-claimed', reason: claim.reason };
      try {
        const result = await transport.sendAndConfirm(chatId, render(release), key,
          { claimant: workerId, fence: claim.fence });
        if (!result || result.confirmed !== true) {
          throw Object.assign(new Error('transport_uncertain'), { uncertain: true });
        }
        const done = await platform.completeReleaseAnnouncement(key, workerId, claim.fence);
        return { status: done.completed ? 'completed' : 'uncertain', transport: result };
      } catch (error) {
        await platform.failReleaseAnnouncement(key, workerId, claim.fence,
          error.uncertain ? 'transport_uncertain' : (error.code || 'send_failed'));
        return { status: error.uncertain ? 'uncertain' : 'failed' };
      }
    } catch (error) {
      this.logger({ event: 'release_delivery_error', code: error.code || 'bridge_error' });
      return { status: 'unavailable' };
    }
  }
}

module.exports = { ReleaseAnnouncerRuntime };
