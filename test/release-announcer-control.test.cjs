'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {spawn}=require('node:child_process');
const {makeControl}=require('../scripts/lib/release-announcer-control.cjs');

function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'release-control-'));const file=path.join(dir,'state.json');const control=makeControl(file);return {dir,file,control};}

test('missing, malformed, and permission-unsafe control fail closed',()=>{
  const f=fixture();assert.throws(()=>f.control.read(),{code:'control_missing'});
  fs.writeFileSync(f.file,'{');assert.throws(()=>f.control.read(),{code:'control_unreadable'});
  fs.writeFileSync(f.file,JSON.stringify({version:1,state:'open',updatedAt:'now',reason:'test'}));fs.chmodSync(f.file,0o666);
  assert.throws(()=>f.control.read(),{code:'control_permissions_invalid'});
});

test('symlink control file is rejected without reading its target',()=>{
  const f=fixture();const target=path.join(f.dir,'outside.json');
  fs.writeFileSync(target,JSON.stringify({version:1,state:'open',updatedAt:'now',reason:'test'}),{mode:0o600});
  fs.symlinkSync(target,f.file);
  assert.throws(()=>f.control.read(),{code:'control_permissions_invalid'});
});

test('suspend is durable and a newly constructed controller remains closed',async()=>{
  const f=fixture();f.control.initializeOpen();
  const suspended=await f.control.suspendAndWait({reason:'maintenance'});
  assert.equal(suspended.state,'suspended');assert.equal(suspended.quiescent,true);
  const afterRestart=makeControl(f.file);
  assert.equal(afterRestart.status().state,'suspended');
  await assert.rejects(afterRestart.withPublication(async()=>assert.fail('must not run')),{code:'publication_suspended'});
});

test('first-run maintenance state can be created suspended without an open window',()=>{
  const f=fixture();
  assert.equal(f.control.initializeSuspended('cold-backup').state,'suspended');
  assert.equal(makeControl(f.file).status().state,'suspended');
  assert.throws(()=>f.control.initializeOpen(),{code:'control_already_exists'});
});

test('suspension waits for an admitted publication and prevents later admissions',async()=>{
  const f=fixture();f.control.initializeOpen();let finish;const barrier=new Promise(resolve=>{finish=resolve;});
  const sending=f.control.withPublication(async()=>barrier,'synthetic-key');
  await new Promise(resolve=>setImmediate(resolve));
  const suspending=f.control.suspendAndWait({reason:'maintenance',timeoutMs:1000});
  await new Promise(resolve=>setTimeout(resolve,20));
  assert.equal(f.control.status().state,'suspended');assert.equal(f.control.status().publicationInFlight,true);
  await assert.rejects(f.control.withPublication(async()=>{}),{code:'publication_suspended'});
  finish('done');await sending;
  const result=await suspending;assert.equal(result.quiescent,true);assert.equal(result.publicationInFlight,false);
});

test('timeout keeps suspension active and resume refuses a live lock',async()=>{
  const f=fixture();f.control.initializeOpen();let finish;const barrier=new Promise(resolve=>{finish=resolve;});
  const sending=f.control.withPublication(()=>barrier,'synthetic-key');
  await new Promise(resolve=>setImmediate(resolve));
  const result=await f.control.suspendAndWait({reason:'maintenance',timeoutMs:0});
  assert.equal(result.quiescent,false);assert.equal(result.state,'suspended');
  assert.throws(()=>f.control.resume(),{code:'publication_inflight'});
  finish();await sending;
  assert.equal(f.control.resume().state,'open');
});

test('orphan publication lock remains fail-closed until explicit audited recovery',async()=>{
  const f=fixture();f.control.initializeOpen();
  const lock=`${f.file}.publication-inflight`;
  fs.writeFileSync(lock,JSON.stringify({version:1,pid:999999,startedAt:'synthetic-crash'}),{mode:0o600});

  const afterRestart=makeControl(f.file);
  assert.equal(afterRestart.status().publicationInFlight,true);
  await assert.rejects(afterRestart.withPublication(async()=>assert.fail('orphan lock must block')),
    {code:'publication_inflight'});
  const suspended=await afterRestart.suspendAndWait({reason:'recovery',timeoutMs:0});
  assert.equal(suspended.state,'suspended');
  assert.equal(suspended.quiescent,false);
  assert.equal(suspended.publicationInFlight,true);
  assert.throws(()=>afterRestart.resume(),{code:'publication_inflight'});
  await assert.rejects(afterRestart.withPublication(async()=>assert.fail('suspension must block')),
    {code:'publication_suspended'});
  assert.equal(fs.existsSync(lock),true);
  assert.equal(afterRestart.status().state,'suspended');
});

test('abrupt publisher death leaves a durable orphan lease and cannot resume',async()=>{
  const f=fixture();f.control.initializeOpen();
  const modulePath=path.resolve(__dirname,'../scripts/lib/release-announcer-control.cjs');
  const childCode=`const {makeControl}=require(${JSON.stringify(modulePath)});`+
    `const c=makeControl(${JSON.stringify(f.file)});`+
    `c.withPublication(()=>new Promise(()=>{}),'crash-test').catch(()=>{});`+
    `process.stdout.write('LEASE_HELD\\n');`;
  const child=spawn(process.execPath,['-e',childCode],{stdio:['ignore','pipe','pipe']});
  let output='';
  try{
    await new Promise((resolve,reject)=>{
      const timeout=setTimeout(()=>reject(new Error('lease_start_timeout')),3000);
      child.once('error',reject);
      child.stdout.on('data',chunk=>{
        output+=chunk.toString();
        if(output.includes('LEASE_HELD')){clearTimeout(timeout);resolve();}
      });
    });
    assert.equal(f.control.status().publicationInFlight,true);
    child.kill('SIGKILL');
    await new Promise(resolve=>child.once('close',resolve));
    const afterCrash=makeControl(f.file);
    const suspended=await afterCrash.suspendAndWait({reason:'crash-recovery',timeoutMs:0});
    assert.equal(suspended.state,'suspended');
    assert.equal(suspended.quiescent,false);
    assert.equal(suspended.publicationInFlight,true);
    assert.throws(()=>afterCrash.resume(),{code:'publication_inflight'});
    assert.equal(fs.existsSync(`${f.file}.publication-inflight`),true);
  }finally{
    if(child.exitCode===null&&!child.killed)child.kill('SIGKILL');
  }
});

test('suspend fails closed if persistent control cannot be written',async()=>{
  const f=fixture();
  // A directory at the state path makes atomic publication impossible.
  fs.mkdirSync(f.file);
  await assert.rejects(f.control.suspendAndWait(),{code:'control_permissions_invalid'});
});

test('operational reason is constrained to non-sensitive tokens',async()=>{
  const f=fixture();f.control.initializeOpen();
  await assert.rejects(f.control.suspendAndWait({reason:'maintenance-token secret-value'}),{code:'reason_invalid'});
  assert.equal(f.control.status().state,'open');
});
