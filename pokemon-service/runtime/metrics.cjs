'use strict';
const {AsyncLocalStorage}=require('node:async_hooks');
const {performance}=require('node:perf_hooks');
const contexts=new AsyncLocalStorage();
function run(fn){return contexts.run({cassandra_ms:0,pokeapi_ms:0,sprite_download_ms:0,image_processing_ms:0},fn);}
function snapshot(){return {...contexts.getStore()};}
function measure(name,fn){
  const context=contexts.getStore(); const start=performance.now();
  const done=()=>{if(context)context[name]=(context[name]||0)+(performance.now()-start);};
  try{const value=fn();if(value&&typeof value.then==='function')return value.then(x=>{done();return x;},e=>{done();throw e;});done();return value;}catch(e){done();throw e;}
}
module.exports={run,snapshot,measure};
