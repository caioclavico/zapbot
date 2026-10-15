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
class RestoreError extends Error{constructor(code,details={}){super(code);this.code=code;Object.assign(this,details);}}
const fail=(code,details)=>{throw new RestoreError(code,details)};
const SAFE_RECEIPTS=['adventure-entregas'];
// Valid configuration and cumulative markers are not in-flight operations.
// Their references are validated below; monotonic account markers are merged
// by mergeOperationalPolicy rather than copied blindly.
const SAFE_HISTORICAL_FIELDS=['times-liga','ultima-cacada','time-ginasio','times-prontos','pe-raids','raid-progresso-dia'];
function trainerProgress(account){
  const direct=Number.isFinite(account?.xp)?account.xp:null;
  const parts=['vitorias-treinador','pe-ginasios','pe-raids'];
  const derived=parts.some(k=>Number.isFinite(account?.[k])) ? parts.reduce((n,k)=>n+(Number.isFinite(account?.[k])?account[k]:0),0) : null;
  const total=direct!==null?direct:derived;
  if(total===null) return {available:false};
  let level=1,remaining=Math.max(0,total),needed=5;
  while(remaining>=needed){remaining-=needed;level++;needed+=2;}
  return {available:true,total,level,xpCurrent:remaining,xpNeeded:needed};
}
const PENDING_FIELDS=['adventure-eventos','transferencia-professor','ofertas-golpes','ofertas','combate','batalha','cacada','raid','raide'];
const RISK_RULES={
  'adventure-eventos':'O runtime mantém cada entrada nesta fila até aplicar o evento e removê-lo com confirmação Cassandra.',
  'adventure-entregas':'Recibos confirmam Pokémon já entregues; recibos válidos são unidos por ID para impedir reentrega.',
  'times-liga':'Times guardam índices de Pokémon da equipe; a origem só é segura se todos os índices continuarem válidos.',
  'ultima-cacada':'Cooldown é um timestamp; a troca conserva o maior timestamp válido entre origem e conta atual.',
  'time-ginasio':'Escalação salva por índices da equipe; a origem só é segura se os índices forem válidos. A ocupação real é verificada separadamente no estado dos ginásios.',
  'times-prontos':'Escalações nomeadas guardam IDs de Pokémon da equipe; todos os IDs precisam apontar para registros únicos do mesmo save.',
  'pe-raids':'Pontos de raide já concedidos são progresso cumulativo, sem operação externa pendente; a restauração conserva o maior total entre os dois estados.',
  'raid-progresso-dia':'Chave de idempotência do último progresso de raide (ID da raide, ou data local em estados antigos); dois valores diferentes não podem ser unidos com segurança pelo schema atual.',
  'xp-raids-pendente':'XP de raide ainda aguarda aplicação a um Pokémon identificado. Resolva pelo fluxo normal antes de preparar; não descarte o recibo pendente.',
  'ofertas-golpes':'Ofertas de golpes ainda não decididas dependem do Pokémon e precisam ser resolvidas antes da troca.',
  'transferencia-professor':'Transferência pendente pode consumir um Pokémon e precisa ser concluída ou cancelada.',
  'default':'Campo operacional não reconhecido; requer revisão até que sua semântica e suas referências sejam verificadas.'
};
const LIVE_OPERATION_RULES={
  'batalha':'Combate em andamento; aguarde a conclusão para evitar que o callback grave um snapshot anterior.',
  'caçada/PvP':'Caçada ou PvP em andamento; aguarde a conclusão da operação volátil.',
  'evolução':'Evolução assíncrona em andamento; aguarde o callback finalizar.',
  'oferta de golpe':'Oferta de golpe pendente para um Pokémon da conta.',
  'raid':'Participação em raid ainda ativa.',
  'captura de raide pendente':'Vitória de raid ainda permite capturas; aguarde o prazo/consumo das tentativas antes de trocar a conta.',
  'ginásio com time associado':'Este treinador ainda ocupa um ginásio, cujo estado guarda uma cópia externa dos defensores. Não há encerramento transacional de ocupações integrado à restauração; a atividade deve terminar pelo fluxo normal do ginásio.',
  'troca':'Proposta de troca pendente envolve esta conta.'
};
const SAFE_ACTIONS={
  'time-ginasio':'Revise a escalação no save correspondente e gere uma nova prévia; não altere nem remova Pokémon automaticamente.',
  'times-prontos':'Corrija ou exclua a escalação inválida pelo fluxo normal do jogo e gere uma nova prévia.',
  'pe-raids':'Não prossiga até revisar manualmente o total inválido; não some nem zere pontos para liberar a restauração.',
  'raid-progresso-dia':'Se os IDs de raide diferirem entre os saves, solicite reconciliação do histórico antes de preparar; não altere nem descarte o marcador.',
  'xp-raids-pendente':'Consuma o XP pelo fluxo normal quando o Pokémon estiver na equipe; se o registro não puder ser resgatado, solicite reconciliação sem apagar o pendente.',
  'ginásio com time associado':'Aguarde outro treinador vencer normalmente a ocupação no ginásio e execute uma nova prévia. Não remova nem altere manualmente o registro; a restauração não desocupa nem cancela a atividade.',
  'captura de raide pendente':'Use apenas as tentativas normais da captura pendente ou aguarde a expiração; a restauração não consome nem cancela a recompensa.'
};
const LEAGUE_LEVELS={iniciante:[1,10],bronze:[11,25],prata:[26,40],ouro:[41,60],diamante:[61,100]};
const isObject=value=>Boolean(value)&&typeof value==='object'&&!Array.isArray(value);
const nonEmpty=value=>Array.isArray(value)?value.length>0:(isObject(value)?Object.keys(value).length>0:Boolean(value));
function receiptRecord(value){
  if(value==null)return {ok:true,receipts:{}};
  if(!isObject(value))return {ok:false,receipts:{}};
  const receipts={};
  for(const [id,pokemonId] of Object.entries(value)){
    if(!id||typeof pokemonId!=='string'||!pokemonId.trim())return {ok:false,receipts:{}};
    receipts[id]=pokemonId;
  }
  return {ok:true,receipts};
}
function validRaidProgressMarker(value){
  // The runtime stores the raid ID here (falling back to the local date for
  // legacy records), so this is an opaque idempotency key, not always a date.
  return typeof value==='string'&&value.trim().length>0&&value.length<=200;
}
function teamPokemonIdCounts(account){
  const counts=new Map();
  // PC is appended to equipe by the existing collection migration before
  // named teams are used at runtime, so legacy PC references remain valid.
  for(const pokemon of [...(Array.isArray(account?.equipe)?account.equipe:[]),...(Array.isArray(account?.pc)?account.pc:[])]){
    const id=pokemon?.['id-pokemon'];
    if(typeof id==='string'&&id)counts.set(id,(counts.get(id)||0)+1);
  }
  return counts;
}
function accountPokemonIdCounts(account){
  const counts=new Map();
  for(const slot of ['equipe','pc','enfermaria'])for(const row of Array.isArray(account?.[slot])?account[slot]:[]){
    const pokemon=slot==='enfermaria'?row?.pokemon:row;
    const id=pokemon?.['id-pokemon'];
    if(typeof id==='string'&&id)counts.set(id,(counts.get(id)||0)+1);
  }
  return counts;
}
function validGymTeamConfiguration(account){
  const slots=account?.['time-ginasio'];
  return slots===undefined||(Array.isArray(slots)&&(slots.length===0||(
    slots.length===3&&slots.every(index=>index===null||(Number.isInteger(index)&&index>=0&&index<(account.equipe||[]).length))&&
    new Set(slots.filter(index=>index!==null)).size===slots.filter(index=>index!==null).length)));
}
function validSavedTeams(account){
  const teams=account?.['times-prontos'];
  if(teams===undefined)return true;
  if(!isObject(teams))return false;
  const counts=teamPokemonIdCounts(account);
  return Object.entries(teams).every(([name,team])=>{
    const ids=team?.pokemons;
    return Boolean(name)&&isObject(team)&&Array.isArray(ids)&&ids.length<=3&&
      ids.every(id=>typeof id==='string'&&id&&counts.get(id)===1)&&new Set(ids).size===ids.length;
  });
}
function classifyOperationalState(account){
  const findings=[];
  const add=(field,category,state,blocking)=>findings.push({field,category,state,rule:RISK_RULES[field]||RISK_RULES.default,
    ...(blocking&&SAFE_ACTIONS[field]?{action:SAFE_ACTIONS[field]}:{}),blocking});
  const events=account?.['adventure-eventos'];
  if(events!==undefined){
    if(!isObject(events))add('adventure-eventos','invalid_or_unknown','estrutura inválida',true);
    else if(Object.keys(events).length===0)add('adventure-eventos','empty','nenhum evento aguardando confirmação',false);
    else for(const [id,event] of Object.entries(events)){
      // The production consumer treats every persisted event as work and only
      // removes it after the activity state is durably updated. A status label
      // alone is not an acknowledgement receipt.
      const valid=isObject(event)&&event['event-id']===id&&typeof event.kind==='string'&&Number.isFinite(event.at);
      add('adventure-eventos',valid?'operation_pending':'invalid_or_unknown',valid?'evento ainda registrado na fila':'evento malformado; não reconhecido como consumido',true);
    }
  }
  for(const field of PENDING_FIELDS.filter(k=>k!=='adventure-eventos')){
    const value=account?.[field];
    if(value!==undefined)add(field,nonEmpty(value)?'operation_pending':'empty',nonEmpty(value)?'registro pendente presente':'estrutura vazia',nonEmpty(value));
  }
  const receipts=receiptRecord(account?.['adventure-entregas']);
  if(!receipts.ok)add('adventure-entregas','invalid_or_unknown','recibo inválido',true);
  else if(Object.keys(receipts.receipts).length)add('adventure-entregas','delivered_reward','recibos válidos preserváveis',false);
  else if(account?.['adventure-entregas']!==undefined)add('adventure-entregas','empty','nenhum recibo registrado',false);
  if(account?.['ultima-cacada']!==undefined){
    const valid=Number.isFinite(account['ultima-cacada'])&&account['ultima-cacada']>=0;
    add('ultima-cacada',valid?'cooldown':'invalid_or_unknown',valid?'timestamp válido':'timestamp inválido',!valid);
  }
  if(account?.['times-liga']!==undefined){
    const teams=account['times-liga'];let valid=isObject(teams);
    if(valid)for(const [league,slots] of Object.entries(teams)){
      const levels=LEAGUE_LEVELS[league];
      if(!levels||!Array.isArray(slots)||slots.length!==3){valid=false;break;}
      if(slots.some(index=>index!==null&&(!Number.isInteger(index)||index<0||index>=(account.equipe||[]).length))){valid=false;break;}
      // A named team must map each non-empty slot to a distinct collection row.
      const selected=slots.filter(index=>index!==null);
      if(new Set(selected).size!==selected.length){valid=false;break;}
      if(selected.some(index=>account.equipe[index].nivel<levels[0]||account.equipe[index].nivel>levels[1])){valid=false;break;}
      if(typeof league!=='string'||!league){valid=false;break;}
    }
    add('times-liga',valid?'permanent_configuration':'incompatible_reference',valid?'configuração com índices válidos':'índice de equipe ausente ou incompatível',!valid);
  }
  if(account?.['time-ginasio']!==undefined){
    const valid=validGymTeamConfiguration(account);
    add('time-ginasio',valid?'permanent_configuration':'incompatible_reference',valid?'configuração de índices válida':'índices da escalação de ginásio inválidos',!valid);
  }
  if(account?.['times-prontos']!==undefined){
    const valid=validSavedTeams(account);
    add('times-prontos',valid?'permanent_configuration':'incompatible_reference',valid?'IDs da escalação apontam para Pokémon únicos da equipe':'escalação contém Pokémon ausente, duplicado ou inválido',!valid);
  }
  if(account?.['pe-raids']!==undefined){
    const valid=Number.isSafeInteger(account['pe-raids'])&&account['pe-raids']>=0;
    add('pe-raids',valid?'cumulative_progress':'invalid_or_unknown',valid?'pontos de raide já concedidos':'total de pontos de raide inválido',!valid);
  }
  if(account?.['raid-progresso-dia']!==undefined){
    const valid=validRaidProgressMarker(account['raid-progresso-dia']);
    add('raid-progresso-dia',valid?'idempotency_marker':'invalid_or_unknown',valid?'marcador de raide válido':'marcador de raide inválido',!valid);
  }
  if(account?.['xp-raids-pendente']!==undefined){
    const pending=account['xp-raids-pendente'];
    const valid=isObject(pending)&&Object.entries(pending).every(([id,xp])=>Boolean(id)&&Number.isSafeInteger(xp)&&xp>0);
    if(!valid)add('xp-raids-pendente','invalid_or_unknown','estrutura de XP pendente inválida',true);
    else if(Object.keys(pending).length){
      const counts=accountPokemonIdCounts(account);
      const refsValid=Object.keys(pending).every(id=>counts.get(id)===1);
      add('xp-raids-pendente',refsValid?'operation_pending':'incompatible_reference',
        refsValid?'XP ainda aguarda aplicação a Pokémon referenciado':'XP pendente aponta para Pokémon ausente ou ambíguo',true);
    }
    else add('xp-raids-pendente','empty','nenhum XP de raide pendente',false);
  }
  if(Object.prototype.hasOwnProperty.call(account||{},'restore-receipt'))
    add('restore-receipt','unknown_or_inconsistent','recibo interno de restauração na origem',true);
  for(const [slot,list] of ['equipe','pc','enfermaria'].map(k=>[k,account?.[k]])){
    if(!Array.isArray(list))continue;
    list.forEach((row,index)=>{
      const pokemon=slot==='enfermaria'?row?.pokemon:row;
      if(nonEmpty(pokemon?.['ofertas-golpes']))
        add('ofertas-golpes','operation_pending',`oferta associada a Pokémon em ${slot} #${index+1}`,true);
    });
  }
  const known=new Set([...PENDING_FIELDS,...SAFE_RECEIPTS,...SAFE_HISTORICAL_FIELDS,'xp-raids-pendente','restore-receipt']);
  for(const key of Object.keys(account||{})){
    if(!known.has(key)&&/adventure|activity|pendente|transferencia|times|time-ginasio|ofertas|raid|raide|combate|batalha|cacada|troca|trade|doacao|recibo|restore/i.test(key))
      add(key,'unknown_field','valor não inspecionado; conteúdo ocultado',true);
  }
  return findings;
}
function blockingFindings(account){return classifyOperationalState(account).filter(item=>item.blocking);}
function assertOperationalSafe(account){
  const findings=blockingFindings(account);
  if(findings.length)fail('operational_review_required',{findings});
  return findings;
}
function operationalAssessment(source,current){
  const checkedCurrent=current?copy(current):{};
  // The receipt belongs to the restore transaction protocol, not to the
  // player's operational state being assessed.
  delete checkedCurrent['restore-receipt'];
  const sourceRisks=classifyOperationalState(source);
  const currentRisks=classifyOperationalState(checkedCurrent);
  if(receiptConflict(source,checkedCurrent))sourceRisks.push({field:'adventure-entregas',
    category:'incompatible_reference',state:'IDs de entrega conflitantes entre a origem e a conta atual',
    rule:RISK_RULES['adventure-entregas'],blocking:true});
  const sourceRaidMarker=source?.['raid-progresso-dia'];
  const currentRaidMarker=checkedCurrent?.['raid-progresso-dia'];
  if(validRaidProgressMarker(sourceRaidMarker)&&validRaidProgressMarker(currentRaidMarker)&&sourceRaidMarker!==currentRaidMarker)
    sourceRisks.push({field:'raid-progresso-dia',category:'incompatible_idempotency_marker',
      state:'marcadores de raide diferentes entre a origem e a conta atual',rule:RISK_RULES['raid-progresso-dia'],
      action:SAFE_ACTIONS['raid-progresso-dia'],blocking:true});
  return {sourceRisks,currentRisks,checkedCurrent,
    blocking:[...sourceRisks,...currentRisks].filter(item=>item.blocking)};
}
function assertAssessmentSafe(assessment){
  if(assessment.blocking.length)fail('operational_review_required',{findings:assessment.blocking});
  return assessment;
}
function operationalPolicy(account){
  assertOperationalSafe(account);
  const policy={};
  const receipts=receiptRecord(account?.['adventure-entregas']);
  if(Object.keys(receipts.receipts).length)policy['adventure-entregas']=copy(receipts.receipts);
  if(Number.isSafeInteger(account?.['pe-raids'])&&account['pe-raids']>=0)policy['pe-raids']=account['pe-raids'];
  if(validRaidProgressMarker(account?.['raid-progresso-dia']))policy['raid-progresso-dia']=account['raid-progresso-dia'];
  return policy;
}
async function liveOperations(store,chat,player){
  if(typeof store.hooks?.preflight!=='function')return [];
  const result=await store.hooks.preflight(chat,player);
  return Array.isArray(result)?[...new Set(result.filter(x=>typeof x==='string'&&x))]:[];
}
function liveRisk(operation){
  const gymOccupancy=operation.startsWith('ginásio ocupado:');
  const key=gymOccupancy?'ginásio com time associado':operation;
  return {field:operation,category:'operation_pending',state:gymOccupancy?'ocupação ativa com defensores externos':'operação ativa no runtime',
    rule:LIVE_OPERATION_RULES[key]||RISK_RULES.default,
    ...(SAFE_ACTIONS[key]?{action:SAFE_ACTIONS[key]}:{}),blocking:true};
}
function mergeOperationalPolicy(target,source,current){
  const sourcePolicy=operationalPolicy(source),currentPolicy=operationalPolicy(current);
  const merged={...target};
  const receipts={...(sourcePolicy['adventure-entregas']||{})};
  for(const [id,pokemonId] of Object.entries(currentPolicy['adventure-entregas']||{})){
    if(receipts[id]&&receipts[id]!==pokemonId)fail('operational_review_required',{findings:[{field:'adventure-entregas',category:'incompatible_reference',state:'IDs de entrega conflitantes',rule:RISK_RULES['adventure-entregas'],blocking:true}]});
    receipts[id]=pokemonId;
  }
  if(Object.keys(receipts).length)merged['adventure-entregas']=receipts;else delete merged['adventure-entregas'];
  const cooldowns=[source?.['ultima-cacada'],current?.['ultima-cacada']].filter(n=>Number.isFinite(n)&&n>=0);
  if(cooldowns.length)merged['ultima-cacada']=Math.max(...cooldowns);
  const raidPoints=[source?.['pe-raids'],current?.['pe-raids']].filter(n=>Number.isSafeInteger(n)&&n>=0);
  if(raidPoints.length)merged['pe-raids']=Math.max(...raidPoints);
  const raidMarkers=[source?.['raid-progresso-dia'],current?.['raid-progresso-dia']].filter(validRaidProgressMarker);
  if(raidMarkers.length)merged['raid-progresso-dia']=raidMarkers[0];
  return merged;
}
function receiptConflict(source,current){
  const a=receiptRecord(source?.['adventure-entregas']),b=receiptRecord(current?.['adventure-entregas']);
  if(!a.ok||!b.ok)return false;
  return Object.entries(a.receipts).some(([id,pokemonId])=>b.receipts[id]&&b.receipts[id]!==pokemonId);
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
  assertOperationalSafe(account);
  return count;
}
// Kept equivalent to treinador/normalizar-xp-registro: nine XP per level and
// a 3% rounded increase per level. This is a pure migration of the preview
// copy; the legacy source is never modified.
function normalizeLegacyPokemon(input){
  const p=copy(input); const hasXp=Object.prototype.hasOwnProperty.call(p,'xp-desde-nivel');
  const hasWins=Object.prototype.hasOwnProperty.call(p,'vitorias-desde-nivel');
  if(hasXp && (!Number.isInteger(p['xp-desde-nivel'])||p['xp-desde-nivel']<0))fail('normalization_review_required');
  if(hasWins && (!Number.isInteger(p['vitorias-desde-nivel'])||p['vitorias-desde-nivel']<0))fail('normalization_review_required');
  const xp=hasXp?p['xp-desde-nivel']:(hasWins?p['vitorias-desde-nivel']*3:0);
  if(hasXp&&hasWins&&p['xp-desde-nivel']!==p['vitorias-desde-nivel']*3)fail('normalization_review_required');
  const level=p.nivel; const hpBefore=p.hp;
  if(!Number.isInteger(level)||level<1||level>100)fail('normalization_review_required');
  const rises=Math.min(Math.floor(xp/9),100-level); let hpDelta=0;
  const grow=value=>{if(!Number.isFinite(value)||value<0)fail('normalization_review_required');return Math.round(value*1.03);};
  for(let i=0;i<rises;i++){
    const hp=p.hp; p.hp=grow(hp); hpDelta+=p.hp-hp;
    p.ataque=grow(p.ataque); p.defesa=grow(p.defesa);
    p['atq-esp']=grow(p['atq-esp']); p['def-esp']=grow(p['def-esp']); p.veloc=grow(p.veloc); p.nivel++;
  }
  if(rises){p['hp-atual']=p['hp-atual']>0?p['hp-atual']+hpDelta:0;}
  p['xp-desde-nivel']=p.nivel===100?0:xp-rises*9; delete p['vitorias-desde-nivel'];
  return {pokemon:p,changed:Boolean(rises||!hasXp||hasWins),fromXp:xp,rises,levelBefore:level,hpBefore,hpAfter:p.hp};
}
function migrateLegacyAccount(source){
  const target=copy(source); const transformations=[];
  for(const slot of ['equipe','pc','enfermaria']){
    const list=target[slot]??[];
    if(!Array.isArray(list))fail('invalid_collection');
    target[slot]=list.map((entry,index)=>{
      const pokemon=slot==='enfermaria'?entry?.pokemon:entry;
      const migrated=normalizeLegacyPokemon(pokemon);
      if(migrated.changed) transformations.push(`${slot} #${index+1}: XP ${migrated.fromXp} → ${migrated.pokemon['xp-desde-nivel']}, nível ${migrated.levelBefore} → ${migrated.pokemon.nivel}, HP ${migrated.hpBefore} → ${migrated.hpAfter}, atributos normalizados.`);
      if(slot==='enfermaria') return {...entry,pokemon:migrated.pokemon};
      return migrated.pokemon;
    });
  }
  return {target,transformations};
}
// Read-only diagnostic projection used by the WhatsApp preview.  Unlike the
// restore preparation path, one malformed record is represented in the
// result instead of aborting the whole page.  No source object is mutated and
// this function never creates a confirmation token or writes to Cassandra.
function diagnosticEntries(source){
  const entries=[];
  for(const [local,slot] of [['Equipe','equipe'],['PC','pc'],['Enfermaria','enfermaria']]){
    const list=source?.[slot];
    if(!Array.isArray(list)) continue;
    list.forEach((entry,index)=>{
      const original=slot==='enfermaria' ? entry?.pokemon : entry;
      let status='compatible', proposed=(original && typeof original==='object') ? copy(original) : {}, detail=null;
      try {
        if(!original || typeof original!=='object' || Array.isArray(original)) fail('invalid_pokemon');
        const migrated=normalizeLegacyPokemon(original);
        proposed=migrated.pokemon;
        status=migrated.changed?'converted':'compatible';
        detail={fromXp:migrated.fromXp,levelBefore:migrated.levelBefore,
          levelAfter:migrated.pokemon.nivel,hpBefore:migrated.hpBefore,
          hpAfter:migrated.hpAfter,rises:migrated.rises};
      } catch(error) {
        status='invalid'; detail={code:error.code||'invalid_pokemon'};
      }
      entries.push({local,index:index+1,original:(original && typeof original==='object') ? copy(original) : {},pokemon:proposed,status,detail});
    });
  }
  return entries;
}
// Deliberately refuse XP conversions that would change original attributes.
function prepare(source) {
  assertOperationalSafe(source);
  const migrated=migrateLegacyAccount(source);
  validate(migrated.target);
  const target = migrated.target;
  const transformations = [...migrated.transformations];
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
  async prepareCandidate(chat,player,source){
    const active=await liveOperations(this.store,chat,player);
    if(active.length)fail('external_operation_pending',{operations:active});
    const group=await this.store.readGroupSerial(chat);
    const current=group[player]??null;
    const assessment=assertAssessmentSafe(operationalAssessment(source,current));
    const checked=assessment.checkedCurrent;
    const prepared=prepare(source);
    if(current)validate(checked);
    const target=mergeOperationalPolicy(prepared.target,source,checked);
    validate(target);
    return {target,current,transformations:prepared.transformations,count:validate(target),
      currentCount:current?['equipe','pc','enfermaria'].reduce((n,k)=>n+(current[k]??[]).length,0):0};
  }
  async checkPreparation(chat,player,source){
    const checked=await this.prepareCandidate(chat,player,source);
    return {count:checked.count,currentCount:checked.currentCount,transformations:checked.transformations,
      tokenIssued:false,sourceHash:hash(source),currentHash:hash(checked.current)};
  }
  async checkBackup(chat,player,id){
    const backup=await this.store.readBackupSerial(id);
    if(!backup||backup.chat!==chat||backup.player!==player||backup.id!==id||hash(backup.account)!==backup.hash)fail('invalid_backup');
    const source=copy(backup.account);delete source['restore-receipt'];
    return this.checkPreparation(chat,player,source);
  }
  async preview(chat,player,source,version){
    const prepared=await this.prepareCandidate(chat,player,source);
    for(const [key,value] of this.previews) if(value.expires<=this.now()) this.previews.delete(key);
    if(this.previews.size>=1000) fail('preview_capacity');
    const token=randomBytes(24).toString('hex');
    this.previews.set(hash(token),{chat,player,version,source:prepared.target,sourceHash:hash(source),targetHash:hash(prepared.target),currentHash:hash(prepared.current),expires:this.now()+600000});
    return {token,expires:this.now()+600000,count:prepared.count,currentCount:prepared.currentCount,transformations:prepared.transformations};
  }
  async previewBackup(chat,player,id){
    const backup=await this.store.readBackupSerial(id);
    if(!backup||backup.chat!==chat||backup.player!==player||backup.id!==id||hash(backup.account)!==backup.hash)fail('invalid_backup');
    const source=copy(backup.account);delete source['restore-receipt'];
    return this.preview(chat,player,source,{backup:id});
  }
  async diagnostic(chat,player,source,version){
    const active=await liveOperations(this.store,chat,player);
    const group=await this.store.readGroupSerial(chat);
    const current=group[player]??null;
    // Diagnostics must remain readable while the current account has active
    // game work. Strict validation is intentionally reserved for confirmation.
    // Only bounded, read-only metadata is inspected here.
    if(current && Buffer.byteLength(JSON.stringify(current))>8*1024*1024) fail('source_too_large');
    const entries=diagnosticEntries(source);
    const assessment=operationalAssessment(source,current);
    const {checkedCurrent,currentRisks,sourceRisks}=assessment;
    currentRisks.push(...active.map(liveRisk));
    const classifyValidation=(account,side)=>{
      try{validate(side==='source'?migrateLegacyAccount(account).target:account);return null;}
      catch(error){
        if(Array.isArray(error.findings))return null;
        return {field:'save',category:'invalid_or_unknown',state:error.code||'registro incompatível',
          rule:'A validação estrutural transacional não aceitou os dados; consulte os Pokémon inválidos na prévia.',blocking:true};
      }
    };
    const sourceValidation=classifyValidation(source,'source');
    const currentValidation=current?classifyValidation(checkedCurrent,'current'):null;
    if(sourceValidation)sourceRisks.push(sourceValidation);
    if(currentValidation)currentRisks.push(currentValidation);
    const blocked=currentRisks.filter(item=>item.blocking);
    const sourceBlocked=sourceRisks.filter(item=>item.blocking);
    return {entries,total:entries.length,currentCount:current ? ['equipe','pc','enfermaria'].reduce((n,k)=>n+(current[k]??[]).length,0) : 0,version,
      confirmationBlocked:Boolean(blocked.length||sourceBlocked.length),blockingOperations:blocked.map(item=>item.field),sourceBlockingOperations:sourceBlocked.map(item=>item.field),
      currentRisks,sourceRisks,
      currentTrainer:trainerProgress(current||{}),legacyTrainer:trainerProgress(source)};
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
    // Volatile operations live outside the treinador partition hash. Recheck
    // after the durable account lock is claimed and before backup/CAS, so a
    // gym occupation or raid capture that appeared after preview cannot leave
    // an external reference pointing at a replaced team.
    const active=await liveOperations(this.store,op.chat,op.player);
    if(active.length)fail('external_operation_pending',{operations:active});
    const backup={id:op.backupId,operation:key,chat:op.chat,player:op.player,createdAt:op.createdAt,account:copy(current),hash:op.currentHash};
    await this.store.insertBackupImmutable(op.backupId,backup);
    if(hash(await this.store.readBackupSerial(op.backupId))!==hash(backup))fail('backup_unconfirmed');
    await this.store.indexBackup(op.chat,op.player,op.backupId,key);
    await this.store.transitionOperation(key,'prepared','cas_pending');
    const checkedCurrent=current?copy(current):{};
    delete checkedCurrent['restore-receipt'];
    const target=mergeOperationalPolicy(copy(op.source),op.source,checkedCurrent);
    validate(target);
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
module.exports={RestoreProtocol,RestoreError,validate,prepare,normalizeLegacyPokemon,migrateLegacyAccount,diagnosticEntries,hash};
