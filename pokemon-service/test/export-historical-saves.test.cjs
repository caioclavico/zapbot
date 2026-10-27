'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const cassandra = require('cassandra-driver');
const {exportHistorical, listHistoricalPlayers, probeHistoricalPresence, toEdn} = require('../scripts/export-historical-saves.cjs');

const v1Account = {equipe:[{'id-pokemon':'v1-poke', nome:'Pikachu'}], moedas:12};
const v2Account = {equipe:[{'id-pokemon':'v2-poke', nome:'Eevee'}], moedas:34};

test('exporta V1/V2 por SELECT e preserva identidade de proprietário', async () => {
  const calls = [];
  const execute = async (query, params, options) => {
    calls.push({query, params, options});
    assert.match(query, /^SELECT /);
    if (query.includes('legacy_estado_particionado')) {
      return {rows:[{valor:JSON.stringify({'player@c.us':v1Account})}]};
    }
    return {rows:[{valor:JSON.stringify({grupo:{'player@c.us':v2Account}})}]};
  };
  const result = await exportHistorical({execute, keyspace:'zapbot', chat:'grupo', player:'player@c.us', now:()=> '2026-01-01T00:00:00.000Z'});
  assert.deepEqual(result.meta.requestedVersions, ['v1','v2']);
  assert.equal(result.historical_snapshots.length, 2);
  assert.equal(result.historical_snapshots[0].treinador.equipe[0]['id-pokemon'], 'v1-poke');
  assert.equal(result.historical_snapshots[1].treinador.equipe[0]['id-pokemon'], 'v2-poke');
  assert.equal(calls.length, 4);
  assert.deepEqual(calls.map(c => c.options.consistency), [
    cassandra.types.consistencies.localOne,
    cassandra.types.consistencies.localOne,
    cassandra.types.consistencies.localOne,
    cassandra.types.consistencies.localOne
  ]);
  assert.ok(calls.every(c => c.options.consistency !== cassandra.types.consistencies.any));
  assert.ok(calls.every(c => c.options.serialConsistency === undefined));
  assert.deepEqual(calls.map(c => c.params), [['treinador','grupo'], ['treinador','grupo'], ['treinador'], ['treinador']]);
});

test('não inventa versão ausente e rejeita dados inconsistentes', async () => {
  const execute = async (query) => query.includes('legacy_estado_particionado')
    ? {rows:[]}
    : {rows:[{valor:JSON.stringify({grupo:{p:v2Account}})}]};
  const partial = await exportHistorical({execute, keyspace:'zapbot', chat:'grupo', player:'p', versions:'both'});
  assert.deepEqual(partial.meta.missingVersions, ['v1']);
  assert.equal(partial.historical_snapshots.length, 1);
  const missingExecute = async () => ({rows:[]});
  await assert.rejects(exportHistorical({execute:missingExecute, keyspace:'zapbot', chat:'grupo', player:'p', versions:'v2'}), /historical_save_not_found_v2/);
  await assert.rejects(exportHistorical({execute, keyspace:'zapbot', chat:'grupo', player:'p', versions:'v3'}), /invalid_versions/);
});

test('serializador produz EDN estrutural sem converter payload legado em keywords', () => {
  const edn = toEdn({meta:{'authorized-offline-copy':true}, historical_snapshots:[{treinador:{'id-pokemon':'x'}}]}, true);
  assert.match(edn, /:authorized-offline-copy true/);
  assert.match(edn, /"id-pokemon" "x"/);
});

test('detecta alteração concorrente entre leituras LOCAL_ONE', async () => {
  let reads = 0;
  const execute = async () => {
    reads++;
    const account = reads === 1 ? v1Account : {...v1Account, moedas: 999};
    return {rows:[{valor:JSON.stringify({'p':account})}]};
  };
  await assert.rejects(exportHistorical({execute, keyspace:'zapbot', chat:'g', player:'p', versions:'v1'}), /changed_during_export_v1/);
});

test('lista apenas player-id das partições V1 com SELECT parametrizado', async () => {
  const calls = [];
  const execute = async (query, params, options) => {
    calls.push({query, params, options});
    assert.match(query, /^SELECT valor FROM zapbot\.legacy_estado_particionado WHERE modulo = \? AND particao = \?$/);
    assert.equal(options.consistency, cassandra.types.consistencies.localOne);
    assert.equal(options.serialConsistency, undefined);
    if (params[1] === 'chat-a') return {rows: [{valor: JSON.stringify({
      'player-b@c.us': {moedas: 999, equipe: [{nome: 'Pikachu'}]},
      'player-a@c.us': {moedas: 123, equipe: [{nome: 'Eevee'}]}
    })}]};
    return {rows: [{valor: JSON.stringify({'player-c@lid': {moedas: 1}})}]};
  };
  const result = await listHistoricalPlayers({execute, keyspace: 'zapbot', chatIds: ['chat-a', 'chat-b']});
  assert.deepEqual(result, [
    {chatId: 'chat-a', playerIds: ['player-a@c.us', 'player-b@c.us']},
    {chatId: 'chat-b', playerIds: ['player-c@lid']}
  ]);
  assert.equal(JSON.stringify(result).includes('moedas'), false);
  assert.equal(JSON.stringify(result).includes('Pikachu'), false);
  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.query.startsWith('SELECT ')));
});

test('listagem V1 rejeita keyspace diferente do legado alvo', async () => {
  await assert.rejects(
    listHistoricalPlayers({execute: async () => ({rows: []}), keyspace: 'outro', chatIds: ['chat-a']}),
    /list_players_requires_zapbot_keyspace/
  );
});

test('probe retorna só versões presentes sem atributos de conta', async () => {
  const execute = async (query) => {
    if (query.includes('legacy_estado_particionado')) {
      return {rows: [{valor: JSON.stringify({
        'player@c.us': {moedas: 99, equipe: [{nome: 'Secret'}]},
        'other@lid': {moedas: 1}
      })}]};
    }
    return {rows: [{valor: JSON.stringify({
      grupo: {'player@c.us': {moedas: 7, equipe: [{nome: 'Hidden'}]}}
    })}]};
  };
  const presence = await probeHistoricalPresence({
    execute, keyspace: 'zapbot', chat: 'grupo', player: 'player@c.us'
  });
  assert.deepEqual(presence, {chatId: 'grupo', playerId: 'player@c.us', versions: ['v1', 'v2']});
  assert.equal(JSON.stringify(presence).includes('moedas'), false);
  assert.equal(JSON.stringify(presence).includes('Secret'), false);
  const onlyV2 = await probeHistoricalPresence({
    execute, keyspace: 'zapbot', chat: 'grupo', player: 'missing@c.us'
  });
  assert.deepEqual(onlyV2.versions, []);
});
