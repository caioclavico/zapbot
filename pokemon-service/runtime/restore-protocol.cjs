'use strict';
// Internal coordinator. Production confirmation stays disabled.
const {createHash, randomBytes, randomUUID}=require('node:crypto');
const copy=x=>JSON.parse(JSON.stringify(x));
function canonical(x){
  if(Array.isArray(x))return x.map(canonical);
  if(x&&typeof x==='object')return Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])]));
  return x;
}
const hash=x=>createHash('sha256').update(JSON.stringify(canonical(x))).digest('hex');
class RestoreError extends Error{constructor(code){super(code);this.code=code;}}
const fail=code=>{throw new RestoreError(code)};
const SAFE_RECEIPTS=['adventure-entregas'];
const PENDING_FIELDS=['adventure-eventos','transferencia-professor','ofertas-golpes','ofertas','combate','batalha','cacada','raid','raide'];
function operationalPolicy(account){
  for(const key of PENDING_FIELDS){
    const value=account?.[key];
    if(value && ((Array.isArray(value)&&value.length) || (typeof value==='object'&&Object.keys(value).length))) fail('operational_review_required');
  }
  return Object.fromEntries(SAFE_RECEIPTS.filter(k=>account?.[k]&&typeof account[k]==='object').map(k=>[k,copy(account[k])]));
}
function validate(account){
  if(!account||typeof account!=='object'||Array.isArray(account))fail('invalid_account');
  const slots=['equipe','pc','enfermaria'];const ids=new Set();
  let count=0;
  for(const slot of slots){
    const list=account[slot]??[];if(!Array.isArray(list))fail('invalid_collection');
    for(const entry of list){
      const p=slot==='enfermaria'?entry?.pokemon:entry;
      if(!p||typeof p!=='object')fail('invalid_pokemon');
      const id=p['id-pokemon'];
      if(typeof id!=='string'||!id||ids.has(id))fail('invalid_pokemon_identity');
      ids.add(id);count++;
      for(const k of ['nivel','hp','hp-atual','ataque','defesa','atq-esp','def-esp','veloc']){
        if(!Number.isFinite(p[k])||p[k]<0)fail('invalid_stats');
      }
      if(p.nivel<1||p.nivel>100||p.hp<1||p['hp-atual']>p.hp||!Array.isArray(p.golpes)||!Array.isArray(p.tipos))fail('invalid_pokemon');
      if ('shiny' in p && typeof p.shiny !== 'boolean') fail('invalid_shiny');
      if ('raridade' in p && typeof p.raridade !== 'string') fail('invalid_rarity');
      if (p.item != null && typeof p.item !== 'string') fail('invalid_item');
      if (p.tipos.some(t=>typeof t!=='string') || p.golpes.some(g=>!g || typeof g!=='object' || Array.isArray(g))) fail('invalid_moves');
      if (slot==='enfermaria' && (!Number.isFinite(entry['pronto-em']) || entry['pronto-em']<0)) fail('invalid_infirmary');
      // Domain normalization can change stats/levels. Require an already-normalized
      // source until that conversion and all indexed references are reviewed.
      if(!Number.isInteger(p['xp-desde-nivel'])||p['xp-desde-nivel']<0||p['xp-desde-nivel']>=9)fail('normalization_review_required');
    }
  }
  for(const field of ['pokedex','ginasios']) {
    if(field in account && (!account[field] || typeof account[field]!=='object' || Array.isArray(account[field]))) fail('invalid_profile');
  }
  for(const field of ['nivel','xp','vitorias-treinador','moedas','sequencia-capturas']) {
    if(field in account && (!Number.isFinite(account[field]) || account[field]<0)) fail('invalid_profile');
  }
  if(count>1000||Buffer.byteLength(JSON.stringify(account))>8*1024*1024)fail('source_too_large');
  if(!Number.isInteger(account.ativo)||account.ativo<0||account.ativo>=Math.max(1,(account.equipe??[]).length))fail('invalid_active_index');
  for(const key of Object.keys(account)){
    if(/adventure|pendente|transferencia-professor|times|ofertas|raid|recibo|restore/i.test(key) && key!=='adventure-entregas')fail('operational_review_required');
  }
  return count;
}
// Deliberately refuse XP conversions that would change original attributes.
function prepare(source) {
  validate(source);
  if (SAFE_RECEIPTS.some(k=>Object.prototype.hasOwnProperty.call(source,k))) fail('operational_review_required');
  operationalPolicy(source);
  const target = copy(source);
  const transformations = [];
  if ((target.pc ?? []).length) transformations.push('PC acrescentado ao fim da equipe; índices anteriores preservados.');
  target.equipe = [...(target.equipe ?? []), ...(target.pc ?? [])];
  target.pc = [];
  target['colecao-unificada'] = true;
  if (target.equipe.some(p=>'vitorias-desde-nivel' in p)) transformations.push('Contador legado de vitórias removido; XP explícito preservado.');
  for (const pokemon of target.equipe) delete pokemon['vitorias-desde-nivel'];
  return {target, transformations};
}
class RestoreProtocol{
  constructor(store,{now=Date.now,gate=()=>false}={}){this.store=store;this.now=now;this.gate=gate;this.previews=new Map();}
  async preview(chat,player,source,version){
    const prepared=prepare(source);
    if (typeof this.store.hooks?.preflight==='function') await this.store.hooks.preflight(chat,player);
    const group=await this.store.readGroupSerial(chat);
    const current=group[player]??null;
    let currentReceipts={};
    if(current){const checked=copy(current);delete checked['restore-receipt'];validate(checked);currentReceipts=operationalPolicy(checked);}
    Object.assign(prepared.target,currentReceipts);
    for(const [key,value] of this.previews) if(value.expires<=this.now()) this.previews.delete(key);
    if(this.previews.size>=1000) fail('preview_capacity');
    const token=randomBytes(24).toString('hex');
    this.previews.set(hash(token),{chat,player,version,source:prepared.target,sourceHash:hash(source),targetHash:hash(prepared.target),currentHash:hash(current),expires:this.now()+600000});
    return {token,expires:this.now()+600000,count:validate(source),currentCount:current?['equipe','pc','enfermaria'].reduce((n,k)=>n+(current[k]??[]).length,0):0,transformations:prepared.transformations};
  }
  async previewBackup(chat,player,id){
    const backup=await this.store.readBackupSerial(id);
    if(!backup||backup.chat!==chat||backup.player!==player||backup.id!==id||hash(backup.account)!==backup.hash)fail('invalid_backup');
    const source=copy(backup.account);delete source['restore-receipt'];
    return this.preview(chat,player,source,{backup:id});
  }
  async confirm(chat,player,token,readSource){
    // gate must include readiness, writer exclusion and startup reconciliation.
    if(!this.gate())fail('production_restore_disabled');
    const key=hash(token);
    const existing=await this.store.readOperationSerial(key);
    if(existing){if(existing.chat!==chat||existing.player!==player)fail('invalid_code');return this.reconcile(key);}
    const preview=this.previews.get(key);
    if(!preview||preview.chat!==chat||preview.player!==player||preview.expires<=this.now())fail('invalid_code');
    if(hash(await readSource(preview.version))!==preview.sourceHash)fail('source_changed');
    // Consume synchronously before the first reservation. A failed/uncertain
    // reservation requires reconciliation or a fresh preview, never reuse.
    this.previews.delete(key);
    const op={...copy(preview),id:key,backupId:randomUUID(),state:'prepared',createdAt:this.now()};
    await this.store.insertOperationImmutable(key,op);
    const confirmed=await this.store.readOperationSerial(key);
    if(hash(confirmed)!==hash(op))fail('operation_unconfirmed');
    await this.store.claimAccount(chat,player,key);
    return this.apply(key);
  }
  async apply(key){
    const op=await this.store.readOperationSerial(key);
    const group=await this.store.readGroupSerial(op.chat);const current=group[op.player]??null;
    if(hash(current)!==op.currentHash)fail('target_conflict');
    const backup={id:op.backupId,operation:key,chat:op.chat,player:op.player,createdAt:op.createdAt,account:copy(current),hash:op.currentHash};
    await this.store.insertBackupImmutable(op.backupId,backup);
    if(hash(await this.store.readBackupSerial(op.backupId))!==hash(backup))fail('backup_unconfirmed');
    await this.store.indexBackup(op.chat,op.player,op.backupId,key);
    await this.store.transitionOperation(key,'prepared','cas_pending');
    const target=copy(op.source);
    Object.assign(target,operationalPolicy(current));
    target['restore-receipt']={operation:key,sourceHash:op.sourceHash,backup:op.backupId};
    const next={...group,[op.player]:target};
    // Exactly one CAS attempt; any exception leaves the durable lock intact.
    if(!await this.store.casGroup(op.chat,group,next))fail('cas_conflict');
    return this.reconcile(key);
  }
  async diagnose(key) {
    const op=await this.store.readOperationSerial(key);
    if(!op) return {state:'missing',releaseAllowed:false};
    const group=await this.store.readGroupSerial(op.chat);
    const receipt=group[op.player]?.['restore-receipt'];
    const target=copy(group[op.player]??null);
    if(target) delete target['restore-receipt'];
    const backup=await this.store.readBackupSerial(op.backupId);
    const proven=receipt?.operation===key && receipt.sourceHash===op.sourceHash &&
      receipt.backup===op.backupId && hash(target)===(op.targetHash??op.sourceHash) &&
      backup?.operation===key && backup.chat===op.chat && backup.player===op.player &&
      hash(backup.account)===op.currentHash;
    return {state:op.state,receiptConfirmed:proven,releaseAllowed:false,
      action:proven?'reconcile_with_authorized_writer':'administrative_review_required'};
  }
  async reconcile(key){
    const op=await this.store.readOperationSerial(key);
    if(!op)fail('operation_missing');
    if(op.state==='completed'){
      const lock=await this.store.readAccountLock(op.chat,op.player);
      if(!lock)return {status:'completed',backup:op.backupId};
      if(lock!==key)fail('conflicting_operation');
    }
    const group=await this.store.readGroupSerial(op.chat);const account=group[op.player]??null;
    const receipt=account?.['restore-receipt'];
    if(receipt?.operation!==key)fail('result_unknown');
    const target=copy(account);delete target['restore-receipt'];
    if(hash(target)!==(op.targetHash??op.sourceHash)||receipt.sourceHash!==op.sourceHash||receipt.backup!==op.backupId)fail('result_unknown');
    const backup=await this.store.readBackupSerial(op.backupId);
    if(!backup||backup.id!==op.backupId||backup.operation!==key||backup.chat!==op.chat||backup.player!==op.player||hash(backup.account)!==op.currentHash)fail('backup_unconfirmed');
    await this.store.indexBackup(op.chat,op.player,op.backupId,key);
    await this.store.installConfirmedGroup(op.chat,group);
    if(op.state!=='completed')await this.store.transitionOperation(key,'cas_pending','completed');
    await this.store.releaseAccount(op.chat,op.player,key);
    return {status:'completed',backup:op.backupId};
  }
}
module.exports={RestoreProtocol,RestoreError,validate,prepare,hash};
