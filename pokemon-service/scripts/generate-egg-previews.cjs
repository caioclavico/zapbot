'use strict';
const fs=require('node:fs'); const path=require('node:path'); const {render}=require('../runtime/egg-cards.cjs');
const out=path.join(__dirname,'../previews'); fs.mkdirSync(out,{recursive:true});
const eggGoals={comum:100,raro:250,epico:500};
const egg=(category,state='inventory',points=0,goal=eggGoals[category])=>({category,state,points,'points-goal':goal,'time-goal-ms':7200000,...(state==='incubating'?{'due-at':Date.now()+90*60*1000}:{})});
const incubator=(kind,activeEggId=null,uses=null,bonusPoints=0)=>({kind,activeEggId,uses,bonusPoints});
const cases=[
 ['preview-ovos-vazio.png','eggs',{eggs:[]}],
 ['preview-ovos-completo.png','eggs',{eggs:[egg('comum','incubating',45,100),egg('raro','inventory',0,250),egg('epico','ready-to-hatch',500,500),egg('comum'),egg('raro'),egg('epico'),egg('comum'),egg('raro'),egg('epico')]}],
 ['preview-incubadoras-vazias.png','incubators',{incubators:[incubator('permanente'),incubator('extra',null,3),incubator('especial',null,3,25)]}],
 ['preview-incubadoras-ocupadas.png','incubators',{incubators:[{...incubator('permanente','egg-1'),rarity:'comum'},{...incubator('extra','egg-2',2),rarity:'incomum'},{...incubator('especial','egg-3',1,25),rarity:'raro'},{...incubator('especial','egg-4',1,25),rarity:'epico'}]}],
 ['preview-incubadoras-bloqueadas.png','incubators',{incubators:[incubator('permanente','egg-1'),incubator('extra','egg-2',0),incubator('especial',null,0,25)]}],
];
(async()=>{for(const [name,kind,data] of cases){const b=await render(kind,data);fs.writeFileSync(path.join(out,name),b);console.log(name,b.length)}})().catch(e=>{console.error(e);process.exitCode=1});
