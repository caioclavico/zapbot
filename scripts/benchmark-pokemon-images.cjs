'use strict';
// Compile pokemon-service's benchmark target first. All fixtures are offline;
// this does not initialize Cassandra, WhatsApp, game state or background jobs.
const {performance} = require('node:perf_hooks');
const {spawnSync} = require('node:child_process');
const path = require('node:path');
const sharp = require('sharp');
const root = path.resolve(__dirname,'..');
const summarize = values => {
  const sorted = [...values].sort((a,b) => a-b);
  return {median_ms:+sorted[Math.floor(sorted.length/2)].toFixed(3),p95_ms:+sorted[Math.ceil(sorted.length*.95)-1].toFixed(3)};
};
async function sample(fn) {
  const values = [];
  for (let i=0;i<23;i++) { const start=performance.now(); await fn(); if(i>=3) values.push(performance.now()-start); }
  return summarize(values);
}
async function worker(file) {
  const renderer = require(file); const results = {};
  for (const [name,fn] of Object.entries({trainer:renderer.render,battle:renderer.battle,raid:renderer.raid,team:renderer.team})) {
    const start=performance.now(); const buffer=await fn(); const coldMs=performance.now()-start;
    if (!Buffer.isBuffer(buffer)) throw Error(`${name} did not return media bytes`);
    const info=await sharp(buffer).metadata();
    const render=await sample(fn);
    const base64=await sample(() => buffer.toString('base64'));
    results[name]={format:info.format,width:info.width,height:info.height,bytes:buffer.length,
      base64_chars:4*Math.ceil(buffer.length/3),cold_ms:+coldMs.toFixed(3),warm:render,base64};
  }
  return {fixtures:results,peak_process_rss_kib:process.resourceUsage().maxRSS};
}
async function formats(file) {
  const source=await require(file).render();
  const {data,info}=await sharp(source).resize(640,337).flatten({background:'#0f172a'}).raw().toBuffer({resolveWithObject:true});
  const report={fixture:'Same trainer pixels at 640x337; encoding only'};
  for(const [name,encode] of Object.entries({png:p=>p.png(),jpeg70:p=>p.jpeg({quality:70}),webp70:p=>p.webp({quality:70})})) {
    const pipeline=()=>encode(sharp(data,{raw:info})).toBuffer();
    const buffer=await pipeline(); report[name]={bytes:buffer.length,...await sample(pipeline)};
  }
  return report;
}
async function main() {
  if(process.argv[2]==='--worker') { console.log(JSON.stringify(await worker(path.resolve(process.argv[3])))); return; }
  if(process.argv[2]==='--formats') { console.log(JSON.stringify(await formats(path.resolve(process.argv[3])))); return; }
  const after=path.join(root,'pokemon-service/target/benchmark.cjs');
  const before=process.argv[2]==='--before' && process.argv[3] ? path.resolve(process.argv[3]) : null;
  const report={environment:{node:process.version,platform:process.platform,arch:process.arch,sharp:sharp.versions.sharp},
    method:'Same real renderers and local 475px SVG artwork; separate processes; cold once, 3 warmups + 20 samples; no network/DB/WhatsApp'};
  for(const [name,file] of Object.entries({...(before?{before}:{}),after})) {
    const child=spawnSync(process.execPath,[__filename,'--worker',file],{
      encoding:'utf8',timeout:120000,env:{...process.env,NODE_PATH:path.join(root,'node_modules')}});
    if(child.status!==0) throw Error(child.stderr||child.stdout);
    report[name]=JSON.parse(child.stdout);
  }
  if(before) {
    const codecs=spawnSync(process.execPath,[__filename,'--formats',before],{
      encoding:'utf8',timeout:120000,env:{...process.env,NODE_PATH:path.join(root,'node_modules')}});
    if(codecs.status!==0) throw Error(codecs.stderr||codecs.stdout);
    report.encoding_formats=JSON.parse(codecs.stdout);
    report.reduction_percent={};
    for(const name of Object.keys(report.after.fixtures)) {
      const old=report.before.fixtures[name], next=report.after.fixtures[name];
      const reduction=(a,b)=>+(100*(1-b/a)).toFixed(1);
      report.reduction_percent[name]={bytes:reduction(old.bytes,next.bytes),warm_render_median:reduction(old.warm.median_ms,next.warm.median_ms)};
    }
  }
  console.log(JSON.stringify(report,null,2));
}
main().catch(error=>{console.error(error);process.exitCode=1;});
