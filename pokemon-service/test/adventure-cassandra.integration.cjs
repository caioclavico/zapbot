'use strict';
// Opt-in: SOMENTE o container descartável com este nome. Nunca aceita IP/VM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const cassandra = require('cassandra-driver');
const host = process.env.ADVENTURE_TEST_HOST;
if (host !== 'zapbot-adventure-cassandra-validation-v2') {
  throw new Error('Este teste só permite o Cassandra descartável de validação.');
}
const keyspace = 'adventure_test_' + process.pid + '_' + Math.floor(Date.now()/1000);
process.env.CASSANDRA_CONTACT_POINTS = host;
process.env.CASSANDRA_DATACENTER = 'datacenter1';
process.env.CASSANDRA_KEYSPACE = keyspace;
process.env.ADVENTURE_SYNC_ENABLED = 'true';
process.env.POKEMON_READ_ONLY = 'false';
process.env.PERFORMANCE_METRICS = 'false';
process.env.EGG_CAPTURE_COMMON_PERCENT = '100';
process.env.EGG_PVP_EPIC_PERCENT = '100';
process.env.EGG_PVP_RARE_PERCENT = '100';

async function main() {
  const client = new cassandra.Client({contactPoints:[host],localDataCenter:'datacenter1'});
  let domain;
  const clock = Date.now;
  const execute = cassandra.Client.prototype.execute;
  try {
    await client.connect();
    await client.execute(`CREATE KEYSPACE ${keyspace} WITH replication = {'class':'SimpleStrategy','replication_factor':1}`);
    await client.execute(`CREATE TABLE ${keyspace}.estado (chave text PRIMARY KEY, valor text)`);
    await client.execute(`CREATE TABLE ${keyspace}.estado_particionado (modulo text, particao text, valor text, PRIMARY KEY ((modulo,particao)))`);
    const sentinel = JSON.stringify({'jogador-legado':{equipe:[],ativo:0,'preservar':'valor-original'}});
    await client.execute(`INSERT INTO ${keyspace}.estado_particionado (modulo,particao,valor) VALUES (?,?,?)`,
      ['treinador','grupo-sentinel',sentinel],{prepare:true});
    const schema = fs.readFileSync(path.join(__dirname,'../../scripts/cassandra/adventure-sync-v1.cql'),'utf8')
      .replaceAll('{{KEYSPACE}}',keyspace).replace(/^--.*$/gm,'');
    for (const sql of schema.split(';').map(s=>s.trim()).filter(Boolean)) await client.execute(sql);
    domain = require('../target/domain.cjs');
    const {PokemonService} = require('../runtime/service.cjs');
    const service = new PokemonService({domain,media:{},logger:()=>{}});
    await domain.initialize();
    let now = clock(); Date.now = ()=>now;
    const walk = (eventId,mm)=>{
      now += 600000;
      return {'event-id':eventId,'from-ms':now-300000,'to-ms':now,'distance-mm':mm};
    };
    const eggId = result=>result.operation['egg-id'];
    const account = ()=>domain.load('treinador')['grupo-real-test']['jogador@lid'];
    const results = await Promise.all([domain.eggsActivate('grupo-real-test','jogador@lid'),
                                      domain.eggsActivate('grupo-real-test','jogador@lid')]);
    assert.equal(results[1].state.eggs.length,1);
    await domain.eggsIncubate('grupo-real-test','jogador@lid','incubar',eggId(results[0]));
    const event = walk('passo-unico',700000);
    await Promise.all([domain.eggsDistance('grupo-real-test','jogador@lid',event),
                      domain.eggsDistance('grupo-real-test','jogador@lid',event)]);
    assert.equal((await domain.eggsState('grupo-real-test','jogador@lid')).eggs[0]['progress-mm'],700000);
    const other = await domain.eggsCreate('outro-grupo','jogador@lid','passo-unico',10);
    assert.notEqual(results[0].state['account-id'],other.state['account-id']);
    assert.equal(other.state.eggs[0].category,10);

    // Simula resposta perdida DEPOIS de um CAS real da coleção ter confirmado.
    let fault = true;
    cassandra.Client.prototype.execute = function(sql,args,opts) {
      const work = execute.call(this,sql,args,opts);
      if (fault && sql.includes('.estado_particionado') && sql.includes('IF ')) {
        fault = false;
        return work.then(()=>{throw Error('simulated lost response after trainer commit');});
      }
      return work;
    };
    const finish = walk('meta',2000000);
    await assert.rejects(domain.eggsDistance('grupo-real-test','jogador@lid',finish),/simulated/);
    cassandra.Client.prototype.execute = execute;
    await assert.rejects(domain.eggsState('grupo-real-test','jogador@lid'),/incerta/);
    await domain.eggsRecover('grupo-real-test','jogador@lid');
    await domain.eggsDistance('grupo-real-test','jogador@lid',finish);
    assert.equal(account().equipe.length,1);
    assert.equal(account().equipe[0].nivel,1);
    assert.equal(account().equipe[0]['hp-atual'],account().equipe[0].hp);
    assert.equal(account().equipe[0].ivs,undefined);
    assert.equal(account()['sequencia-capturas'],0);
    const month = new Date(now).toISOString().slice(0,7);
    const history = await domain.eggsHistory('grupo-real-test','jogador@lid',month,null);
    assert.equal(history.length,1);
    assert.equal(history[0]['pokemon-id'],account().equipe[0]['id-pokemon']);
    // Reexecutar somente CREATE IF NOT EXISTS no schema de teste não muda dados.
    for (const sql of schema.split(';').map(s=>s.trim()).filter(Boolean)) await client.execute(sql);
    const kept = await client.execute(`SELECT valor FROM ${keyspace}.estado_particionado WHERE modulo=? AND particao=?`,
      ['treinador','grupo-sentinel'],{prepare:true});
    assert.equal(kept.first().valor,sentinel);

    // Doação/remoção posterior não apaga o receipt. Recria leitura do snapshot
    // como faria uma recuperação após restart; nunca remove dados de produção.
    const row = await client.execute(`SELECT valor FROM ${keyspace}.estado_particionado WHERE modulo=? AND particao=?`,['treinador','grupo-real-test'],{prepare:true});
    const players = JSON.parse(row.first().valor);
    players['jogador@lid'].equipe=[];
    await client.execute(`UPDATE ${keyspace}.estado_particionado SET valor=? WHERE modulo=? AND particao=?`,
      [JSON.stringify(players),'treinador','grupo-real-test'],{prepare:true});
    await domain.eggsRecover('grupo-real-test','jogador@lid');
    await domain.eggsDistance('grupo-real-test','jogador@lid',finish);
    assert.equal(account().equipe.length,0);
    assert.equal((await domain.eggsHistory('grupo-real-test','jogador@lid',month,null)).length,1);
    // New activity-state uses the same real CAS and collection receipt.
    const activity = await domain.activityActivate('activity-real','player','activity-welcome');
    const activityEgg = activity.eggs[0];
    await domain.activityIncubate('activity-real','player','start',activityEgg['egg-id']);
    await Promise.all([domain.activityPoints('activity-real','player','points',100),
                       domain.activityPoints('activity-real','player','points',100)]);
    await domain.activityTime('activity-real','player',now + 7200000);
    let activityAccount = domain.load('treinador')['activity-real'].player;
    assert.equal(activityAccount.equipe.length,1);
    assert.equal(activityAccount.equipe[0].nivel,1);
    assert.equal(activityAccount.equipe[0]['hp-atual'],activityAccount.equipe[0].hp);
    assert.equal(activityAccount['sequencia-capturas'],0);
    assert.equal((await domain.activityState('activity-real','player')).eggs[0].state,'hatched');
    const timeActivity = await domain.activityActivate('activity-time','player','welcome');
    await domain.activityIncubate('activity-time','player','start',timeActivity.eggs[0]['egg-id']);
    await domain.activityTime('activity-time','player',now + 7200000);
    assert.equal(domain.load('treinador')['activity-time'].player.equipe.length,1);

    const uncertain = await domain.activityActivate('activity-uncertain','player','welcome');
    await domain.activityIncubate('activity-uncertain','player','start',uncertain.eggs[0]['egg-id']);
    fault = true;
    cassandra.Client.prototype.execute = function(sql,args,opts) {
      const work = execute.call(this,sql,args,opts);
      if (fault && sql.includes('.estado_particionado') && sql.includes('IF ')) {
        fault = false;
        return work.then(()=>{throw Error('simulated activity trainer response lost');});
      }
      return work;
    };
    await assert.rejects(domain.activityPoints('activity-uncertain','player','points',100),/simulated/);
    cassandra.Client.prototype.execute = execute;
    await domain.eggsRecover('activity-uncertain','player');
    await domain.activityPoints('activity-uncertain','player','points',100);
    assert.equal(domain.load('treinador')['activity-uncertain'].player.equipe.length,1);
    assert.equal((await domain.activityState('activity-uncertain','player')).eggs[0].species,
                 uncertain.eggs[0].species);
    // Lifecycle restart rereads Cassandra and recovers an expired activity egg.
    const restart = await domain.activityActivate('activity-restart','player','welcome');
    await domain.activityIncubate('activity-restart','player','start',restart.eggs[0]['egg-id']);
    await domain.shutdown();
    now += 7200000;
    await domain.initialize();
    assert.equal((await domain.activityState('activity-restart','player')).eggs[0].state,'hatched');
    assert.equal(domain.load('treinador')['activity-restart'].player.equipe.length,1);

    // O estágio anterior avança o relógio para testar prazos. Não usar esse
    // relógio no diário: timestamps de writes comuns ficariam no futuro em
    // relação aos timestamps de Paxos do servidor Cassandra. Reabrir também
    // reinicia o gerador monotônico de timestamps do driver.
    Date.now = clock;
    await domain.shutdown();
    await domain.initialize();
    const sourceTimes = new Map();
    async function sourceEvent(group,id,kind) {
      const snapshot = domain.load('treinador') || {};
      snapshot[group] ||= {};
      snapshot[group].player ||= {equipe:[],ativo:0};
      snapshot[group].player['adventure-eventos'] ||= {};
      const sourceKey = JSON.stringify([group,id]);
      if (!sourceTimes.has(sourceKey)) sourceTimes.set(sourceKey,clock());
      snapshot[group].player['adventure-eventos'][id] = {'event-id':id,kind,at:sourceTimes.get(sourceKey)};
      await domain.store('treinador',snapshot);
    }
    async function publish(group,id,kind) {
      await sourceEvent(group,id,kind);
      await domain.activityDrain(group);
    }
    await Promise.all([domain.activityActivate('rewards-real','player','welcome-a'),
                       domain.activityActivate('rewards-real','player','welcome-b')]);
    assert.equal((await domain.activityState('rewards-real','player')).eggs.length,1);
    await publish('rewards-real','capture-a','capture');
    await publish('rewards-real','capture-b','capture');
    await domain.activityGrantIncubator('rewards-real','player','extra','extra');
    await domain.activityGrantIncubator('rewards-real','player','extra','extra');
    await domain.activityGrantIncubator('rewards-real','player','special','especial');
    const rewards = await domain.activityState('rewards-real','player');
    assert.equal(rewards.eggs.length,3);
    assert.equal(rewards.incubators.length,3);
    for (const egg of rewards.eggs) {
      await domain.activityIncubate('rewards-real','player','start:'+egg['egg-id'],egg['egg-id']);
    }
    await publish('rewards-real','battle-a','battle');
    await publish('rewards-real','battle-a','battle');
    assert.deepEqual((await domain.activityState('rewards-real','player')).eggs.map(e=>e.points),[10,10,12.5]);
    await domain.activityPoints('rewards-real','player','finish',100);
    assert.equal(domain.load('treinador')['rewards-real'].player.equipe.length,3);
    const deliveries = await client.execute(`SELECT valor FROM ${keyspace}.estado_particionado WHERE modulo=? AND particao=?`,
      ['treinador','rewards-real'],{prepare:true,consistency:cassandra.types.consistencies.localQuorum});
    const durablePlayer = JSON.parse(deliveries.first().valor).player;
    assert.equal(durablePlayer.equipe.length,3);
    assert.equal(new Set(durablePlayer.equipe.map(pk=>pk['id-pokemon'])).size,3);
    assert.equal(Object.keys(durablePlayer['adventure-entregas']).length,3);
    assert.deepEqual((await domain.activityState('rewards-real','player')).incubators.map(i=>i.uses),[null,2,2]);
    assert.deepEqual(domain.load('treinador')['rewards-real'].player['adventure-eventos'],{});

    // Response lost after real activity CAS; origin journal survives and is drained at boot.
    const journal = await domain.activityActivate('journal-real','player','welcome');
    await domain.activityIncubate('journal-real','player','start',journal.eggs[0]['egg-id']);
    await sourceEvent('journal-real','stable-capture','capture');
    fault = true;
    cassandra.Client.prototype.execute = function(sql,args,opts) {
      const work = execute.call(this,sql,args,opts);
      if (fault && sql.includes('.adventure_account_state') && sql.includes('IF version')) {
        fault = false;
        return work.then(()=>{throw Error('simulated event commit response lost');});
      }
      return work;
    };
    await assert.rejects(domain.activityDrain('journal-real'),/incerto/);
    cassandra.Client.prototype.execute = execute;
    await domain.shutdown();
    await domain.initialize();
    const recoveredJournal = await domain.activityState('journal-real','player');
    assert.equal(recoveredJournal.eggs[0].points,15);
    assert.equal(recoveredJournal.eggs.length,2);
    assert.deepEqual(domain.load('treinador')['journal-real'].player['adventure-eventos'],{});
    await publish('journal-real','stable-capture','capture');
    const replayedJournal = await domain.activityState('journal-real','player');
    const withoutElapsed = eggs => eggs.map(({['time-ms']:elapsed,...egg})=>egg);
    assert.deepEqual(withoutElapsed(replayedJournal.eggs),withoutElapsed(recoveredJournal.eggs));
    assert.ok(replayedJournal.eggs[0]['time-ms'] >= recoveredJournal.eggs[0]['time-ms']);

    // Ponte real domínio -> outbox no mesmo Cassandra descartável.
    service.startTimers();
    const waitUntil = clock()+5000;
    while(service.pendingEvents(100).filter(e=>e.chatId==='rewards-real').length<3 && clock()<waitUntil) {
      await new Promise(resolve=>setTimeout(resolve,20));
    }
    const notifications=service.pendingEvents(100).filter(e=>e.chatId==='rewards-real');
    assert.equal(notifications.length,3);
    assert.equal(new Set(notifications.map(e=>e.id)).size,3);
    assert.ok(notifications.every(e=>e.messages[0].text.includes('Nível 1')));
    assert.ok(notifications.every(e=>e.messages[0].mentions.includes('player')));
    domain.stopTimers();
    for(const event of notifications)await service.ack(event.id);
    await domain.shutdown();
    await domain.initialize();
    const restartedService=new PokemonService({domain,media:{},logger:()=>{}});
    for(const event of notifications)await restartedService.enqueueEvent(event.chatId,{'event-id':event.id,texto:'Replay do handoff'});
    assert.equal(restartedService.pendingEvents(100).filter(e=>e.chatId==='rewards-real').length,0);
    assert.equal(domain.load('treinador')['rewards-real'].player.equipe.length,3);

    console.log('PASS: schema, CAS/LOCAL_SERIAL, isolamento, nascimento, recompensas, pontos, incubadoras, diário, notificações/outbox e recuperação no Cassandra 5.0.9 local.');
  } finally {
    Date.now = clock;
    cassandra.Client.prototype.execute = execute;
    if (domain) await domain.shutdown();
    await client.shutdown();
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
