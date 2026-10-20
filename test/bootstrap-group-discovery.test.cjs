'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {
  isGroupChat,
  chatIdSerialized,
  chatDisplayName,
  countIdServers,
  discoverGroupsInPage,
} = require('../scripts/lib/bootstrap-group-discovery.cjs');

test('detecta grupo por @g.us e id.isGroup sem exigir groupMetadata', () => {
  assert.equal(isGroupChat({id: {_serialized: '120363@g.us', server: 'g.us'}}), true);
  assert.equal(
    isGroupChat({
      id: {
        _serialized: 'x@g.us',
        isGroup() {
          return true;
        },
      },
    }),
    true,
  );
  assert.equal(isGroupChat({id: {_serialized: '5511@c.us', server: 'c.us'}}), false);
  assert.equal(isGroupChat({id: {_serialized: 'chan@newsletter', server: 'newsletter'}}), false);
  assert.equal(isGroupChat({id: {_serialized: 'x@c.us'}, groupMetadata: {}}), true);
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

test('discoverGroupsInPage usa WAWebCollections e ignora DMs', async () => {
  const models = [
    {id: {_serialized: '5511999999999@c.us', server: 'c.us'}, name: 'Privado'},
    {
      id: {_serialized: '12036342@g.us', server: 'g.us'},
      formattedTitle: 'Pokémon',
    },
    {
      id: {
        _serialized: 'meta@g.us',
        server: 'g.us',
        isGroup() {
          return true;
        },
      },
      name: 'Com meta',
      groupMetadata: {id: 'x'},
    },
  ];
  global.window = {
    require(name) {
      assert.equal(name, 'WAWebCollections');
      return {Chat: {getModelsArray: () => models}};
    },
    WWebJS: {
      getChats: async () => {
        throw new Error('não deve chamar getChats no caminho leve');
      },
      getChatModel: async () => {
        throw new Error('não deve chamar getChatModel');
      },
    },
    Store: {Chat: {getModelsArray: () => []}},
  };
  try {
    const out = await discoverGroupsInPage();
    assert.equal(out.diagnostics.collectionSource, 'WAWebCollections.Chat');
    assert.equal(out.diagnostics.chatCount, 3);
    assert.equal(out.diagnostics.groupCount, 2);
    assert.equal(out.diagnostics.chatsWithGroupMetadata, 1);
    assert.equal(out.diagnostics.idServerCounts['g.us'], 2);
    assert.equal(out.diagnostics.idServerCounts['c.us'], 1);
    assert.deepEqual(
      out.groups.map((g) => g.id).sort(),
      ['12036342@g.us', 'meta@g.us'],
    );
    assert.ok(out.groups.every((g) => !('msgs' in g) && !('lastReceivedKey' in g)));
  } finally {
    delete global.window;
  }
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
