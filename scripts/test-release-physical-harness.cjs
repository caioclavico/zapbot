'use strict';

// Harness local da Fase 12.8.32. Usa bridge real, Datomic Local novo e
// processos Node filhos. O transporte WhatsApp é exclusivamente sintético.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const { EventEmitter } = require('node:events');
const { spawn } = require('node:child_process');
const { setTimeout: delay } = require('node:timers/promises');
const { createReleaseBootstrap } = require('./lib/release-bootstrap.cjs');

const TOKEN = 'release-physical-local-only';
const PILOT = 'phase32-pilot@g.us';
const CHAT = 'phase32-pilot@g.us';
const ROOT = process.cwd();

function json(file) { return JSON.parse(fs.readFileSync(file, 'utf8')); }
function write(file, value) { fs.writeFileSync(file, JSON.stringify(value)); }
function onceExit(child) {
  if (child.exitCode !== null || child.signalCode !== null) return Promise.resolve({code:child.exitCode,signal:child.signalCode});
  return new Promise(resolve => child.once('exit', (code, signal) => resolve({code, signal})));
}
const children = new Set();
function trackChild(child) {
  children.add(child);
  child.once('exit', () => children.delete(child));
  return child;
}
async function stopChild(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  const exited = onceExit(child);
  child.kill('SIGTERM');
  const forceKill = setTimeout(() => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
  }, 5000);
  forceKill.unref?.();
  try { await exited; }
  finally { clearTimeout(forceKill); }
}
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function waitFor(file, child, timeoutMs = 15000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (fs.existsSync(file)) return;
    if (child && child.exitCode !== null) throw new Error('child_exited_before_marker');
    await delay(20);
  }
  throw new Error('marker_timeout');
}

