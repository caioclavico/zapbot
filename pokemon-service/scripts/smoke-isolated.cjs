'use strict';
// Executar na imagem com --network none. Não usa credenciais ou dados externos.
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const sharp=require('sharp');
async function main(){
  for(const pkg of ['whatsapp-web.js','puppeteer','puppeteer-core'])assert.throws(()=>require.resolve(pkg));
  const domain=require('../target/domain.cjs');
  assert.equal(typeof domain.command,'function');
  const png=await sharp({create:{width:2,height:2,channels:4,background:'#ffdd00'}}).png().toBuffer();
  const child=spawn(process.execPath,['runtime/main.cjs'],{env:{...process.env,HOST:'127.0.0.1',PORT:'18090',API_TOKEN:'local-smoke-test-token-only',CASSANDRA_CONTACT_POINTS:'127.0.0.1'},stdio:['ignore','ignore','ignore']});
  const exited=new Promise(r=>child.once('exit',r));
  const call=(p,options={})=>fetch('http://127.0.0.1:18090'+p,{...options,signal:AbortSignal.timeout(1000)});
  try{
    let health;
    for(let i=0;i<100;i++){
      try{health=await call('/health');break;}catch{await new Promise(r=>setTimeout(r,100));}
    }
    assert.equal(health?.status,200);
    assert.equal((await call('/ready')).status,503);
    const result=await call('/commands',{method:'POST',headers:{authorization:'Bearer local-smoke-test-token-only','content-type':'application/json'},body:JSON.stringify({requestId:'smoke',chatId:'test',playerId:'test',command:'pk treinador'})});
    assert.equal(result.status,503);
    console.log(JSON.stringify({arch:process.arch,pngBytes:png.length,domainExports:Object.keys(domain).length,whatsappDependencies:false,health:200,readyWithoutCassandra:503,commandWithoutCassandra:503}));
  }finally{
    child.kill('SIGTERM');const kill=setTimeout(()=>child.kill('SIGKILL'),5000);await exited;clearTimeout(kill);
  }
}
main().catch(e=>{console.error(e);process.exitCode=1;});
