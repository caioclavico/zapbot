'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {installStartupRecovery}=require('../scripts/lib/whatsapp-startup.cjs');
const contextError=()=>new Error('Protocol error (Runtime.callFunctionOn): Execution context was destroyed');
function deferred(){let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b;});return {promise,resolve,reject};}
function fixture(inject=async()=>{},options={}){
  let live=true,closed=0,logouts=0,initializations=0,calls=0;
  const logs=[],states=[],page=new EventEmitter(),client=new EventEmitter();
  const frame={url:()=> 'https://web.whatsapp.com/'};
  page.isClosed=()=>!live;page.mainFrame=()=>frame;
  const browser={isConnected:()=>live,process:()=>({exitCode:live?null:0,signalCode:null})};
  Object.assign(client,{pupPage:page,pupBrowser:browser,
    authStrategy:{logout:async()=>{logouts++;}},
    inject:async()=>{calls++;return inject(client);},
    initialize:async()=>{
      initializations++;await client.inject();
      page.on('framenavigated',async current=>{
        if(current.url().includes('post_logout=1'))await client.authStrategy.logout();
        await client.inject();client.emit('ready');
      });
      client.emit('ready');
    },
    destroy:async()=>{closed++;live=false;}
  });
  const recovery=installStartupRecovery(client,{backoff:[1,2],timeoutMs:10000,injectTimeoutMs:1000,
    logger:s=>logs.push(JSON.parse(s)),onState:s=>states.push(s),...options});
  return {client,recovery,page,frame,logs,states,get calls(){return calls;},get closed(){return closed;},
    get logouts(){return logouts;},get initializations(){return initializations;}};
}
test('transient inject retries the same browser, reaches READY and never initializes twice',async()=>{
  let count=0;const f=fixture(async()=>{if(++count===1)throw contextError();});
  const browser=f.client.pupBrowser;
  await f.recovery.initialize();await f.recovery.initialize();
  assert.equal(f.calls,2);assert.equal(f.initializations,1);assert.equal(f.client.pupBrowser,browser);
  assert.equal(f.closed,0);assert.equal(f.logouts,0);assert.equal(f.recovery.acceptingReady(),true);
  assert.deepEqual(f.logs.map(x=>x.event),['whatsapp_inject_retry']);
  await f.recovery.close();assert.equal(f.closed,1);
});
test('bounded exhaustion closes Chromium, preserves auth and rejects late READY',async()=>{
  const f=fixture(async()=>{throw contextError();});
  await assert.rejects(f.recovery.initialize(),/Execution context was destroyed/);
  assert.equal(f.calls,3);assert.equal(f.closed,1);assert.equal(f.logouts,0);
  assert.deepEqual(f.logs.filter(x=>x.event==='whatsapp_inject_retry').map(x=>x.backoff_ms),[1,2]);
  f.client.emit('ready');assert.equal(f.recovery.acceptingReady(),false);
  assert.equal(f.client.zapbotStartupState,'error');await f.recovery.close();assert.equal(f.closed,1);
});
test('non-transient errors have no retries or auth reset',async()=>{
  const error=new Error('unknown startup failure');const f=fixture(async()=>{throw error;});
  await assert.rejects(f.recovery.initialize(),e=>e===error);
  assert.equal(f.calls,1);assert.equal(f.closed,1);assert.equal(f.logouts,0);
});
test('concurrent injections share one Promise and one attempt',async()=>{
  const work=deferred(),f=fixture(()=>work.promise);
  const a=f.client.inject(),b=f.client.inject();assert.equal(a,b);
  await new Promise(setImmediate);assert.equal(f.calls,1);
  work.resolve();await a;await f.recovery.close();
});
test('navigation rejection is handled and subframes do not inject',async()=>{
  let bad=false;const f=fixture(async()=>{if(bad)throw contextError();});
  await f.recovery.initialize();bad=true;
  const navigate=f.page.listeners('framenavigated')[0];
  await navigate({url:()=> 'https://irrelevant-frame.example/'});assert.equal(f.calls,1);
  await navigate(f.frame);assert.equal(f.calls,4);assert.equal(f.closed,1);
  assert.equal(f.client.zapbotStartupState,'error');assert.equal(f.logouts,0);
});
test('logout navigation is terminal without invoking upstream LocalAuth.logout',async()=>{
  const f=fixture();await f.recovery.initialize();
  const navigate=f.page.listeners('framenavigated')[0];
  f.frame.url=()=> 'https://web.whatsapp.com/?post_logout=1';
  await navigate(f.frame);assert.equal(f.logouts,0);assert.equal(f.calls,1);assert.equal(f.closed,1);
  assert.equal(f.logs.at(-1).reason,'authentication_transition');
});
test('injection timeout closes the browser and never retries a timed-out operation',async()=>{
  const work=deferred(),timers=[],f=fixture(()=>work.promise,
    {schedule(fn,ms){const t={fn,ms};timers.push(t);return t;},cancel(){}});
  const destroy=f.client.destroy;f.client.destroy=async()=>{await destroy();work.reject(contextError());};
  const startup=f.recovery.initialize();const checked=assert.rejects(startup,e=>e.code==='STARTUP_TIMEOUT');
  await new Promise(setImmediate);timers.find(t=>t.ms===1000).fn();await checked;
  assert.equal(f.calls,1);assert.equal(f.closed,1);assert.equal(f.logouts,0);
});
test('SIGTERM during backoff cancels recovery and drains the only initializer',async()=>{
  const retry=deferred(),f=fixture(async()=>{throw contextError();},
    {schedule(fn,ms){if(ms===1)retry.resolve();return {fn,ms};},cancel(){}});
  const starting=f.recovery.initialize();const checked=assert.rejects(starting);
  await retry.promise;await f.recovery.close();await checked;
  assert.equal(f.calls,1);assert.equal(f.closed,1);assert.equal(f.logouts,0);
  assert.equal(f.client.zapbotStartupState,'stopped');
});
test('timeout during launch closes a browser assigned late; no browser is abandoned',async()=>{
  const launch=deferred(),timers=[],f=fixture(()=>Promise.resolve(),
    {schedule(fn,ms){const t={fn,ms};timers.push(t);return t;},cancel(){}});
  const browser=f.client.pupBrowser;f.client.pupBrowser=null;
  f.client.initialize=async()=>{await launch.promise;f.client.pupBrowser=browser;await f.client.inject();};
  // install after replacing initialize, as it is captured exactly once.
  const recovery=installStartupRecovery(f.client,{timeoutMs:100,schedule(fn,ms){const t={fn,ms};timers.push(t);return t;},cancel(){},logger(){}});
  const checked=assert.rejects(recovery.initialize(),e=>e.code==='STARTUP_TIMEOUT');
  await new Promise(setImmediate);timers.find(t=>t.ms===100).fn();await checked;
  launch.resolve();await recovery.close();assert.equal(f.closed,1);assert.equal(f.calls,0);
});
test('cleanup errors remain fatal; profile locks and auth are never deleted',async()=>{
  const f=fixture(async()=>{throw new Error('not transient');});
  f.client.destroy=async()=>{throw new Error('browser close failed');};
  await assert.rejects(f.recovery.initialize(),/browser close failed/);
  assert.ok(f.logs.some(x=>x.event==='whatsapp_cleanup_failed'));assert.equal(f.logouts,0);
  await assert.rejects(f.recovery.close(),/browser close failed/);
});
test('attempt timeout is cancelled before backoff, even when the error arrives near its deadline',async()=>{
  const backoff=deferred(),timers=[];let count=0;
  const f=fixture(async()=>{if(++count===1)throw contextError();},
    {backoff:[5000,5000],schedule(fn,ms){const t={fn,ms,cancelled:false};timers.push(t);if(ms===5000)backoff.resolve();return t;},cancel(t){t.cancelled=true;}});
  const startup=f.recovery.initialize();await backoff.promise;
  assert.equal(timers.find(t=>t.ms===1000).cancelled,true);
  timers.find(t=>t.ms===5000).fn();await startup;assert.equal(f.calls,2);await f.recovery.close();
});
test('READY emitted during a failing inject cannot publish readiness while recovering',async()=>{
  const backoff=deferred(),timers=[];let count=0;
  const f=fixture(async client=>{if(++count===1){client.emit('ready');throw contextError();}},
    {schedule(fn,ms){const t={fn,ms};timers.push(t);if(ms===1)backoff.resolve();return t;},cancel(){}});
  const startup=f.recovery.initialize();await backoff.promise;
  assert.equal(f.recovery.acceptingReady(),false);assert.equal(f.client.zapbotStartupState,'recovering');
  timers.find(t=>t.ms===1).fn();await startup;assert.equal(f.recovery.acceptingReady(),true);await f.recovery.close();
});
test('a browser process killed during cleanup does not masquerade as clean shutdown',async()=>{
  const f=fixture();await f.recovery.initialize();
  f.client.pupBrowser.process=()=>({exitCode:null,signalCode:'SIGKILL'});
  await assert.rejects(f.recovery.close(),/Chromium closure was not confirmed/);
});
test('QR requested for an existing profile is terminal, never a recovery strategy',async()=>{
  const f=fixture(undefined,{restoreOnly:true});await f.recovery.initialize();
  assert.equal(f.recovery.acceptingQR(),false);
  f.client.emit('qr','SECRET_QR_TOKEN');await new Promise(setImmediate);
  assert.equal(f.closed,1);assert.equal(f.logouts,0);assert.equal(f.recovery.acceptingReady(),false);
  assert.ok(!JSON.stringify(f.logs).includes('SECRET_QR_TOKEN'));
});
test('a missing session under an existing root is not recreated or initialized',async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'zapbot-auth-metadata-'));
  t.after(()=>fs.rmSync(root,{recursive:true,force:true}));
  fs.writeFileSync(path.join(root,'existing-profile-evidence'),'PRESERVE');
  const client=new EventEmitter();let initialized=0;
  client.authStrategy={dataPath:root};client.initialize=async()=>{initialized++;};
  const recovery=installStartupRecovery(client,{logger(){}});
  await assert.rejects(recovery.initialize(),/automatic recreation blocked/);
  assert.equal(initialized,0);assert.equal(fs.existsSync(path.join(root,'session')),false);
  assert.equal(fs.readFileSync(path.join(root,'existing-profile-evidence'),'utf8'),'PRESERVE');
});
test('page crash after READY invalidates health and closes owned resources',async()=>{
  const f=fixture();await f.recovery.initialize();
  f.page.emit('error',new Error('fixture renderer crashed'));await new Promise(setImmediate);
  assert.equal(f.recovery.acceptingReady(),false);assert.equal(f.client.zapbotStartupState,'error');
  assert.equal(f.closed,1);assert.equal(f.logouts,0);assert.equal(f.logs.at(-1).phase,'page');
});
test('a page application error mentioning context destruction is not retried as a CDP failure',async()=>{
  const f=fixture(async()=>{throw new Error('Evaluation failed: Execution context was destroyed');});
  await assert.rejects(f.recovery.initialize());assert.equal(f.calls,1);assert.equal(f.closed,1);
});

