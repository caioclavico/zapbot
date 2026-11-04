const test=require('node:test');
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const {createReleaseTransport}=require('../scripts/lib/whatsapp-release-transport.cjs');

function harness(existing={}) {
  const rows=new Map(existing.key?[ [existing.key,{...existing}] ]:[]), sends=[];
  const platform={
    async getReleaseAnnouncement(key){return {announcement:rows.get(key)||null};},
    async getReleaseByMessageId(id){return {announcement:[...rows.values()].find(x=>x.messageId===id)||null};},
    async beginReleaseAnnouncementSend(key,claimant,fence){const row=rows.get(key);if(row.status!=='claimed'||row.claimant!==claimant||row.fence!==fence)return {started:false};Object.assign(row,{status:'uncertain',sendStartedAt:Date.now()});return {started:true};},
    async associateReleaseMessage(key,claimant,fence,messageId){const row=rows.get(key);Object.assign(row,{messageId,messageClaimant:claimant,messageFence:fence,status:'uncertain'});return {announcement:row};},
    async ackReleaseMessage(id,ack,claimant,fence){const row=[...rows.values()].find(x=>x.messageId===id);if(!row)return {confirmed:false,reason:'unknown_message'};if(row.messageClaimant!==claimant||row.messageFence!==fence||row.claimant!==claimant||row.fence!==fence)return {confirmed:false,reason:'fence_mismatch'};if(ack<1)return {confirmed:false,reason:'ack_insufficient'};row.status='completed';return {confirmed:true,announcement:row};}
  };
  const client=new EventEmitter();client.sendMessage=async(chat,body)=>{sends.push({chat,body});return {id:{_serialized:'m-new'}};};
  return {rows,sends,platform,client};
}

test('ACK_SERVER before associate is buffered, then correlated and persisted',async()=>{
  const h=harness();h.rows.set('key',{idempotencyKey:'key',status:'claimed',claimant:'w',fence:1});
  h.client.sendMessage=async()=>{h.client.emit('message_ack',{id:{_serialized:'m-new'}},1);return {id:{_serialized:'m-new'}};};
  const t=createReleaseTransport({client:h.client,platform:h.platform});
  const result=await t.sendAndConfirm('pilot','message','key',{timeoutMs:100,claimant:'w',fence:1});
  assert.equal(result.confirmed,true);assert.equal(h.rows.get('key').status,'completed');assert.equal(h.rows.get('key').messageId,'m-new');
});

test('ACK_SERVER during delivery confirms; low ACK and unrelated IDs do not',async()=>{
  const h=harness();h.rows.set('key',{idempotencyKey:'key',status:'claimed',claimant:'w',fence:1});
  const t=createReleaseTransport({client:h.client,platform:h.platform});
  const p=t.sendAndConfirm('pilot','message','key',{timeoutMs:200,claimant:'w',fence:1});
  h.client.emit('message_ack',{id:{_serialized:'unrelated'}},3);
  h.client.emit('message_ack',{id:{_serialized:'m-new'}},0);
  await new Promise(r=>setTimeout(r,5));
  h.client.emit('message_ack',{id:{_serialized:'m-new'}},1);
  assert.equal((await p).confirmed,true);assert.equal(h.rows.get('key').status,'completed');
});

test('restart after association reuses Datomic correlation and does not resend',async()=>{
  const h=harness({key:'key',idempotencyKey:'key',status:'claimed',messageId:'m-existing',claimant:'w2',fence:2,messageClaimant:'w2',messageFence:2});
  let sends=0;h.client.sendMessage=async()=>{sends++;return {id:{_serialized:'wrong'}};};
  const t=createReleaseTransport({client:h.client,platform:h.platform});
  const p=t.sendAndConfirm('pilot','message','key',{timeoutMs:200,claimant:'w2',fence:2});
  h.client.emit('message_ack',{id:{_serialized:'m-existing'}},1);
  assert.equal((await p).confirmed,true);assert.equal(sends,0);
});

test('one listener per client and obsolete fence is rejected',async()=>{
  const h=harness({key:'key',idempotencyKey:'key',status:'claimed',messageId:'m-existing',claimant:'new',fence:2,messageClaimant:'old',messageFence:1});
  const a=createReleaseTransport({client:h.client,platform:h.platform});
  createReleaseTransport({client:h.client,platform:h.platform});
  assert.equal(h.client.listenerCount('message_ack'),1);
  const result=await h.platform.ackReleaseMessage('m-existing',1,'old',1);
  assert.equal(result.confirmed,false);assert.equal(result.reason,'fence_mismatch');
  await a.reconcileBuffered();
});
