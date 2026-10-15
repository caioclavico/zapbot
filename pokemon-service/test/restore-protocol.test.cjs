'use strict';
const test=require('node:test');const assert=require('node:assert/strict');
const {RestoreProtocol,validate,prepare,hash}=require('../runtime/restore-protocol.cjs');
const copy=x=>JSON.parse(JSON.stringify(x));
const account=id=>({ativo:0,equipe:[{'id-pokemon':id,nivel:1,hp:10,'hp-atual':10,ataque:5,defesa:5,'atq-esp':5,'def-esp':5,veloc:5,'xp-desde-nivel':0,golpes:[],tipos:['normal']}],pc:[]});
function fixture(){
 const data={group:{p:account('old'),other:account('other')},ops:{},backups:{},locks:{}};const events=[];
 const store={
 readAccountLock:async(c,p)=>data.locks[p]??null,
 readGroupSerial:async()=>copy(data.group),readOperationSerial:async id=>data.ops[id]?copy(data.ops[id]):null,
 insertOperationImmutable:async(id,op)=>{if(data.ops[id])throw Error('exists');data.ops[id]=copy(op);},
 claimAccount:async(c,p,id)=>{if(data.locks[p])throw Error('locked');data.locks[p]=id;},
 insertBackupImmutable:async(id,b)=>{events.push('backup');if(data.backups[id]&&hash(data.backups[id])!==hash(b))throw Error('backup_conflict');data.backups[id]=copy(b);},
 readBackupSerial:async id=>data.backups[id]?copy(data.backups[id]):null,indexBackup:async()=>{},
 transitionOperation:async(id,from,to)=>{if(data.ops[id].state!==from)throw Error('conflict');data.ops[id].state=to;},
 casGroup:async(c,before,next)=>{events.push('cas');if(hash(before)!==hash(data.group))return false;data.group=copy(next);return true;},
 installConfirmedGroup:async()=>events.push('installed'),releaseAccount:async(c,p,id)=>{if(data.locks[p]!==id)throw Error('lock');delete data.locks[p];}
 };return {data,events,store,controller:new RestoreProtocol(store,{gate:()=>true})};
}
test('preview performs no writes; gate defaults closed',async()=>{const f=fixture();const before=copy(f.data);const c=new RestoreProtocol(f.store);const p=await c.preview('g','p',account('new'),1);assert.deepEqual(f.data,before);await assert.rejects(c.confirm('g','p',p.token,async()=>account('new')),/disabled/);});
test('backup confirmed before CAS; other account unchanged; replay idempotent',async()=>{const f=fixture(),old=copy(f.data.group);const p=await f.controller.preview('g','p',account('new'),1);const r=await f.controller.confirm('g','p',p.token,async()=>account('new'));assert.equal(r.status,'completed');assert.deepEqual(f.data.backups[r.backup].account,old.p);assert.deepEqual(f.data.group.other,old.other);assert.deepEqual(f.events,['backup','cas','installed']);await f.controller.confirm('g','p',p.token,async()=>null);assert.equal(f.events.filter(x=>x==='cas').length,1);});
test('backup failure cannot touch group',async()=>{const f=fixture(),before=copy(f.data.group);f.store.insertBackupImmutable=async()=>{throw Error('failed');};const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')));assert.deepEqual(f.data.group,before);assert.ok(f.data.locks.p);});
test('lost CAS response is reconciled after restart, never repeated',async()=>{const f=fixture(),original=f.store.casGroup;f.store.casGroup=async(...x)=>{await original(...x);throw Error('lost');};const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')));assert.ok(f.data.locks.p);const restarted=new RestoreProtocol(f.store,{gate:()=>true});assert.equal((await restarted.confirm('g','p',p.token,async()=>null)).status,'completed');assert.equal(f.events.filter(x=>x==='cas').length,1);assert.equal(f.data.locks.p,undefined);});
test('source/target change and foreign code are rejected',async()=>{const f=fixture(),p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','other',p.token,async()=>account('new')),/invalid_code/);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('changed')),/source_changed/);f.data.group.p=account('modified');await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')),/target_conflict/);});
test('invalid identities/normalization are blocked',()=>{const a=account('x');a.pc=[copy(a.equipe[0])];assert.throws(()=>validate(a),/identity/);a.pc=[];a.equipe[0]['xp-desde-nivel']=10;assert.throws(()=>validate(a),/normalization/);});
test('legacy XP and victories migrate with the domain 9-XP/3-percent rule',()=>{
 const base=account('legacy');base.equipe[0].nivel=2;base.equipe[0]['xp-desde-nivel']=18;
 const migrated=prepare(base),p=migrated.target.equipe[0];assert.equal(p.nivel,4);assert.equal(p['xp-desde-nivel'],0);assert.ok(migrated.transformations.some(x=>x.includes('nível 2 → 4')&&x.includes('HP')));
 const victories=account('wins');delete victories.equipe[0]['xp-desde-nivel'];victories.equipe[0]['vitorias-desde-nivel']=3;assert.equal(prepare(victories).target.equipe[0]['xp-desde-nivel'],0);
 const absent=account('absent');delete absent.equipe[0]['xp-desde-nivel'];assert.equal(prepare(absent).target.equipe[0]['xp-desde-nivel'],0);
});
test('legacy migration preserves zero HP, level 100, infirmary and identity',()=>{
 const a=account('zero');a.equipe[0].nivel=99;a.equipe[0]['xp-desde-nivel']=18;a.equipe[0]['hp-atual']=0;
 a.enfermaria=[{pokemon:{...a.equipe[0],'id-pokemon':'ward','nivel':1,'xp-desde-nivel':9},'pronto-em':1}];
 const out=prepare(a).target;assert.equal(out.equipe[0].nivel,100);assert.equal(out.equipe[0]['hp-atual'],0);assert.equal(out.equipe[0]['id-pokemon'],'zero');
});
test('read-only diagnostic keeps every record and marks conversions independently',async()=>{
 const f=fixture(),source=account('diagnostic');
 source.equipe=Array.from({length:13},(_,i)=>({...source.equipe[0],'id-pokemon':`diag-${i}`}));
 source.equipe[1]['xp-desde-nivel']=9;
 source.equipe[2]['xp-desde-nivel']='bad';
 source.equipe.push(null);
 const out=await f.controller.diagnostic('g','p',source,2);
 assert.equal(out.total,14);
 assert.equal(out.version,2);
 assert.equal(f.controller.previews.size,0);
 assert.equal(out.entries.filter(x=>x.status==='converted').length,1);
 assert.equal(out.entries.filter(x=>x.status==='invalid').length,2);
 assert.deepEqual(out.entries.map(x=>x.index),Array.from({length:14},(_,i)=>i+1));
 assert.equal(out.entries[1].original.nivel,1);
 assert.equal(out.entries[1].pokemon.nivel,2);
});
test('diagnostic remains available with active current operations while confirmation stays blocked',async()=>{
 const f=fixture();
 Object.assign(f.data.group.p,{'times-liga':{active:true},raid:{id:'r'},'adventure-eventos':{event:true},ofertas:{move:true},batalha:{id:'b'}});
 const out=await f.controller.diagnostic('g','p',account('legacy'),1);
 assert.equal(out.total,1);
 assert.equal(out.confirmationBlocked,true);
 assert.deepEqual(new Set(out.blockingOperations),new Set(['adventure-eventos','ofertas','batalha','raid','times-liga']));
 await assert.rejects(f.controller.preview('g','p',account('legacy'),1),/operational_review/);
});
test('ambiguous legacy XP fields remain blocked',()=>{const a=account('ambiguous');a.equipe[0]['xp-desde-nivel']=1;a.equipe[0]['vitorias-desde-nivel']=2;assert.throws(()=>prepare(a),/normalization/);const invalid=account('invalid');invalid.equipe[0]['xp-desde-nivel']=-1;assert.throws(()=>prepare(invalid),/normalization/);});
test('expiry and simultaneous confirmations cannot create two CAS writes',async()=>{const f=fixture();let clock=0;const c=new RestoreProtocol(f.store,{gate:()=>true,now:()=>clock});const p=await c.preview('g','p',account('new'),1);clock=600001;await assert.rejects(c.confirm('g','p',p.token,async()=>account('new')),/invalid_code/);clock=0;const p2=await c.preview('g','p',account('new'),1);await Promise.allSettled([c.confirm('g','p',p2.token,async()=>account('new')),c.confirm('g','p',p2.token,async()=>account('new'))]);assert.equal(f.events.filter(x=>x==='cas').length,1);});
test('CAS conflict stays blocked and preserves concurrent accounts',async()=>{const f=fixture();f.store.casGroup=async()=>{f.data.group.other=account('concurrent');return false;};const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')),/cas_conflict/);assert.equal(f.data.group.p.equipe[0]['id-pokemon'],'old');assert.equal(f.data.group.other.equipe[0]['id-pokemon'],'concurrent');assert.ok(f.data.locks.p);});
test('Cassandra backup is immutable and confirmed with SERIAL; no legacy writes',async()=>{const {RestoreStore}=require('../runtime/restore-store.cjs');const calls=[];let row=null;const s=new RestoreStore(async(sql,args,opts)=>{calls.push({sql,args,opts});if(sql.startsWith('INSERT'))row={valor:args[2]};return {first:()=>row,wasApplied:()=>true};},'zapbot');await s.insertImmutable('pokemon-restauracao-backups','id',{a:1});assert.equal(calls[0].sql.includes('IF NOT EXISTS'),true);assert.equal(calls[1].opts.consistency,'SERIAL');await assert.rejects(s.cas('pokemon-restauracao-backups','id',{},{}),/immutable/);await assert.rejects(s.readSerial('legacy_estado','id'),/invalid/);assert.equal(calls.some(c=>c.sql.includes('legacy_')),false);});

test('journal completion with failed lock release is recovered, not reported prematurely',async()=>{const f=fixture(),release=f.store.releaseAccount;let fail=true;f.store.releaseAccount=async(...args)=>{if(fail)throw Error('release failed');return release(...args);};const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')));assert.ok(f.data.locks.p);fail=false;assert.equal((await new RestoreProtocol(f.store,{gate:()=>true}).confirm('g','p',p.token,async()=>null)).status,'completed');assert.equal(f.data.locks.p,undefined);assert.equal(f.events.filter(x=>x==='cas').length,1);});

test('undo requires new preview and new immutable backup; old backups never change',async()=>{const f=fixture(),old=copy(f.data.group.p);const p=await f.controller.preview('g','p',account('new'),1);const r=await f.controller.confirm('g','p',p.token,async()=>account('new'));const firstBackup=copy(f.data.backups[r.backup]);await assert.rejects(f.controller.previewBackup('g','other',r.backup),/invalid_backup/);const undo=await f.controller.previewBackup('g','p',r.backup);const undone=await f.controller.confirm('g','p',undo.token,async()=>f.data.backups[r.backup].account);assert.notEqual(r.backup,undone.backup);assert.deepEqual(f.data.backups[r.backup],firstBackup);assert.equal(f.data.backups[undone.backup].account.equipe[0]['id-pokemon'],'new');assert.deepEqual(f.data.group.p.equipe,old.equipe);});
test('index failure keeps immutable backup and durable lock without a CAS attempt',async()=>{const f=fixture();f.store.indexBackup=async()=>{throw Error('index_failed');};const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')));assert.equal(Object.keys(f.data.backups).length,1);assert.ok(f.data.locks.p);assert.equal(f.events.filter(x=>x==='cas').length,0);assert.equal(f.data.group.p.equipe[0]['id-pokemon'],'old');});
test('PC unification preserves records, original team indices and trainer fields',async()=>{const f=fixture(),source=account('team');source.pc=account('pc').equipe;source.moedas=123;source.pokedex={original:true};const p=await f.controller.preview('g','p',source,1);assert.equal(p.transformations.length,1);await f.controller.confirm('g','p',p.token,async()=>source);assert.deepEqual(f.data.group.p.equipe,[...source.equipe,...source.pc]);assert.deepEqual(f.data.group.p.pc,[]);assert.equal(f.data.group.p.moedas,123);assert.deepEqual(f.data.group.p.pokedex,source.pokedex);});
test('supervised diagnosis without receipt never unlocks or retries CAS',async()=>{const f=fixture();f.store.casGroup=async()=>false;const p=await f.controller.preview('g','p',account('new'),1);await assert.rejects(f.controller.confirm('g','p',p.token,async()=>account('new')));const before=copy(f.data);const d=await f.controller.diagnose(hash(p.token));assert.equal(d.receiptConfirmed,false);assert.equal(d.releaseAllowed,false);assert.deepEqual(f.data,before);});
test('current delivery receipts are not discarded by replacing trainer',async()=>{const f=fixture();f.data.group.p['adventure-entregas']={egg:true};const preview=await f.controller.preview('g','p',account('new'),1);assert.equal(preview.count,1);assert.equal(Object.keys(f.data.ops).length,0);});
test('pending adventure callback blocks preview',async()=>{const f=fixture();f.data.group.p['adventure-eventos']={event:true};await assert.rejects(f.controller.preview('g','p',account('new'),1),/operational_review/);assert.equal(Object.keys(f.data.ops).length,0);});
test('current delivery receipts are preserved while pending callbacks block',async()=>{const f=fixture();f.data.group.p['adventure-entregas']={egg:'receipt'};const p=await f.controller.preview('g','p',account('new'),1);assert.equal(p.count,1);await f.controller.confirm('g','p',p.token,async()=>account('new')).catch(()=>{});assert.equal(f.data.group.p['adventure-entregas'].egg,'receipt');});
test('infirmary, shiny, rarity, equipped items and trainer progress are preserved',async()=>{const f=fixture(),source=account('team');source.enfermaria=[{pokemon:{...account('joy').equipe[0],shiny:true,raridade:'raro',item:'restos'},'pronto-em':123456}];source.ginasios={agua:{vitorias:2}};source['vitorias-treinador']=12;const p=await f.controller.preview('g','p',source,1);await f.controller.confirm('g','p',p.token,async()=>source);assert.deepEqual(f.data.group.p.enfermaria,source.enfermaria);assert.deepEqual(f.data.group.p.ginasios,source.ginasios);assert.equal(f.data.group.p['vitorias-treinador'],12);});
test('invalid moves, shiny and infirmary timestamps block preview without writes',async()=>{for(const change of [a=>a.equipe[0].golpes=['unknown'],a=>a.equipe[0].shiny='true',a=>a.enfermaria=[{pokemon:account('joy').equipe[0]}]]){const f=fixture(),source=account('x');change(source);await assert.rejects(f.controller.preview('g','p',source,1));assert.equal(Object.keys(f.data.ops).length,0);}});
