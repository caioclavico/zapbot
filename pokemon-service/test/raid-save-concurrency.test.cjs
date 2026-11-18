'use strict';

// Concurrency matrix: raid lateral writers vs save reservation/activation.
// Does NOT flip writerProtocolCoverageVerified — residual risk stays documented.
const test = require('node:test');
const assert = require('node:assert/strict');
const {CanonicalTrainerStore} = require('../runtime/canonical-trainer-store.cjs');
const {evaluate} = require('../runtime/save-activity-eligibility.cjs');
const {hash} = require('../runtime/restore-protocol.cjs');

function mock() {
  const accounts = new Map(), groups = new Set(), backups = new Map();
  let state = 'active';
  const key = (chat, player) => `${chat}\0${player}`;
  const execute = async (sql, args) => {
    const result = {rows: [], first() { return this.rows[0]; }, wasApplied() { return this.applied; }};
    if (sql.includes('canonical_trainer_control')) {
      result.rows = [{protocol_version: 2, state}]; return result;
    }
    if (sql.includes('canonical_trainer_groups')) {
      if (sql.startsWith('INSERT')) { groups.add(args[1]); result.applied = true; }
      else result.rows = [...groups].filter(chat => Number.parseInt(hash(chat).slice(0, 8), 16) % 16 === args[0]).map(chat_id => ({chat_id}));
      return result;
    }
    if (sql.includes('canonical_trainer_accounts')) {
      const id = key(args[0], args[1]);
      if (sql.startsWith('SELECT') && sql.includes('player_id = ?')) {
        const value = accounts.get(id); result.rows = value ? [{...value}] : []; return result;
      }
      if (sql.startsWith('SELECT')) {
        result.rows = [...accounts].filter(([k]) => k.startsWith(`${args[0]}\0`)).map(([k, value]) => ({player_id: k.split('\0')[1], ...value}));
        return result;
      }
      if (sql.startsWith('INSERT')) {
        if (accounts.has(id)) result.applied = false;
        else {
          accounts.set(id, {generation: args[2], schema_version: args[3], protocol_version: args[4], state_hash: args[5], state_json: args[6],
            fence_generation: args[10], active_save_id: args[11], transition_owner: args[12], operation_receipts: {}});
          result.applied = true;
        }
        return result;
      }
      if (sql.startsWith('UPDATE')) {
        const setColumns = sql.split(' SET ')[1].split(' WHERE ')[0].split(', ').map(part => part.split(' = ')[0]);
        const offset = setColumns.length;
        const conditions = sql.split(' IF ')[1].split(' AND ').map(part => part.split(' = ')[0]);
        const value = accounts.get(key(args[offset], args[offset + 1]));
        result.applied = Boolean(value && conditions.every((column, index) => value[column] === args[offset + 2 + index]));
        if (result.applied) {
          accounts.set(key(args[offset], args[offset + 1]),
            {...value, ...Object.fromEntries(setColumns.map((column, index) => [column, args[index]]))});
        }
        return result;
      }
    }
    if (sql.includes('canonical_trainer_backups')) {
      const id = key(args[0], args[1]) + `\0${args[2]}`;
      if (sql.startsWith('INSERT')) {
        if (!backups.has(id)) backups.set(id, {source_hash: args[3], source_json: args[4]});
        result.applied = true;
      } else result.rows = backups.has(id) ? [backups.get(id)] : [];
      return result;
    }
    throw new Error(`Unexpected query: ${sql}`);
  };
  return {execute, accounts};
}

const eligible = (extra = {}) => ({
  chat: 'g', player: 'ash', account: {enfermaria: []}, states: {},
  writerCoverageVerified: true, now: 50, ...extra
});

async function switchAshToSaveB(store) {
  const before = await store.readAccount('g', 'ash');
  await store.reserveTransition('g', 'ash', before, 'op_switch_b');
  const reserved = await store.readAccount('g', 'ash');
  await store.applyOnce('g', 'ash', {
    effectId: 'raid_concurrency_switch_b',
    intentHash: hash({to: 'b'}),
    expectedGeneration: reserved.generation,
    expectedStateHash: reserved.stateHash,
    writerContext: reserved,
    targetSaveId: 'b',
    transform: state => ({...state, slot: 'b'})
  });
  const fenced = await store.readAccount('g', 'ash');
  await store.releaseTransition('g', 'ash', fenced, 'op_switch_b');
  return store.readAccount('g', 'ash');
}

