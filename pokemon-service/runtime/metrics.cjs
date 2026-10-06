'use strict';
const {AsyncLocalStorage}=require('node:async_hooks');
const {performance}=require('node:perf_hooks');
const enabled=(process.env.PERFORMANCE_METRICS||'false').trim().toLowerCase()==='true';
const empty=Object.freeze({});
const contexts=enabled?new AsyncLocalStorage():null;
function run(fn){return contexts.run({cassandra_ms:0,pokeapi_ms:0,sprite_download_ms:0,image_processing_ms:0},fn);}
function snapshot(){return {...contexts.getStore()};}
function measure(name,fn){
  const context=contexts.getStore(); const start=performance.now();
  const done=()=>{if(context)context[name]=(context[name]||0)+(performance.now()-start);};
  try{const value=fn();if(value&&typeof value.then==='function')return value.then(x=>{done();return x;},e=>{done();throw e;});done();return value;}catch(e){done();throw e;}
}
function now(){return enabled?performance.now():undefined;}
function elapsed(start){return enabled?Math.round(performance.now()-start):undefined;}
function commandTimings(start,processingMs,responseStart){
  if(!enabled)return empty;
  return {...Object.fromEntries(Object.entries(snapshot()).map(([k,v])=>[k,Math.round(v)])),
    command_processing_ms:Math.round(processingMs),response_preparation_ms:elapsed(responseStart),request_total_ms:elapsed(start)};
}
function completed(logger,requestId,timings){
  if(enabled)logger(JSON.stringify({event:'command_completed',requestId,...timings}));
}
module.exports={enabled,now,elapsed,commandTimings,completed,
  run:enabled?run:fn=>fn(),snapshot:enabled?snapshot:()=>empty,
  measure:enabled?measure:(_name,fn)=>fn()};
