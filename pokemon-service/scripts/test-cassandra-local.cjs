'use strict';
// Run only in the disposable Cassandra container's network namespace.
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {randomUUID}=require('node:crypto');
if(process.env.POKEMON_ISOLATED_TEST!=='true')throw Error('Requires POKEMON_ISOLATED_TEST=true and disposable local Cassandra.');
process.env.CASSANDRA_CONTACT_POINTS='127.0.0.1';
process.env.CASSANDRA_DATACENTER='datacenter1';
process.env.CASSANDRA_KEYSPACE='pokemon_test_'+randomUUID().replaceAll('-','');
process.env.POKEMON_READ_ONLY='false';
const cassandra=require('cassandra-driver');
const domain=require('../target/domain.cjs');
const {PokemonService,handler}=require('../runtime/service.cjs');
const {MediaStore}=require('../runtime/media.cjs');
(async()=>{
  const client=new cassandra.Client({contactPoints:['127.0.0.1'],localDataCenter:'datacenter1'});
  const keyspace=process.env.CASSANDRA_KEYSPACE;
  try {
    await client.connect();
    await client.execute(`CREATE KEYSPACE ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
    await client.execute(`CREATE TABLE ${keyspace}.estado (chave text PRIMARY KEY, valor text)`);
    await client.execute(`CREATE TABLE ${keyspace}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`);
  } finally { await client.shutdown(); }
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'pokemon-cassandra-test-'));
  const service=new PokemonService({domain,media:new MediaStore(directory)});
  const server=http.createServer(handler(service,{token:'isolated-test-token'}));
  try {
    await service.initialize(); // Never start timers.
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const base=`http://127.0.0.1:${server.address().port}`;
    for(const route of ['/health','/ready','/events/pending']) {
      const response=await fetch(base+route,{headers:{authorization:'Bearer isolated-test-token'}});
      assert.equal(response.status,200);
      if(route==='/events/pending')assert.deepEqual(await response.json(),{events:[]});
    }
    const request={requestId:randomUUID(),chatId:'isolated',playerId:'isolated@lid',playerName:'Teste',
      command:'pk treinador',context:{mentionedIds:[],quotedPlayerId:null,isAdmin:false,botVersion:'teste',bugTarget:null}};
    const call=()=>fetch(base+'/commands',{method:'POST',headers:{authorization:'Bearer isolated-test-token','content-type':'application/json'},body:JSON.stringify(request)});
    const response=await call();const reply=await response.json();
    assert.equal(response.status,200,JSON.stringify(reply));
    assert.equal(reply.requestId,request.requestId);
    assert.ok(Array.isArray(reply.messages));assert.ok(Array.isArray(reply.effects));assert.ok(reply.timings);
    assert.equal(reply.messages[0].type,'image');
    const media=await service.media.get(reply.messages[0].mediaId);
    assert.equal(media.buffer.subarray(0,8).toString('hex'),'89504e470d0a1a0a');
    assert.deepEqual(await (await call()).json(),reply);
    console.log(JSON.stringify({event:'isolated_cassandra_test_passed',requestId:request.requestId,persistence:domain.diagnostics()}));
  } finally {
    server.closeAllConnections();server.close();
    await service.close();await fs.rm(directory,{recursive:true,force:true});
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
