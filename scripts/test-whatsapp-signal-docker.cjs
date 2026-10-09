'use strict';
// Only called inside an isolated Docker fixture. No WhatsApp/Cassandra access.
const assert=require('node:assert/strict');
const {EventEmitter}=require('node:events');
const fs=require('node:fs');
const {execFile}=require('node:child_process');
const os=require('node:os');
const path=require('node:path');
const puppeteer=require('/app/node_modules/puppeteer');
const {installStartupRecovery}=require('/fixture-lib/whatsapp-startup.cjs');
const mode=process.argv[2],signal=process.argv[3]||'SIGTERM';
assert.equal(process.env.ZAPBOT_SHUTDOWN_FIXTURE,'yes');
assert.ok(['default','managed','legacy'].includes(mode));
assert.ok(['SIGTERM','SIGINT'].includes(signal));
const root=fs.mkdtempSync(path.join(os.tmpdir(),'zapbot-browser-fixture-'));
if(mode==='legacy')fs.mkdirSync('/app/.wwebjs_auth'); // Refuse any existing profile.
const profile=mode==='legacy'?'/app/.wwebjs_auth/session':path.join(root,'session');fs.mkdirSync(profile);
fs.writeFileSync(path.join(profile,'auth-sentinel'),'fixture-session-must-survive');
const client=new EventEmitter();let browser,logouts=0;
client.authStrategy={destroy:async()=>{},logout:async()=>{logouts++;}};
client.inject=async()=>{};
client.initialize=async()=>{
  browser=await puppeteer.launch({headless:true,executablePath:'/usr/bin/chromium',userDataDir:profile,
    args:['--no-sandbox','--disable-setuid-sandbox'],
    ...(mode==='managed'?{handleSIGTERM:false,handleSIGINT:false}:{})});
  client.pupBrowser=browser;client.pupPage=(await browser.pages())[0];
  await client.inject();client.emit('ready');
};
client.destroy=async()=>{if(client.pupBrowser?.isConnected())await client.pupBrowser.close();};
const recovery=installStartupRecovery(client,{timeoutMs:20000,injectTimeoutMs:5000,
  logger:()=>{}});
const timer=setTimeout(()=>process.exit(2),25000);
process.on(signal,()=>{
  Promise.resolve().then(()=>recovery.close()).then(()=>{
    clearTimeout(timer);
    assert.equal(browser.process().exitCode,0);assert.equal(browser.process().signalCode,null);
    assert.equal(fs.readFileSync(path.join(profile,'auth-sentinel'),'utf8'),'fixture-session-must-survive');
    assert.equal(logouts,0);
    console.log(JSON.stringify({mode,signal,exit_code:0,browser_signal:null,session_preserved:true}));
    process.exit(0);
  }).catch(()=>{
    clearTimeout(timer);
    console.log(JSON.stringify({mode,signal,exit_code:browser.process().exitCode,browser_signal:browser.process().signalCode}));
    process.exit(1);
  });
});
(async()=>{
  await recovery.initialize();
  if(mode==='legacy'){
    // Exercise the exact node -e invocation used by the root deployment helper,
    // from another process, including its default profile and module resolution.
    const script=fs.readFileSync('/fixture-tests/odisseu-legacy-stop.cjs','utf8')+'\nrunLegacyStop();';
    await new Promise((resolve,reject)=>execFile('node',['-e',script],{timeout:25000},error=>error?reject(error):resolve()));
  }
  process.kill(process.pid,signal);
})().catch(error=>{console.error('Fixture setup error: '+error.message);process.exit(3);});
