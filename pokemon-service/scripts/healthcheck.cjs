'use strict';
const http=require('node:http');
const host=process.env.HOST==='::1'?'::1':'127.0.0.1';
const req=http.get({hostname:host,port:Number(process.env.PORT||8090),path:'/ready',timeout:3000},res=>{
  res.resume();process.exitCode=res.statusCode===200?0:1;
});
req.on('timeout',()=>req.destroy(new Error('timeout')));
req.on('error',()=>{process.exitCode=1;});
