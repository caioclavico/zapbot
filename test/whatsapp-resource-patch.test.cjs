'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const {EventEmitter}=require('node:events');
const {installStartupRecovery}=require('../scripts/lib/whatsapp-startup.cjs');
const {patchClientResources}=require('../scripts/patch-whatsapp-media.js');
test('pinned Client captures browsers before pages, and pages before proxy/user-agent setup',()=>{
  const source=fs.readFileSync(require.resolve('whatsapp-web.js/src/Client.js'),'utf8');
  const patched=patchClientResources(source);assert.equal(patchClientResources(patched),patched);
  assert.equal(patchClientResources(patched,true),patched);
  assert.ok(patched.indexOf('this.pupBrowser = browser; // zapbot: early connected browser')<patched.indexOf('page = await browser.newPage();'));
  assert.ok(patched.indexOf('this.pupBrowser = browser; // zapbot: early launched browser')<patched.indexOf('page = (await browser.pages())[0];'));
  assert.ok(patched.indexOf('this.pupPage = page; // zapbot: early launched page')<patched.indexOf('await page.authenticate('));
});
test('missing, duplicate or modified markers fail closed before shipping another dependency version',()=>{
  const source=fs.readFileSync(require.resolve('whatsapp-web.js/src/Client.js'),'utf8');
  assert.throws(()=>patchClientResources(source.replaceAll('this.pupBrowser = browser;', 'this.pupBrowser = undefined;'),true));
  assert.throws(()=>patchClientResources(source+source));
  assert.throws(()=>patchClientResources('another version'));
});
test('actual pinned initialize method cleans up after pages, proxy or newPage failures',async()=>{
  const source=patchClientResources(fs.readFileSync(require.resolve('whatsapp-web.js/src/Client.js'),'utf8'));
  const start=source.indexOf('    async initialize() {');
  const method=source.slice(start,source.indexOf('\n    /**',start));
  for(const stage of ['pages','authenticate','newPage']){
    let live=true,closed=0;const error=new Error('fixture setup failure');
    const page={authenticate:async()=>{throw error;}};
    const browser={isConnected:()=>live,process:()=>({exitCode:live?null:0,signalCode:null}),
      pages:async()=>{if(stage==='pages')throw error;return [page];},newPage:async()=>{throw error;}};
    const initialize=vm.runInNewContext('({'+method+'})',
      {puppeteer:{launch:async()=>browser,connect:async()=>browser}}).initialize;
    const client=Object.assign(new EventEmitter(),{
      options:{puppeteer:stage==='newPage'?{browserURL:'fixture.invalid'}:{},userAgent:false,
        proxyAuthentication:stage==='authenticate'?{}:undefined},
      authStrategy:{beforeBrowserInitialized:async()=>{}},initialize,inject:async()=>{},
      destroy:async()=>{assert.equal(client.pupBrowser,browser);closed++;live=false;}});
    const recovery=installStartupRecovery(client,{logger(){}});
    await assert.rejects(recovery.initialize(),e=>e===error);
    assert.equal(closed,1);assert.equal(live,false);await recovery.close();assert.equal(closed,1);
  }
});
