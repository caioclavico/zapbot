'use strict';
const {createHash, randomUUID, timingSafeEqual} = require('node:crypto');
const {performance} = require('node:perf_hooks');
const {State} = require('./state.cjs');
const metrics = require('./metrics.cjs');
const mode = require('./mode.cjs');
const {logError} = require('./errors.cjs');
const shutdown = require('./shutdown.cjs');
const REQUESTS = 'pokemon-http-requests';
const EVENTS = 'pokemon-http-events';
class HttpError extends Error {
  constructor(status, code, message) { super(message); this.status=status; this.code=code; }
}
function validString(value, max=256) { return typeof value === 'string' && value.length > 0 && value.length <= max; }
function validateCommand(input) {
  if (!input || typeof input !== 'object' || !validString(input.requestId) ||
      !validString(input.chatId) || !validString(input.playerId) || !validString(input.command,4096) ||
      (input.playerName !== undefined && !validString(input.playerName,256))) {
    throw new HttpError(400,'invalid_request','requestId, chatId, playerId e command são obrigatórios.');
  }
  const ctx = input.context || {};
  if (typeof ctx !== 'object' || Array.isArray(ctx) ||
      (ctx.mentionedIds && (!Array.isArray(ctx.mentionedIds) || ctx.mentionedIds.length > 50 || ctx.mentionedIds.some(x=>!validString(x)))) ||
      (ctx.quotedPlayerId != null && !validString(ctx.quotedPlayerId))) {
    throw new HttpError(400,'invalid_context','Contexto inválido.');
  }
  return {...input,context:ctx};
}
function fingerprint(input) {
  // Ordenação recursiva evita conflito por ordem de propriedades JSON.
  const canonical = x => Array.isArray(x) ? x.map(canonical) : x && typeof x === 'object' ?
    Object.fromEntries(Object.keys(x).sort().map(k=>[k,canonical(x[k])])) : x;
  return createHash('sha256').update(JSON.stringify(canonical(input))).digest('hex');
}
class PokemonService {
  constructor({domain,media,logger=console.log,maxConcurrent=8,readOnly=mode.readOnly}) {
    this.readOnly=readOnly; this.domain=domain; this.media=media; this.logger=logger; this.maxConcurrent=maxConcurrent;
    this.state=new State(domain); this.active=new Map(); this.activeHashes=new Map(); this.chats=new Map(); this.accepting=true;
    domain.registerModule(REQUESTS); domain.registerModule(EVENTS);
  }
  ready() { return this.accepting && this.domain.isReady(); }
  async initialize() { await this.domain.initialize(); }
  assertWritable() {
    if(this.readOnly)throw new HttpError(403,'read_only','Serviço em validação somente leitura.');
  }
  async messages(raw) {
    this.assertWritable();
    if (raw == null) return [];
    if (typeof raw === 'string') return raw ? [{type:'text',text:raw}] : [];
    const text = raw.texto || '';
    const mentions = raw.mentions || [];
    const media = raw.media || raw.documento;
    if (raw.medias) {
      const result=[];
      for (let i=0;i<raw.medias.length;i++) {
        result.push({type:'image',text:`🎒 Página ${i+1}/${raw.medias.length}${i===raw.medias.length-1 && raw['legenda-ultima'] ? '\n\n'+raw['legenda-ultima'] : ''}`,mentions,...await this.media.put(raw.medias[i])});
      }
      return result;
    }
    if (!media) return text ? [{type:'text',text,mentions}] : [];
    const info=await this.media.put(media);
    if (raw.documento) return [...(text ? [{type:'text',text,mentions}] : []),{type:'document',text:'',mentions,...info}];
    const long=text.length>900;
    return [{type:'image',text:long ? (raw.legenda||'🖼️ *Imagem Pokémon*') : text,mentions,...info},
      ...(long ? [{type:'text',text,mentions}] : [])];
  }
  async enqueueEvent(chatId, raw, suppliedEffects) {
    this.assertWritable();
    const messages=await this.messages(raw);
    const effects=suppliedEffects || this.domain.takeEffects(chatId) || [];
    if (!messages.length && !effects.length) return;
    const event={id:randomUUID(),chatId,messages,effects,createdAt:Date.now()};
    await this.state.update(EVENTS,events=>{events[event.id]=event;});
    return event;
  }
  startTimers() {
    if(this.readOnly)return;
    this.domain.startTimers((chatId,raw)=>this.enqueueEvent(chatId,raw));
  }
  pendingEvents(limit=20) {
    return Object.values(this.state.read(EVENTS)).sort((a,b)=>a.createdAt-b.createdAt).slice(0,limit);
  }
  async ack(id) {
    this.assertWritable();
    if (!validString(id)) throw new HttpError(400,'invalid_event','Evento inválido.');
    await this.state.update(EVENTS,events=>{delete events[id];});
    return {acknowledged:true};
  }
  async execute(input) {
    this.assertWritable();
    if (!this.ready()) throw new HttpError(503,'not_ready','Persistência Pokémon indisponível.');
    input=validateCommand(input);
    const key=createHash('sha256').update(input.requestId).digest('hex');
    const hash=fingerprint(input);
    if (this.active.has(key)) {
      if (this.activeHashes.get(key)!==hash) throw new HttpError(409,'request_conflict','requestId já usado com outros dados.');
      return this.active.get(key);
    }
    const existing=this.state.read(REQUESTS)[key];
    if (existing) {
      if (existing.fingerprint!==hash) throw new HttpError(409,'request_conflict','requestId já usado com outros dados.');
      if (existing.status==='completed') return existing.response;
      if (this.active.has(key)) return this.active.get(key);
      throw new HttpError(409,'result_unknown','Comando já recebido; resultado incerto. Não o execute com outro requestId.');
    }
    if (this.active.size>=this.maxConcurrent) throw new HttpError(429,'busy','Serviço ocupado.');
    const work=metrics.run(()=>this.executeReserved(key,hash,input));
    this.activeHashes.set(key,hash);
    this.active.set(key,work);
    try { return await work; } finally { this.active.delete(key); this.activeHashes.delete(key); }
  }
  async executeReserved(key,hash,input) {
    this.assertWritable();
    const start=performance.now();
    const record={status:'processing',fingerprint:hash,createdAt:Date.now(),requestId:input.requestId};
    // Reserva durável ANTES das regras. Após crash, processing nunca é reexecutado.
    let claimed;
    try {
      claimed=await this.state.reserve(REQUESTS,key,record);
    } catch(error) {
      logError(this.logger,{event:'command_failed',requestId:input.requestId,command:input.command,stage:'reservation'},error);
      // A timed-out LWT may have committed. Never retry or enter the domain.
      throw new HttpError(500,'result_unknown','Falha ao reservar comando; não repita com outro requestId.');
    }
    if (!claimed) throw new HttpError(409,'already_received','requestId já reservado.');
    const previous=this.chats.get(input.chatId)||Promise.resolve();
    const work=previous.catch(()=>{}).then(async()=>{
      const outputs=[]; let open=true; let effects=[]; let effectsSaved=false;
      const processingStart=performance.now();
      try {
        const emit=async raw=>{
          if (open) outputs.push(raw);
          else await this.enqueueEvent(input.chatId,raw);
        };
        const result=await this.domain.command(input,emit);
        const processingMs=performance.now()-processingStart;
        effects=this.domain.takeEffects(input.chatId)||[];
        if(effects.length) await this.enqueueEvent(input.chatId,null,effects);
        effectsSaved=true;
        const responseStart=performance.now();
        const messages=[];
        for (const raw of [...outputs,result]) messages.push(...await this.messages(raw));
        const timings={...Object.fromEntries(Object.entries(metrics.snapshot()).map(([k,v])=>[k,Math.round(v)])),command_processing_ms:Math.round(processingMs),response_preparation_ms:Math.round(performance.now()-responseStart),request_total_ms:Math.round(performance.now()-start)};
        const response={requestId:input.requestId,messages,effects,timings};
        await this.state.update(REQUESTS,records=>{records[key]={...record,status:'completed',response};});
        this.logger(JSON.stringify({event:'command_completed',requestId:input.requestId,...timings}));
        return response;
      } catch(error) {
        logError(this.logger,{event:'command_failed',requestId:input.requestId,command:input.command,
          stage:'processing',request_total_ms:Math.round(performance.now()-start)},error);
        // Efeitos de rank são uma outbox separada; o consumidor faz dedupe.
        // Drena também se a regra falhou após produzir efeitos intermediários.
        if(!effectsSaved) {
          try {
            effects=[...effects,...(this.domain.takeEffects(input.chatId)||[])];
            if(effects.length) await this.enqueueEvent(input.chatId,null,effects);
          } catch(recoveryError) {
            this.accepting=false;
            logError(this.logger,{event:'command_recovery_failed',requestId:input.requestId},recoveryError);
          }
        }
        // Não existe rollback transacional de toda jogada. Bloqueia replay incerto.
        await this.state.update(REQUESTS,records=>{records[key]={...record,status:'uncertain'};}).catch(()=>{});
        throw new HttpError(500,'result_unknown','Falha ao concluir comando; não repita com outro requestId.');
      } finally { open=false; }
    });
    this.chats.set(input.chatId,work);
    try { return await work; } finally { if(this.chats.get(input.chatId)===work)this.chats.delete(input.chatId); }
  }
  async pruneMedia() {
    if(this.readOnly)return;
    const ids=new Set(this.pendingEvents(Number.MAX_SAFE_INTEGER).flatMap(e=>e.messages.map(m=>m.mediaId).filter(Boolean)));
    await this.media.prune(ids);
  }
  shutdownPending() {
    const pending={http_active:this.active.size,http_chat_queues:this.chats.size,state_queues:this.state.queues.size};
    try { if(this.domain.shutdownPending)Object.assign(pending,this.domain.shutdownPending()); }
    catch {pending.diagnostics_unavailable=true;}
    return pending;
  }
  async close() {
    return shutdown.observe(async()=>{
      this.accepting=false; this.domain.stopTimers();
      await shutdown.stage('active.allSettled',()=>Promise.allSettled([...this.active.values()]));
      await shutdown.stage('domain.shutdown',()=>this.domain.shutdown());
    },{logger:this.logger,pending:()=>this.shutdownPending()});
  }
}
function authorized(req,token) {
  if (!token) return true;
  const got=Buffer.from(req.headers.authorization||''); const expected=Buffer.from('Bearer '+token);
  return got.length===expected.length && timingSafeEqual(got,expected);
}
function json(res,status,body) { if(res.destroyed||res.writableEnded)return; res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(body)); }
async function body(req,max=64*1024) {
  let size=0;const chunks=[];
  for await(const chunk of req){size+=chunk.length;if(size>max)throw new HttpError(413,'too_large','Corpo grande demais.');chunks.push(chunk);}
  try{return JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw new HttpError(400,'invalid_json','JSON inválido.');}
}
function handler(service,{token}={}) {
  return async(req,res)=>{
    let pathname='';
    const deadline=setTimeout(()=>json(res,504,{error:'request_timeout',message:'Resultado incerto; reutilize o mesmo requestId.'}),60000);
    deadline.unref();
    res.once('finish',()=>clearTimeout(deadline));res.once('close',()=>clearTimeout(deadline));
    try {
      const url=new URL(req.url,'http://localhost');
      pathname=url.pathname;
      if(req.method==='GET'&&url.pathname==='/health')return json(res,200,{status:'ok'});
      if(req.method==='GET'&&url.pathname==='/ready')return json(res,service.ready()?200:503,{ready:service.ready()});
      if(!authorized(req,token))throw new HttpError(401,'unauthorized','Autenticação obrigatória.');
      service.assertWritable(); // Em validação, apenas health/ready são publicados.
      if(req.method==='POST'&&url.pathname==='/commands')return json(res,200,await service.execute(await body(req)));
      if(req.method==='GET'&&url.pathname==='/events/pending'){
        if(!service.ready())throw new HttpError(503,'not_ready','Persistência indisponível.');
        return json(res,200,{events:service.pendingEvents()});
      }
      const ack=url.pathname.match(/^\/events\/([^/]+)\/ack$/);
      if(req.method==='POST'&&ack){if(!service.ready())throw new HttpError(503,'not_ready','Persistência indisponível.');return json(res,200,await service.ack(ack[1]));}
      const media=url.pathname.match(/^\/media\/([a-f0-9]{64})$/);
      if(req.method==='GET'&&media){
        const found=await service.media.get(media[1]);
        if(!found)throw new HttpError(410,'media_expired','Mídia expirada; o comando não será reexecutado.');
        if(res.destroyed||res.writableEnded)return;
        res.writeHead(200,{'Content-Type':found.mimeType,'Content-Length':found.buffer.length,'Cache-Control':'private, max-age=60','X-Content-Type-Options':'nosniff'});return res.end(found.buffer);
      }
      throw new HttpError(404,'not_found','Rota não encontrada.');
    }catch(e){
      const known=e instanceof HttpError;
      const status=known?e.status:500;
      logError(service.logger,{event:'http_error',method:req.method,pathname,status},e,[token]);
      json(res,status,{error:known?e.code:'internal_error',message:known?e.message:'Falha interna.'});
    }
  };
}
module.exports={PokemonService,HttpError,handler,REQUESTS,EVENTS};
