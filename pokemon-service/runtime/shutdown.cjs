'use strict';
const {AsyncLocalStorage}=require('node:async_hooks');
const {performance}=require('node:perf_hooks');
const {logError}=require('./errors.cjs');
const context=new AsyncLocalStorage();

function emit(ctx,event,extra={}) {
  try { ctx.logger(JSON.stringify({event,elapsed_ms:Math.round(ctx.now()-ctx.started),...extra})); } catch {}
}
function snapshot(ctx) {
  const pending={};
  for(const provider of [ctx.pending,...[...ctx.stages.values()].map(s=>s.pending)]) {
    try { if(provider)Object.assign(pending,provider()); } catch { pending.diagnostics_unavailable=true; }
  }
  return {pending,stages:[...ctx.stages.values()].map(s=>({stage:s.name,duration_ms:Math.round(ctx.now()-s.started)}))};
}
function observe(execute,{logger=console.log,pending=()=>({}),now=()=>performance.now()}={}) {
  if(context.getStore())return execute(context.getStore());
  const ctx={logger,pending,now,started:now(),stages:new Map()};
  return context.run(ctx,()=>execute(ctx));
}
function stage(name,execute,pending) {
  const ctx=context.getStore();
  if(!ctx)return execute();
  const token={};const started=ctx.now();
  ctx.stages.set(token,{name,started,pending});
  emit(ctx,'shutdown_stage_started',{stage:name,...snapshot(ctx)});
  return Promise.resolve().then(execute).then(value=>{
    const summary=name==='active.allSettled'?{settled:value.length,rejected:value.filter(v=>v.status==='rejected').length}:{};
    if(name==='active.allSettled')for(const result of value)if(result.status==='rejected')
      logError(ctx.logger,{event:'shutdown_active_failed',stage:name},result.reason);
    emit(ctx,'shutdown_stage_completed',{stage:name,duration_ms:Math.round(ctx.now()-started),...summary,...snapshot(ctx)});
    return value;
  },error=>{
    logError(ctx.logger,{event:'shutdown_stage_failed',stage:name,duration_ms:Math.round(ctx.now()-started),elapsed_ms:Math.round(ctx.now()-ctx.started),...snapshot(ctx)},error);
    throw error;
  }).finally(()=>ctx.stages.delete(token));
}
function createStop({service,server,cleanup=()=>{},logger=console.log,exit=code=>process.exit(code),
  schedule=setTimeout,cancel=clearTimeout,now=()=>performance.now()}) {
  let closing=false;
  return function stop(signal='unknown') {
    if(closing)return;closing=true;
    return observe(async ctx=>{
      const timers=[];let timedOut=false;
      emit(ctx,'shutdown_started',{signal,timeout_ms:60000,...snapshot(ctx)});
      for(const seconds of [10,30,50]) {
        const timer=schedule(()=>emit(ctx,'shutdown_waiting',{threshold_seconds:seconds,...snapshot(ctx)}),seconds*1000);
        timer.unref?.();timers.push(timer);
      }
      const deadline=schedule(()=>{
        timedOut=true;emit(ctx,'shutdown_timeout',{timeout_ms:60000,...snapshot(ctx)});
        for(const timer of timers)cancel(timer);
        exit(1);
      },60000);
      // Pending Promises alone do not keep Node alive. Keep the deadline
      // referenced until close settles, preventing an implicit exit 0.
      timers.push(deadline);
      try {
        service.accepting=false;cleanup();server.close();
        await stage('service.close',()=>service.close());
        if(!timedOut){emit(ctx,'shutdown_completed',snapshot(ctx));exit(0);}
      } catch(error) {
        logError(logger,{event:'shutdown_failed',elapsed_ms:Math.round(now()-ctx.started),...snapshot(ctx)},error);
        if(!timedOut)exit(1);
      } finally {for(const timer of timers)cancel(timer);}
    },{logger,now,pending:()=>service.shutdownPending()});
  };
}
module.exports={observe,stage,createStop};
