'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {createClient}=require('./lib/pokemon-http-client.cjs');
const realDomain=require('../pokemon-service/target/domain.cjs');
const {PokemonService,handler}=require('../pokemon-service/runtime/service.cjs');

test('cliente HTTP recebe resposta do domínio compilado real para consulta da loja',async t=>{
  // Esta consulta não grava estado de jogo. Somente recibos usam o fake abaixo.
  // Não chamar initialize/startTimers: nenhuma conexão Cassandra ou timer real.
  const data={};let calls=0;
  const domain={registerModule:k=>{data[k]||={};},load:k=>data[k],store:async(k,v)=>{data[k]=v;},reserve:async(k,id,v)=>{if(data[k][id])return false;data[k][id]=v;return true;},isReady:()=>true,takeEffects:()=>[],command:async(...args)=>{calls++;return realDomain.command(...args);}};
  const service=new PokemonService({domain,media:{put:()=>{throw Error('consulta deveria retornar texto');}},logger:()=>{}});
  const server=http.createServer(handler(service,{token:'domain-contract'}));
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const client=createClient({baseUrl:`http://127.0.0.1:${server.address().port}`,token:'domain-contract'});
  t.after(async()=>{client.close();server.closeAllConnections();await new Promise(r=>server.close(r));});
  const request={requestId:'real-domain',chatId:'fixture',playerId:'fixture@lid',command:'loja detalhes atadura'};
  const reply=await client.command(request);
  assert.match(reply.messages[0].text,/Detalhes do item/);
  assert.match(reply.messages[0].text,/Atadura/);
  assert.equal(reply.messages[0].type,'text');
  assert.deepEqual(await client.command(request),reply);
  assert.equal(calls,1);
});
