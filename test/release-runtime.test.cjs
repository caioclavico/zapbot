const test = require('node:test');
const assert = require('node:assert/strict');
const { ReleaseAnnouncerRuntime } = require('../scripts/lib/release-runtime.cjs');

const gates={jvmHealthy:true,nodeHealthy:true,whatsappReady:true,datomicAccessible:true,versionConfirmed:true,rollbackComplete:true};
function intent(overrides={}) { return {status:'confirmed',deployedVersion:'12.8.31',confirmedVersion:'12.8.31',rollbackComplete:true,chatId:'pilot@g.us',release:{version:'12.8.31',contentHash:'h',highlights:['Ajuda']},...overrides}; }
function fakePlatform() {
  const calls=[];
  return {calls,
    async createReleaseAnnouncement(...args){calls.push(['create',...args]);return {duplicate:false};},
    async claimReleaseAnnouncement(...args){calls.push(['claim',...args]);return {claimed:true,fence:1};},
    async completeReleaseAnnouncement(...args){calls.push(['complete',...args]);return {completed:true};},
    async failReleaseAnnouncement(...args){calls.push(['fail',...args]);return {failed:true};}
  };
}
const transport={async sendAndConfirm(){return {confirmed:true,messageId:'synthetic'};}};

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
  const p=fakePlatform(); const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true});
  const result=await rt.publishConfirmed({intent:intent(),gates,platform:p,transport,workerId:'worker-1'});
  assert.equal(result.status,'completed');
  assert.deepEqual(p.calls.map(x=>x[0]),['create','claim','complete']);
  assert.equal(p.calls[0][1].idempotencyKey,'12.8.31:h:pilot@g.us');
});

test('uncertain transport is recorded through canonical store fail; legacy outbox API is absent', async()=>{
  const p=fakePlatform(); const rt=new ReleaseAnnouncerRuntime({enabled:true,pilotChatId:'pilot@g.us',isChatAuthorized:()=>true});
  const uncertain={async sendAndConfirm(){return {confirmed:false,uncertain:true};}};
  assert.equal((await rt.publishConfirmed({intent:intent(),gates,platform:p,transport:uncertain,workerId:'w'})).status,'uncertain');
  assert.deepEqual(p.calls.map(x=>x[0]),['create','claim','fail']);
  assert.equal(typeof rt.onOperational,'undefined');
});
