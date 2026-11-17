'use strict';
const fs=require('node:fs');
const path=require('node:path');

// Adapter for the pinned whatsapp-web.js Client. Retries inject(), never
// initialize(), logout(), authentication or a command sent to WhatsApp.
function transient(error) {
  return /^(?:Protocol error \(Runtime\.(?:callFunctionOn|evaluate)\): )?(?:Execution context was destroyed(?:, most likely because of a navigation)?|Cannot find context with specified id)\.?$/i.test(String(error?.message||error).trim());
}
function installStartupRecovery(client,{attempts=3,backoff=[2000,5000],timeoutMs=480000,injectTimeoutMs=120000,closeTimeoutMs=30000,restoreOnly,
  schedule=setTimeout,cancel=clearTimeout,logger=console.error,onState=()=>{}}={}) {
  if(!Number.isInteger(attempts)||attempts<1||attempts>4||backoff.length<attempts-1||
    !backoff.every(n=>Number.isInteger(n)&&n>=0)||!Number.isInteger(timeoutMs)||timeoutMs<1||
    !Number.isInteger(injectTimeoutMs)||injectTimeoutMs<1||
    !Number.isInteger(closeTimeoutMs)||closeTimeoutMs<1||closeTimeoutMs>30000)
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
  let injectGeneration=0;
  client.zapbotStartupState='starting';
  function log(event,extra={}) {try{logger(JSON.stringify({event,...extra}));}catch{}}
  // Structured stages for operators (no URLs, cookies, QR, or tokens).
  function stage(name,extra={}) {
    log('whatsapp_startup_stage',{
      stage:name,
      startup_state:client.zapbotStartupState,
      inject_pending:!!pending,
      ready_deferred:readyDuringInject,
      inject_generation:injectGeneration,
      browser_connected:!!client.pupBrowser?.isConnected?.(),
      page_closed:client.pupPage?!!client.pupPage.isClosed?.():null,
      ...extra
    });
  }
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
      // Capture/listen before destroy(): exit may precede its resolution. Null
      // exitCode without a signal means pending, never success or failure yet.
      const child=browser.process?.();
      let onExit,onError,timer;
      const failure=(reason,message)=>Object.assign(
        new Error('Chromium closure was not confirmed: '+message),{code:reason});
      try{
        const exited=!child?Promise.resolve():new Promise((resolve,reject)=>{
          onExit=(code,signal)=>{
            if(signal!==null)reject(failure('CHROMIUM_EXIT_SIGNAL','terminated by '+signal));
            else if(code!==0)reject(failure('CHROMIUM_EXIT_CODE','exit code '+code));
            else resolve();
          };
          onError=()=>reject(failure('CHROMIUM_PROCESS_ERROR','child process error'));
          if(child.exitCode!==null||child.signalCode!==null){onExit(child.exitCode,child.signalCode);return;}
          if(typeof child.once!=='function'||typeof child.removeListener!=='function'){
            reject(failure('CHROMIUM_PROCESS_ERROR','exit observation unavailable'));return;
          }
          child.once('exit',onExit);child.once('error',onError);
          // Also cover exit between the initial state check and registration.
          if(child.exitCode!==null||child.signalCode!==null)onExit(child.exitCode,child.signalCode);
        });
        await Promise.race([
          Promise.all([Promise.resolve().then(()=>client.destroy()),exited]), // No logout.
          new Promise((_,reject)=>{timer=schedule(()=>reject(
            failure('CHROMIUM_EXIT_TIMEOUT','shutdown deadline exceeded')),closeTimeoutMs);})
        ]);
        if(browser.isConnected())throw failure('CHROMIUM_STILL_CONNECTED','browser still connected');
      }finally{
        if(timer!==undefined)cancel(timer);
        if(child&&typeof child.removeListener==='function'){
          if(onExit)child.removeListener('exit',onExit);
          if(onError)child.removeListener('error',onError);
        }
      }
      closedBrowser=browser;
    })().then(()=>{closing=null;});
    // Retain a failed close, too: a timed-out destroy may still be running.
    // Retrying cleanup would race it and could mask the original failure.
    return closing;
  }
  async function fail(error,phase){
    if(!failed&&!stopped){
      failed=true;state('error');clearDeadline();cancelWait();
      const reason=transient(error)?'context_destroyed':
        error?.code==='STARTUP_TIMEOUT'?'startup_timeout':error?.code==='AUTH_TRANSITION'?'authentication_transition':'non_transient';
      stage('failed',{phase,reason,code:error?.code||null});
      log('whatsapp_recovery_failed',{phase,reason});
    }
    try{await closeBrowser();}catch(cleanup){log('whatsapp_cleanup_failed',{phase});throw cleanup;}
  }
  // wwjs emits AUTHENTICATED+READY from onAppStateHasSyncedEvent. inject() only
  // installs the Socket.hasSynced listener and returns. A later framenavigated
  // re-inject can race the first hasSynced callback (LoadUtils / WWebJS) and leave
  // Socket.hasSynced=true with WWebJS undefined and no READY. If hasSynced is
  // already true when listeners are (re)attached, change:hasSynced also never
  // fires again — catch up explicitly after a successful inject.
  async function catchupHasSynced(page){
    if(stopped||failed||!page||page.isClosed?.())return;
    async function probe(){
      return page.evaluate(()=>{
        try{
          const Socket=window.require('WAWebSocketModel').Socket;
          return {
            hasSynced:!!Socket.hasSynced,
            socket_state:Socket.state||null,
            has_catchup_fn:typeof window.onAppStateHasSyncedEvent==='function',
            has_wwebjs:typeof window.WWebJS!=='undefined'
          };
        }catch{
          return {hasSynced:false,has_catchup_fn:false,has_wwebjs:false,probe_error:true};
        }
      });
    }
    let status;
    try{status=await probe();}
    catch(error){
      stage('hasSynced_probe_failed',{transient:transient(error),code:error?.code||null});
      return;
    }
    stage('hasSynced_probe',status||{});
    if(!status?.hasSynced)return;
    // Do NOT only call onAppStateHasSyncedEvent after pre-injecting LoadUtils:
    // wwjs then sees WWebJS already defined, skips ClientInfo/attachEventListeners,
    // emits READY, and the bot never receives message_create.
    if(!status.has_wwebjs){
      try{
        const utilsPath=require.resolve('whatsapp-web.js/src/util/Injected/Utils');
        const {LoadUtils}=require(utilsPath);
        stage('hasSynced_loadutils_inject');
        await page.evaluate(LoadUtils);
      }catch(error){
        stage('hasSynced_loadutils_failed',{transient:transient(error),code:error?.code||null});
        return;
      }
    }
    try{
      if(!client.info){
        const ClientInfo=require(require.resolve('whatsapp-web.js/src/structures/ClientInfo'));
        const InterfaceController=require(require.resolve('whatsapp-web.js/src/util/InterfaceController'));
        const raw=await page.evaluate(()=>({
          ...window.require('WAWebConnModel').Conn.serialize(),
          wid:window.require('WAWebUserPrefsMeUser').getMaybeMePnUser()||
              window.require('WAWebUserPrefsMeUser').getMaybeMeLidUser()
        }));
        client.info=new ClientInfo(client,raw);
        client.interface=new InterfaceController(client);
        stage('hasSynced_clientinfo');
      }
      if(!client.zapbotListenersAttached){
        await client.attachEventListeners();
        client.zapbotListenersAttached=true;
        stage('hasSynced_listeners_attached');
      }
    }catch(error){
      stage('hasSynced_wire_failed',{transient:transient(error),code:error?.code||null});
      return;
    }
    if(client.zapbotStartupState!=='ready'){
      stage('hasSynced_catchup_ready_emit');
      client.emit('ready');
    }
    stage('hasSynced_catchup_done');
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
        // Avoid re-inject churn once READY is accepted; navigation after ready
        // previously raced LoadUtils and stranded RECOVERING with hasSynced=true.
        if(client.zapbotStartupState==='ready'){
          stage('inject_skip_after_ready');
          return;
        }
        return Promise.resolve().then(()=>listener.call(page,frame)).catch(error=>fail(error,'navigation')).catch(()=>{});
        }catch(error){return fail(error,'navigation').catch(()=>{});}
      });
    };
  }
  if(inject)client.inject=function(){
    if(pending){
      stage('inject_coalesced');
      return pending;
    }
    let succeeded=false;
    const generation=++injectGeneration;
    const page=client.pupPage;
    const work=(async()=>{
      if(!alive()){await closeBrowser();throw new Error('WhatsApp startup is stopped or browser is unavailable');}
      hookPage(page);state('recovering');
      stage('inject_enter',{generation});
      for(let attempt=1;attempt<=attempts;attempt++){
        if(!alive()||client.pupPage!==page)throw new Error('WhatsApp page changed or recovery stopped');
        readyDuringInject=false;
        state('recovering');
        stage('inject_attempt',{generation,attempt,attempts,inject_timeout_ms:injectTimeoutMs});
        let timer;
        try{
          const value=await Promise.race([Promise.resolve().then(inject),new Promise((_,reject)=>{
            timer=schedule(()=>{
              const error=Object.assign(new Error('WhatsApp injection did not finish'),{code:'STARTUP_TIMEOUT'});
              stage('inject_timeout',{generation,attempt,inject_timeout_ms:injectTimeoutMs});
              fail(error,'injection').catch(()=>{});reject(error);
            },injectTimeoutMs);
          })]);
          if(timer!==undefined){cancel(timer);timer=undefined;}
          succeeded=true;
          stage('inject_success',{generation,attempt});
          return value;
        }
        catch(error){
          if(timer!==undefined){cancel(timer);timer=undefined;}
          readyDuringInject=false;
          if(!stopped&&!failed)state('recovering');
          if(!transient(error)||attempt===attempts||!alive()){
            stage('inject_failed',{generation,attempt,transient:transient(error),code:error?.code||null});
            throw error;
          }
          const delay=backoff[attempt-1];
          log('whatsapp_inject_retry',{attempt,next_attempt:attempt+1,backoff_ms:delay,reason:'context_destroyed'});
          stage('inject_retry',{generation,attempt,next_attempt:attempt+1,backoff_ms:delay});
          await new Promise(resolve=>{wait={resolve,timer:schedule(()=>{wait=null;resolve();},delay)};});
        }
        finally{if(timer!==undefined)cancel(timer);}
      }
    })();
    // Use then() (not async finally) so callers always await catch-up / ready re-emit.
    pending=work.then(async(value)=>{
      pending=null;
      if(succeeded&&!stopped&&!failed){
        try{await catchupHasSynced(page);}
        catch{stage('hasSynced_catchup_unhandled');}
      }
      if(succeeded&&readyDuringInject&&!stopped&&!failed){
        stage('ready_reemit_after_inject',{generation});
        client.emit('ready');
      }
      readyDuringInject=false;
      return value;
    },async(error)=>{
      pending=null;
      readyDuringInject=false;
      throw error;
    });
    return pending;
  };
  client.on('ready',()=>{
    if(stopped||failed)return;
    if(!alive()||client.lastLoggedOut){
      fail(Object.assign(new Error('READY without a live authenticated browser'),{code:'AUTH_TRANSITION'}),'readiness').catch(()=>{});
      return;
    }
    if(pending){
      readyDuringInject=true;
      stage('ready_deferred_during_inject');
      return;
    }
    state('ready');clearDeadline();
    stage('ready_accepted');
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
