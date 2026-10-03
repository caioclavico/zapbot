'use strict';
require('dotenv').config();
const http=require('node:http');
const path=require('node:path');
const {readOnly}=require('./mode.cjs');
const domain=require('../target/domain.cjs');
const {PokemonService,handler}=require('./service.cjs');
const {MediaStore}=require('./media.cjs');
const {startBackground}=require('./background.cjs');
const host=process.env.HOST||'127.0.0.1';
const port=Number(process.env.PORT||8090);
const token=process.env.API_TOKEN||'';
if(!['127.0.0.1','::1','localhost'].includes(host)&&token.length<24)throw new Error('API_TOKEN de pelo menos 24 caracteres é obrigatório fora do loopback.');
if(!Number.isInteger(port)||port<1||port>65535)throw new Error('PORT inválida.');
const media=new MediaStore(path.join(process.env.DATA_DIR||path.join(__dirname,'../data'),'media'));
const service=new PokemonService({domain,media});
const server=http.createServer(handler(service,{token}));
server.requestTimeout=90000;server.headersTimeout=10000;server.keepAliveTimeout=5000;server.maxRequestsPerSocket=100;
server.listen(port,host,()=>console.log(JSON.stringify({event:'listening',host,port})));
let cleanup=null;
service.initialize().then(()=>{if(closing)return;cleanup=startBackground(service);console.log(JSON.stringify({event:'ready',readOnly,gameTimersEnabled:!readOnly,persistence:domain.diagnostics()}));}).catch(e=>{
  console.error(JSON.stringify({event:'initialization_failed',message:e.message}));
});

let closing=false;
async function stop(){
  if(closing)return;closing=true;clearInterval(cleanup);service.accepting=false;
  server.close();
  const deadline=setTimeout(()=>process.exit(1),60000);deadline.unref();
  await service.close();clearTimeout(deadline);process.exit(0);
}
process.on('SIGTERM',stop);process.on('SIGINT',stop);
