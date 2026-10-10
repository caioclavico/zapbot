'use strict';
const test=require('node:test'); const assert=require('node:assert/strict'); const sharp=require('sharp');
const {render}=require('../runtime/egg-cards.cjs'); const {PokemonService}=require('../runtime/service.cjs');
const egg=(category,state='inventory',points=0,goal=100)=>({category,state,points,'points-goal':goal});
test('renders empty, full and progress egg cards as bounded PNGs',async()=>{
  for(const data of [{eggs:[]},{eggs:Array.from({length:9},(_,i)=>egg(['comum','raro','epico'][i%3],i===0?'incubating':'inventory',i*10))}]){
    const out=await render('eggs',data), meta=await sharp(out).metadata(); assert.equal(meta.format,'png'); assert.deepEqual([meta.width,meta.height],[640,820]); assert.ok(out.length<150000);
  }
});
test('renders capsule states and does not invent unavailable slots',async()=>{
  const out=await render('incubators',{incubators:[{kind:'permanente',uses:null},{kind:'extra',uses:0},{kind:'especial',uses:3,bonusPoints:25}]});
  const meta=await sharp(out).metadata(); assert.equal(meta.format,'png'); assert.ok(out.length<150000);
});
test('service converts card response to image and falls back to text on renderer failure',async()=>{
  const domain={registerModule(){},load(){return{}},store:async()=>{},reserve:async()=>true,initialize:async()=>{},isReady:()=>true,command:async()=>({cardType:'eggs',cardData:{eggs:[]},texto:'fallback'}),takeEffects:()=>[],stopTimers(){},shutdown:async()=>{}};
  const media={put:async()=>({mediaId:'card-media',mimeType:'image/png',filename:'card.png'})};
  const svc=new PokemonService({domain,media,logger:()=>{}}); await svc.initialize();
  const response=await svc.execute({requestId:'card-1',chatId:'g',playerId:'p',command:'pokemon ovos'}); assert.equal(response.messages[0].type,'image'); assert.equal(response.messages[0].mimeType,'image/png');
  const bad=new PokemonService({domain:{...domain,command:async()=>({cardType:'invalid',texto:'fallback'})},media,logger:()=>{}}); await bad.initialize(); const fallback=await bad.execute({requestId:'card-2',chatId:'g',playerId:'p',command:'pokemon ovos'}); assert.equal(fallback.messages[0].type,'text');
});