test('late launch followed by setup failure cleans up even after the startup timeout won',async()=>{
  const launch=deferred(),timers=[],client=new EventEmitter();let live=true,closed=0;
  const browser={isConnected:()=>live,process:()=>({exitCode:live?null:0,signalCode:null})};
  Object.assign(client,{inject:async()=>{},initialize:async()=>{
    await launch.promise;client.pupBrowser=browser;throw new Error('late page setup failed');
  },destroy:async()=>{closed++;live=false;}});
  const recovery=installStartupRecovery(client,{timeoutMs:100,logger(){},
    schedule(fn,ms){const t={fn,ms};timers.push(t);return t;},cancel(){}});
  const checked=assert.rejects(recovery.initialize(),e=>e.code==='STARTUP_TIMEOUT');
  await new Promise(setImmediate);timers.find(t=>t.ms===100).fn();await checked;
  assert.equal(closed,0);launch.resolve();await new Promise(setImmediate);
  assert.equal(closed,1);assert.equal(live,false);await recovery.close();assert.equal(closed,1);
});

test('READY cannot start the application with a closed page or logged-out client',async()=>{
  for(const invalidate of [f=>{f.page.isClosed=()=>true;},f=>{f.client.lastLoggedOut=true;}]){
    const f=fixture();await f.recovery.initialize();invalidate(f);f.client.emit('ready');
    await new Promise(setImmediate);
    assert.equal(f.recovery.acceptingReady(),false);assert.equal(f.client.zapbotStartupState,'error');
    assert.equal(f.closed,1);assert.equal(f.logouts,0);await f.recovery.close();
  }
});

test('disconnection is terminal; a late READY never revives health or exposes its reason',async()=>{
  const f=fixture();await f.recovery.initialize();f.client.emit('disconnected','PRIVATE_REASON');
  await new Promise(setImmediate);f.client.emit('ready');
  assert.equal(f.recovery.acceptingReady(),false);assert.equal(f.closed,1);assert.equal(f.logouts,0);
  assert.ok(!JSON.stringify(f.logs).includes('PRIVATE_REASON'));await f.recovery.close();
});

test('a detached main frame cannot throw outside the navigation failure handler',async()=>{
  const f=fixture();await f.recovery.initialize();
  f.page.mainFrame=()=>{throw new Error('fixture frame disposed');};
  await f.page.listeners('framenavigated')[0](f.frame);
  assert.equal(f.recovery.acceptingReady(),false);assert.equal(f.closed,1);assert.equal(f.logouts,0);
  assert.equal(f.logs.at(-1).phase,'navigation');await f.recovery.close();
});
