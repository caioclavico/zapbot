'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const {PokemonService,handler,REQUESTS}=require('../runtime/service.cjs');
const {MediaStore}=require('../runtime/media.cjs');
function fakeDomain(data={}) {
  return {
    data, calls:0, healthy:true,
    registerModule(k){data[k] ||= {};}, initialize:async()=>{}, isReady(){return this.healthy;},
    load(k){return data[k]||{};},async store(k,v){data[k]=structuredClone(v);},
    async reserve(k,id,v){if(data[k][id])return false;data[k][id]=structuredClone(v);return true;},
    async command(req,emit){this.calls++;await emit({texto:'intermediária'});return {texto:'resposta',media:{mime:'image/png',buffer:Buffer.from('png fixture'),filename:'imagem.png'}};},
    takeEffects:()=>[],startTimers:()=>{},stopTimers:()=>{},shutdown:async()=>{}
  };
}
const request={requestId:'message-1',chatId:'existing-chat',playerId:'existing-player@lid',playerName:'Teste',command:'pk treinador'};
test('egg notifications keep stable identity through lost response, ack and restart',async()=>{
  const domain=fakeDomain();
  const service=new PokemonService({domain,media:{},logger:()=>{}});
  const raw={'event-id':'egg:hatch-1',texto:'Nasceu Bulbasaur!',mentions:['player@lid']};
  const store=domain.store;
  let lost=true;
  domain.store=async function(k,v){await store.call(this,k,v);if(lost){lost=false;throw Error('lost response');}};
  await assert.rejects(service.enqueueEvent('group',raw),/lost response/);
  domain.store=store;
  await service.enqueueEvent('group',raw);
  assert.equal(service.pendingEvents().length,1);
  assert.equal(service.pendingEvents()[0].id,'egg:hatch-1');
  assert.deepEqual(service.pendingEvents()[0].messages[0].mentions,['player@lid']);
  // Enquanto o WhatsApp não confirma, reinício mantém a mensagem disponível.
  const restarted=new PokemonService({domain:fakeDomain(domain.data),media:{},logger:()=>{}});
  assert.equal(restarted.pendingEvents().length,1);
  await restarted.ack('egg:hatch-1');
  await restarted.enqueueEvent('group',raw); // CAS de origem ainda não confirmou handoff.
  assert.equal(restarted.pendingEvents().length,0);
  const again=new PokemonService({domain:fakeDomain(restarted.domain.data),media:{},logger:()=>{}});
  await again.enqueueEvent('group',raw);
  assert.equal(again.pendingEvents().length,0);
  await assert.rejects(again.enqueueEvent('other-group',raw),e=>e.code==='event_conflict');
});
test('egg notification persistence failure leaves no acknowledged success',async()=>{
  const domain=fakeDomain();
  const service=new PokemonService({domain,media:{},logger:()=>{}});
  domain.store=async()=>{throw Error('storage unavailable');};
  await assert.rejects(service.enqueueEvent('group',{'event-id':'egg:hatch',texto:'Nasceu!'}),/storage unavailable/);
  assert.equal(service.pendingEvents().length,0);
});
async function fixture(t,domain=fakeDomain()) {
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'pokemon-api-'));
  const media=new MediaStore(dir);
  const service=new PokemonService({domain,media,logger:()=>{}});
  await service.initialize();
  const server=http.createServer(handler(service,{token:'test-secret'}));
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${server.address().port}`;
  t.after(async()=>{server.closeAllConnections();await new Promise(r=>server.close(r));await service.close();await fs.rm(dir,{recursive:true,force:true});});
  const call=async(p,body,token='test-secret')=>fetch(base+p,{method:body===undefined?'GET':'POST',headers:{authorization:'Bearer '+token,'content-type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(3000)});
  return {service,domain,media,call,base};
}
test('API health, ready, auth, ordered responses and binary media',async t=>{
  const {call,domain}=await fixture(t);
  assert.equal((await call('/health',undefined,'')).status,200);
  assert.equal((await call('/ready')).status,200);
  assert.deepEqual(await (await call('/events/pending')).json(),{events:[]});
  assert.equal((await call('/commands',request,'wrong')).status,401);
  const response=await call('/commands',request);assert.equal(response.status,200);
  const data=await response.json();assert.equal(data.messages[0].text,'intermediária');
  assert.equal(data.requestId,request.requestId);
  assert.ok(Array.isArray(data.messages));assert.ok(Array.isArray(data.effects));
  if(require('../runtime/metrics.cjs').enabled)assert.equal(typeof data.timings.request_total_ms,'number');
  else assert.deepEqual(data.timings,{});
  assert.equal(data.messages[1].type,'image');assert.ok(!JSON.stringify(data).includes('png fixture'));
  const media=await call('/media/'+data.messages[1].mediaId);assert.equal(media.headers.get('content-type'),'image/png');assert.equal(await media.text(),'png fixture');
  domain.healthy=false;assert.equal((await call('/ready')).status,503);assert.equal((await call('/commands',{...request,requestId:'new'})).status,503);
});
test('duplicates execute once, survive service restart and reject changed payload',async t=>{
  const f=await fixture(t);
  const results=await Promise.all([f.service.execute(request),f.service.execute(request)]);
  assert.deepEqual(results[0],results[1]);assert.equal(f.domain.calls,1);
  assert.deepEqual(await f.service.execute(request),results[0]);
  const restarted=new PokemonService({domain:fakeDomain(f.domain.data),media:f.media,logger:()=>{}});
  assert.deepEqual(await restarted.execute(request),results[0]);assert.equal(restarted.domain.calls,0);
  await assert.rejects(()=>restarted.execute({...request,command:'pk capturar'}),e=>e.status===409);
});
test('uncertain reservation after crash cannot reexecute effects',async t=>{
  const f=await fixture(t);f.domain.command=async()=>{f.domain.calls++;throw new Error('crash');};
  await assert.rejects(()=>f.service.execute(request),e=>e.code==='result_unknown');
  const restarted=new PokemonService({domain:fakeDomain(f.domain.data),media:f.media,logger:()=>{}});
  await assert.rejects(()=>restarted.execute(request),e=>e.status===409);
  assert.equal(restarted.domain.calls,0);
});
test('persistent event survives restart, media stays available, ack is idempotent',async t=>{
  const f=await fixture(t);
  await f.service.enqueueEvent('chat',{texto:'raide',media:{mime:'image/png',buffer:Buffer.from('event fixture'),filename:'raid.png'}});
  const restarted=new PokemonService({domain:fakeDomain(f.domain.data),media:f.media,logger:()=>{}});
  const [event]=restarted.pendingEvents();assert.equal(event.chatId,'chat');assert.ok(await f.media.get(event.messages[0].mediaId));
  await restarted.ack(event.id);await restarted.ack(event.id);assert.deepEqual(restarted.pendingEvents(),[]);
});
test('independent chat proceeds while another command waits',async t=>{
  const f=await fixture(t);let release;
  f.domain.command=async r=>{if(r.chatId==='slow')await new Promise(resolve=>release=resolve);return 'ok';};
  const slow=f.service.execute({...request,requestId:'slow',chatId:'slow'});
  await new Promise(r=>setImmediate(r));
  const fast=await f.service.execute({...request,requestId:'fast',chatId:'fast'});assert.equal(fast.messages[0].text,'ok');
  release();await slow;
});
test('invalid bodies and unknown routes fail without touching domain',async t=>{
  const {call,domain}=await fixture(t);
  assert.equal((await call('/commands',{})).status,400);
  assert.equal((await call('/nothing')).status,404);assert.equal(domain.calls,0);
});
module.exports={fakeDomain};

test('Cassandra 4352 before domain execution logs original cause and never retries',async t=>{
  const f=await fixture(t);const logs=[];f.service.logger=line=>logs.push(JSON.parse(line));
  const {errors,types}=require('cassandra-driver');
  const cause=new errors.ResponseError(types.responseErrorCodes.writeTimeout,'CAS reservation timed out');
  Object.assign(cause,{writeType:'CAS',consistency:types.consistencies.serial,received:0,blockFor:1});
  let attempts=0;
  f.domain.reserve=async()=>{attempts++;throw cause;};
  const response=await f.call('/commands',{...request,requestId:'reservation-timeout'});
  assert.equal(response.status,500);
  const data=await response.json();assert.equal(data.error,'result_unknown');
  assert.ok(!JSON.stringify(data).includes('CAS reservation'));assert.ok(!data.stack);
  const failed=logs.find(x=>x.event==='command_failed');
  assert.equal(failed.stage,'reservation');assert.equal(failed.command,'pk treinador');
  assert.equal(failed.error.code,4352);assert.equal(failed.error.writeType,'CAS');
  assert.equal(failed.error.message,cause.message);assert.equal(failed.error.stack,cause.stack);
  assert.equal(logs.find(x=>x.event==='http_error').status,500);
  assert.equal(f.domain.calls,0);assert.equal(attempts,1);
  assert.deepEqual(f.domain.data[REQUESTS],{});
});

test('domain errors retain original diagnostics, redact secrets and preserve uncertain replay',async t=>{
  const f=await fixture(t);const logs=[];f.service.logger=line=>logs.push(JSON.parse(line));
  const old=process.env.API_TOKEN;process.env.API_TOKEN='private-fixture-api-token';
  t.after(()=>{if(old===undefined)delete process.env.API_TOKEN;else process.env.API_TOKEN=old;});
  const cause=Object.assign(Error('original failure private-fixture-api-token Bearer header-fixture-secret'),{code:'EIO'});
  f.domain.command=async()=>{f.domain.calls++;throw cause;};
  const response=await f.call('/commands',request);assert.equal(response.status,500);
  const data=await response.json();assert.equal(data.error,'result_unknown');
  assert.ok(!JSON.stringify(data).includes('original failure'));assert.ok(!data.stack);
  const failed=logs.find(x=>x.event==='command_failed');
  assert.equal(failed.requestId,request.requestId);assert.equal(failed.command,request.command);
  assert.equal(failed.error.name,'Error');assert.equal(failed.error.code,'EIO');
  assert.match(failed.error.message,/original failure/);assert.match(failed.error.stack,/api.test.cjs/);
  assert.ok(!JSON.stringify(logs).includes('private-fixture-api-token'));
  assert.ok(!JSON.stringify(logs).includes('header-fixture-secret'));
  const replay=await f.call('/commands',request);assert.equal(replay.status,409);
  assert.equal(f.domain.calls,1);
});

test('HTTP catch sanitizes unknown errors and redacts authorization token in logs',async t=>{
  const f=await fixture(t);const logs=[];f.service.logger=line=>logs.push(JSON.parse(line));
  f.service.pendingEvents=()=>{throw Object.assign(Error('failure test-secret'),{code:4352,status:418});};
  const response=await f.call('/events/pending');assert.equal(response.status,500);
  assert.deepEqual(await response.json(),{error:'internal_error',message:'Falha interna.'});
  const logged=logs.find(x=>x.event==='http_error');
  assert.equal(logged.method,'GET');assert.equal(logged.pathname,'/events/pending');
  assert.equal(logged.error.code,4352);assert.ok(logged.error.stack);
  assert.ok(!JSON.stringify(logs).includes('test-secret'));
});

test('rank effect survives a lost command response and consumer acknowledges it separately',async t=>{
  const f=await fixture(t);
  const effect={id:'rank-1',chatId:request.chatId,playerId:request.playerId,type:'rank.increment',game:'pokemon'};
  let pending=[effect];
  f.domain.takeEffects=()=>{const result=pending;pending=[];return result;};
  const reply=await f.service.execute(request);
  assert.deepEqual(reply.effects,[effect]);
  const restarted=new PokemonService({domain:fakeDomain(f.domain.data),media:f.media,logger:()=>{}});
  const [event]=restarted.pendingEvents();
  assert.deepEqual(event.effects,[effect]);
  assert.deepEqual(event.messages,[]);
  assert.deepEqual(await restarted.execute(request),reply);
  await restarted.ack(event.id);
  assert.equal(restarted.pendingEvents().length,0);
});

test('rank effect is retained when the rule fails after an intermediate mutation',async t=>{
  const f=await fixture(t);
  let pending=[];
  const effect={id:'rank-partial',chatId:request.chatId,playerId:request.playerId,type:'rank.increment',game:'pokemon'};
  f.domain.command=async()=>{pending=[effect];throw Error('later rule failed');};
  f.domain.takeEffects=()=>{const result=pending;pending=[];return result;};
  await assert.rejects(()=>f.service.execute(request),e=>e.code==='result_unknown');
  assert.deepEqual(f.service.pendingEvents()[0].effects,[effect]);
  await assert.rejects(()=>f.service.execute(request),e=>e.status===409);
});


test('store catalogue stays attached to the image for menus and purchase results', async () => {
  const service = new PokemonService({domain:fakeDomain(),logger:()=>{},media:{
    put:async media=>({mediaId:'shop-image',mimeType:media.mime,filename:media.filename})
  }});
  const catalogue='🏪 Loja\n'+ 'Item - 20 moedas\n'.repeat(90);
  for (const text of [catalogue, '✅ Comprou Poção!\n\n'+catalogue]) {
    const messages=await service.messages({texto:text,media:{mime:'image/jpeg',buffer:Buffer.from('fixture'),filename:'loja-pokemon.jpg'}});
    assert.equal(messages.length,1);
    assert.equal(messages[0].type,'image');
    assert.equal(messages[0].text,text);
  }
  const other=await service.messages({texto:catalogue,media:{mime:'image/jpeg',buffer:Buffer.from('fixture'),filename:'treinador.jpg'}});
  assert.deepEqual(other.map(message=>message.type),['image','text']);
  assert.equal(other[1].text,catalogue);
});
