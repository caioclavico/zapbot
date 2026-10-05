'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {MediaStore} = require('../runtime/media.cjs');

const fixture = {buffer:Buffer.from('binary media fixture'), mime:'image/png', filename:'trainer.png'};
const mediaId = createHash('sha256').update(fixture.mime).update(fixture.buffer).digest('hex');

function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return {promise, resolve};
}

async function setup(t, options) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'pokemon-media-'));
  t.after(() => fs.rm(directory, {recursive:true, force:true}));
  return {store:new MediaStore(directory, options), directory, file:path.join(directory, mediaId)};
}

test('identical content reuses files after restart and preserves each response filename', async t => {
  const {store, directory, file} = await setup(t);
  assert.deepEqual(await store.put(fixture), {mediaId, mimeType:'image/png', filename:'trainer.png'});
  const before = await Promise.all([fs.stat(file), fs.stat(file+'.json')]);
  const restarted = new MediaStore(directory);
  const write = t.mock.method(fs, 'writeFile', () => { throw Error('Identical media must not be rewritten'); });
  assert.deepEqual(await restarted.put({...fixture, filename:'same-image.png'}),
                   {mediaId, mimeType:'image/png', filename:'same-image.png'});
  assert.equal(write.mock.callCount(), 0);
  const after = await Promise.all([fs.stat(file), fs.stat(file+'.json')]);
  assert.deepEqual(after.map(stat => stat.ino), before.map(stat => stat.ino));
  assert.deepEqual(await store.get(mediaId), {mimeType:'image/png', size:fixture.buffer.length, buffer:fixture.buffer});
  for (const stat of after) assert.equal(stat.mode & 0o777, 0o600);
});

test('concurrent identical puts publish bytes and metadata only once and release their queues', async t => {
  const {store, directory} = await setup(t);
  const original = fs.writeFile;
  let byteWrites = 0, metadataWrites = 0;
  t.mock.method(fs, 'writeFile', async (...args) => {
    if (Buffer.isBuffer(args[1])) byteWrites++; else metadataWrites++;
    return original(...args);
  });
  const replies = await Promise.all(Array.from({length:12}, (_, index) => store.put({...fixture, filename:index+'.png'})));
  assert.equal(byteWrites, 1);
  assert.equal(metadataWrites, 1);
  assert.deepEqual(replies.map(reply => reply.filename), Array.from({length:12}, (_, index) => index+'.png'));
  assert.equal(store.operations.size, 0);
  assert.deepEqual((await fs.readdir(directory)).sort(), [mediaId, mediaId+'.json']);
});

test('reusing existing media refreshes its expiry without rewriting content', async t => {
  const {store, file} = await setup(t, {ttlMs:1000});
  await store.put(fixture);
  const expired = new Date(Date.now()-5000);
  await fs.utimes(file, expired, expired);
  await fs.utimes(file+'.json', expired, expired);
  const originalInode = (await fs.stat(file)).ino;
  await store.put(fixture);
  await store.prune();
  assert.ok(await store.get(mediaId));
  assert.equal((await fs.stat(file)).ino, originalInode);
  assert.ok(Date.now()-(await fs.stat(file)).mtimeMs < 1000);
  assert.ok(Date.now()-(await fs.stat(file+'.json')).mtimeMs < 1000);
});

test('prune keeps protected IDs and removes expired unprotected pairs', async t => {
  const {store, file} = await setup(t, {ttlMs:1000});
  await store.put(fixture);
  const expired = new Date(Date.now()-5000);
  await fs.utimes(file, expired, expired);
  await store.prune(new Set([mediaId]));
  assert.ok(await store.get(mediaId));
  await store.prune();
  assert.equal(await store.get(mediaId), null);
  await assert.rejects(fs.stat(file+'.json'), {code:'ENOENT'});
});

