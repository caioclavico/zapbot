'use strict';
// Low-level store. Instantiation requires a trusted, prepared-query executor.
// No instance is registered with the production domain in this stage.
const {hash,RestoreError}=require('./restore-protocol.cjs');
const MODULES=new Set(['pokemon-restauracao-backups','pokemon-restauracao-operacoes','pokemon-restauracao-bloqueios','pokemon-restauracao-controle']);
class RestoreStore{
 constructor(execute,keyspace,hooks={}){this.hooks=hooks;if(!/^[a-z][a-z0-9_]*$/.test(keyspace))throw new RestoreError('invalid_keyspace');this.execute=execute;this.table=keyspace+'.estado_particionado';}
 async readSerial(module,id){
  if(!MODULES.has(module))throw new RestoreError('invalid_module');
  const r=await this.execute(`SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,[module,id],{prepare:true,consistency:'SERIAL'});
  const row=typeof r.first==='function'?r.first():r.rows?.[0];return row?.valor?JSON.parse(row.valor):null;
 }
 async insertImmutable(module,id,value){
  if(!MODULES.has(module))throw new RestoreError('invalid_module');
  // No retry on a timeout, and never overwrite an existing record.
  await this.execute(`INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,[module,id,JSON.stringify(value)],{prepare:true,consistency:'QUORUM',serialConsistency:'SERIAL'});
  const confirmed=await this.readSerial(module,id);
  if(hash(confirmed)!==hash(value))throw new RestoreError('durability_unconfirmed');
  return confirmed;
 }
 async cas(module,id,old,value){
  if(!MODULES.has(module)||module==='pokemon-restauracao-backups')throw new RestoreError('immutable_or_invalid_module');
  const r=await this.execute(`UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,[JSON.stringify(value),module,id,JSON.stringify(old)],{prepare:true,consistency:'QUORUM',serialConsistency:'SERIAL'});
  return r.wasApplied();
 }
 async registry(){
  const value=await this.readSerial('pokemon-restauracao-controle','registry')||{operations:{},locks:{},backups:{}};
  for(const key of ['operations','locks','backups'])if(!value[key]||typeof value[key]!=='object'||Array.isArray(value[key]))throw new RestoreError('registry_invalid');
  return value;
 }
 async changeRegistry(change){
  const old=await this.readSerial('pokemon-restauracao-controle','registry');
  const next=change(old||{operations:{},locks:{},backups:{}});
  if(old){if(!await this.cas('pokemon-restauracao-controle','registry',old,next))throw new RestoreError('registry_conflict');}
  else await this.insertImmutable('pokemon-restauracao-controle','registry',next);
  const confirmed=await this.registry();if(hash(confirmed)!==hash(next))throw new RestoreError('registry_unconfirmed');
 }
 identity(chat,player){return hash([chat,player]);}
 async readOperationSerial(id){return this.readSerial('pokemon-restauracao-operacoes',id);}
 async insertOperationImmutable(id,op){
  this.hooks.block(op.chat,id);
  // Index before writing the journal: lost responses cannot hide an operation.
  await this.changeRegistry(r=>({...r,operations:{...r.operations,[id]:{chat:op.chat,player:op.player}}}));
  return this.insertImmutable('pokemon-restauracao-operacoes',id,op);
 }
 async transitionOperation(id,from,to){
  const old=await this.readOperationSerial(id);
  if(old?.state===to)return;
  if(old?.state!==from||!await this.cas('pokemon-restauracao-operacoes',id,old,{...old,state:to}))throw new RestoreError('operation_conflict');
  if((await this.readOperationSerial(id))?.state!==to)throw new RestoreError('operation_unconfirmed');
 }
 async readAccountLock(chat,player){return (await this.registry()).locks[this.identity(chat,player)]?.operation||null;}
 async claimAccount(chat,player,operation){
  const identity=this.identity(chat,player);
  const registry=await this.registry();
  if(Object.values(registry.locks).some(l=>l.chat===chat&&l.operation!==operation))throw new RestoreError('partition_locked');
  this.hooks.block(chat,operation);
  await this.changeRegistry(r=>{
   const current=r.locks[identity];if(current&&current.operation!==operation)throw new RestoreError('account_locked');
   return {...r,locks:{...r.locks,[identity]:{chat,player,operation}}};
  });
 }
 async releaseAccount(chat,player,operation){
  const identity=this.identity(chat,player);
  await this.changeRegistry(r=>{const current=r.locks[identity];if(current&&current.operation!==operation)throw new RestoreError('account_locked');const locks={...r.locks};delete locks[identity];const operations={...r.operations};delete operations[operation];return {...r,locks,operations};});
  this.hooks.unblock(chat,operation);
 }
 async insertBackupImmutable(id,value){return this.insertImmutable('pokemon-restauracao-backups',id,value);}
 async readBackupSerial(id){return this.readSerial('pokemon-restauracao-backups',id);}
 async indexBackup(chat,player,id,operation){
  const backup=await this.readBackupSerial(id);
  if(!backup||backup.chat!==chat||backup.player!==player||backup.operation!==operation)throw new RestoreError('invalid_backup');
  await this.changeRegistry(r=>{const identity=this.identity(chat,player);return {...r,backups:{...r.backups,[identity]:Array.from(new Set([...(r.backups[identity]||[]),id]))}};});
 }
 async listBackups(chat,player){
  const ids=(await this.registry()).backups[this.identity(chat,player)]||[];const result=[];
  for(const id of ids){const b=await this.readBackupSerial(id);if(!b||b.chat!==chat||b.player!==player||hash(b.account)!==b.hash)throw new RestoreError('invalid_backup');result.push({id:b.id,createdAt:b.createdAt});}
  return result;
 }
 async pending(){
  const registry=await this.registry();const result=[];
  const identities=new Map(Object.entries(registry.operations));
  for(const lock of Object.values(registry.locks)){
   if(!lock||typeof lock.operation!=='string'||typeof lock.chat!=='string'||typeof lock.player!=='string')throw new RestoreError('registry_invalid');
   const existing=identities.get(lock.operation);
   if(existing&&(existing.chat!==lock.chat||existing.player!==lock.player))throw new RestoreError('registry_invalid');
   identities.set(lock.operation,{chat:lock.chat,player:lock.player});
  }
  for(const [id,identity] of identities){
   const op=await this.readOperationSerial(id);
   if(typeof identity.chat!=='string'||typeof identity.player!=='string'||(op&&(op.id!==id||op.chat!==identity.chat||op.player!==identity.player)))throw new RestoreError('registry_invalid');
   if(!op||op.state!=='completed'||Object.values(registry.locks).some(l=>l.operation===id))result.push({id,...identity,op});
  }
  return result;
 }
 readGroupSerial(chat){return this.hooks.readGroup(chat);}
 casGroup(chat,before,next){return this.hooks.casGroup(chat,before,next);}
 installConfirmedGroup(chat,value){return this.hooks.install(chat,value);}

}
module.exports={RestoreStore};