test('1 inscrição durável antes da reserva: elegibilidade bloqueia ativação', () => {
  // Linearização: persistência raids (estado_particionado) → reserve → re-check eligibility.
  const report = evaluate(eligible({
    states: {raids: {fase: 'inscricoes', participantes: {ash: {'save-id': 'a'}}, expira: 100}}
  }));
  assert.deepEqual(report.blocks.map(b => b.code), ['activity_raid_active']);
});

test('2 reserva antes da inscrição: assertLateralAdmission recusa raids do jogador reservado', async () => {
  // Linearização: reserveTransition na linha da conta → lateral raids.
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');
  await store.createAccount('g', 'peer', {xp: 1}, 'm');
  const old = await store.readAccount('g', 'ash');
  await store.reserveTransition('g', 'ash', old, 'op_before_enter');
  await assert.rejects(
    store.withWriterContext('g', () => store.assertLateralAdmission('raids'), undefined, true, 'ash'),
    {code: 'canonical_trainer_transition_reserved'});
});

test('5 captura pendente bloqueia troca', () => {
  const report = evaluate(eligible({
    states: {
      raids: {
        fase: 'vitoria',
        participantes: {ash: {dano: 2}},
        'captura-expira': 100,
        capturas: {}
      }
    }
  }));
  assert.ok(report.blocks.some(b => b.code === 'activity_raid_capture_pending'));
});

test('8 outro jogador do grupo durante reserva de ash continua admitido', async () => {
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');
  await store.createAccount('g', 'peer', {xp: 1}, 'm');
  const old = await store.readAccount('g', 'ash');
  await store.reserveTransition('g', 'ash', old, 'op_peer_ok');
  await store.withWriterContext('g', () => store.assertLateralAdmission('raids'), undefined, true, 'peer');
  const peerOnly = evaluate(eligible({
    player: 'peer',
    states: {raids: {fase: 'combate', participantes: {ash: {dano: 1}}, expira: 100}}
  }));
  assert.equal(peerOnly.eligible, true);
});

test('3+4 ataque/recompensa de A após B ativo: lateral raids recusa fence obsoleto', async () => {
  // Linearização da correção mínima: assertLateralAdmission compara fence/save
  // do contexto congelado com a linha canônica vigente (não só transitionOwner).
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');
  let sealA;
  await store.withWriterContext('g', async () => {
    sealA = store.freezeSaveBoundTask('ash');
  }, undefined, true, 'ash');
  assert.equal(sealA.activeSaveId, 'a');
  assert.equal(sealA.fenceGeneration, 1);

  const after = await switchAshToSaveB(store);
  assert.equal(after.activeSaveId, 'b');
  assert.ok(after.fenceGeneration > sealA.fenceGeneration);
  assert.equal(after.transitionOwner, '');

  await assert.rejects(
    store.runPersistedContext('g', 'ash', sealA, () => store.assertLateralAdmission('raids')),
    {code: 'canonical_trainer_fence_conflict'});
});

test('6 resposta Cassandra perdida na reserva: se a reserva reconciliar, lateral permanece fechada', async () => {
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');
  const old = await store.readAccount('g', 'ash');
  let lose = true;
  const flaky = new CanonicalTrainerStore(async (sql, args, options) => {
    const result = await execute(sql, args, options);
    if (lose && sql.startsWith('UPDATE') && sql.includes('transition_owner')) {
      lose = false;
      throw Object.assign(new Error('lost'), {code: 'synthetic_lost_lwt_response'});
    }
    return result;
  }, 'zapbot');
  // Reserva pode reconciliar via SERIAL e retornar sucesso apesar da ack perdida.
  try {
    await flaky.reserveTransition('g', 'ash', old, 'op_lost_reserve');
  } catch {
    // Falha ambígua também é aceitável; o estado durável decide.
  }
  const observed = await store.readAccount('g', 'ash');
  if (observed.transitionOwner === 'op_lost_reserve') {
    await assert.rejects(
      store.withWriterContext('g', () => store.assertLateralAdmission('raids'), undefined, true, 'ash'),
      {code: 'canonical_trainer_transition_reserved'});
  } else {
    // Sem reserva durável, um writer fresco de A ainda é coerente com a linha.
    await store.withWriterContext('g', () => store.assertLateralAdmission('raids'), undefined, true, 'ash');
  }
});

