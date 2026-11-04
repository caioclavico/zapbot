'use strict';

const CLIENT_STATE = new WeakMap();
const MAX_BUFFERED_IDS = 32;
const MAX_ACKS_PER_ID = 8;
const ACK_TTL_MS = 30000;

function stateFor(client, platform, logger) {
  let state = CLIENT_STATE.get(client);
  if (state) return state;
  state = { buffer: new Map(), active: new Map(), platform, logger };
  const bufferAck = (id, ack) => {
    if (!state.buffer.has(id)) {
      if (state.buffer.size >= MAX_BUFFERED_IDS) state.buffer.delete(state.buffer.keys().next().value);
      const values = [];
      state.buffer.set(id, values);
      const timer = setTimeout(() => state.buffer.delete(id), ACK_TTL_MS);
      timer.unref?.();
    }
    const values = state.buffer.get(id);
    values.push(ack);
    if (values.length > MAX_ACKS_PER_ID) values.shift();
  };
  const removeBufferedAck = (id, ack) => {
    const values = state.buffer.get(id);
    if (!values) return;
    const index = values.indexOf(ack);
    if (index >= 0) values.splice(index, 1);
    if (!values.length) state.buffer.delete(id);
  };
  const handleAck = async (id, ack) => {
    if (!id || !Number.isFinite(Number(ack)) || Number(ack) < 1) return { confirmed: false, reason: 'ack_insufficient' };
    const active = state.active.get(id);
    if (active && !active.associated) {
      active.early.push(Number(ack));
      return { confirmed: false, buffered: true };
    }
    // Buffer synchronously before any HTTP round trip. A send can return and
    // persist the correlation while this lookup is still in flight.
    if (!active) bufferAck(id, Number(ack));
    try {
      const announcement = active
        ? null
        : (await state.platform.getReleaseByMessageId(id)).announcement;
      if (!active && !announcement) {
        return { confirmed: false, reason: 'unknown_message' };
      }
      const claimant = active?.claimant || announcement.messageClaimant;
      const fence = active?.fence ?? announcement.messageFence;
      if (!claimant || !Number.isSafeInteger(Number(fence))) {
        return { confirmed: false, reason: 'message_claim_missing' };
      }
      const result = await state.platform.ackReleaseMessage(id, Number(ack), claimant, fence);
      if (!active) removeBufferedAck(id, Number(ack));
      if (result?.confirmed === true) active?.resolve?.({ confirmed: true, messageId: id });
      state.logger({ event: result?.confirmed ? 'release_ack_confirmed' : 'release_ack_rejected', reason: result?.reason || undefined });
      return result;
    } catch (error) {
      bufferAck(id, Number(ack));
      state.logger({ event: 'release_ack_deferred', code: error.code || 'bridge_error' });
      return { confirmed: false, reason: 'bridge_unavailable' };
    }
  };
  state.handleAck = handleAck;
  state.bufferAck = bufferAck;
  state.listener = (message, ack) => {
    const id = message?.id?._serialized;
    if (!id) return;
    void handleAck(id, ack);
  };
  client.on('message_ack', state.listener);
  CLIENT_STATE.set(client, state);
  return state;
}

function createReleaseTransport({ client, platform, logger = () => {} }) {
  if (!client || typeof client.sendMessage !== 'function' || !platform ||
      typeof platform.getReleaseAnnouncement !== 'function' ||
      typeof platform.getReleaseByMessageId !== 'function' ||
      typeof platform.beginReleaseAnnouncementSend !== 'function') {
    throw new Error('transport_invalid');
  }
  const state = stateFor(client, platform, logger);

  return {
    async reconcileBuffered() {
      for (const [id, acks] of [...state.buffer.entries()]) {
        state.buffer.delete(id);
        for (const ack of acks) await state.handleAck(id, ack);
      }
    },
    async sendAndConfirm(chatId, body, key, { timeoutMs = 5000, claimant, fence } = {}) {
      const current = (await platform.getReleaseAnnouncement(key)).announcement;
      if (current?.status === 'completed') return { confirmed: true, duplicate: true, messageId: current.messageId };

      let messageId = current?.messageId;
      if (messageId && (current.messageClaimant !== claimant || Number(current.messageFence) !== Number(fence))) {
        return { confirmed: false, uncertain: true, reason: 'message_claim_stale', messageId };
      }
      let active = messageId && state.active.get(messageId);
      if (!active) {
        active = { claimant, fence, associated: Boolean(messageId), early: [], resolve: null };
      }
      if (!messageId) {
        const started = await platform.beginReleaseAnnouncementSend(key, claimant, fence);
        if (started?.started !== true) return { confirmed: false, uncertain: true, reason: 'send_claim_stale' };
        let message;
        try { message = await client.sendMessage(chatId, body); }
        catch (error) {
          error.uncertain = true;
          throw error;
        }
        messageId = message?.id?._serialized;
        if (!messageId) return { confirmed: false, uncertain: true };
        active.claimant = claimant;
        active.fence = fence;
        active.associated = false;
        state.active.set(messageId, active);
        const buffered = state.buffer.get(messageId) || [];
        state.buffer.delete(messageId);
        active.early.push(...buffered);
        try {
          await platform.associateReleaseMessage(key, claimant, fence, messageId);
          active.associated = true;
        } catch (error) {
          error.uncertain = true;
          state.active.delete(messageId);
          throw error;
        }
      } else {
        active.claimant = claimant;
        active.fence = fence;
        active.associated = true;
        state.active.set(messageId, active);
      }

      let resolveAck;
      const ackPromise = new Promise(resolve => { resolveAck = resolve; });
      active.resolve = resolveAck;
      for (const ack of active.early.splice(0)) await state.handleAck(messageId, ack);
      const afterReplay = (await platform.getReleaseAnnouncement(key)).announcement;
      if (afterReplay?.status === 'completed') {
        state.active.delete(messageId);
        return { confirmed: true, duplicate: true, messageId };
      }
      let timer;
      const result = await Promise.race([
        ackPromise,
        new Promise(resolve => {
          timer = setTimeout(() => resolve({ confirmed: false, uncertain: true, messageId }), timeoutMs);
          timer.unref?.();
        })
      ]);
      clearTimeout(timer);
      state.active.delete(messageId);
      return result.confirmed ? result : { ...result, uncertain: true, messageId };
    }
  };
}

module.exports = { createReleaseTransport };
