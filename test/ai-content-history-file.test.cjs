'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {createStore} = require('../scripts/lib/ai-content-history-file.cjs');
const {fingerprint} = require('../scripts/lib/ai-content-generator.cjs');
const {createService} = require('../scripts/lib/ai-content-generator.cjs');

async function fixture(t, options = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'odisseu-ai-history-'));
  t.after(() => fs.rm(dir, {recursive:true, force:true}));
  return {dir, file:path.join(dir, 'history.json'), store:createStore({filePath:path.join(dir, 'history.json'), ...options})};
}

function entry(content, category = 'cotidiano') {
  return {category, subtopic:'teste', summary:content, fingerprint:fingerprint(content), createdAt:new Date().toISOString(), source:'gemini'};
}

test('arquivo persistente sobrevive a nova instância e compartilha o histórico entre chats', async t => {
  const f = await fixture(t);
  await f.store.registrar('piada', entry('Piada compartilhada entre grupos'));
  const restarted = createStore({filePath:f.file});
  assert.equal((await restarted.recentes('piada')).length, 1);
  assert.equal(await restarted.existeSimilar('piada', 'Piada compartilhada entre grupos'), true);
  assert.equal((await restarted.recentes('curiosidade')).length, 0);
  const saved = await fs.readFile(f.file, 'utf8');
  assert.doesNotMatch(saved, /chatId|playerId|secret|token/i);
});

test('gerador real usa o arquivo após reinício para rejeitar duplicata', async t => {
  const f = await fixture(t);
  const firstStore = f.store;
  const answer = {content:'A capivara abriu uma biblioteca e emprestava livros para relaxar.',summary:'Capivara administra uma biblioteca relaxante',subtopic:'capivara bibliotecária'};
  const first = createService({generate:async()=>({text:JSON.stringify(answer),provider:'gemini'}),
    loadHistory:firstStore.load,saveHistory:firstStore.save,windowSize:120,maxAttempts:2});
  const sent = await first.generate('piada');
  assert.equal(sent.content,answer.content);

  const restartedStore = createStore({filePath:f.file});
  let calls=0;
  const second = createService({generate:async()=>({text:JSON.stringify(calls++===0
    ? answer
    : {...answer,content:'O carteiro virou DJ e agora entrega sucessos antes do almoço.'}),provider:'gemini'}),
    loadHistory:restartedStore.load,saveHistory:restartedStore.save,windowSize:120,maxAttempts:2});
  const next = await second.generate('piada');
  assert.equal(next.ok,true);
  assert.equal(calls,2);
  assert.notEqual(next.content,answer.content);
});

test('serializa registros concorrentes entre instâncias do store no processo', async t => {
  const f = await fixture(t);
  const other = createStore({filePath:f.file});
  await Promise.all(Array.from({length:40}, (_, i) => (i % 2 ? f.store : other).registrar('curiosidade', entry(`Fato ${i}`))));
  const rows = await f.store.recentes('curiosidade', 200);
  assert.equal(rows.length, 40);
  assert.equal(new Set(rows.map(row => row.fingerprint)).size, 40);
});

test('retém somente a janela e limparExcedentes não remove o último histórico', async t => {
  const f = await fixture(t, {windowSize:20});
  for (let i=0;i<27;i++) await f.store.registrar('piada', entry(`Conteúdo ${i}`));
  assert.equal((await f.store.limparExcedentes()).piada.items.length, 20);
  assert.match((await f.store.recentes('piada'))[0].summary, /7$/);
});

test('campo fora do contrato e arquivo JSON corrompido falham sem sobrescrever', async t => {
  const f = await fixture(t);
  await f.store.registrar('piada', entry('Registro preservado'));
  const before = await fs.readFile(f.file, 'utf8');
  await assert.rejects(f.store.registrar('piada', {...entry('não gravar'), fullText:'PII'}), /unexpected history field/);
  await fs.writeFile(f.file, '{broken');
  await assert.rejects(f.store.load(), SyntaxError);
  assert.equal(await fs.readFile(f.file, 'utf8'), '{broken');
  assert.notEqual(before, '{broken');
});

test('próxima leitura remove temporário órfão sem afetar o arquivo válido', async t => {
  const f = await fixture(t);
  await f.store.registrar('piada', entry('Registro válido'));
  const tempPath = path.join(f.dir, '.history.json.orphan.tmp');
  await fs.writeFile(tempPath, 'incompleto');
  assert.equal((await f.store.load()).piada.items.length, 1);
  await assert.rejects(fs.stat(tempPath), {code:'ENOENT'});
});

test('falha na publicação mantém o arquivo anterior intacto e limpa staging', async t => {
  const f = await fixture(t);
  await f.store.registrar('piada', entry('Versão anterior'));
  const before = await fs.readFile(f.file, 'utf8');
  const failingFs = Object.create(fs);
  failingFs.rename = async () => { const error = new Error('simulated rename failure'); error.code = 'EIO'; throw error; };
  const failing = createStore({filePath:f.file, fs:failingFs});
  await assert.rejects(failing.registrar('piada', entry('Não publicar')), /simulated rename failure/);
  assert.equal(await fs.readFile(f.file, 'utf8'), before);
  assert.deepEqual((await fs.readdir(f.dir)).filter(name => name.endsWith('.tmp')), []);
});

test('diretório indisponível retorna erro em vez de confirmar persistência', async t => {
  const f = await fixture(t);
  const failingFs = Object.create(fs);
  failingFs.open = async () => { const error = new Error('permission denied'); error.code = 'EACCES'; throw error; };
  const failing = createStore({filePath:f.file, fs:failingFs});
  await assert.rejects(failing.registrar('curiosidade', entry('Não persistir')), /permission denied/);
});