test('7 reinício: elegibilidade só vê raid durável (atom local não basta)', () => {
  // Reinício entre recompensa e confirmação: sem linha em Cassandra, troca não vê atividade.
  const idle = evaluate(eligible({states: {raids: undefined}}));
  assert.equal(idle.eligible, true,
    'risco residual: participação só em memória não bloqueia elegibilidade após restart');
  const durable = evaluate(eligible({
    states: {raids: {fase: 'vitoria', participantes: {ash: {dano: 1}}, 'captura-expira': 100, capturas: {}}}
  }));
  assert.ok(durable.blocks.some(b => b.code === 'activity_raid_capture_pending'));
});

test('TOCTOU admission→INSERT: troca A→B completa enquanto raid já admitida ainda pode confirmar', async () => {
  // Demonstra a corrida restante após o fence-check em assertLateralAdmission.
  //
  // Linha do tempo:
  //   1) writer A passa assertLateralAdmission (read)
  //   2) pausa ANTES do INSERT em estado_particionado/raids
  //   3) reserve+applyOnce+release publica save B
  //   4) INSERT (análogo) confirma — a partição raids não participa do LWT da conta
  //
  // Barreira transacional com as tabelas atuais: inviável. Conta canônica
  // ((chat_id), player_id) e raids (modulo, particao) são partições distintas;
  // Cassandra não oferece IF atômico cross-partition. Locks em memória /
  // re-leitura de reserva NÃO fecham interrupção, timeout, reinício nem
  // outro processo. A loja usa o mesmo assertLateralAdmission → mesma janela.
  //
  // Conclusão: corrida MITIGADA (callback pós-B sem re-admission falha) mas
  // NÃO ELIMINADA. Certificação exige journal/coordenação durável entre
  // partições — fora do escopo deste MVP.
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');
  await store.createAccount('g', 'peer', {xp: 1}, 'm');

  let releaseInsert;
  const pausedBeforeInsert = new Promise(resolve => { releaseInsert = resolve; });
  let phase = 'start';
  const raidRows = new Map();

  const lateralRaid = store.withWriterContext('g', async () => {
    await store.assertLateralAdmission('raids');
    phase = 'admitted';
    await pausedBeforeInsert;
    // Análogo a operacoes-alteradas! / INSERT sem IF na fence da conta.
    raidRows.set('g', {
      fase: 'combate',
      participantes: {ash: {'save-id': 'a', dano: 1}},
      writtenAfterSwitch: true
    });
    phase = 'inserted';
  }, undefined, true, 'ash');

  for (let i = 0; i < 50 && phase !== 'admitted'; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.equal(phase, 'admitted', 'admission deve completar antes da troca');

  const after = await switchAshToSaveB(store);
  assert.equal(after.activeSaveId, 'b');
  assert.equal(after.transitionOwner, '');

  // Peer não fica bloqueado pela reserva/ativação de ash (já liberada).
  await store.withWriterContext('g', () => store.assertLateralAdmission('raids'), undefined, true, 'peer');

  releaseInsert();
  await lateralRaid;
  assert.equal(phase, 'inserted');
  assert.equal(raidRows.get('g').writtenAfterSwitch, true,
    'escrita lateral admitida sob A confirmou depois de B publicado');
  assert.equal(raidRows.get('g').participantes.ash['save-id'], 'a');
});

test('TOCTOU equivalente na loja: mesmo admission read-then-write', async () => {
  const {execute} = mock();
  const store = new CanonicalTrainerStore(execute, 'zapbot');
  await store.createAccount('g', 'ash', {xp: 1}, 'm');

  let releaseWrite;
  const paused = new Promise(resolve => { releaseWrite = resolve; });
  let phase = 'start';

  const lateralLoja = store.withWriterContext('g', async () => {
    await store.assertLateralAdmission('loja');
    phase = 'admitted';
    await paused;
    phase = 'inserted';
  }, undefined, true, 'ash');

  for (let i = 0; i < 50 && phase !== 'admitted'; i++) {
    await new Promise(resolve => setImmediate(resolve));
  }
  assert.equal(phase, 'admitted');
  await switchAshToSaveB(store);
  releaseWrite();
  await lateralLoja;
  assert.equal(phase, 'inserted',
    'loja compartilha a janela: escrita após B não é fenced pela conta');
});
