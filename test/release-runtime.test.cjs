const test = require('node:test');
const assert = require('node:assert/strict');
const { ReleaseAnnouncerRuntime } = require('../scripts/lib/release-runtime.cjs');
const { makeControl } = require('../scripts/lib/release-announcer-control.cjs');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const gates={jvmHealthy:true,nodeHealthy:true,whatsappReady:true,datomicAccessible:true,versionConfirmed:true,rollbackComplete:true};
function intent(overrides={}) { return {status:'confirmed',deployedVersion:'12.8.31',confirmedVersion:'12.8.31',rollbackComplete:true,chatId:'pilot@g.us',release:{version:'12.8.31',contentHash:'h',highlights:['Ajuda']},...overrides}; }
function fakePlatform() {
  const calls=[]; let row=null;
  return {calls,
    async getReleaseAnnouncement(){return {announcement:row};},
    async createReleaseAnnouncement(...args){calls.push(['create',...args]);return {duplicate:false};},
    async claimReleaseAnnouncement(...args){calls.push(['claim',...args]);row={status:'claimed',sendStartedAt:null};return {claimed:true,fence:1};},
    async completeReleaseAnnouncement(...args){calls.push(['complete',...args]);row.status='completed';return {completed:true};},
    async failReleaseAnnouncement(...args){calls.push(['fail',...args]);row.status='uncertain';return {failed:true};},
    setRow(value){row=value;}
  };
}
const transport={async sendAndConfirm(){return {confirmed:true,messageId:'synthetic'};}};

function openControl(){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'release-control-runtime-'));
  const control=makeControl(path.join(dir,'control.json'));control.initializeOpen();
  return {dir,control};
}

test('disabled, unconfirmed, and ordinary boot are inert', async()=>{
  const p=fakePlatform();
  const off=new ReleaseAnnouncerRuntime({enabled:false,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true});
  assert.equal((await off.publishConfirmed({intent:intent(),gates,platform:p,transport,workerId:'w'})).status,'disabled');
  const on=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true});
  assert.equal((await on.publishConfirmed({gates,platform:p,transport,workerId:'w'})).status,'no-confirmed-release');
  assert.equal((await on.publishConfirmed({intent:intent({status:'candidate'}),gates,platform:p,transport,workerId:'w'})).status,'no-confirmed-release');
  assert.equal(p.calls.length,0);
});

test('requires all gates, confirmed deployed version, rollback, and the authorized pilot', async()=>{
  const p=fakePlatform(); const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:id=>id==='pilot@g.us'});
  assert.equal((await rt.publishConfirmed({intent:intent(),gates:{...gates,jvmHealthy:false},platform:p,transport,workerId:'w'})).status,'not-ready');
  assert.equal((await rt.publishConfirmed({intent:intent({rollbackComplete:false}),gates,platform:p,transport,workerId:'w'})).status,'no-confirmed-release');
  assert.equal((await rt.publishConfirmed({intent:intent({confirmedVersion:'other'}),gates,platform:p,transport,workerId:'w'})).status,'version-mismatch');
  assert.equal((await rt.publishConfirmed({intent:intent({chatId:'other@g.us'}),gates,platform:p,transport,workerId:'w'})).status,'group-not-authorized');
  assert.equal(p.calls.length,0);
});

test('persistent path is create → claim → ACK-backed transport → complete', async()=>{
  const p=fakePlatform(); const c=openControl(); const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true,publicationControl:c.control});
  const result=await rt.publishConfirmed({intent:intent(),gates,platform:p,transport,workerId:'worker-1'});
  assert.equal(result.status,'completed');
  assert.deepEqual(p.calls.map(x=>x[0]),['create','claim','complete']);
  assert.equal(p.calls[0][1].idempotencyKey,'12.8.31:h:pilot@g.us');
});

test('uncertain transport is recorded through canonical store fail; legacy outbox API is absent', async()=>{
  const p=fakePlatform(); const c=openControl(); const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true,publicationControl:c.control});
  const uncertain={async sendAndConfirm(){return {confirmed:false,uncertain:true};}};
  assert.equal((await rt.publishConfirmed({intent:intent(),gates,platform:p,transport:uncertain,workerId:'w'})).status,'uncertain');
  assert.deepEqual(p.calls.map(x=>x[0]),['create','claim','fail']);
  assert.equal(typeof rt.onOperational,'undefined');
});

test('runtime without a durable control cannot touch store or transport',async()=>{
  const p=fakePlatform();let sends=0;
  const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true});
  assert.equal((await rt.publishConfirmed({intent:intent(),gates,platform:p,transport:{sendAndConfirm:async()=>{sends++;return {confirmed:true};}},workerId:'w'})).status,'control-unavailable');
  assert.equal(sends,0);assert.deepEqual(p.calls,[]);
});

test('uncertain receipt blocks replay after persistent suspension and explicit resume',async()=>{
  const p=fakePlatform();p.setRow({status:'uncertain',sendStartedAt:Date.now()});
  const c=openControl();let sends=0;
  const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true,publicationControl:c.control});
  await c.control.suspendAndWait({reason:'maintenance'});
  c.control.resume('maintenance-complete');
  const first=await rt.publishConfirmed({intent:intent(),gates,platform:p,transport:{sendAndConfirm:async()=>{sends++;return {confirmed:true};}},workerId:'w'});
  assert.equal(first.status,'uncertain-manual-review');
  assert.equal(sends,0);assert.deepEqual(p.calls,[]);
});
