const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {EventEmitter}=require('node:events');
const {createReleaseBootstrap}=require('../scripts/lib/release-bootstrap.cjs');

function fixture(){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'release-bootstrap-'));
  const intentPath=path.join(dir,'intent.json');
  const intent={status:'confirmed',deployedVersion:'12.8.31',confirmedVersion:'12.8.31',rollbackComplete:true,chatId:'pilot@g.us',release:{version:'12.8.31',contentHash:'synthetic',highlights:['Ajuda']}};
  fs.writeFileSync(intentPath,JSON.stringify(intent));
  const rows=new Map(),calls=[];let sends=0;
  const platform={
    async getReleaseAnnouncement(key){return {announcement:rows.get(key)||null};},
    async getReleaseByMessageId(id){return {announcement:[...rows.values()].find(x=>x.messageId===id)||null};},
    async beginReleaseAnnouncementSend(key,claimant,fence){const row=rows.get(key);if(row.claimant!==claimant||row.fence!==fence)return {started:false};Object.assign(row,{status:'uncertain',sendStartedAt:Date.now()});return {started:true};},
    async createReleaseAnnouncement(a){calls.push('create');if(!rows.has(a.idempotencyKey))rows.set(a.idempotencyKey,{...a,status:'pending',fence:0});return {duplicate:false};},
    async claimReleaseAnnouncement(key,claimant){calls.push('claim');const row=rows.get(key);if(row.status==='completed')return {claimed:false,reason:'not_claimable'};Object.assign(row,{status:'claimed',claimant,fence:(row.fence||0)+1});return {claimed:true,fence:row.fence};},
    async associateReleaseMessage(key,claimant,fence,messageId){const row=rows.get(key);Object.assign(row,{messageId,claimant,fence});return {announcement:row};},
    async ackReleaseMessage(id,ack,claimant,fence){const row=[...rows.values()].find(x=>x.messageId===id);if(!row||row.claimant!==claimant||row.fence!==fence||ack<1)return {confirmed:false};row.status='completed';return {confirmed:true};},
    async completeReleaseAnnouncement(key){calls.push('complete');rows.get(key).status='completed';return {completed:true};},
    async failReleaseAnnouncement(){calls.push('fail');}
  };
  const client=new EventEmitter();client.sendMessage=async()=>{sends++;const message={id:{_serialized:'wam-bootstrap'}};client.emit('message_ack',message,1);return message;};
  const options={enabled:true,intentPath,pilotChatId:'pilot@g.us',isChatAuthorized:id=>id==='pilot@g.us',client,platform,checkJvmHealth:async()=>true,isWhatsAppReady:()=>true,isNodeHealthy:()=>true,workerId:'node-test'};
  return {dir,intentPath,intent,platform,calls,client,options,get sends(){return sends;},rows};
}

test('WhatsApp READY alone and disabled configuration create no announcement',async()=>{
  const f=fixture();
  const off=createReleaseBootstrap({...f.options,enabled:false});
  assert.equal(f.client.listenerCount('message_ack'),0);
  assert.equal((await off.onWhatsAppReady()).status,'disabled');
  fs.unlinkSync(f.intentPath);
  const noIntent=createReleaseBootstrap(f.options);
  assert.equal((await noIntent.onWhatsAppReady()).status,'no-release-intent');
  assert.equal(f.calls.length,0);assert.equal(f.sends,0);
});

test('requires explicit release, all gates, rollback, enabled release, and authorized pilot',async()=>{
  const f=fixture();
  const run=async overrides=>createReleaseBootstrap({...f.options,...overrides}).onWhatsAppReady();
  assert.equal((await run({checkJvmHealth:async()=>false})).status,'not-ready');
  fs.writeFileSync(f.intentPath,JSON.stringify({...f.intent,rollbackComplete:false}));
  assert.equal((await run({})).status,'invalid-release-intent');
  fs.writeFileSync(f.intentPath,JSON.stringify({...f.intent,release:{...f.intent.release,announcementPolicy:{enabled:false}}}));
  assert.equal((await run({})).status,'release-disabled');
  fs.writeFileSync(f.intentPath,JSON.stringify({...f.intent,chatId:'other@g.us'}));
  assert.equal((await run({})).status,'group-not-authorized');
  assert.equal(f.calls.length,0);
});

test('confirmed pilot uses only Datomic store and simulated ACK path',async()=>{
  const f=fixture();
  const coordinator=createReleaseBootstrap(f.options);
  const result=await coordinator.onWhatsAppReady();
  assert.equal(result.status,'completed');
  assert.deepEqual(f.calls,['create','claim','complete']);
  assert.equal(f.sends,1);
  assert.equal([...f.rows.values()][0].status,'completed');
});

test('announcer failure is converted to isolated result',async()=>{
  const f=fixture();
  f.platform.getReleaseAnnouncement=async()=>{throw Object.assign(new Error('offline'),{code:'NETWORK'});};
  const coordinator=createReleaseBootstrap(f.options);
  assert.equal((await coordinator.onWhatsAppReady()).status,'not-ready');
  assert.equal(f.sends,0);
});
