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
    console.log('PASS: schema aditivo, bindings, CAS/LOCAL_SERIAL, recibos, concorrência, isolamento, nascimento, histórico e recuperação no Cassandra 5.0.9 local.');
  } finally {
    Date.now = clock;
    cassandra.Client.prototype.execute = execute;
    if (domain) await domain.shutdown();
    await client.shutdown();
  }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
