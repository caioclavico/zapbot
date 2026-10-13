'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const {createClient, retryAfterMs} = require('../scripts/lib/ai-client.cjs');

const response = (provider, status = 200, text = 'Resposta.') => ({
  ok: status === 200, status, headers: new Headers(),
  json: async () => provider === 'gemini'
    ? {candidates: [{content: {parts: [{text}]}}]}
    : {choices: [{message: {content: text}}]},
});
function fixture(results, overrides = {}) {
  const calls = [], events = [], delays = [];
  let time = 0;
  const client = createClient({geminiApiKey:'gemini-fixture',geminiModel:'modelo-gemini-configurado',
    groqApiKey:'groq-fixture',groqModel:'modelo-groq-configurado',systemPrompt:'Você é Odisseu. Contexto progressista.',
    now:()=>time, sleep:async ms=>{delays.push(ms);time+=ms;}, logger:event=>events.push(event),
    fetch:async (url, options)=>{
      calls.push({url,options,body:JSON.parse(options.body)});
      const next=results.shift();
      if(next instanceof Error)throw next;
      if(typeof next==='function')return next(url,options);
      assert.ok(next,'Unexpected extra API request');
      return next;
    },...overrides});
  return {client,calls,events,delays};
}

test('Gemini success preserves configured model and never calls Groq',async()=>{
  const f=fixture([response('gemini')]);
  assert.equal(await f.client.generate('Mensagem privada'), 'Resposta.');
  assert.equal(f.calls.length,1);
  assert.match(f.calls[0].url,/modelo-gemini-configurado:generateContent$/);
  assert.equal(f.calls[0].body.contents[0].parts[0].text,'Mensagem privada');
  assert.equal(f.calls[0].options.headers['x-goog-api-key'],'gemini-fixture');
  assert.ok(f.events.some(e=>e.provider==='gemini'&&e.model==='modelo-gemini-configurado'&&e.outcome==='complete'));
});

for(const status of [429,500,502,503,504]){
  test(`Gemini ${status}: two exponential retries then Groq with same context`,async()=>{
    const f=fixture([response('gemini',status),response('gemini',status),response('gemini',status),response('groq')]);
    assert.equal(await f.client.generate('Contexto completo da conversa'),'Resposta.');
    assert.equal(f.calls.length,4);
    assert.deepEqual(f.delays,[1000,2000]);
    const gemini=f.calls[0].body, groq=f.calls[3].body;
    assert.equal(f.calls[3].url,'https://api.groq.com/openai/v1/chat/completions');
    assert.equal(groq.model,'modelo-groq-configurado');
    assert.equal(groq.messages[0].content,gemini.systemInstruction.parts[0].text);
    assert.equal(groq.messages[1].content,gemini.contents[0].parts[0].text);
    assert.equal(f.calls[3].options.headers.Authorization,'Bearer groq-fixture');
    assert.ok(f.events.some(e=>e.provider==='groq'&&e.outcome==='complete'));
  });
}

test('Retry-After seconds and HTTP date override shorter exponential delays',async()=>{
  const a=response('gemini',429);a.headers.set('retry-after','3');
  const b=response('gemini',503);b.headers.set('retry-after',new Date(7000).toUTCString());
  const f=fixture([a,b,response('gemini')]);
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
  assert.deepEqual(f.delays,[3000,4000]);
  assert.equal(retryAfterMs('invalid',0),0);
  assert.equal(retryAfterMs('-1',0),0);
});

test('Retry-After beyond budget skips Gemini retry instead of retrying too early',async()=>{
  const limited=response('gemini',429);limited.headers.set('retry-after','3600');
  const f=fixture([limited,response('groq')]);
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
  assert.deepEqual(f.delays,[]);
  assert.equal(f.calls.length,2);
});

test('permanent Gemini error falls back without wasting retries',async()=>{
  const f=fixture([response('gemini',401),response('groq')]);
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
  assert.equal(f.calls.length,2);
  assert.deepEqual(f.delays,[]);
});

test('both providers unavailable return null for the caller short outage message',async()=>{
  const f=fixture([response('gemini',500),response('gemini',500),response('gemini',500),response('groq',503)]);
  assert.equal(await f.client.generate('Pergunta'),null);
  assert.equal(f.calls.length,4);
  assert.equal(f.events.at(-1).outcome,'unavailable');
});

test('absent Groq key keeps Gemini-only retries and no Groq request',async()=>{
  const f=fixture([response('gemini',503),response('gemini',503),response('gemini',503)],{groqApiKey:'  '});
  assert.equal(await f.client.generate('Pergunta'),null);
  assert.equal(f.calls.length,3);
  assert.ok(f.calls.every(c=>c.url.includes('googleapis.com')));
  assert.ok(!f.events.some(e=>e.provider==='groq'));
});

test('default Groq model and Groq-only configuration',async()=>{
  const f=fixture([response('groq')],{geminiApiKey:'',groqModel:undefined});
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
  assert.equal(f.calls[0].body.model,'llama-3.1-8b-instant');
});

test('missing all keys returns null without network',async()=>{
  const f=fixture([],{geminiApiKey:'',groqApiKey:''});
  assert.equal(await f.client.generate('Pergunta'),null);
  assert.equal(f.calls.length,0);
});

test('network failure retries but logs never contain error messages, keys or prompts',async()=>{
  const secret='PRIVATE MESSAGE gemini-fixture groq-fixture';
  const failure=response('groq',400);failure.json=async()=>{throw new Error(secret);};
  const f=fixture([new Error(secret),new Error(secret),new Error(secret),failure]);
  assert.equal(await f.client.generate(secret),null);
  const log=JSON.stringify(f.events);
  for(const value of [secret,'gemini-fixture','groq-fixture'])assert.ok(!log.includes(value));
  assert.ok(f.events.some(e=>e.error==='network'));
});

test('empty response and invalid JSON both fall back',async()=>{
  for(const first of [response('gemini',200,''),{...response('gemini'),json:async()=>{throw new SyntaxError('private');}}]){
    const f=fixture([first,response('groq')]);
    assert.equal(await f.client.generate('Pergunta'),'Resposta.');
    assert.equal(f.calls.length,2);
  }
});

test('timeout includes stalled JSON, aborts Gemini and still leaves fallback time',async()=>{
  let signal;
  const f=fixture([(_url,options)=>{signal=options.signal;return {...response('gemini'),json:()=>new Promise(()=>{})};},response('groq')],
    {now:Date.now,requestTimeoutMs:20,totalTimeoutMs:80,retryBaseMs:1000});
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
  assert.equal(signal.aborted,true);
  assert.ok(f.events.some(e=>e.error==='timeout'));
});

test('both hung providers obey total deadline even if fetch ignores abort',async()=>{
  const signals=[];
  const f=fixture([],{now:Date.now,requestTimeoutMs:1000,totalTimeoutMs:50,
    fetch:async(_url,options)=>{signals.push(options.signal);return new Promise(()=>{});}});
  const start=Date.now();
  assert.equal(await f.client.generate('Pergunta'),null);
  assert.ok(Date.now()-start<250,'Total time must stay bounded');
  assert.equal(signals.length,2);
  assert.ok(signals.every(signal=>signal.aborted));
});

test('logger failure cannot stop successful generation',async()=>{
  const f=fixture([response('gemini')],{logger:()=>{throw new Error('logging unavailable');}});
  assert.equal(await f.client.generate('Pergunta'),'Resposta.');
});
