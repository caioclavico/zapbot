// Passive, bounded readiness probe. Never accesses the session or restarts it.
'use strict';
const http=require('node:http');
const states=new Set(['STARTING','AUTHENTICATED','QR','READY','DISCONNECTED','AUTH_FAILURE','ERROR','RECOVERING']);
function checkHealth({get=http.get,schedule=setTimeout,cancel=clearTimeout,log=console.log,error=console.error}={}){
  return new Promise(resolve=>{
    let request,timer,done=false;
    function finish(code,reason,health){
      if(done)return;done=true;cancel(timer);
      try{
        if(health)log(JSON.stringify({status:health.status,whatsapp:health.whatsapp,chromium:health.chromium}));
        else error('Healthcheck failed: '+reason);
      }catch{}
      if(code!==0)request?.destroy();
      resolve(code);
    }
    // Total deadline, including a body that continues arriving in tiny chunks.
    timer=schedule(()=>finish(1,'deadline'),5000);timer.unref?.();
    try{
      request=get('http://127.0.0.1:3001/health',{timeout:5000},res=>{
        let body='',bytes=0;
        res.setEncoding('utf8');
        res.on('data',chunk=>{
          if(done)return;
          bytes+=Buffer.byteLength(chunk);
          if(bytes>4096){finish(1,'body_limit');return;}
          body+=chunk;
        });
        res.on('end',()=>{
          if(done)return;
          try{
            const health=JSON.parse(body);
            if(!health||!['ok','degraded'].includes(health.status)||!states.has(health.whatsapp)||typeof health.chromium!=='boolean')
              return finish(1,'invalid_response');
            finish(res.statusCode===200&&health.status==='ok'&&health.whatsapp==='READY'&&health.chromium?0:1,'not_ready',health);
          }catch{finish(1,'invalid_response');}
        });
        res.on('error',()=>finish(1,'response_error'));
      });
      request.on('timeout',()=>finish(1,'socket_timeout'));
      request.on('error',()=>finish(1,'connection_error'));
      if(done)request.destroy();
    }catch{finish(1,'request_error');}
  });
}
if(require.main===module)checkHealth().then(code=>{process.exitCode=code;});
module.exports={checkHealth};
