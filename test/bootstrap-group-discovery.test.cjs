'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  isGroupChat,
  chatIdSerialized,
  chatDisplayName,
  countIdServers,
  discoverGroupsInPage,
  runDiscoverInIsolatedContext,
} = require('../scripts/lib/bootstrap-group-discovery.cjs');

const privateChat = {
  id: {_serialized: '5511999999999@c.us', server: 'c.us'},
  name: 'Privado',
};
const groupByServer = {
  id: {_serialized: '12036342@g.us', server: 'g.us'},
  formattedTitle: 'Pokémon',
};
const groupByIsGroup = {
  id: {
    _serialized: 'meta@g.us',
    server: 'g.us',
    isGroup() {
      return true;
    },
  },
  name: 'Com meta',
  groupMetadata: {id: 'x'},
};

test('detecta grupo por @g.us e id.isGroup sem exigir groupMetadata', () => {
  assert.equal(isGroupChat(groupByServer), true);
  assert.equal(isGroupChat(groupByIsGroup), true);
  assert.equal(isGroupChat(privateChat), false);
  assert.equal(
    isGroupChat({id: {_serialized: 'chan@newsletter', server: 'newsletter'}}),
    false,
  );
  assert.equal(
    isGroupChat({id: {_serialized: 'x@c.us'}, groupMetadata: {}}),
    true,
  );
});

test('serializa id e nome sem ler mensagens', () => {
  assert.equal(chatIdSerialized({id: {_serialized: '1@g.us'}}), '1@g.us');
  assert.equal(chatIdSerialized({id: {user: '1', server: 'g.us'}}), '1@g.us');
  assert.equal(
    chatDisplayName({formattedTitle: 'Pokémon', name: 'ignored'}),
    'Pokémon',
  );
  assert.equal(chatDisplayName({contact: {pushname: 'Alias'}}), 'Alias');
});

test('discoverGroupsInPage.toString é autossuficiente (sem helpers externos)', () => {
  const src = discoverGroupsInPage.toString();
  assert.match(src, /function helpersDisponiveis/);
  assert.match(src, /function getChatModels/);
  assert.match(src, /function isGroupChat/);
  assert.match(src, /WAWebCollections/);
  assert.match(src, /@g\.us/);
  // Não deve depender de free vars do módulo (padrão clássico do Puppeteer).
  assert.doesNotMatch(src, /require\(['"]\.\.?/);
});

test('execução isolada (simula page.evaluate) com WAWebCollections', async () => {
  const models = [privateChat, groupByServer, groupByIsGroup];
  const windowMock = {
    require(name) {
      assert.equal(name, 'WAWebCollections');
      return {Chat: {getModelsArray: () => models}};
    },
    WWebJS: {
      getChats: async () => {
        throw new Error('não deve chamar getChats');
      },
      getChatModel: async () => {
        throw new Error('não deve chamar getChatModel');
      },
    },
    Store: {Chat: {getModelsArray: () => []}},
  };
  const out = await runDiscoverInIsolatedContext(windowMock);
  assert.equal(out.diagnostics.collectionSource, 'WAWebCollections.Chat');
  assert.equal(out.diagnostics.chatCount, 3);
  assert.equal(out.diagnostics.groupCount, 2);
  assert.equal(out.diagnostics.helpers.waWebCollectionsChat, true);
  assert.deepEqual(
    out.groups.map((g) => g.id).sort(),
    ['12036342@g.us', 'meta@g.us'],
  );
});

test('fallback Store.Chat quando WAWebCollections ausente', async () => {
  const models = [privateChat, groupByServer];
  const windowMock = {
    require() {
      throw new Error('module not found');
    },
    Store: {
      Chat: {
        getModelsArray: () => models,
      },
    },
  };
  const out = await runDiscoverInIsolatedContext(windowMock);
  assert.equal(out.diagnostics.collectionSource, 'Store.Chat');
  assert.equal(out.diagnostics.helpers.waWebCollectionsChat, false);
  assert.equal(out.diagnostics.helpers.storeChat, true);
  assert.equal(out.groups.length, 1);
  assert.equal(out.groups[0].id, '12036342@g.us');
});

test('sem coleção retorna groups vazios e source none', async () => {
  const out = await runDiscoverInIsolatedContext({});
  assert.equal(out.diagnostics.collectionSource, 'none');
  assert.equal(out.diagnostics.chatCount, 0);
  assert.equal(out.groups.length, 0);
});

test('countIdServers agrega sem expor números', () => {
  const counts = countIdServers([
    {id: {server: 'g.us'}},
    {id: {_serialized: 'a@c.us'}},
    {id: {_serialized: 'b@c.us'}},
  ]);
  assert.equal(counts['g.us'], 1);
  assert.equal(counts['c.us'], 2);
});
