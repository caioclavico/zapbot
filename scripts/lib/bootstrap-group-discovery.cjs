'use strict';

/**
 * Descoberta leve de grupos para STAGING_BOOTSTRAP_GROUP.
 *
 * CRÍTICO (M9.5.1): page.evaluate serializa SÓ o corpo de discoverGroupsInPage.
 * Helpers de módulo NÃO existem no WhatsApp Web — tudo deve ser function
 * declaration aninhada dentro de discoverGroupsInPage.
 *
 * NÃO usa WWebJS.getChats/getChatModel (groupMetadata.update → "r: r").
 */

/**
 * Função injetada via pupPage.evaluate — 100% autossuficiente.
 */
async function discoverGroupsInPage() {
  function isGroupChat(chat) {
    if (!chat || !chat.id) return false;
    const id = chat.id;
    try {
      if (typeof id.isGroup === 'function' && id.isGroup()) return true;
    } catch (_) {
      /* ignore */
    }
    if (id.server === 'g.us') return true;
    const ser = id._serialized || (typeof id === 'string' ? id : '');
    if (typeof ser === 'string' && /@g\.us$/i.test(ser)) return true;
    if (chat.groupMetadata) return true;
    return false;
  }

  function chatIdSerialized(chat) {
    if (!chat || !chat.id) return '';
    const id = chat.id;
    if (id._serialized) return String(id._serialized);
    if (typeof id === 'string') return id;
    if (id.user && id.server) return id.user + '@' + id.server;
    return String(id);
  }

  function chatDisplayName(chat) {
    if (!chat) return '';
    return (
      chat.formattedTitle ||
      chat.name ||
      (chat.contact &&
        (chat.contact.formattedName || chat.contact.name || chat.contact.pushname)) ||
      ''
    );
  }

  function countIdServers(models) {
    const counts = {};
    for (let i = 0; i < models.length; i++) {
      const chat = models[i];
      let server = 'unknown';
      try {
        if (chat && chat.id && chat.id.server) server = String(chat.id.server);
        else {
          const ser = chatIdSerialized(chat);
          const at = ser.lastIndexOf('@');
          if (at >= 0) server = ser.slice(at + 1);
        }
      } catch (_) {
        server = 'unknown';
      }
      counts[server] = (counts[server] || 0) + 1;
    }
    return counts;
  }

  function getChatModels() {
    try {
      if (typeof window !== 'undefined' && typeof window.require === 'function') {
        const cols = window.require('WAWebCollections');
        if (cols && cols.Chat && typeof cols.Chat.getModelsArray === 'function') {
          return {
            source: 'WAWebCollections.Chat',
            models: cols.Chat.getModelsArray() || [],
          };
        }
      }
    } catch (_) {
      /* continue */
    }
    try {
      const Store = typeof window !== 'undefined' ? window.Store : null;
      if (Store && Store.Chat) {
        const Chat = Store.Chat;
        const models = Chat.getModelsArray
          ? Chat.getModelsArray()
          : Chat.models || [];
        return {source: 'Store.Chat', models: models || []};
      }
    } catch (_) {
      /* continue */
    }
    return {source: 'none', models: []};
  }

  function helpersDisponiveis() {
    const w = typeof window !== 'undefined' ? window : {};
    let waWebCollections = false;
    try {
      waWebCollections = !!(
        typeof w.require === 'function' &&
        w.require('WAWebCollections') &&
        w.require('WAWebCollections').Chat
      );
    } catch (_) {
      waWebCollections = false;
    }
    return {
      wwebjsGetChats: !!(w.WWebJS && typeof w.WWebJS.getChats === 'function'),
      wwebjsGetChatModel: !!(
        w.WWebJS && typeof w.WWebJS.getChatModel === 'function'
      ),
      waWebCollectionsChat: waWebCollections,
      storeChat: !!(w.Store && w.Store.Chat),
    };
  }

  const helpers = helpersDisponiveis();
  const got = getChatModels();
  const source = got.source;
  const list = Array.isArray(got.models) ? got.models : [];
  const groups = [];
  let withGroupMetadata = 0;

  for (let i = 0; i < list.length; i++) {
    const chat = list[i];
    if (chat && chat.groupMetadata) withGroupMetadata += 1;
    if (!isGroupChat(chat)) continue;
    const id = chatIdSerialized(chat);
    if (!id) continue;
    groups.push({
      id: id,
      name: chatDisplayName(chat),
      hasGroupMetadata: !!chat.groupMetadata,
    });
  }

  return {
    groups: groups,
    diagnostics: {
      collectionSource: source,
      helpers: helpers,
      chatCount: list.length,
      groupCount: groups.length,
      chatsWithGroupMetadata: withGroupMetadata,
      idServerCounts: countIdServers(list),
      note:
        'Grupos detectados por @g.us / id.isGroup() / groupMetadata — sem getChatModel.',
    },
  };
}

/**
 * Simula page.evaluate: roda a função serializada sem escopo do módulo Node.
 * `windowMock` vira `this` / `window` no contexto isolado.
 */
async function runDiscoverInIsolatedContext(windowMock) {
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  const isolated = new AsyncFunction(
    'return await (' + discoverGroupsInPage.toString() + ')();',
  );
  const prev = global.window;
  global.window = windowMock;
  try {
    return await isolated.call(windowMock);
  } finally {
    if (prev === undefined) delete global.window;
    else global.window = prev;
  }
}

/** Helpers exportados só para testes unitários de classificação (Node). */
function isGroupChat(chat) {
  if (!chat || !chat.id) return false;
  const id = chat.id;
  try {
    if (typeof id.isGroup === 'function' && id.isGroup()) return true;
  } catch (_) {
    /* ignore */
  }
  if (id.server === 'g.us') return true;
  const ser = id._serialized || (typeof id === 'string' ? id : '');
  if (typeof ser === 'string' && /@g\.us$/i.test(ser)) return true;
  if (chat.groupMetadata) return true;
  return false;
}

function chatIdSerialized(chat) {
  if (!chat || !chat.id) return '';
  const id = chat.id;
  if (id._serialized) return String(id._serialized);
  if (typeof id === 'string') return id;
  if (id.user && id.server) return id.user + '@' + id.server;
  return String(id);
}

function chatDisplayName(chat) {
  if (!chat) return '';
  return (
    chat.formattedTitle ||
    chat.name ||
    (chat.contact &&
      (chat.contact.formattedName || chat.contact.name || chat.contact.pushname)) ||
    ''
  );
}

function countIdServers(models) {
  const counts = {};
  for (let i = 0; i < models.length; i++) {
    const chat = models[i];
    let server = 'unknown';
    try {
      if (chat && chat.id && chat.id.server) server = String(chat.id.server);
      else {
        const ser = chatIdSerialized(chat);
        const at = ser.lastIndexOf('@');
        if (at >= 0) server = ser.slice(at + 1);
      }
    } catch (_) {
      server = 'unknown';
    }
    counts[server] = (counts[server] || 0) + 1;
  }
  return counts;
}

module.exports = {
  discoverGroupsInPage,
  runDiscoverInIsolatedContext,
  isGroupChat,
  chatIdSerialized,
  chatDisplayName,
  countIdServers,
};
