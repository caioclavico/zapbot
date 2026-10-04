'use strict';
// Only explicit diagnostic fields: never serialize requests, headers or driver objects.
function redact(value, secrets=[]) {
  let text=String(value ?? '');
  const configured=Object.entries(process.env)
    .filter(([key,value])=>value && /TOKEN|SECRET|PASSWORD|API_KEY|AUTHORIZATION/i.test(key))
    .map(([,value])=>value);
  for(const secret of [...configured,...secrets].filter(Boolean).sort((a,b)=>b.length-a.length)) {
    text=text.split(secret).join('[REDACTED]');
  }
  return text.replace(/\bBearer\s+[^\s"'<>]+/gi,'Bearer [REDACTED]');
}
function logError(logger, context, error, secrets=[]) {
  const details={};
  for(const key of ['name','code','message','stack','writeType','consistency','received','blockFor']) {
    const value=error?.[key];
    if(value!==undefined) details[key]=typeof value==='number'?value:redact(value,secrets);
  }
  if(!details.message) details.message=redact(error,secrets);
  const safeContext=Object.fromEntries(Object.entries(context).map(([key,value])=>
    [key,typeof value==='string'?redact(value,secrets):value]));
  try { logger(JSON.stringify({...safeContext,error:details})); } catch { /* Logging must not mask the failure. */ }
}
module.exports={logError};
