'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const {closeLegacyBrowser}=require('../scripts/odisseu-legacy-stop.cjs');
const profile='/app/.wwebjs_auth/session';
function fixture({ownerCount=1,endpointProfile=profile,exit=true,symlink=false}={}){
  let alive=true,closed=0,disconnected=0,time=0;
  const files={
    '/proc/1/cmdline':`node\0/app/target/main.js\0`,
    '/proc/2/cmdline':`/usr/lib/chromium/chromium\0--user-data-dir=${profile}\0`,
    '/proc/3/cmdline':`/usr/lib/chromium/chromium\0--type=renderer\0--user-data-dir=${profile}\0`,
    [`${profile}/DevToolsActivePort`]:'12345\n/devtools/browser/fixture-id\n'
  };
  const fs={
    readdirSync(path){assert.equal(path,'/proc');return alive?['1',...(ownerCount?['2','3']:[]),...(ownerCount>1?['4']:[])]:['1'];},
    readFileSync(path,encoding){
      if(path==='/proc/4/cmdline')return Buffer.from(files['/proc/2/cmdline']);
      return encoding?files[path]:Buffer.from(files[path]);
    },
    lstatSync(path){
      if(path===profile)return {isDirectory:()=>true};
      assert.equal(path,profile+'/DevToolsActivePort');return {size:50,isFile:()=>!symlink,isSymbolicLink:()=>symlink};
    }
  };
  const browser={
    target:()=>({createCDPSession:async()=>({send:async method=>{
      assert.equal(method,'Browser.getBrowserCommandLine');return {arguments:[`--user-data-dir=${endpointProfile}`]};
    }})}),
    close:async()=>{closed++;if(exit)alive=false;},disconnect:async()=>{disconnected++;}
  };
  const options={fs,connect:async({browserWSEndpoint})=>{
    assert.equal(browserWSEndpoint,'ws://127.0.0.1:12345/devtools/browser/fixture-id');return browser;
  },now:()=>time,sleep:async ms=>{time+=ms;}};
  return {options,files,get closed(){return closed;},get disconnected(){return disconnected;}};
}
test('legacy transition closes only the existing local browser, never deletes or signals processes',async()=>{
  const f=fixture();await closeLegacyBrowser(f.options);
  assert.equal(f.closed,1);assert.equal(f.disconnected,1);
  assert.equal(f.files[`${profile}/DevToolsActivePort`],'12345\n/devtools/browser/fixture-id\n');
  // fs exposes no mutation or kill functions: the operation cannot use them.
});
test('absent or ambiguous owners fail before connecting or closing',async()=>{
  for(const ownerCount of [0,2]){
    const f=fixture({ownerCount});await assert.rejects(closeLegacyBrowser(f.options),/exactly one/);
    assert.equal(f.closed,0);
  }
});
test('symlinks and malformed endpoint metadata never reach the browser',async()=>{
  const f=fixture({symlink:true});await assert.rejects(closeLegacyBrowser(f.options),/metadata/);
  for(const metadata of ['99999\n/devtools/browser/fixture-id','22\nhttps://remote.example/secret','22\n/devtools/browser/id/extra']){
    const g=fixture();g.files[`${profile}/DevToolsActivePort`]=metadata;
    await assert.rejects(closeLegacyBrowser(g.options),/endpoint/);assert.equal(g.closed,0);
  }
});
test('endpoint for another profile is disconnected without closing that browser',async()=>{
  const f=fixture({endpointProfile:'/other/profile'});
  await assert.rejects(closeLegacyBrowser(f.options),/another profile/);
  assert.equal(f.closed,0);assert.equal(f.disconnected,1);
});
test('a browser remaining alive after CDP close fails without force-kill',async()=>{
  const f=fixture({exit:false});await assert.rejects(closeLegacyBrowser(f.options),/did not exit/);
  assert.equal(f.closed,1);assert.equal(f.disconnected,1);
});
