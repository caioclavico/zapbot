'use strict';
// A inicialização do Cassandra não depende dos trabalhos periódicos.
function startBackground(service,{schedule=setInterval,logger=console.error}={}) {
  if(service.readOnly)return null;
  service.startTimers();
  const timer=schedule(()=>service.pruneMedia().catch(()=>logger(JSON.stringify({event:'media_cleanup_failed'}))),3600000);
  timer.unref();
  return timer;
}
module.exports={startBackground};
