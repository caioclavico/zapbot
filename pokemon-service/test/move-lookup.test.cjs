'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {createMoveLookup}=require('../runtime/move-lookup.cjs');
const flush=()=>new Promise(setImmediate);
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
function fixture(options={}) {
  let now=0;const timers=new Map();
  const lookup=createMoveLookup({...options,now:()=>now,
    schedule(fn,ms){const timer={fn,due:now+ms};timers.set(timer,timer);return timer;},cancel:t=>timers.delete(t)});
  return {lookup,timers,advance(ms){now+=ms;for(const timer of [...timers.values()])if(timer.due<=now)timer.fn();},setTime(ms){now=ms;}};
}
test('one request per move shares successful results and bounded LRU cache expires',async()=>{
  const f=fixture({maxEntries:2,ttlMs:100000});let calls=0;const work=deferred();
  const a=f.lookup.lookup('tackle',()=>{calls++;return work.promise;});
  const b=f.lookup.lookup('tackle',()=>{calls++;});
  assert.equal(a,b);await flush();assert.equal(calls,1);
  const value={slug:'tackle'};work.resolve(value);assert.equal(await a,value);await flush();
  assert.equal(await f.lookup.lookup('tackle',()=>{throw Error('should use cache');}),value);
  await f.lookup.lookup('growl',()=>({slug:'growl'}));await flush();
  await f.lookup.lookup('bite',()=>({slug:'bite'}));await flush();
  assert.equal(f.lookup.stats().cache_entries,2);
  await f.lookup.lookup('tackle',()=>{calls++;return value;});await flush();assert.equal(calls,2);
  f.advance(100001);
  await f.lookup.lookup('tackle',()=>{calls++;return value;});await flush();assert.equal(calls,3);
  assert.equal(f.timers.size,0);
});
test('deadline is 15 seconds, including waiting for a slot; queued work never starts after expiry',async()=>{
  const f=fixture({concurrency:1});let calls=0,signal;
  const a=f.lookup.lookup('slow',s=>{calls++;signal=s;return new Promise(()=>{});});
  const b=f.lookup.lookup('queued',()=>{calls++;});
  const settled=Promise.allSettled([a,b]);await flush();assert.equal(calls,1);
  assert.ok([...f.timers.values()].every(t=>t.due===15000));
  f.advance(15000);const results=await settled;
  assert.ok(results.every(r=>r.status==='rejected'&&r.reason.name==='TimeoutError'));
  assert.equal(signal.aborted,true);assert.equal(calls,1);
  assert.equal(f.lookup.stats().pending,0);assert.equal(f.lookup.stats().queued,0);
  assert.equal(f.lookup.stats().running,1);assert.equal(f.lookup.stats().aborting,1);
  await assert.rejects(f.lookup.lookup('slow',()=>{calls++;}),/still aborting/);
  assert.equal(calls,1);
});
test('aborted cooperative requests release slots and failures are never cached or retried',async()=>{
  const f=fixture({concurrency:1});let calls=0;
  const load=signal=>{calls++;return new Promise((_,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));};
  const failed=f.lookup.lookup('slow',load);const observed=assert.rejects(failed,{name:'TimeoutError'});
  await flush();f.advance(15000);await observed;await flush();
  assert.equal(f.lookup.stats().running,0);assert.equal(calls,1);assert.equal(f.lookup.stats().cache_entries,0);
  assert.equal(await f.lookup.lookup('slow',()=>{calls++;return {valid:true};}).then(v=>v.valid),true);
  await flush();assert.equal(calls,2);
});
test('partial failure preserves ordered successes, null and HTTP failures are not cached',async()=>{
  const f=fixture();let calls=0;
  const results=await Promise.allSettled([
    f.lookup.lookup('ok',()=>({power:40})),
    f.lookup.lookup('missing',()=>{calls++;return null;}),
    f.lookup.lookup('bad',()=>Promise.reject(Error('HTTP 503')))]);
  await flush();assert.equal(results[0].value.power,40);assert.equal(results[1].value,null);
  assert.equal(results[2].status,'rejected');assert.equal(f.lookup.stats().cache_entries,1);
  await f.lookup.lookup('missing',()=>{calls++;return null;});assert.equal(calls,2);
});
test('concurrency and queue size are bounded without background retries',async()=>{
  const f=fixture({concurrency:2,maxPending:3});const work=deferred();let active=0,max=0,calls=0;
  const load=async()=>{calls++;max=Math.max(max,++active);await work.promise;active--;return 1;};
  const jobs=['a','b','c'].map(key=>f.lookup.lookup(key,load));
  await assert.rejects(f.lookup.lookup('d',load),/full/);await flush();assert.equal(calls,2);
  work.resolve();assert.deepEqual(await Promise.all(jobs),[1,1,1]);await flush();
  assert.equal(max,2);assert.equal(calls,3);assert.equal(f.lookup.stats().pending,0);assert.equal(f.timers.size,0);
});
test('late response after deadline never seeds cache even if timer callback was delayed',async()=>{
  const f=fixture();const work=deferred();
  const value=f.lookup.lookup('late',()=>work.promise);const observed=assert.rejects(value,{name:'TimeoutError'});
  await flush();f.setTime(15001);work.resolve({power:40});await observed;await flush();
  assert.equal(f.lookup.stats().cache_entries,0);assert.equal(f.lookup.stats().running,0);
});
test('invalid identifiers and limits fail without network work',async()=>{
  assert.throws(()=>createMoveLookup({concurrency:0}));
  const f=fixture();await assert.rejects(f.lookup.lookup('../secrets',()=>{throw Error('not called');}),/identifier/);
  assert.equal(f.lookup.stats().pending,0);
});
