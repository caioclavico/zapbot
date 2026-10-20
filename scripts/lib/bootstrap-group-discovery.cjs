'use strict';

/**
 * Descoberta leve de grupos para STAGING_BOOTSTRAP_GROUP.
 * Roda dentro do pupPage.evaluate — sem closures Node, sem ler mensagens.
 *
 * NÃO usa WWebJS.getChats/getChatModel: esses fazem groupMetadata.update e
 * serializam participantes (origem provável do erro opaco "r: r" e de
 * isGroup=false quando metadata ainda não carregou).
 */

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
  if (id.user && id.server) return `${id.user}@${id.server}`;
  return String(id);
}

function chatDisplayName(chat) {
  if (!chat) return '';
  return (
    chat.formattedTitle ||
    chat.name ||
    (chat.contact && (chat.contact.formattedName || chat.contact.name || chat.contact.pushname)) ||
    ''
  );
}

function countIdServers(models) {
  const counts = Object.create(null);
  for (const chat of models) {
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
      return { source: 'Store.Chat', models: models || [] };
    }
  } catch (_) {
    /* continue */
  }
  return { source: 'none', models: [] };
}

function helpersDisponiveis() {
  const w =
    typeof window !== 'undefined'
      ? window
      : typeof globalThis !== 'undefined'
        ? globalThis
        : {};
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
    wwebjsGetChatModel: !!(w.WWebJS && typeof w.WWebJS.getChatModel === 'function'),
    waWebCollectionsChat: waWebCollections,
    storeChat: !!(w.Store && w.Store.Chat),
  };
}

/**
 * Função injetada no WhatsApp Web via page.evaluate.
 * Retorna só id+nome de grupos + diagnóstico agregado (sem mensagens/PII).
 */
async function discoverGroupsInPage() {
  const helpers = helpersDisponiveis();
  const { source, models } = getChatModels();
  const list = Array.isArray(models) ? models : [];
  const groups = [];
  let withGroupMetadata = 0;

  for (const chat of list) {
    if (chat && chat.groupMetadata) withGroupMetadata += 1;
    if (!isGroupChat(chat)) continue;
    const id = chatIdSerialized(chat);
    if (!id) continue;
    groups.push({
      id,
      name: chatDisplayName(chat),
      hasGroupMetadata: !!chat.groupMetadata,
    });
  }

  return {
    groups,
    diagnostics: {
      collectionSource: source,
      helpers,
      chatCount: list.length,
      groupCount: groups.length,
      chatsWithGroupMetadata: withGroupMetadata,
      idServerCounts: countIdServers(list),
      // Intencionalmente sem sample de IDs privados / conteúdo.
      note:
        'Grupos detectados por @g.us / id.isGroup() / groupMetadata — sem getChatModel.',
    },
  };
}

module.exports = {
  discoverGroupsInPage,
  // Exportados para testes Node (mesma lógica; não usados no evaluate).
  isGroupChat,
  chatIdSerialized,
  chatDisplayName,
  countIdServers,
};
