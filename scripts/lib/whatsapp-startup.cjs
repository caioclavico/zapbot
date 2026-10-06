'use strict';
const fs=require('node:fs');
const path=require('node:path');

// Adapter for the pinned whatsapp-web.js Client. Retries inject(), never
// initialize(), logout(), authentication or a command sent to WhatsApp.
function transient(error) {
  return /^(?:Protocol error \(Runtime\.(?:callFunctionOn|evaluate)\): )?(?:Execution context was destroyed(?:, most likely because of a navigation)?|Cannot find context with specified id)\.?$/i.test(String(error?.message||error).trim());
}
function installStartupRecovery(client,{attempts=3,backoff=[2000,5000],timeoutMs=480000,injectTimeoutMs=120000,restoreOnly,
  schedule=setTimeout,cancel=clearTimeout,logger=console.error,onState=()=>{}}={}) {
  if(!Number.isInteger(attempts)||attempts<1||attempts>4||backoff.length<attempts-1||
    !backoff.every(n=>Number.isInteger(n)&&n>=0)||!Number.isInteger(timeoutMs)||timeoutMs<1||
    !Number.isInteger(injectTimeoutMs)||injectTimeoutMs<1)
    throw new Error('Invalid WhatsApp startup recovery limits');
  const inject=client.inject?.bind(client),initialize=client.initialize.bind(client);
  const auth=client.authStrategy;
  const sessionPath=typeof auth?.dataPath==='string'?path.join(auth.dataPath,auth.clientId?'session-'+auth.clientId:'session'):null;
  if(restoreOnly===undefined){
    // Metadata only. An existing/nonempty LocalAuth root is a restoration,
    // whereas a genuinely fresh installation may perform its initial pairing.
    try{restoreOnly=!!sessionPath&&fs.existsSync(auth.dataPath)&&fs.readdirSync(auth.dataPath).length>0;}
    catch{restoreOnly=true;}
  }
  let pending=null,initialWork=null,initialResult=null,deadline=null,wait=null;
  let stopped=false,failed=false,closing=null,closedBrowser=null;
  let readyDuringInject=false;
  client.zapbotStartupState='starting';
  function log(event,extra={}) {try{logger(JSON.stringify({event,...extra}));}catch{}}
  function state(value){client.zapbotStartupState=value;try{onState(value);}catch{}}
  function clearDeadline(){if(deadline!==null){cancel(deadline);deadline=null;}}
  function cancelWait(){if(wait){cancel(wait.timer);wait.resolve();wait=null;}}
  function alive(){return !stopped&&!failed&&client.pupBrowser?.isConnected()&&
    client.pupPage&&!client.pupPage.isClosed();}
  function closeBrowser(){
    if(closing)return closing;
    const browser=client.pupBrowser;
    if(!browser||browser===closedBrowser)return Promise.resolve();
    closing=(async()=>{
      await client.destroy(); // Browser.close + authStrategy.destroy; no logout.
      const process=browser.process?.();
      if(browser.isConnected()||(process&&(process.exitCode!==0||process.signalCode!==null)))
        throw new Error('Chromium closure was not confirmed');
      closedBrowser=browser;
    })().finally(()=>{closing=null;});
    return closing;
  }
  async function fail(error,phase){
    if(!failed&&!stopped){
      failed=true;state('error');clearDeadline();cancelWait();
      log('whatsapp_recovery_failed',{phase,reason:transient(error)?'context_destroyed':
        error?.code==='STARTUP_TIMEOUT'?'startup_timeout':error?.code==='AUTH_TRANSITION'?'authentication_transition':'non_transient'});
    }
    try{await closeBrowser();}catch(cleanup){log('whatsapp_cleanup_failed',{phase});throw cleanup;}
  }
  function hookPage(page){
    if(page.zapbotNavigationGuard)return;
    page.zapbotNavigationGuard=true;
    const originalOn=page.on,on=page.on.bind(page);
    on('error',error=>{if(!stopped&&!failed)fail(error,'page').catch(()=>{});});
    if(typeof client.pupBrowser.once==='function')client.pupBrowser.once('disconnected',()=>{
      if(!stopped&&!failed)fail(new Error('Chromium disconnected'),'browser').catch(()=>{});
    });
    page.on=function(event,listener){
      if(event!=='framenavigated')return on(event,listener);
      // The pinned Client installs exactly one navigation listener immediately
      // after its first inject. Do not wrap future application/page listeners.
      page.on=originalOn;
      return on(event,function(frame){
        try{
        if(stopped||failed||frame!==page.mainFrame())return;
        // Upstream's navigation callback calls LocalAuth.logout(), which rm's
        // the profile. A server-side logout is terminal here, never repaired by
        // deleting credentials or triggering initialize() with an empty profile.
        if(String(frame.url()).includes('post_logout=1')||client.lastLoggedOut){
          const error=Object.assign(new Error('Authentication transition requires operator review'),{code:'AUTH_TRANSITION'});
          return fail(error,'navigation').catch(()=>{});
        }
        return Promise.resolve().then(()=>listener.call(page,frame)).catch(error=>fail(error,'navigation')).catch(()=>{});
        }catch(error){return fail(error,'navigation').catch(()=>{});}
      });
    };
  }
  if(inject)client.inject=function(){
    if(pending)return pending;
    let succeeded=false;
    pending=(async()=>{
      if(!alive()){await closeBrowser();throw new Error('WhatsApp startup is stopped or browser is unavailable');}
      const page=client.pupPage;hookPage(page);state('recovering');
      for(let attempt=1;attempt<=attempts;attempt++){
        if(!alive()||client.pupPage!==page)throw new Error('WhatsApp page changed or recovery stopped');
        readyDuringInject=false;
        state('recovering');
        let timer;
        try{
          const value=await Promise.race([Promise.resolve().then(inject),new Promise((_,reject)=>{
            timer=schedule(()=>{
              const error=Object.assign(new Error('WhatsApp injection did not finish'),{code:'STARTUP_TIMEOUT'});
              fail(error,'injection').catch(()=>{});reject(error);
            },injectTimeoutMs);
          })]);
          succeeded=true;return value;
        }
        catch(error){
          if(timer!==undefined){cancel(timer);timer=undefined;}
          readyDuringInject=false;
          if(!stopped&&!failed)state('recovering');
          if(!transient(error)||attempt===attempts||!alive())throw error;
          const delay=backoff[attempt-1];
          log('whatsapp_inject_retry',{attempt,next_attempt:attempt+1,backoff_ms:delay,reason:'context_destroyed'});
          await new Promise(resolve=>{wait={resolve,timer:schedule(()=>{wait=null;resolve();},delay)};});
        }
        finally{if(timer!==undefined)cancel(timer);}
      }
    })().finally(()=>{
      pending=null;
      if(succeeded&&readyDuringInject&&!stopped&&!failed)client.emit('ready');
      readyDuringInject=false;
    });
    return pending;
  };
  client.on('ready',()=>{
    if(stopped||failed)return;
    if(!alive()||client.lastLoggedOut){
      fail(Object.assign(new Error('READY without a live authenticated browser'),{code:'AUTH_TRANSITION'}),'readiness').catch(()=>{});
      return;
    }
    if(pending){readyDuringInject=true;return;}
    state('ready');clearDeadline();
  });
  client.on('auth_failure',()=>{fail(Object.assign(new Error('Authentication failure'),{code:'AUTH_TRANSITION'}),'authentication').catch(()=>{});});
  client.on('disconnected',()=>{if(!stopped&&!failed)fail(new Error('WhatsApp disconnected'),'connection').catch(()=>{});});
  client.on('qr',()=>{
    if(restoreOnly)fail(Object.assign(new Error('Existing authentication was not accepted; operator review required'),{code:'AUTH_TRANSITION'}),'authentication').catch(()=>{});
  });
  return {
    initialize(){
      if(initialResult)return initialResult;
      if(stopped||failed)return Promise.reject(new Error('WhatsApp startup is stopped'));
      if(restoreOnly&&sessionPath){
        try{if(!fs.lstatSync(sessionPath).isDirectory())throw new Error('Missing session');}
        catch{
          const error=Object.assign(new Error('Existing session directory is unavailable; automatic recreation blocked'),{code:'AUTH_TRANSITION'});
          initialResult=fail(error,'authentication').then(()=>{throw error;});return initialResult;
        }
      }
      const timeout=new Promise((_,reject)=>{
        deadline=schedule(()=>{
          const error=Object.assign(new Error('WhatsApp startup did not reach READY'),{code:'STARTUP_TIMEOUT'});
          fail(error,'startup').catch(()=>{});reject(error);
        },timeoutMs);
      });
      initialWork=Promise.resolve().then(initialize).finally(async()=>{
        // A timeout can win before launch finishes. Setup may then fail before
        // inject(), so its guard alone cannot clean up the late browser.
        if(failed||stopped)await closeBrowser();
      });
      initialResult=Promise.race([initialWork,timeout]).catch(async error=>{await fail(error,'startup');throw error;});
      return initialResult;
    },
    async close(){
      stopped=true;state('stopped');clearDeadline();cancelWait();
      await closeBrowser();
      // If SIGTERM arrives during launch, wait for that launch to settle and
      // close the browser it may have created late. The process shutdown guard
      // still fails closed if this cannot finish; never launch a second browser.
      if(initialWork)await initialWork.catch(()=>{});
      await closeBrowser();
    },
    acceptingReady(){return !stopped&&!failed&&client.zapbotStartupState==='ready';},
    acceptingQR(){return !restoreOnly&&!stopped&&!failed;},
    stopping(){return stopped;}
  };
}
module.exports={installStartupRecovery,transient};
