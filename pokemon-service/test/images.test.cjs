'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const images = require('../runtime/images.cjs');
const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400"><rect width="760" height="400" fill="#fff"/><text x="30" y="40" font-size="24">HP 200/440</text></svg>';

test('cache coalesces concurrent misses, retries failures and bounds retained bytes/entries', async () => {
  const cache = new images.ImageCache({maxBytes:8,maxEntries:2}); let calls = 0;
  const load = async () => { calls++; await Promise.resolve(); return Buffer.alloc(4); };
  const values = await Promise.all(Array.from({length:10}, () => cache.get('a',load)));
  assert.equal(calls,1); assert.equal(values[0],values[9]);
  await cache.get('b',load); await cache.get('a',load); await cache.get('c',load);
  assert.equal(cache.bytes,8); assert.equal(cache.entries.has('b'),false);
  await cache.get('large',() => Buffer.alloc(20)); assert.equal(cache.bytes,8);
  await assert.rejects(cache.get('fail',() => { throw Error('offline'); }));
  await cache.get('fail',load); assert.equal(cache.pending.size,0);
  const expired = new images.ImageCache({ttlMs:0});
  await expired.get('a',load); const before = calls; await expired.get('a',load); assert.equal(calls,before+1);
});

test('limiter bounds active work and waiting queue, recovering from rejected jobs', async () => {
  const run = images.limiter(2,1); let release;
  const blocked = new Promise(resolve => { release = resolve; });
  const a = run(() => blocked), b = run(() => blocked), c = run(() => 'queued');
  await assert.rejects(run(() => 'overflow'),/Fila/); release();
  assert.deepEqual(await Promise.all([a,b,c]),[undefined,undefined,'queued']);
  await assert.rejects(run(() => { throw Error('failure'); })); assert.equal(await run(() => 42),42);
});

test('transparent sprites are cached at delivery size without enlarging raster artwork', async () => {
  const input = await sharp({create:{width:32,height:32,channels:4,background:'#ff0000'}}).png().toBuffer();
  const a = await images.sprite(input,280); const b = await images.sprite(input,280);
  assert.equal(a,b);
  const info = await sharp(a).metadata(); assert.equal(info.format,'png'); assert.equal(info.width,236); assert.equal(info.hasAlpha,true);
  const {data,info:raw} = await sharp(a).raw().toBuffer({resolveWithObject:true});
  const opaque = Array.from({length:raw.width*raw.height},(_,i) => data[i*4+3]).filter(a => a===255).length;
  assert.equal(opaque,32*32);
});

test('JPEG cards use smaller canvas; scaled sprites and effects retain their position', async () => {
  const red = await sharp({create:{width:100,height:100,channels:4,background:'#ff0000'}}).png().toBuffer();
  const sprite = await images.sprite(red,100);
  const raw = await images.render(svg,[{input:sprite,left:500,top:250}],{intermediate:true});
  assert.equal(raw.info.width,640); assert.equal(raw.info.height,337); assert.equal(raw.info.format,'raw');
  const buffer = await images.overlay(raw,'<svg xmlns="http://www.w3.org/2000/svg" width="760" height="400"><rect x="20" y="300" width="60" height="60" fill="#0000ff"/></svg>');
  const info = await sharp(buffer).metadata(); assert.equal(info.format,'jpeg'); assert.equal(info.hasAlpha,false);
  const pixels = await sharp(buffer).raw().toBuffer();
  const at = (x,y) => pixels.subarray((y*info.width+x)*3,(y*info.width+x)*3+3);
  assert.ok(at(460,250)[0]>200 && at(460,250)[1]<40,'red sprite stays on the right');
  assert.ok(at(40,275)[2]>200 && at(40,275)[0]<40,'blue effect stays on the left');
  assert.ok(buffer.length<20000);
});

test('team cards retain readable dimensions and repeated static assets reuse JPEG bytes', async () => {
  const team = await images.render(svg.replace('760','1000'),[],{maxWidth:800});
  assert.equal((await sharp(team).metadata()).width,800);
  const path = require('node:path').join(__dirname,'../assets/loja-pokemon.png');
  const a = await images.asset(path), b = await images.asset(path); assert.equal(a,b);
  const info = await sharp(a).metadata(); assert.equal(info.format,'jpeg'); assert.equal(info.width,640); assert.equal(info.height,337);
});

test('equal sprite pixel sizes on different design scales are not resized twice', async () => {
  const input = await sharp({create:{width:475,height:475,channels:4,background:'#ff0000'}}).png().toBuffer();
  // A small species in a battle and the 145px team slot both become 116px.
  const battle = await images.sprite(input,138);
  const team = await images.sprite(input,145,145,0.8);
  for (const [sprite,maxWidth,canvas] of [[battle,640,svg],[team,800,svg.replace('760','1000')]]) {
    const result = await images.render(canvas,[{input:sprite,left:0,top:100}],{maxWidth,intermediate:true});
    const offset = (Math.round(100*maxWidth/(maxWidth===640?760:1000))+50)*result.info.width*4+110*4;
    assert.ok(result.data[offset]>200 && result.data[offset+1]<40,'116px sprite retains its full width');
  }
});

test('download cache coalesces requests and oversized or failed bodies are rejected', async () => {
  let calls = 0;
  const load = () => { calls++; return Buffer.from('sprite'); };
  const results = await Promise.all(Array.from({length:12}, () => images.download('fixture:25',load)));
  assert.equal(calls,1); assert.equal(results[0],results[11]);
  await assert.rejects(images.responseBuffer(new Response('missing',{status:404})),/HTTP 404/);
  await assert.rejects(images.responseBuffer(new Response('a',{headers:{'content-length':5000000}})),/grande/);
  await assert.rejects(images.responseBuffer(new Response(Buffer.alloc(4*1024*1024+1))),/grande/);
  assert.equal((await images.responseBuffer(new Response('abc'))).toString(),'abc');
});

test('a cold twelve-sprite team has one download budget including queue wait', async () => {
  let started = 0;
  const slow = async deadline => {
    started++; assert.ok(deadline-Date.now()<=10);
    // Simulate unavailable active downloads. Queued jobs must expire before
    // launching another six batches of network requests.
    await new Promise(resolve => setTimeout(resolve,25)); throw Error('offline');
  };
  const results = await Promise.allSettled(Array.from({length:12},(_,i) => images.download(`cold-team:${i}`,slow,10)));
  assert.equal(started,2); assert.equal(results.filter(r => r.status==='rejected').length,12);
});
