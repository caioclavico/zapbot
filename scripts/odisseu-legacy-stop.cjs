'use strict';
// Operator-only transition for images whose Puppeteer signal handlers compete
// with the application's graceful shutdown. No kill, logout or profile cleanup.
async function closeLegacyBrowser({profile='/app/.wwebjs_auth/session',fs=require('node:fs'),connect,
  now=Date.now,sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))}={}) {
  if(!fs.lstatSync(profile).isDirectory())throw Error('Profile must be a real directory');
  function owners(){
    const result=[];
    for(const pid of fs.readdirSync('/proc').filter(name=>/^\d+$/.test(name))){
      let args;
      try{args=fs.readFileSync(`/proc/${pid}/cmdline`).toString().split('\0');}
      catch(error){if(error.code==='ENOENT'||error.code==='ESRCH')continue;throw Error('Process inventory unavailable');}
      if(args.some(arg=>arg.startsWith('--type=')))continue;
      const index=args.indexOf('--user-data-dir');
      if(!args.includes(`--user-data-dir=${profile}`)&&!(index>=0&&args[index+1]===profile))continue;
      if(!/(?:^|\/)(?:chromium|chrome)(?:$|[-.])/.test(args[0]||''))throw Error('Unexpected profile owner');
      result.push(pid);
    }
    return result;
  }
  if(owners().length!==1)throw Error('Expected exactly one Chromium profile owner');
  const info=fs.lstatSync(profile+'/DevToolsActivePort');
  if(!info.isFile()||info.isSymbolicLink()||info.size>1024)throw Error('Invalid local browser endpoint metadata');
  const [port,browserPath]=fs.readFileSync(profile+'/DevToolsActivePort','utf8').trim().split('\n');
  if(!/^[1-9]\d{0,4}$/.test(port)||Number(port)>65535||!/^\/devtools\/browser\/[a-zA-Z0-9-]+$/.test(browserPath))
    throw Error('Invalid local browser endpoint');
  const browser=await (connect||require('/app/node_modules/puppeteer').connect)({
    browserWSEndpoint:`ws://127.0.0.1:${port}${browserPath}`,defaultViewport:null,protocolTimeout:10000});
  try{
    const session=await browser.target().createCDPSession();
    const info=await session.send('Browser.getBrowserCommandLine');
    const args=info.arguments||[],index=args.indexOf('--user-data-dir');
    if(!args.includes(`--user-data-dir=${profile}`)&&!(index>=0&&args[index+1]===profile))
      throw Error('Browser endpoint belongs to another profile');
    await browser.close();
  }finally{await browser.disconnect();}
  const deadline=now()+10000;
  while(owners().length){
    if(now()>=deadline)throw Error('Chromium did not exit after graceful close');
    await sleep(100);
  }
}
function runLegacyStop(){
  const timer=setTimeout(()=>{console.error('Legacy browser stop timed out');process.exit(1);},20000);
  closeLegacyBrowser().then(()=>{clearTimeout(timer);process.exitCode=0;},()=>{
    clearTimeout(timer);console.error('Legacy browser stop could not be confirmed');process.exitCode=1;
  });
}
if(require.main===module)runLegacyStop();
module.exports={closeLegacyBrowser};
