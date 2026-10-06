'use strict';
const {performance}=require('node:perf_hooks');

// Read-only HTTP work. The deadline includes queue wait, headers and body.
// An uncooperative loader retains its slot after abort: never exceed the
// physical concurrency limit by starting more requests behind a stuck one.
function createMoveLookup({timeoutMs=15000,concurrency=4,maxPending=128,maxEntries=256,
  ttlMs=6*60*60*1000,now=()=>performance.now(),schedule=setTimeout,cancel=clearTimeout}={}) {
  for(const value of [timeoutMs,concurrency,maxPending,maxEntries,ttlMs])
    if(!Number.isSafeInteger(value)||value<=0)throw new Error('Invalid move lookup limits');
  const cache=new Map(),pending=new Map(),queue=[],running=new Set();
  let timeouts=0,failures=0,hits=0;
  function finish(job,error,value) {
    if(job.done)return;
    if(now()>=job.deadline&&error?.name!=='TimeoutError'){expire(job);return;}
    job.done=true;cancel(job.timer);pending.delete(job.key);
    const index=queue.indexOf(job);if(index>=0)queue.splice(index,1);
    if(error){failures++;job.reject(error);}
    else {
      if(value!=null){cache.delete(job.key);cache.set(job.key,{value,expires:now()+ttlMs});
        while(cache.size>maxEntries)cache.delete(cache.keys().next().value);}
      job.resolve(value);
    }
  }
  function drain() {
    while(running.size<concurrency&&queue.length) {
      const job=queue.shift();if(job.done)continue;
      if(now()>=job.deadline){expire(job);continue;}
      running.add(job);
      Promise.resolve().then(()=>{
        if(job.done||now()>=job.deadline){expire(job);throw new Error('Move lookup expired before loading');}
        return job.load(job.controller.signal);
      }).then(
        value=>finish(job,null,value),error=>finish(job,error)
      ).finally(()=>{running.delete(job);drain();});
    }
  }
  function expire(job) {
    if(job.done)return;
    timeouts++;
    const error=Object.assign(new Error('Move lookup deadline exceeded'),{name:'TimeoutError'});
    job.controller.abort(error);finish(job,error);drain();
  }
  return {
    lookup(key,load) {
      if(typeof key!=='string'&&!Number.isSafeInteger(key))return Promise.reject(new Error('Invalid move identifier'));
      key=String(key);
      if(!/^[a-z0-9-]{1,80}$/.test(key))return Promise.reject(new Error('Invalid move identifier'));
      const cached=cache.get(key);
      if(cached&&cached.expires>now()){hits++;cache.delete(key);cache.set(key,cached);return Promise.resolve(cached.value);}
      cache.delete(key);
      if(pending.has(key))return pending.get(key).promise;
      if(pending.size>=maxPending)return Promise.reject(new Error('Move lookup queue is full'));
      // An expired, still running request cannot be duplicated by another caller.
      if([...running].some(job=>job.key===key))return Promise.reject(new Error('Move request is still aborting'));
      const job={key,load,done:false,controller:new AbortController(),deadline:now()+timeoutMs};
      job.promise=new Promise((resolve,reject)=>{job.resolve=resolve;job.reject=reject;});
      pending.set(key,job);queue.push(job);
      job.timer=schedule(()=>expire(job),timeoutMs);
      drain();return job.promise;
    },
    stats() {return {pending:pending.size,queued:queue.length,running:running.size,
      aborting:[...running].filter(job=>job.done).length,cache_entries:cache.size,hits,timeouts,failures};}
  };
}
module.exports={createMoveLookup};