test('a refresh in progress prevents prune from deleting the reused pair', async t => {
  const {store, file} = await setup(t, {ttlMs:1000});
  await store.put(fixture);
  const expired = new Date(Date.now()-5000);
  await fs.utimes(file, expired, expired);
  const started = deferred(), release = deferred();
  const original = fs.utimes;
  let paused = false;
  t.mock.method(fs, 'utimes', async (...args) => {
    if (!paused && args[0] === file) {
      paused = true;
      started.resolve();
      await release.promise;
    }
    return original(...args);
  });
  const reuse = store.put(fixture);
  await started.promise;
  const cleanup = store.prune();
  release.resolve();
  await Promise.all([reuse, cleanup]);
  assert.ok(await store.get(mediaId));
});

for (const stage of ['bytes', 'metadata']) {
  test('partial '+stage+' writes are not published to readers', async t => {
    const {store, directory, file} = await setup(t);
    const reader = new MediaStore(directory);
    const started = deferred(), release = deferred();
    const original = fs.writeFile;
    let paused = false;
    t.mock.method(fs, 'writeFile', async (target, content, options) => {
      const matches = stage === 'bytes' ? Buffer.isBuffer(content) : typeof content === 'string';
      if (!paused && matches) {
        paused = true;
        await original(target, content.subarray ? content.subarray(0,1) : content.slice(0,1), options);
        started.resolve();
        await release.promise;
        await fs.appendFile(target, content.subarray ? content.subarray(1) : content.slice(1));
        return;
      }
      return original(target, content, options);
    });
    const publication = store.put(fixture);
    await started.promise;
    if (stage === 'bytes') await assert.rejects(fs.stat(file), {code:'ENOENT'});
    else assert.deepEqual(await fs.readFile(file), fixture.buffer);
    await assert.rejects(fs.stat(file+'.json'), {code:'ENOENT'});
    assert.equal(await reader.get(mediaId), null);
    release.resolve();
    await publication;
    assert.deepEqual((await reader.get(mediaId)).buffer, fixture.buffer);
    assert.equal(store.operations.size, 0);
  });
}

test('failed metadata publication leaves no partial JSON and can be repaired without rewriting bytes', async t => {
  const {store, directory, file} = await setup(t);
  const original = fs.rename;
  let fail = true;
  t.mock.method(fs, 'rename', async (...args) => {
    if (fail && args[1] === file+'.json') throw Object.assign(Error('fixture publication failure'), {code:'EACCES'});
    return original(...args);
  });
  await assert.rejects(store.put(fixture), {code:'EACCES'});
  assert.equal(await store.get(mediaId), null);
  assert.deepEqual(await fs.readdir(directory), [mediaId]);
  const before = await fs.stat(file);
  fail = false;
  await store.put(fixture);
  assert.equal((await fs.stat(file)).ino, before.ino);
  assert.deepEqual((await store.get(mediaId)).buffer, fixture.buffer);
  assert.equal(store.operations.size, 0);
});

test('truncated bytes and corrupt metadata from an earlier interrupted write are repaired', async t => {
  const {store, directory, file} = await setup(t);
  await fs.writeFile(file, fixture.buffer.subarray(0,3));
  await fs.writeFile(file+'.json', '{');
  await store.put(fixture);
  assert.deepEqual(await store.get(mediaId), {mimeType:'image/png', size:fixture.buffer.length, buffer:fixture.buffer});
  assert.deepEqual((await fs.readdir(directory)).sort(), [mediaId, mediaId+'.json']);
});

test('MIME participates in the stable SHA and defaults/size validation remain unchanged', async t => {
  const {store, directory} = await setup(t, {maxBytes:32});
  const png = await store.put(fixture);
  const jpeg = await store.put({...fixture, mime:'image/jpeg'});
  assert.notEqual(png.mediaId, jpeg.mediaId);
  const fallback = await store.put({buffer:Buffer.from('fallback')});
  assert.equal(fallback.mimeType, 'application/octet-stream');
  assert.equal(fallback.filename, 'pokemon.png');
  assert.equal(await store.get('../outside'), null);
  const files = await fs.readdir(directory);
  await assert.rejects(store.put({buffer:'not binary'}), /Mídia inválida/);
  await assert.rejects(store.put({buffer:Buffer.alloc(33)}), /Mídia inválida/);
  assert.deepEqual(await fs.readdir(directory), files);
});
