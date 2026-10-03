'use strict';
// Offline: renderizadores reais, sprite SVG fixo e persistência em memória.
// Não inicia WhatsApp nem Cassandra. Cada versão roda em processo separado.
const {performance}=require('node:perf_hooks');
const {spawnSync}=require('node:child_process');
const {createHash}=require('node:crypto');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const mode=process.argv[2];
const summary=values=>{
  const sorted=[...values].sort((a,b)=>a-b);
  return {n:values.length,median_ms:+sorted[Math.floor(sorted.length/2)].toFixed(2),p95_ms:+sorted[Math.ceil(sorted.length*.95)-1].toFixed(2)};
};
async function measure(fn){const samples=[];for(let i=0;i<23;i++){const start=performance.now();await fn();if(i>=3)samples.push(performance.now()-start);}return summary(samples);}
async function main(){
  if(!mode){
    const results={environment:{node:process.version,platform:process.platform,arch:process.arch},fixture:'cartão treinador com sprite SVG local, 3 warmups + 20 amostras; Cassandra fake; sem WhatsApp'};
    for(const m of ['old','new']){
      const p=spawnSync(process.execPath,[__filename,m],{cwd:m==='old'?root:path.join(root,'pokemon-service'),encoding:'utf8',timeout:120000});
      if(p.status!==0)throw Error(p.stderr||p.stdout);
      results[m]=JSON.parse(p.stdout);
    }
    results.same_png=results.old.sha256===results.new.sha256;
    if(!results.same_png)throw Error('PNG antigo e novo divergentes');
    console.log(JSON.stringify(results,null,2));return;
  }
  const renderer=require(path.join(process.cwd(),'target/benchmark.cjs'));
  const buffer=await renderer.render();
  const result={pngBytes:buffer.length,sha256:createHash('sha256').update(buffer).digest('hex'),render:await measure(()=>renderer.render())};
  if(mode==='new'){
    const http=require('node:http');const fs=require('node:fs/promises');const os=require('node:os');
    const {PokemonService,handler}=require('../pokemon-service/runtime/service.cjs');
    const {MediaStore}=require('../pokemon-service/runtime/media.cjs');
    const {createClient}=require('./lib/pokemon-http-client.cjs');
    const data={};const domain={registerModule:k=>{data[k]||={};},load:k=>data[k],store:async(k,v)=>{data[k]=v;},reserve:async(k,id,v)=>{if(data[k][id])return false;data[k][id]=v;return true;},isReady:()=>true,takeEffects:()=>[],command:async()=>({texto:'treinador',media:{mime:'image/png',buffer:await renderer.render(),filename:'treinador.png'}})};
    const dir=await fs.mkdtemp(path.join(os.tmpdir(),'pokemon-benchmark-'));
    const service=new PokemonService({domain,media:new MediaStore(dir),logger:()=>{}});
    const server=http.createServer(handler(service,{token:'local-benchmark'}));
    await new Promise(r=>server.listen(0,'127.0.0.1',r));
    const client=createClient({baseUrl:`http://127.0.0.1:${server.address().port}`,token:'local-benchmark'});
    let seq=0,mediaId;const timings=[];
    try{
      result.command_http=await measure(async()=>{const reply=await client.command({requestId:`bench-${++seq}`,chatId:'fixture',playerId:'fixture',command:'pk treinador'});mediaId=reply.messages[0].mediaId;if(seq>3)timings.push(reply.timings);});
      result.service_processing=summary(timings.map(t=>t.command_processing_ms));
      result.image_processing=summary(timings.map(t=>t.image_processing_ms));
      result.response_preparation=summary(timings.map(t=>t.response_preparation_ms));
      result.binary_transfer=await measure(()=>client.media(mediaId));
    }finally{client.close();server.closeAllConnections();await new Promise(r=>server.close(r));await fs.rm(dir,{recursive:true,force:true});}
  }
  console.log(JSON.stringify(result));
}
main().catch(e=>{console.error(e);process.exitCode=1;});
