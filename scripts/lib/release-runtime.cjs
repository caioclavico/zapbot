'use strict';

const { idempotencyKey, ready, render } = require('./release-announcer.cjs');

class ReleaseAnnouncerRuntime {
  constructor({ enabled = false, pilotChatId = '', isChatAuthorized = () => false,
    publicationControl = null, logger = () => {} } = {}) {
    this.enabled = enabled === true;
    this.pilotChatId = String(pilotChatId || '');
    this.isChatAuthorized = isChatAuthorized;
    this.publicationControl = publicationControl;
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
    if (!this.publicationControl || typeof this.publicationControl.withPublication !== 'function') {
      return { status: 'control-unavailable' };
    }
    try {
      return await this.publicationControl.withPublication(async ({ assertOpen }) => {
        const existing = (await platform.getReleaseAnnouncement(key)).announcement;
        const status = String(existing?.status || '').replace(/^:/, '');
        const sendStartedAt = existing?.sendStartedAt ?? existing?.['send-started-at'] ?? existing?.send_started_at;
        const messageId = existing?.messageId ?? existing?.['message-id'] ?? existing?.message_id;
        if (status === 'completed') return { status: 'already-completed' };
        // An uncertain transport is evidence that WhatsApp may already have
        // accepted the message. Only an ACK/reconciliation may resolve it.
        if (status === 'uncertain' || (status === 'claimed' && (sendStartedAt || messageId))) {
          return { status: 'uncertain-manual-review' };
        }
        await platform.createReleaseAnnouncement({ version: release.version, contentHash: release.contentHash,
          chatId, idempotencyKey: key }, operationId || `release:${key}`);
        const claim = await platform.claimReleaseAnnouncement(key, workerId, 30000, Date.now());
        if (!claim.claimed) {
          const reason = String(claim.reason || '');
          return { status: reason === 'not_claimable' ? 'uncertain-manual-review' : 'not-claimed', reason };
        }
        try {
          await assertOpen();
          const result = await transport.sendAndConfirm(chatId, render(release), key,
            { claimant: workerId, fence: claim.fence, beforeSend: assertOpen });
          if (result?.controlUnavailable === true) return { status: 'control-unavailable' };
          if (result?.suspended === true) return { status: 'suspended' };
          if (!result || result.confirmed !== true) {
            throw Object.assign(new Error('transport_uncertain'), { uncertain: true });
          }
          const done = await platform.completeReleaseAnnouncement(key, workerId, claim.fence);
          return { status: done.completed ? 'completed' : 'uncertain', transport: result };
        } catch (error) {
          if (error.code === 'publication_suspended' || error.code === 'control_unavailable' ||
              error.code === 'control_missing' || error.code === 'control_unreadable' ||
              error.code === 'control_invalid' || error.code === 'control_permissions_invalid') {
            return { status: error.code.startsWith('control_') ? 'control-unavailable' : 'suspended' };
          }
          await platform.failReleaseAnnouncement(key, workerId, claim.fence,
            error.uncertain ? 'transport_uncertain' : (error.code || 'send_failed'));
          return { status: error.uncertain ? 'uncertain' : 'failed' };
        }
      }, key);
    } catch (error) {
      if (error.code === 'publication_suspended') return { status: 'suspended' };
      if (['control_missing', 'control_unreadable', 'control_invalid',
        'control_permissions_invalid', 'control_unavailable'].includes(error.code)) return { status: 'control-unavailable' };
      if (error.code === 'publication_inflight') return { status: 'publication-in-progress' };
      this.logger({ event: 'release_delivery_error', code: error.code || 'bridge_error' });
      return { status: 'unavailable' };
    }
  }
}

module.exports = { ReleaseAnnouncerRuntime };
