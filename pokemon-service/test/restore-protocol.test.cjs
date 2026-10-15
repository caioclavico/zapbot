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
 Object.assign(f.data.group.p,{'times-liga':{iniciante:[0,null,null]},raid:{id:'r'},'adventure-eventos':{event:true},ofertas:{move:true},batalha:{id:'b'}});
 const out=await f.controller.diagnostic('g','p',account('legacy'),1);
 assert.equal(out.total,1);
 assert.equal(out.confirmationBlocked,true);
 assert.deepEqual(new Set(out.blockingOperations),new Set(['adventure-eventos','ofertas','batalha','raid']));
 assert.equal(out.currentRisks.find(x=>x.field==='adventure-eventos').state,'evento malformado; não reconhecido como consumido');
 assert.equal(out.sourceRisks.length,0);
 await assert.rejects(f.controller.preview('g','p',account('legacy'),1),/operational_review/);
});
test('league teams and hunt cooldown are historical metadata, not pending writers',async()=>{
 const f=fixture();
 Object.assign(f.data.group.p,{'times-liga':{iniciante:[0,null,null]},'ultima-cacada':Date.now()-1000});
 const out=await f.controller.diagnostic('g','p',account('legacy'),1);
 assert.equal(out.confirmationBlocked,false);
 const preview=await f.controller.preview('g','p',account('legacy'),1);
 assert.equal(preview.count,1);
});
test('validated gym and named-team configurations are preserved without blocking; malformed references block',async()=>{
 const source=account('legacy');
 source.equipe=[0,1,2].map(i=>({...source.equipe[0],'id-pokemon':`legacy-${i}`}));
 source['time-ginasio']=[0,null,2];
 source['times-prontos']={"time salvo":{"nome":"time salvo","pokemons":["legacy-0","legacy-1","legacy-2"]}};
 const f=fixture(),diagnostic=await f.controller.diagnostic('g','p',source,1);
 assert.equal(diagnostic.confirmationBlocked,false);
 for(const field of ['time-ginasio','times-prontos'])
   assert.equal(diagnostic.sourceRisks.find(x=>x.field===field).category,'permanent_configuration');
 const preview=await f.controller.preview('g','p',source,1);
 assert.deepEqual(f.controller.previews.get(hash(preview.token)).source['time-ginasio'],source['time-ginasio']);
 assert.deepEqual(f.controller.previews.get(hash(preview.token)).source['times-prontos'],source['times-prontos']);

 for(const [field,mutate] of [
   ['time-ginasio',a=>{a['time-ginasio']=[0,1,99];}],
   ['times-prontos',a=>{a['times-prontos']={invalido:{pokemons:['legacy-0','missing','legacy-2']}};}]
 ]){
   const blocked=fixture(),bad=copy(source);mutate(bad);
   const d=await blocked.controller.diagnostic('g','p',bad,1);
   const finding=d.sourceRisks.find(item=>item.field===field&&item.blocking);
   assert.ok(finding,`${field} invalid reference is visible`);
   assert.equal(typeof finding.action,'string');
   await assert.rejects(blocked.controller.checkPreparation('g','p',bad),error=>
     error.code==='operational_review_required'&&error.findings.some(item=>item.field===field&&item.rule===finding.rule));
 }
});
test('raid points are cumulative and retained by max; raid idempotency markers must match',async()=>{
 const source=account('legacy');source['pe-raids']=8;source['raid-progresso-dia']='raid-shared';
 const f=fixture();f.data.group.p['pe-raids']=14;f.data.group.p['raid-progresso-dia']='raid-shared';
 const d=await f.controller.diagnostic('g','p',source,1);
 assert.equal(d.confirmationBlocked,false);
 assert.equal(d.sourceRisks.find(x=>x.field==='pe-raids').category,'cumulative_progress');
 assert.equal(d.currentRisks.find(x=>x.field==='raid-progresso-dia').category,'idempotency_marker');
 const checked=await f.controller.checkPreparation('g','p',source);
 assert.equal(checked.tokenIssued,false);
 const preview=await f.controller.preview('g','p',source,1);
 assert.equal(f.controller.previews.get(hash(preview.token)).source['pe-raids'],14);
 assert.equal(f.controller.previews.get(hash(preview.token)).source['raid-progresso-dia'],'raid-shared');

 const conflict=fixture();conflict.data.group.p['raid-progresso-dia']='raid-current';
 const conflictDiagnostic=await conflict.controller.diagnostic('g','p',source,1);
 const finding=conflictDiagnostic.sourceRisks.find(x=>x.field==='raid-progresso-dia'&&x.blocking);
 assert.equal(finding.category,'incompatible_idempotency_marker');
 assert.equal(typeof finding.action,'string');
 await assert.rejects(conflict.controller.checkPreparation('g','p',source),error=>
   error.code==='operational_review_required'&&error.findings.some(x=>x.field==='raid-progresso-dia'&&x.category===finding.category));
});
test('invalid raid markers and points block without exposing values',async()=>{
 for(const [field,value] of [['raid-progresso-dia',''],['pe-raids',-1],['pe-raids','six']]){
   const f=fixture(),source=account('legacy');source[field]=value;
   const d=await f.controller.diagnostic('g','p',source,1);
   assert.equal(d.confirmationBlocked,true);
   if(value!=='')assert.equal(d.sourceRisks.find(x=>x.field===field).state.includes(String(value)),false);
   await assert.rejects(f.controller.checkPreparation('g','p',source),error=>
     error.code==='operational_review_required'&&error.findings.some(x=>x.field===field));
 }
});
test('pending raid XP blocks on source or current account, including orphaned references',async()=>{
 for(const side of ['source','current']){
   for(const [reference,expected] of side==='source'?[['legacy','operation_pending'],['missing-pokemon','incompatible_reference']]:[['old','operation_pending'],['missing-pokemon','incompatible_reference']]){
     const f=fixture(),source=account('legacy');
     const target=side==='source'?source:f.data.group.p;
     target['xp-raids-pendente']={[reference]:6};
     const d=await f.controller.diagnostic('g','p',source,1);
     const risks=side==='source'?d.sourceRisks:d.currentRisks;
     const finding=risks.find(x=>x.field==='xp-raids-pendente'&&x.blocking);
     assert.ok(finding,`${side} pending XP is visible`);
     assert.equal(finding.category,expected);
     assert.equal(typeof finding.action,'string');
     await assert.rejects(f.controller.checkPreparation('g','p',source),error=>
       error.code==='operational_review_required'&&error.findings.some(x=>x.field==='xp-raids-pendente'&&x.category===finding.category));
     assert.equal(f.controller.previews.size,0);
   }
 }
 const empty=fixture();empty.data.group.p['xp-raids-pendente']={};
 assert.equal((await empty.controller.diagnostic('g','p',account('legacy'),1)).confirmationBlocked,false);
});
test('active gym occupancy is reported with safe resolution and never removed by restore checks',async()=>{
 const f=fixture(),occupancy={gym:'persisted-external-gym',pid:'p',time:['copied-a','copied-b','copied-c']};
 const detailed=['ginásio ocupado: pedra (2 defensores: Pikachu, Eevee)',
                 'ginásio ocupado: água (1 defensores: Pikachu)'];
 f.store.hooks={preflight:async(_chat,player)=>player==='p'?detailed:[]};
 const before=copy(f.data),d=await f.controller.diagnostic('g','p',account('legacy'),1);
 const finding=d.currentRisks.find(x=>x.field===detailed[0]);
 assert.equal(d.confirmationBlocked,true);
 assert.match(finding.rule,/não há encerramento transacional/i);
 assert.match(finding.action,/Aguarde/);
 await assert.rejects(f.controller.checkPreparation('g','p',account('legacy')),error=>
   error.code==='external_operation_pending'&&detailed.every(gym=>error.operations.includes(gym)));
 assert.equal(f.controller.previews.size,0,'ocupação ativa nunca gera código de confirmação');
 assert.equal(await f.store.readAccountLock('g','p'),null,'checagem não bloqueia nem muta a conta');
 assert.deepEqual(f.data,before);
});
test('gym occupancy beginning after diagnostic blocks preparation; concurrent raid marker change conflicts at confirm',async()=>{
 const f=fixture();let occupied=false;
 f.store.hooks={preflight:async()=>occupied?['ginásio com time associado']:[]};
 assert.equal((await f.controller.diagnostic('g','p',account('legacy'),1)).confirmationBlocked,false);
 occupied=true;
 await assert.rejects(f.controller.checkPreparation('g','p',account('legacy')),error=>
   error.code==='external_operation_pending'&&error.operations.includes('ginásio com time associado'));
 occupied=false;
 const source=account('legacy');source['raid-progresso-dia']='raid-legacy';
 const preview=await f.controller.preview('g','p',source,1);
 f.data.group.p['raid-progresso-dia']='raid-concurrent';
 await assert.rejects(f.controller.confirm('g','p',preview.token,async()=>source),/target_conflict/);
 assert.equal(f.events.filter(event=>event==='cas').length,0);
 assert.ok(f.data.locks.p);
});
test('external gym occupation appearing after preview is rechecked under durable lock before backup or CAS',async()=>{
 const f=fixture();let occupied=false;
 f.store.hooks={preflight:async()=>occupied?['ginásio com time associado']:[]};
 const preview=await f.controller.preview('g','p',account('legacy'),1);
 occupied=true;
 await assert.rejects(f.controller.confirm('g','p',preview.token,async()=>account('legacy')),error=>
   error.code==='external_operation_pending'&&error.operations.includes('ginásio com time associado'));
 assert.ok(f.data.locks.p);
 assert.equal(Object.keys(f.data.backups).length,0);
 assert.equal(f.events.filter(event=>event==='cas').length,0);
 assert.equal(Object.values(f.data.ops)[0].state,'prepared');
});
test('drained adventure events are absent; every stored event blocks until durable acknowledgement',async()=>{
 const completed=fixture();completed.data.group.p['adventure-eventos']={};
 assert.equal((await completed.controller.diagnostic('g','p',account('legacy'),1)).confirmationBlocked,false);
 const pending=fixture();pending.data.group.p['adventure-eventos']={e:{'event-id':'e',kind:'capture',at:123,status:'concluido'}};
 assert.deepEqual((await pending.controller.diagnostic('g','p',account('legacy'),1)).blockingOperations,['adventure-eventos']);
 const malformed=fixture();malformed.data.group.p['adventure-eventos']={e:{foo:'bar'}};
 assert.deepEqual((await malformed.controller.diagnostic('g','p',account('legacy'),1)).blockingOperations,['adventure-eventos']);
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
test('current delivery receipts are carried into prepared target without persistence',async()=>{const f=fixture();f.data.group.p['adventure-entregas']={egg:'pokemon-id'};const preview=await f.controller.preview('g','p',account('new'),1);assert.equal(preview.count,1);assert.deepEqual(f.controller.previews.get(hash(preview.token)).source['adventure-entregas'],{egg:'pokemon-id'});assert.equal(Object.keys(f.data.ops).length,0);});
test('pending adventure callback blocks preview',async()=>{const f=fixture();f.data.group.p['adventure-eventos']={event:true};await assert.rejects(f.controller.preview('g','p',account('new'),1),/operational_review/);assert.equal(Object.keys(f.data.ops).length,0);});
test('current delivery receipts are preserved while pending callbacks block',async()=>{const f=fixture();f.data.group.p['adventure-entregas']={egg:'pokemon-id'};f.data.group.p['adventure-eventos']={event:true};const before=copy(f.data.group.p['adventure-entregas']);await assert.rejects(f.controller.preview('g','p',account('new'),1),/operational_review/);assert.deepEqual(f.data.group.p['adventure-entregas'],before);});
test('empty historical delivery receipt does not diverge diagnostic and preparation',async()=>{const f=fixture(),source=account('legacy');source['adventure-entregas']={};const d=await f.controller.diagnostic('g','p',source,1);assert.equal(d.confirmationBlocked,false);assert.deepEqual(d.sourceBlockingOperations,[]);const p=await f.controller.preview('g','p',source,1);assert.equal(p.count,1);assert.equal(Object.prototype.hasOwnProperty.call(p,'token'),true);});
test('historical and current delivery receipts are validated and unioned to prevent duplicate rewards',async()=>{const f=fixture(),source=account('legacy');source['adventure-entregas']={oldHatch:'oldPokemon'};f.data.group.p['adventure-entregas']={newHatch:'newPokemon'};const d=await f.controller.diagnostic('g','p',source,1);assert.equal(d.confirmationBlocked,false);assert.equal(d.sourceRisks.find(x=>x.field==='adventure-entregas').category,'delivered_reward');const p=await f.controller.preview('g','p',source,1);await f.controller.confirm('g','p',p.token,async()=>source);assert.deepEqual(f.data.group.p['adventure-entregas'],{oldHatch:'oldPokemon',newHatch:'newPokemon'});});
test('conflicting receipt identities, malformed receipts and times-liga references block with field diagnostics',async()=>{const f=fixture(),source=account('legacy');source['adventure-entregas']={same:'oldPokemon'};f.data.group.p['adventure-entregas']={same:'otherPokemon'};const conflict=await f.controller.diagnostic('g','p',source,1);assert.equal(conflict.sourceRisks.some(x=>x.field==='adventure-entregas'&&x.category==='delivered_reward'),true);await assert.rejects(f.controller.preview('g','p',source,1),/operational_review/);const malformed=fixture();malformed.data.group.p['adventure-entregas']={egg:true};assert.equal((await malformed.controller.diagnostic('g','p',account('legacy'),1)).currentRisks.some(x=>x.field==='adventure-entregas'&&x.category==='invalid_or_unknown'),true);const badTeam=account('team');badTeam['times-liga']={bronze:[99,null,null]};const d=await fixture().controller.diagnostic('g','p',badTeam,1);assert.equal(d.sourceRisks.some(x=>x.field==='times-liga'&&x.category==='incompatible_reference'&&x.blocking),true);});
test('diagnostic and read-only preparation share exact source/current operational blockers',async()=>{
 const cases=[
  {side:'source',field:'adventure-eventos',apply:a=>{a['adventure-eventos']={evt:{'event-id':'evt',kind:'capture',at:123,status:'pendente'}};}},
  {side:'current',field:'adventure-eventos',apply:a=>{a['adventure-eventos']={evt:{'event-id':'evt',kind:'capture',at:123,status:'concluido'}};}},
  {side:'source',field:'restore-receipt',apply:a=>{a['restore-receipt']={operation:'legacy-op'};}},
  {side:'source',field:'adventure-entregas',apply:a=>{a['adventure-entregas']={evt:{pokemon:'id'}};}},
  {side:'current',field:'adventure-entregas',apply:a=>{a['adventure-entregas']={evt:{pokemon:'id'}};}},
  {side:'source',field:'adventure-reference-legacy',apply:a=>{a['adventure-reference-legacy']={privateValue:'hidden'};}}
 ];
 for(const testCase of cases){
  const f=fixture(),source=account('legacy');
  testCase.apply(testCase.side==='source'?source:f.data.group.p);
  const before=copy(f.data),diagnostic=await f.controller.diagnostic('g','p',source,1);
  const risks=testCase.side==='source'?diagnostic.sourceRisks:diagnostic.currentRisks;
  const finding=risks.find(item=>item.field===testCase.field&&item.blocking);
  assert.ok(finding,`${testCase.side} ${testCase.field} must be shown as blocking`);
  assert.equal(diagnostic.confirmationBlocked,true);
  await assert.rejects(f.controller.checkPreparation('g','p',source),error=>
   error.code==='operational_review_required'&&error.findings.some(item=>
    item.field===testCase.field&&item.category===finding.category&&item.state===finding.state&&item.rule===finding.rule));
  assert.equal(f.controller.previews.size,0);
  assert.deepEqual(f.data,before);
 }
 const internal=fixture();internal.data.group.p['restore-receipt']={operation:'confirmed-op'};
 const internalDiagnostic=await internal.controller.diagnostic('g','p',account('legacy'),1);
 assert.equal(internalDiagnostic.currentRisks.some(x=>x.field==='restore-receipt'),false);
 assert.equal(internalDiagnostic.confirmationBlocked,false);
 assert.equal((await internal.controller.checkPreparation('g','p',account('legacy'))).tokenIssued,false);
});
test('safe historical settings and validated receipts pass both diagnostic and read-only preparation',async()=>{
 const f=fixture(),source=account('legacy');
 source['times-liga']={iniciante:[0,null,null]};source['ultima-cacada']=123;
 source['adventure-entregas']={oldReward:'pokemon-old'};
 f.data.group.p['adventure-entregas']={currentReward:'pokemon-current'};
 const before=copy(f.data),diagnostic=await f.controller.diagnostic('g','p',source,1);
 assert.equal(diagnostic.confirmationBlocked,false);
 const checked=await f.controller.checkPreparation('g','p',source);
 assert.equal(checked.tokenIssued,false);assert.equal(f.controller.previews.size,0);
 assert.deepEqual(f.data,before);
});
test('legacy delivery receipt and current operational fields produce identical preview/preparation decision',async()=>{
 const f=fixture(),source=account('legacy');
 source['adventure-entregas']={oldHatch:'legacy-pokemon'};
 Object.assign(f.data.group.p,{
  'times-liga':{iniciante:[0,null,null]},
  'ultima-cacada':123,
  raid:{id:'pending-raid'},
  ofertas:{move:'pending-move'}
 });
 const diagnostic=await f.controller.diagnostic('g','p',source,1);
 assert.equal(diagnostic.confirmationBlocked,true);
 assert.equal(diagnostic.sourceRisks.some(x=>x.field==='adventure-entregas'&&x.category==='delivered_reward'&&!x.blocking),true);
 assert.equal(diagnostic.currentRisks.some(x=>x.field==='times-liga'&&x.category==='permanent_configuration'&&!x.blocking),true);
 assert.equal(diagnostic.currentRisks.some(x=>x.field==='ultima-cacada'&&x.category==='cooldown'&&!x.blocking),true);
 const pendingFields=diagnostic.currentRisks.filter(x=>x.blocking).map(x=>x.field).sort();
 assert.deepEqual(pendingFields,['ofertas','raid']);
 await assert.rejects(f.controller.checkPreparation('g','p',source),error=>
  error.code==='operational_review_required'&&
  error.findings.map(x=>x.field).sort().join(',')===pendingFields.join(','));
 assert.equal(f.controller.previews.size,0);
 // Once the actual pending records are absent, the same receipt and historical
 // settings remain safe and are preserved by the prepared candidate.
 delete f.data.group.p.raid;delete f.data.group.p.ofertas;
 const clear=await f.controller.diagnostic('g','p',source,1);
 assert.equal(clear.confirmationBlocked,false);
 const checked=await f.controller.checkPreparation('g','p',source);
 assert.equal(checked.tokenIssued,false);
 assert.equal(checked.count,1);
});
test('a newly visible blocker between diagnostic and preparation is reported without a token',async()=>{
 const f=fixture(),source=account('legacy');
 const diagnostic=await f.controller.diagnostic('g','p',source,1);
 assert.equal(diagnostic.confirmationBlocked,false);
 f.data.group.p['adventure-eventos']={evt:{'event-id':'evt',kind:'capture',at:123}};
 await assert.rejects(f.controller.checkPreparation('g','p',source),error=>
  error.code==='operational_review_required'&&error.findings.some(item=>item.field==='adventure-eventos'));
 assert.equal(f.controller.previews.size,0);
});
test('read-only preparation validation emits no token, writes, journal or lock',async()=>{const f=fixture(),before=copy(f.data);const checked=await f.controller.checkPreparation('g','p',account('candidate'));assert.equal(checked.tokenIssued,false);assert.equal(f.controller.previews.size,0);assert.deepEqual(f.data,before);});
test('backup comparison is read-only and does not mint an undo confirmation token',async()=>{const f=fixture(),saved=account('backup');const b={id:'backup-1',chat:'g',player:'p',account:saved};b.hash=hash(saved);f.data.backups[b.id]=b;const before=copy(f.data);const result=await f.controller.checkBackup('g','p',b.id);assert.equal(result.tokenIssued,false);assert.equal(result.count,1);assert.equal(f.controller.previews.size,0);assert.deepEqual(f.data,before);await assert.rejects(f.controller.checkBackup('g','other',b.id),/invalid_backup/);});
test('119 legacy records, seven XP conversions and shiny identity remain intact in candidate',async()=>{const f=fixture();f.data.group.p=account('current');f.data.group.p.equipe=Array.from({length:26},(_,i)=>({...account(`current-${i}`).equipe[0]}));const source=account('legacy-0');source.equipe=Array.from({length:119},(_,i)=>({...account(`legacy-${i}`).equipe[0],nome:i===17?'Voltorb':'Pokémon'}));source.equipe[17].shiny=true;for(let i=0;i<7;i++)source.equipe[i]['xp-desde-nivel']=9;const original=copy(source);const preview=await f.controller.preview('g','p',source,1);assert.equal(preview.currentCount,26);assert.equal(preview.count,119);assert.equal(preview.transformations.filter(t=>t.includes('XP ')).length,7);const candidate=f.controller.previews.get(hash(preview.token)).source;assert.equal(candidate.equipe.length,119);assert.equal(candidate.equipe[17].shiny,true);assert.deepEqual(source,original);assert.equal(f.data.group.p.equipe.length,26);});
test('preparation failure leaves no token and diagnostics redact saved values',async()=>{const f=fixture();f.store.hooks={preflight:async()=>{throw Error('persistence unavailable');}};await assert.rejects(f.controller.checkPreparation('g','p',account('source')),/unavailable/);assert.equal(f.controller.previews.size,0);const source=account('safe');source['adventure-secret']={value:'PLAYER_PRIVATE_DATA'};const d=await fixture().controller.diagnostic('g','p',source,1);assert.equal(d.confirmationBlocked,true);assert.equal(JSON.stringify(d).includes('PLAYER_PRIVATE_DATA'),false);assert.equal(d.sourceRisks.some(x=>x.field==='adventure-secret'&&x.state==='valor não inspecionado; conteúdo ocultado'),true);});
test('volatile battle, raid, trade and gym operations appear in diagnostic and block preparation',async()=>{const f=fixture();f.store.hooks={preflight:async()=>['batalha','raid','troca','ginásio com time associado']};const d=await f.controller.diagnostic('g','p',account('safe'),1);assert.equal(d.confirmationBlocked,true);assert.deepEqual(d.blockingOperations,['batalha','raid','troca','ginásio com time associado']);await assert.rejects(f.controller.checkPreparation('g','p',account('safe')),error=>error.code==='external_operation_pending'&&error.operations.length===4);assert.equal(f.controller.previews.size,0);});
test('infirmary, shiny, rarity, equipped items and trainer progress are preserved',async()=>{const f=fixture(),source=account('team');source.enfermaria=[{pokemon:{...account('joy').equipe[0],shiny:true,raridade:'raro',item:'restos'},'pronto-em':123456}];source.ginasios={agua:{vitorias:2}};source['vitorias-treinador']=12;const p=await f.controller.preview('g','p',source,1);await f.controller.confirm('g','p',p.token,async()=>source);assert.deepEqual(f.data.group.p.enfermaria,source.enfermaria);assert.deepEqual(f.data.group.p.ginasios,source.ginasios);assert.equal(f.data.group.p['vitorias-treinador'],12);});
test('invalid moves, shiny and infirmary timestamps block preview without writes',async()=>{for(const change of [a=>a.equipe[0].golpes=['unknown'],a=>a.equipe[0].shiny='true',a=>a.enfermaria=[{pokemon:account('joy').equipe[0]}]]){const f=fixture(),source=account('x');change(source);await assert.rejects(f.controller.preview('g','p',source,1));assert.equal(Object.keys(f.data.ops).length,0);}});