async function main() {
  if (process.argv[2] === '--worker') return worker(process.argv.slice(3));
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'zapbot-release-physical-'));
  const port = await freePort();
  const baseUrl = `http://127.0.0.1:${port}`;
  const storage = path.join(temp, 'datomic');
  const system = `phase32-${path.basename(temp)}`;
  let jvm;
  let jvmLog = '';
  let platform;
  const events = [];
  const jvmPids = [];
  const env = {
    ...process.env, ODISSEU_BRIDGE_HOST: '127.0.0.1', ODISSEU_BRIDGE_PORT: String(port),
    ODISSEU_BRIDGE_TOKEN: TOKEN, ODISSEU_BRIDGE_SYSTEM: system,
    ODISSEU_BRIDGE_STORAGE: storage, ODISSEU_BRIDGE_DB: 'game', ODISSEU_PLATFORM_DB: 'platform',
    ODISSEU_BRIDGE_SEED: 'false', ODISSEU_BRIDGE_RESET: 'false',
    ODISSEU_BRIDGE_RAID_AUTO_TICK: 'false', RELEASE_PILOT_CHAT_ID: PILOT
  };
  const startJvm = async () => {
    jvm = trackChild(spawn('clojure', ['-M', '-m', 'odisseu.bridge.http-server'], {cwd: ROOT, env, stdio: ['ignore', 'pipe', 'pipe']}));
    jvmPids.push(jvm.pid);
    const out = chunk => {
      const s = String(chunk);
      jvmLog = `${jvmLog}${s}`.slice(-8000);
      if (s.includes('[odisseu-bridge] up')) events.push('jvm-ready');
    };
    jvm.stdout.on('data', out); jvm.stderr.on('data', out);
    const {createClient} = require('./lib/platform-jvm-client.cjs');
    const client = createClient({baseUrl, token:TOKEN});
    try {
      const until = Date.now() + 60000;
      while (Date.now() < until) {
        if (jvm.exitCode !== null) throw new Error('jvm_failed_start');
        try { await client.getReleaseAnnouncement('phase32-readiness-probe'); return client; }
        catch (_) { await delay(100); }
      }
      throw new Error('jvm_readiness_timeout');
    } catch (error) {
      client.close();
      throw new Error(`${error.message}; temp=${temp}; jvmExit=${jvm.exitCode}; jvm=${JSON.stringify(jvmLog)}`);
    }
  };
  const stopJvm = () => stopChild(jvm);
  const spawnWorker = (mode, key, marker, claimant = 'worker') => trackChild(spawn(process.execPath,
    [__filename, '--worker', mode, baseUrl, TOKEN, key, marker, claimant],
    {cwd: ROOT, env: process.env, stdio: ['ignore', 'pipe', 'pipe']}));
  const outputs = child => {
    let stdout = ''; let stderr = '';
    child.stdout.on('data', x => {stdout += x;}); child.stderr.on('data', x => {stderr += x;});
    return () => ({stdout, stderr});
  };
  const makeKey = label => `12.8.32:${label}:${CHAT}`;
  const create = async (platform, label) => {
    const key = makeKey(label);
    await platform.createReleaseAnnouncement({version:'12.8.32',contentHash:label,chatId:CHAT,idempotencyKey:key},`phase32:${label}`);
    return key;
  };
  const read = async (platform,key) => (await platform.getReleaseAnnouncement(key)).announcement;
  const spawnCount = async (platform,key,claimant,now=Date.now(),lease=30000) => {
    const c = await platform.claimReleaseAnnouncement(key,claimant,lease,now);
    return c;
  };
  const makeIntent = label => ({
    status: 'confirmed', deployedVersion: '12.8.32', confirmedVersion: '12.8.32',
    rollbackComplete: true, chatId: PILOT,
    release: {
      botName: 'Odisseu', version: '12.8.32', contentHash: label,
      highlights: ['release homologada no harness local'], fixes: [], newCommands: [],
      announcementPolicy: {enabled: true}
    }
  });
  const makeBootstrap = ({intentPath, whatsapp, workerId}) => createReleaseBootstrap({
    enabled: true, intentPath, pilotChatId: PILOT,
    isChatAuthorized: chatId => chatId === PILOT,
    client: whatsapp, platform,
    checkJvmHealth: async () => true,
    isWhatsAppReady: () => true,
    isNodeHealthy: () => true,
    workerId
  });
  const makeWhatsApp = ({ack = true, label = 'send'} = {}) => {
    const client = new EventEmitter();
    const sends = [];
    client.sendMessage = async (chatId, body) => {
      const message = {id: {_serialized: `synthetic-${label}-${sends.length + 1}`}};
      sends.push({chatId, body, messageId: message.id._serialized});
      if (ack) setImmediate(() => client.emit('message_ack', message, 1));
      return message;
    };
    return {client, sends};
  };
  try {
    platform = await startJvm();

    // READY sem intent aprovada permanece inerte; publicação confirmada usa o piloto e a bridge real.
    const missingIntentPath = path.join(temp, 'no-release-intent.json');
    const noIntentWhatsApp = makeWhatsApp({label:'no-intent'});
    const noIntentResult = await makeBootstrap({intentPath:missingIntentPath,whatsapp:noIntentWhatsApp.client,workerId:'bootstrap-no-intent'}).onWhatsAppReady();
    events.push({scenario:'ready-without-release-intent',status:noIntentResult.status,sends:noIntentWhatsApp.sends.length});
    if (noIntentResult.status !== 'no-release-intent' || noIntentWhatsApp.sends.length !== 0) throw new Error('ready_without_intent_sent');

    const approvedIntentPath = path.join(temp, 'approved-release-intent.json');
    write(approvedIntentPath, makeIntent('approved-pilot-release'));
    const approvedWhatsApp = makeWhatsApp({label:'approved'});
    const approvedBootstrap = makeBootstrap({intentPath:approvedIntentPath,whatsapp:approvedWhatsApp.client,workerId:'bootstrap-approved'});
    const approvedResult = await approvedBootstrap.onWhatsAppReady();
    const approvedRepeat = await approvedBootstrap.onWhatsAppReady();
    const approvedKey = makeKey('approved-pilot-release');
    const approvedRow = await read(platform,approvedKey);
    events.push({scenario:'confirmed-release-pilot-ack-server',status:approvedResult.status,repeat:approvedRepeat.status,state:approvedRow.status,chatId:approvedWhatsApp.sends[0]?.chatId,messageId:approvedRow.messageId,sends:approvedWhatsApp.sends.length});
    if (approvedResult.status !== 'completed' || approvedRepeat.status !== 'already-completed' || approvedRow.status !== 'completed' || approvedWhatsApp.sends.length !== 1 || approvedWhatsApp.sends[0].chatId !== PILOT) throw new Error('approved_release_bootstrap_failed');

    const timeoutIntentPath = path.join(temp, 'timeout-release-intent.json');
    write(timeoutIntentPath, makeIntent('timeout-uncertain'));
    const timeoutWhatsApp = makeWhatsApp({ack:false,label:'timeout'});
    const timeoutBootstrap = makeBootstrap({intentPath:timeoutIntentPath,whatsapp:timeoutWhatsApp.client,workerId:'bootstrap-timeout'});
    const timeoutResult = await timeoutBootstrap.onWhatsAppReady();
    const timeoutKey = makeKey('timeout-uncertain');
    const timeoutRow = await read(platform,timeoutKey);
    const timeoutRetry = await timeoutBootstrap.onWhatsAppReady();
    events.push({scenario:'transport-timeout-uncertain-no-blind-retry',status:timeoutResult.status,retry:timeoutRetry.status,state:timeoutRow.status,messageId:timeoutRow.messageId,sends:timeoutWhatsApp.sends.length});
    if (timeoutResult.status !== 'uncertain' || timeoutRow.status !== 'uncertain' || timeoutRetry.status !== 'not-claimed' || timeoutWhatsApp.sends.length !== 1) throw new Error('timeout_uncertain_policy_failed');

    // Node morto depois de sendMessage aceito e antes de associate.
    const keyA = await create(platform,'crash-before-associate');
    const markerA = path.join(temp,'before-associate.json');
    const nodeA = spawnWorker('before-associate',keyA,markerA,'node-before');
    const logA = outputs(nodeA);
    try { await waitFor(markerA,nodeA); }
    catch (error) { throw new Error(`${error.message}; temp=${temp}; worker=${JSON.stringify(logA())}; jvmExit=${jvm.exitCode}; jvm=${JSON.stringify(jvmLog)}`); }
    const pidA = nodeA.pid; nodeA.kill('SIGKILL'); await onceExit(nodeA);
    const rowA = await read(platform,keyA);
    const noRetry = await spawnCount(platform,keyA,'retry-before');
    events.push({scenario:'node-kill-before-associate',pid:pidA,state:rowA.status,messageId:rowA.messageId,fence:rowA.fence,claimRetry:noRetry.claimed,simulatedSends:json(markerA).sends,workerLog:logA().stdout.trim()});
    if (rowA.status !== 'uncertain' || rowA.messageId || noRetry.claimed) throw new Error('before_associate_gate_failed');

    // Node morto depois da associação; um novo processo recebe ACK sem novo sendMessage.
    const keyB = await create(platform,'crash-after-associate');
    const markerB = path.join(temp,'after-associate.json');
    const nodeB = spawnWorker('after-associate',keyB,markerB,'node-associate');
    const logB = outputs(nodeB);
    try { await waitFor(markerB,nodeB); }
    catch (error) { throw new Error(`${error.message}; temp=${temp}; worker=${JSON.stringify(logB())}; jvmExit=${jvm.exitCode}; jvm=${JSON.stringify(jvmLog)}`); }
    const pidB = nodeB.pid; nodeB.kill('SIGKILL'); await onceExit(nodeB);
    const beforeRecover = await read(platform,keyB);
    const recover = spawnWorker('recover-associated',keyB,path.join(temp,'unused'),'node-recover');
    const recoverLog = outputs(recover); const recoverExit = await onceExit(recover);
    const afterRecover = await read(platform,keyB);
    events.push({scenario:'node-restart-after-associate',pids:[pidB,recover.pid],before:beforeRecover.status,after:afterRecover.status,messageId:afterRecover.messageId,fence:afterRecover.fence,simulatedSends:json(markerB).sends,recover:recoverLog().stdout.trim()});
    if (beforeRecover.status !== 'uncertain' || afterRecover.status !== 'completed' || recoverExit.code !== 0) throw new Error('after_associate_recovery_failed');

    // ACK antecipado na mesma instância física de Node, antes de associate.
    const keyC = await create(platform,'early-ack');
    const markerC = path.join(temp,'early-ack.json');
    const nodeC = spawnWorker('early-ack',keyC,markerC,'node-early');
    const logC = outputs(nodeC); const exitC = await onceExit(nodeC); const rowC = await read(platform,keyC);
    events.push({scenario:'early-ack',pid:nodeC.pid,state:rowC.status,messageId:rowC.messageId,fence:rowC.fence,result:logC().stdout.trim()});
    if (exitC.code !== 0 || rowC.status !== 'completed') throw new Error('early_ack_failed');

    // Duas instâncias concorrem por um claim; somente uma pode adquirir lease/fence.
    const keyD = await create(platform,'concurrent-claim');
    const n1 = spawnWorker('claim',keyD,path.join(temp,'claim-1.json'),'node-claim-1');
    const n2 = spawnWorker('claim',keyD,path.join(temp,'claim-2.json'),'node-claim-2');
    const n1log = outputs(n1), n2log = outputs(n2);
    const [e1,e2] = await Promise.all([onceExit(n1),onceExit(n2)]);
    const c1 = JSON.parse(n1log().stdout.trim()), c2 = JSON.parse(n2log().stdout.trim());
    events.push({scenario:'concurrent-node-claims',pids:[n1.pid,n2.pid],claims:[c1,c2]});
    if (e1.code || e2.code || Number(c1.claimed)+Number(c2.claimed)!==1) throw new Error('concurrent_claim_failed');

    // Reclaim é bloqueado para envio que já entrou na janela incerta.
    const keyE = await create(platform,'uncertain-no-reclaim');
    const old = await spawnCount(platform,keyE,'node-old');
    await platform.beginReleaseAnnouncementSend(keyE,'node-old',old.fence);
    await platform.associateReleaseMessage(keyE,'node-old',old.fence,'synthetic-reclaim-ack');
    const reclaim = await spawnCount(platform,keyE,'node-new',Date.now()+60000);
    const rowE = await read(platform,keyE);
    const ackNode = spawnWorker('ack-existing',keyE,path.join(temp,'unused'),'node-ack-during-reclaim');
    const ackLog = outputs(ackNode); const ackExit = await onceExit(ackNode);
    const afterAckE = await read(platform,keyE);
    events.push({scenario:'ack-during-reclaim-attempt',pid:ackNode.pid,oldFence:old.fence,reclaim:reclaim.claimed,reason:reclaim.reason,before:rowE.status,after:afterAckE.status,fence:afterAckE.fence,sendStartedAt:rowE.sendStartedAt,ack:ackLog().stdout.trim()});
    if (reclaim.claimed || rowE.status !== 'uncertain' || afterAckE.status !== 'completed' || ackExit.code !== 0) throw new Error('uncertain_reclaim_failed');

    // Restart JVM conserva conclusão; READY com a mesma intent não envia de novo.
    platform.close(); await stopJvm(); platform = await startJvm();
    const rowAfterJvmRestart = await read(platform,keyB);
    const completedBootstrap = spawnWorker('completed-no-send',keyB,path.join(temp,'completed.json'),'node-completed');
    const completedLog = outputs(completedBootstrap); const completedExit = await onceExit(completedBootstrap);
    events.push({scenario:'restart-after-complete',jvmRestart:true,nodePid:completedBootstrap.pid,state:rowAfterJvmRestart.status,result:completedLog().stdout.trim()});
    if (completedExit.code !== 0 || rowAfterJvmRestart.status !== 'completed') throw new Error('completed_restart_failed');

    const bootstrapAfterRestartWhatsApp = makeWhatsApp({label:'restart-after-complete'});
    const bootstrapAfterRestart = await makeBootstrap({intentPath:approvedIntentPath,whatsapp:bootstrapAfterRestartWhatsApp.client,workerId:'bootstrap-after-restart'}).onWhatsAppReady();
    events.push({scenario:'bootstrap-after-restart-no-duplicate',status:bootstrapAfterRestart.status,sends:bootstrapAfterRestartWhatsApp.sends.length});
    if (bootstrapAfterRestart.status !== 'already-completed' || bootstrapAfterRestartWhatsApp.sends.length !== 0) throw new Error('completed_release_resent_after_restart');

    console.log(JSON.stringify({status:'PASS',port,jvmPids,events,tempDirectory:temp}));
  } finally {
    try { if (platform) platform.close(); }
    finally {
      try {
        await Promise.all([...children].filter(child => child !== jvm).map(stopChild));
      } finally {
        await stopJvm();
      }
    }
  }
}

