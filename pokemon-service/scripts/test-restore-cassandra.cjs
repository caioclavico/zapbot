'use strict';
if(process.env.RESTORE_ISOLATED_TEST!=='true')throw Error('Requires disposable local Cassandra');
const assert=require('node:assert/strict');
const cassandra=require('cassandra-driver');
const {RestoreStore}=require('../runtime/restore-store.cjs');
const {RestoreProtocol,hash}=require('../runtime/restore-protocol.cjs');
const keyspace='restore_test_'+process.pid+'_'+Date.now();
const client=new cassandra.Client({contactPoints:['127.0.0.1'],localDataCenter:'datacenter1'});
const account=id=>({ativo:0,'colecao-unificada':true,equipe:[{'id-pokemon':id,nivel:1,hp:10,'hp-atual':10,ataque:5,defesa:5,'atq-esp':5,'def-esp':5,veloc:5,'xp-desde-nivel':0,golpes:[],tipos:['normal']}],pc:[]});
async function main(){
 await client.connect();
 await client.execute(`CREATE KEYSPACE ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
 await client.execute(`CREATE TABLE ${keyspace}.estado_particionado (modulo text,particao text,valor text,PRIMARY KEY ((modulo,particao)))`);
 const execute=(sql,args,opts)=>client.execute(sql,args,{...opts,consistency:opts.consistency==='SERIAL'?cassandra.types.consistencies.serial:cassandra.types.consistencies.quorum,serialConsistency:cassandra.types.consistencies.serial});
 const initial={p:account('old'),other:account('other')};
 await execute(`INSERT INTO ${keyspace}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,['treinador','g',JSON.stringify(initial)],{});
 const memory={},blocked=new Map();let attempts=0;let lose=false;
 const hooks={
  readGroup:async chat=>JSON.parse((await execute(`SELECT valor FROM ${keyspace}.estado_particionado WHERE modulo=? AND particao=?`,['treinador',chat],{consistency:'SERIAL'})).first().valor),
  casGroup:async(chat,before,next)=>{attempts++;const r=await execute(`UPDATE ${keyspace}.estado_particionado SET valor=? WHERE modulo=? AND particao=? IF valor=?`,[JSON.stringify(next),'treinador',chat,JSON.stringify(before)],{});if(lose)throw Error('simulated_lost_response');return r.wasApplied();},
  install:async(chat,value)=>{memory[chat]=value;},block:(chat,op)=>blocked.set(chat,op),unblock:(chat,op)=>{if(blocked.get(chat)===op)blocked.delete(chat);}
 };
 const store=new RestoreStore(execute,keyspace,hooks),controller=new RestoreProtocol(store,{gate:()=>true});
 const preview=await controller.preview('g','p',account('new'),1);
 lose=true;await assert.rejects(controller.confirm('g','p',preview.token,async()=>account('new')));
 assert.equal(attempts,1);assert.ok(blocked.has('g'));
 const restartedStore=new RestoreStore(execute,keyspace,hooks),restarted=new RestoreProtocol(restartedStore,{gate:()=>true});
 const pending=await restartedStore.pending();assert.equal(pending.length,1);
 const result=await restarted.reconcile(pending[0].id);
 assert.equal(result.status,'completed');assert.equal(attempts,1);assert.equal(blocked.size,0);
 assert.deepEqual(memory.g.other,initial.other);
 const backup=await store.readBackupSerial(result.backup);assert.deepEqual(backup.account,initial.p);
 assert.equal((await store.listBackups('g','p')).length,1);assert.equal((await store.listBackups('g','other')).length,0);
 assert.equal((await store.pending()).length,0);
 await assert.rejects(store.insertBackupImmutable(result.backup,{...backup,hash:'modified'}));
 assert.equal(hash(await store.readBackupSerial(result.backup)),hash(backup));
 lose=false;
 const undo=await restarted.previewBackup('g','p',result.backup);
 const undone=await restarted.confirm('g','p',undo.token,async()=>backup.account);
 assert.notEqual(undone.backup,result.backup);assert.deepEqual(memory.g.p.equipe,initial.p.equipe);
 assert.equal((await store.listBackups('g','p')).length,2);
 console.log('PASS: Cassandra isolated SERIAL/LWT, immutable backup, index isolation, lost response, restart reconciliation, undo and preservation.');
}
main().catch(()=>{console.error('FAIL: isolated restoration integration; sensitive details withheld.');process.exitCode=1;}).finally(()=>client.shutdown());
