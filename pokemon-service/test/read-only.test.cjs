'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const http=require('node:http');
const {parseReadOnly}=require('../runtime/mode.cjs');
const {PokemonService,handler}=require('../runtime/service.cjs');
const {startBackground}=require('../runtime/background.cjs');

test('read-only configuration fails closed on invalid values',()=>{
  assert.equal(parseReadOnly(undefined),false);
  assert.equal(parseReadOnly('false'),false);
  assert.equal(parseReadOnly('TRUE'),true);
  for(const value of ['','yes','1','treu'])assert.throws(()=>parseReadOnly(value),/true ou false/);
});

test('read-only hydrates but exposes only health and readiness without side effects',async t=>{
  let initialized=0,sideEffects=0,healthy=false;
  const forbidden=()=>{sideEffects++;throw Error('unexpected side effect');};
  const domain={registerModule:()=>{},initialize:async()=>{initialized++;healthy=true;},isReady:()=>healthy,
    command:forbidden,store:forbidden,reserve:forbidden,takeEffects:forbidden,startTimers:forbidden,
    load:forbidden,stopTimers:()=>{},shutdown:async()=>{}};
  const service=new PokemonService({domain,media:{put:forbidden,get:forbidden,prune:forbidden},readOnly:true});
  const server=http.createServer(handler(service,{token:'test-token'}));
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  t.after(async()=>{server.closeAllConnections();await new Promise(r=>server.close(r));await service.close();});
  const call=(route,method='GET')=>fetch(`http://127.0.0.1:${server.address().port}${route}`,{method,headers:{authorization:'Bearer test-token'},signal:AbortSignal.timeout(2000)});
  assert.equal((await call('/health')).status,200);
  assert.equal((await call('/ready')).status,503);
  await service.initialize();
  assert.equal((await call('/ready')).status,200);
  assert.equal(startBackground(service,{schedule:forbidden}),null);
  service.startTimers();
  await service.pruneMedia();
  for(const [route,method] of [['/commands','POST'],['/events/id/ack','POST'],['/events/pending','GET'],['/media/'+'a'.repeat(64),'GET']]){
    const response=await call(route,method);
    assert.equal(response.status,403);
    assert.equal((await response.json()).error,'read_only');
  }
  for(const fn of [()=>service.execute({}),()=>service.executeReserved('id','hash',{}),()=>service.ack('id'),()=>service.enqueueEvent('chat','message'),()=>service.messages('message')]){
    await assert.rejects(fn,e=>e.code==='read_only');
  }
  assert.equal(initialized,1);
  assert.equal(sideEffects,0);
});

test('normal mode still starts game timers and periodic media cleanup',async()=>{
  let timers=0,cleanup=0,unref=0,callback;
  const service={readOnly:false,startTimers:()=>{timers++;},pruneMedia:async()=>{cleanup++;}};
  startBackground(service,{schedule:(fn,delay)=>{assert.equal(delay,3600000);callback=fn;return{unref:()=>{unref++;}};}});
  await callback();
  assert.deepEqual({timers,cleanup,unref},{timers:1,cleanup:1,unref:1});
});