async function worker(args) {
  const [mode,baseUrl,token,key,marker,claimant] = args;
  const fs = require('node:fs');
  const {EventEmitter} = require('node:events');
  const {createClient} = require('./lib/platform-jvm-client.cjs');
  const {createReleaseTransport} = require('./lib/whatsapp-release-transport.cjs');
  const platform = createClient({baseUrl,token});
  const client = new EventEmitter();
  let sends=0;
  let stage = 'initializing';
  try {
  client.sendMessage = async () => { sends++; const message={id:{_serialized:`synthetic-${claimant}-${sends}`}}; return message; };
  if (mode === 'before-associate' || mode === 'after-associate' || mode === 'early-ack') {
    if (mode === 'before-associate') client.sendMessage=async()=>{sends++;fs.writeFileSync(marker,JSON.stringify({accepted:true,sends}));return new Promise(()=>{});};
    stage = 'claim';
    const claim=await platform.claimReleaseAnnouncement(key,claimant,30000,Date.now());
    if(!claim.claimed) throw new Error('claim_failed');
    let wrapped=platform;
    if(mode==='after-associate') wrapped=Object.create(platform),wrapped.associateReleaseMessage=async(...a)=>{const x=await platform.associateReleaseMessage(...a);fs.writeFileSync(marker,JSON.stringify({messageId:a[3],fence:a[2],sends}));return x;};
    if(mode==='early-ack')client.sendMessage=async()=>{sends++;const m={id:{_serialized:'synthetic-early-ack'}};client.emit('message_ack',m,1);return m;};
    const transport=createReleaseTransport({client,platform:wrapped});
    stage = 'send-and-confirm';
    const promise=transport.sendAndConfirm(PILOT,'synthetic',key,{timeoutMs:60000,claimant,fence:claim.fence});
    if(mode==='early-ack'){const result=await promise;console.log(JSON.stringify({result,sends}));if(!result.confirmed)process.exitCode=1;return;}
    if(mode==='before-associate'||mode==='after-associate')await promise;
    return;
  }
  if(mode==='recover-associated'){
    stage = 'recover-associated';
    const row=(await platform.getReleaseAnnouncement(key)).announcement;
    const transport=createReleaseTransport({client,platform});
    const promise=transport.sendAndConfirm(row.chatId,'synthetic',key,{timeoutMs:3000,claimant:row.claimant,fence:row.fence});
    setTimeout(()=>client.emit('message_ack',{id:{_serialized:row.messageId}},1),50);
    const result=await promise;
    console.log(JSON.stringify({result,sends}));
    if(!result.confirmed||sends!==0)process.exitCode=1;
    return;
  }
  if(mode==='ack-existing'){
    stage = 'ack-existing';
    const row=(await platform.getReleaseAnnouncement(key)).announcement;
    const transport=createReleaseTransport({client,platform});
    client.emit('message_ack',{id:{_serialized:row.messageId}},1);
    const until=Date.now()+3000;
    while(Date.now()<until){const current=(await platform.getReleaseAnnouncement(key)).announcement;if(current.status==='completed'){console.log(JSON.stringify({status:current.status,sends}));return;}await delay(25);}
    throw new Error('ack_not_persisted');
  }
  if(mode==='claim'){
    stage = 'claim';
    const result=await platform.claimReleaseAnnouncement(key,claimant,30000,Date.now());
    console.log(JSON.stringify(result)); return;
  }
  if(mode==='completed-no-send'){
    stage = 'completed-no-send';
    const row=(await platform.getReleaseAnnouncement(key)).announcement;
    console.log(JSON.stringify({status:row.status,sends}));
    if(row.status!=='completed'||sends!==0)process.exitCode=1;
    return;
  }
  throw new Error('unknown_worker_mode');
  } catch (error) {
    error.message = `${mode}:${stage}:${error.message}`;
    throw error;
  } finally {
    platform.close();
  }
}

main().catch(error=>{console.error(error.message||error.code);process.exitCode=1;});
