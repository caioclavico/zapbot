'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createStop,stage}=require('../runtime/shutdown.cjs');
const {PokemonService}=require('../runtime/service.cjs');
const {execFile}=require('node:child_process');
const path=require('node:path');
function deferred(){let resolve;const promise=new Promise(r=>{resolve=r;});return {promise,resolve};}
function fixture(shutdown=async()=>{}) {
  const logs=[],exits=[],timers=[];let time=0,closed=0,stopped=0;
  const domain={registerModule(){},stopTimers(){stopped++;},shutdown};
  const service=new PokemonService({domain,media:{},logger:line=>logs.push(JSON.parse(line))});
  const stop=createStop({service,server:{close(){closed++;}},logger:service.logger,now:()=>time,
    exit:code=>exits.push(code),schedule(fn,ms){const timer={fn,ms,cancelled:false,unref(){}};timers.push(timer);return timer;},cancel:t=>{t.cancelled=true;}});
  return {service,stop,logs,exits,timers,get closed(){return closed;},get stopped(){return stopped;},
    fire(ms){time=ms;const timer=timers.find(t=>t.ms===ms);assert.ok(!timer.cancelled);timer.fn();}};
}
test('normal shutdown logs stages and exits zero only after draining',async()=>{
  const f=fixture(async()=>{await stage('aguardar_operacoes_BANG_',async()=>{},()=>({game_chat_queues:0}));
    await stage('aguardar_todas_BANG_',async()=>{},()=>({persistence_pending:0}));
    await stage('client.shutdown',async()=>{});});
  await f.stop('SIGTERM');
  assert.deepEqual(f.exits,[0]);assert.equal(f.closed,1);assert.equal(f.stopped,1);
  assert.equal(f.service.accepting,false);assert.ok(f.timers.every(t=>t.cancelled));
  const stages=f.logs.filter(l=>l.event==='shutdown_stage_completed').map(l=>l.stage);
  assert.deepEqual(stages,['active.allSettled','aguardar_operacoes_BANG_','aguardar_todas_BANG_','client.shutdown','domain.shutdown','service.close']);
  if(require('../runtime/metrics.cjs').enabled)
    assert.ok(f.logs.filter(l=>l.stage).every(l=>l.duration_ms>=0 || l.event==='shutdown_stage_started'));
  else assert.ok(f.logs.every(l=>!('elapsed_ms' in l)&&!('duration_ms' in l)));
  await f.stop('SIGINT');assert.deepEqual(f.exits,[0]);
});
test('active work is drained before domain and reports 10/30/50 second warnings',async()=>{
  const work=deferred();let domainCalled=false;
  const f=fixture(async()=>{domainCalled=true;});
  f.service.domain.shutdownPending=()=>({game_operations:2,persistence_pending:3});
  f.service.active.set('private-request',work.promise);
  const closing=f.stop('SIGTERM');await new Promise(setImmediate);
  assert.equal(domainCalled,false);
  for(const ms of [10000,30000,50000])f.fire(ms);
  const warnings=f.logs.filter(l=>l.event==='shutdown_waiting');
  assert.deepEqual(warnings.map(l=>l.threshold_seconds),[10,30,50]);
  assert.ok(warnings.every(l=>l.pending.http_active===1&&l.stages.some(s=>s.stage==='active.allSettled')));
  assert.ok(warnings.every(l=>l.pending.game_operations===2&&l.pending.persistence_pending===3));
  assert.ok(!JSON.stringify(f.logs).includes('private-request'));
  f.service.active.clear();work.resolve();await closing;
  assert.equal(domainCalled,true);assert.deepEqual(f.exits,[0]);
});
test('persistence rejection retains failure after Cassandra cleanup and exits one',async()=>{
  let cleaned=false;const error=new Error('unconfirmed persistence');
  const f=fixture(async()=>{
    try{await stage('aguardar_todas_BANG_',()=>Promise.reject(error),()=>({persistence_failed_modules:1}));}
    finally{await stage('client.shutdown',async()=>{cleaned=true;});}
  });
  await f.stop('SIGTERM');assert.equal(cleaned,true);assert.deepEqual(f.exits,[1]);
  const failure=f.logs.find(l=>l.event==='shutdown_stage_failed'&&l.stage==='aguardar_todas_BANG_');
  assert.equal(failure.pending.persistence_failed_modules,1);
  assert.ok(f.logs.some(l=>l.event==='shutdown_failed'&&l.error.message===error.message));
  assert.ok(!f.logs.some(l=>l.event==='shutdown_completed'));
});
test('timeout keeps 60 seconds and never claims success even if work later settles',async()=>{
  const work=deferred();const f=fixture(()=>stage('client.shutdown',()=>work.promise));
  const closing=f.stop('SIGTERM');await new Promise(setImmediate);
  f.fire(60000);assert.deepEqual(f.exits,[1]);
  const timeout=f.logs.find(l=>l.event==='shutdown_timeout');
  assert.equal(timeout.timeout_ms,60000);if(require('../runtime/metrics.cjs').enabled)assert.equal(timeout.elapsed_ms,60000);
  else assert.equal(timeout.elapsed_ms,undefined);
  assert.ok(timeout.stages.some(s=>s.stage==='client.shutdown'));
  work.resolve();await closing;assert.deepEqual(f.exits,[1]);
  assert.ok(!f.logs.some(l=>l.event==='shutdown_completed'));
});
test('a rejected active operation is reported without skipping the durable shutdown',async()=>{
  const f=fixture();f.service.active.set('private',Promise.reject(new Error('failed command')));
  await f.stop('SIGTERM');
  assert.ok(f.logs.some(l=>l.event==='shutdown_active_failed'));
  assert.equal(f.logs.find(l=>l.stage==='active.allSettled'&&l.event==='shutdown_stage_completed').rejected,1);
  assert.equal(f.stopped,1);assert.deepEqual(f.exits,[0]);
});
test('diagnostic logger failure never hides Cassandra shutdown failure',async()=>{
  const exits=[];const service={accepting:true,shutdownPending:()=>({}),close:()=>Promise.reject(new Error('Cassandra failure'))};
  const stop=createStop({service,server:{close(){}},logger(){throw new Error('logger failure');},exit:code=>exits.push(code)});
  await stop('SIGTERM');assert.deepEqual(exits,[1]);
});
test('an unresolved close with no other handles cannot implicitly exit zero',async()=>{
  const program=`const {createStop}=require(process.argv[1]);
    createStop({service:{accepting:true,shutdownPending:()=>({}),close:()=>new Promise(()=>{})},
      server:{close(){}},schedule:(fn,ms)=>setTimeout(fn,ms===60000?20:ms)})('SIGTERM');`;
  await new Promise((resolve,reject)=>execFile(process.execPath,['-e',program,path.resolve(__dirname,'../runtime/shutdown.cjs')],
    {timeout:5000},(error,stdout)=>{
      try {
        assert.equal(error?.code,1);
        const logs=stdout.trim().split('\n').map(line=>JSON.parse(line));
        assert.ok(logs.some(l=>l.event==='shutdown_timeout'&&l.timeout_ms===60000));
        assert.ok(!logs.some(l=>l.event==='shutdown_completed'));
        resolve();
      } catch(failure){reject(failure);}
    }));
});
