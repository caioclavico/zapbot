'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');
const path=require('node:path');

// Separate processes exercise startup configuration and require caches exactly
// as production does. No Cassandra, network, game timers or persistent files.
const script=String.raw`
const assert=require('node:assert/strict');
const enabled=process.env.EXPECT_ENABLED==='true';
const {performance}=require('node:perf_hooks');
let clockReads=0;
Object.defineProperty(performance,'now',{value:()=>{
  clockReads++;if(!enabled)throw Error('disabled metrics read clock');return clockReads;
}});
const metrics=require('./runtime/metrics.cjs');
assert.equal(metrics.enabled,enabled);
const rejected=Promise.reject(new Error('original'));rejected.catch(()=>{});
const original=Promise.resolve('ok');let calls=0;
const work=metrics.run(()=>metrics.measure('pokeapi_ms',()=>{calls++;return original;}));
if(!enabled){
  assert.equal(work,original);
  assert.equal(metrics.measure('failure',()=>rejected),rejected);
  assert.equal(metrics.snapshot(),metrics.snapshot());
  assert.equal(metrics.now(),undefined);
}else assert.ok(clockReads>0);
assert.equal(calls,1);
const thrown=Error('sync original');
assert.throws(()=>metrics.measure('failure',()=>{throw thrown;}),e=>e===thrown);
assert.equal(metrics.measure('sync',()=>42),42);
(async()=>{
  await work;
  if(enabled){
    const captured=await metrics.run(async()=>{
      await metrics.measure('pokeapi_ms',()=>Promise.resolve());
      return metrics.snapshot();
    });
    assert.ok(captured.pokeapi_ms>0);
  }
  const {PokemonService}=require('./runtime/service.cjs');
  const data={},order=[],logs=[];
  let commands=0;
  const domain={registerModule(k){data[k]={};},isReady(){return true;},
    load(k){return data[k];},async store(k,v){data[k]=structuredClone(v);},
    async reserve(k,id,v){if(data[k][id])return false;data[k][id]=v;return true;},
    async command(req,emit){commands++;order.push(req.requestId);await emit('first');return 'second';},
    takeEffects(){return [];},stopTimers(){},async shutdown(){}};
  const service=new PokemonService({domain,media:{},logger:s=>logs.push(JSON.parse(s))});
  const input={requestId:'one',chatId:'chat',playerId:'player',command:'pk treinador'};
  const [a,b]=await Promise.all([service.execute(input),service.execute({...input,requestId:'two'})]);
  assert.deepEqual(order,['one','two']);assert.equal(commands,2);
  assert.deepEqual(a.messages.map(m=>m.text),['first','second']);
  assert.deepEqual(await service.execute(input),a);assert.equal(commands,2);
  if(enabled){assert.equal(typeof a.timings.request_total_ms,'number');assert.equal(logs.filter(l=>l.event==='command_completed').length,2);}
  else {assert.deepEqual(a.timings,{});assert.equal(a.timings,b.timings);assert.equal(logs.length,0);}
  domain.command=async()=>{throw Error('domain failed');};
  await assert.rejects(service.execute({...input,requestId:'failed'}),e=>e.code==='result_unknown');
  assert.ok(logs.some(l=>l.event==='command_failed'&&l.error.message==='domain failed'));
  const {createStop}=require('./runtime/shutdown.cjs');
  const exits=[],timers=[];
  const stop=createStop({service,server:{close(){}},logger:service.logger,exit:c=>exits.push(c),
    now:()=>{if(!enabled)throw Error('shutdown read clock');return 5;},
    schedule(fn,ms){const t={fn,ms,unref(){}};timers.push(t);return t;},cancel(){}});
  await stop('SIGTERM');assert.deepEqual(exits,[0]);
  assert.deepEqual(timers.map(t=>t.ms),[10000,30000,50000,60000]);
  assert.ok(logs.some(l=>l.event==='shutdown_completed'));
  if(!enabled){
    assert.equal(clockReads,0);
    assert.ok(logs.every(l=>!('duration_ms' in l)&&!('elapsed_ms' in l)&&!('request_total_ms' in l)));
    assert.ok(logs.flatMap(l=>l.stages||[]).every(s=>!('duration_ms' in s)));
  }
})().catch(e=>{console.error(e);process.exitCode=1;});
`;
for(const flag of [undefined,'false','true',' TRUE ','invalid']){
  test(`performance flag ${flag??'(unset)'} preserves operations and errors`,()=>{
    const env={...process.env,EXPECT_ENABLED:String(flag?.trim().toLowerCase()==='true')};
    if(flag===undefined)delete env.PERFORMANCE_METRICS;else env.PERFORMANCE_METRICS=flag;
    execFileSync(process.execPath,['-e',script],{cwd:path.join(__dirname,'..'),env,stdio:'pipe'});
  });
}
