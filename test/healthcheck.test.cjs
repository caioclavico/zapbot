'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {checkHealth}=require('../scripts/healthcheck.js');
function fixture(status=200){
  const req=new EventEmitter(),res=new EventEmitter(),logs=[],timers=[];
  let destroyed=0;req.destroy=()=>{destroyed++;};res.statusCode=status;res.setEncoding=()=>{};
  const result=checkHealth({get(url,options,callback){assert.equal(url,'http://127.0.0.1:3001/health');callback(res);return req;},
    schedule(fn,ms){const timer={fn,ms,unref(){}};timers.push(timer);return timer;},cancel(t){t.cancelled=true;},
    log:s=>logs.push(s),error:s=>logs.push(s)});
  return {req,res,result,logs,timers,get destroyed(){return destroyed;}};
}
test('READY alone is insufficient; status, HTTP code and connected Chromium must agree',async()=>{
  for(const health of [{status:'ok',whatsapp:'READY',chromium:true},
    {status:'degraded',whatsapp:'READY',chromium:true},{status:'ok',whatsapp:'READY',chromium:false},
    {status:'degraded',whatsapp:'RECOVERING',chromium:true}]){
    const f=fixture();f.res.emit('data',JSON.stringify(health));f.res.emit('end');
    assert.equal(await f.result,health.status==='ok'&&health.chromium?0:1);
    assert.ok(f.timers.every(t=>t.cancelled));
  }
  const f=fixture(503);f.res.emit('data','{"status":"ok","whatsapp":"READY","chromium":true}');f.res.emit('end');
  assert.equal(await f.result,1);
});
test('dripping or absent response still has an absolute five-second deadline',async()=>{
  const f=fixture();f.res.emit('data','{"status":');f.timers[0].fn();f.res.emit('data','"ok"');
  assert.equal(await f.result,1);assert.equal(f.destroyed,1);assert.equal(f.timers[0].ms,5000);
});
test('oversized, invalid and secret-bearing responses never expose bodies',async()=>{
  for(const body of ['x'.repeat(5000),'SECRET_CANARY','{"status":"ok","whatsapp":"SECRET_CANARY","chromium":true}',
    '{"status":"ok","whatsapp":"READY","chromium":true,"token":"SECRET_CANARY"}']){
    const f=fixture();f.res.emit('data',body);f.res.emit('end');await f.result;
    assert.ok(!f.logs.join('').includes('SECRET_CANARY'));
  }
});
test('connection and response errors are failures, without raw exception messages',async()=>{
  for(const emitter of ['req','res']){
    const f=fixture();f[emitter].emit('error',new Error('SECRET_CANARY'));
    assert.equal(await f.result,1);assert.ok(!f.logs.join('').includes('SECRET_CANARY'));
  }
});
