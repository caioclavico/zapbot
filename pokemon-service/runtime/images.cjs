'use strict';
// Only public artwork and derived pixels live here; no disk cache or timers.
const sharp = require('sharp');
const {createHash} = require('node:crypto');
const metrics = require('./metrics.cjs');
const CARD_WIDTH = 640;
const CARD_SCALE = CARD_WIDTH / 760;
const TEAM_WIDTH = 800;
const QUALITY = 70;
const PNG = {compressionLevel:1, adaptiveFiltering:false};
const INPUT = {limitInputPixels:16 * 1024 * 1024};
sharp.concurrency(1);
sharp.cache({memory:8, files:0, items:32});

function limiter(limit, maxWaiting = 128) {
  let active = 0;
  const waiting = [];
  const next = () => {
    while (active < limit && waiting.length) {
      const {fn, resolve, reject} = waiting.shift(); active++;
      Promise.resolve().then(fn).then(resolve, reject).finally(() => { active--; next(); });
    }
  };
  return fn => new Promise((resolve, reject) => {
    if (waiting.length >= maxWaiting) { reject(new Error('Fila de imagens cheia')); return; }
    waiting.push({fn, resolve, reject}); next();
  });
}

class ImageCache {
  constructor({maxBytes = 16 * 1024 * 1024, maxEntries = 128, ttlMs = 600000} = {}) {
    this.maxBytes = maxBytes; this.maxEntries = maxEntries; this.ttlMs = ttlMs;
    this.bytes = 0; this.entries = new Map(); this.pending = new Map();
  }
  async get(key, produce) {
    const hit = this.entries.get(key);
    if (hit && hit.until > Date.now()) {
      this.entries.delete(key); this.entries.set(key, hit); return hit.value;
    }
    if (hit) this.remove(key);
    if (this.pending.has(key)) return this.pending.get(key);
    const promise = Promise.resolve().then(produce).then(value => {
      const bytes = Buffer.isBuffer(value) ? value.length : value.data.length;
      if (bytes <= this.maxBytes) {
        while (this.entries.size && (this.bytes + bytes > this.maxBytes || this.entries.size >= this.maxEntries)) {
          this.remove(this.entries.keys().next().value);
        }
        this.entries.set(key, {value, bytes, until:Date.now() + this.ttlMs}); this.bytes += bytes;
      }
      return value;
    }).finally(() => this.pending.delete(key));
    this.pending.set(key, promise); return promise;
  }
  remove(key) { const item = this.entries.get(key); if (item) this.bytes -= item.bytes; this.entries.delete(key); }
  clear() { this.entries.clear(); this.bytes = 0; }
}
const cache = new ImageCache();
const processImage = limiter(1);
const downloadImage = limiter(2);
const identities = new WeakMap();
const scales = new WeakMap();
function identity(input) {
  if (typeof input === 'string') return input;
  if (!identities.has(input)) identities.set(input, createHash('sha256').update(input).digest('hex'));
  return identities.get(input);
}

async function download(url, load, budgetMs = 12000) {
  if (!url) throw new Error('Pokémon sem URL de imagem');
  return cache.get(`download:${url}`, () => {
    // Waiting in the two-download queue consumes the same budget as fetching.
    // Twelve unavailable team sprites must not take six separate 12s batches.
    const deadline = Date.now() + budgetMs;
    return downloadImage(() => {
      if (Date.now() >= deadline) throw new Error('Tempo de download do sprite esgotado');
      return load(deadline);
    });
  });
}

async function responseBuffer(response) {
  const maxBytes = 4 * 1024 * 1024;
  if (!response.ok) throw new Error(`Imagem respondeu HTTP ${response.status}`);
  if (Number(response.headers.get('content-length')) > maxBytes) {
    await response.body?.cancel(); throw new Error('Sprite grande demais');
  }
  const chunks = []; let bytes = 0;
  for await (const chunk of response.body) {
    bytes += chunk.length;
    if (bytes > maxBytes) throw new Error('Sprite grande demais');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, bytes);
}

function sprite(input, width, height = width, scale = CARD_SCALE) {
  const w = Math.max(1, Math.round(width * scale));
  const h = Math.max(1, Math.round(height * scale));
  return cache.get(`sprite:${identity(input)}:${w}:${h}:${scale}`, () => processImage(async () => {
    const buffer = await sharp(input, INPUT)
      .resize(w, h, {fit:'contain', withoutEnlargement:true, background:{r:0,g:0,b:0,alpha:0}})
      .png(PNG).toBuffer();
    scales.set(buffer, scale); return buffer;
  }));
}

function scaledSVG(svg, maxWidth) {
  const root = svg.match(/<svg\b[^>]*>/);
  const width = Number(root?.[0].match(/\bwidth=['"]([\d.]+)['"]/)?.[1]);
  const height = Number(root?.[0].match(/\bheight=['"]([\d.]+)['"]/)?.[1]);
  if (!width || !height) throw new Error('SVG sem dimensões');
  const scale = Math.min(1, maxWidth / width);
  const w = Math.round(width * scale), h = Math.round(height * scale);
  // Rasterize SVG directly at delivery size, rather than resize a composed PNG.
  const tag = root[0].replace(/\b(width|height|viewBox)=['"][^'"]*['"]/g, '')
    .replace(/>$/, ` width="${w}" height="${h}" viewBox="0 0 ${width} ${height}">`);
  return {svg:svg.replace(root[0], tag), scale};
}

async function overlayInput(input, scale) {
  if (scales.get(input) === scale) return input;
  return cache.get(`overlay:${identity(input)}:${scale}`, () => processImage(async () => {
    const pipeline = sharp(input, INPUT);
    const info = await pipeline.metadata();
    return pipeline.resize(Math.max(1, Math.round(info.width * scale)), Math.max(1, Math.round(info.height * scale)))
      .png(PNG).toBuffer();
  }));
}

function jpeg(pipeline) {
  return pipeline.flatten({background:'#0f172a'})
    .jpeg({quality:QUALITY, chromaSubsampling:'4:2:0', mozjpeg:false, progressive:false});
}

async function render(svg, overlays = [], {maxWidth = CARD_WIDTH, intermediate = false} = {}) {
  const scaled = scaledSVG(svg, maxWidth);
  const base = await cache.get(`base:${scaled.svg}`, () => processImage(() =>
    sharp(Buffer.from(scaled.svg), INPUT).ensureAlpha().raw().toBuffer({resolveWithObject:true})));
  const layers = await Promise.all(overlays.map(async layer => ({...layer,
    input:await overlayInput(layer.input, scaled.scale),
    left:Math.round((layer.left || 0) * scaled.scale), top:Math.round((layer.top || 0) * scaled.scale)
  })));
  return processImage(async () => {
    const pipeline = sharp(base.data, {raw:base.info}).composite(layers);
    if (intermediate) return pipeline.raw().toBuffer({resolveWithObject:true});
    return jpeg(pipeline).toBuffer();
  });
}

async function overlay(base, svg) {
  const scaled = scaledSVG(svg, CARD_WIDTH);
  const layer = await overlayInput(Buffer.from(svg), scaled.scale);
  return processImage(() => jpeg(Buffer.isBuffer(base) ? sharp(base, INPUT).composite([{input:layer}]) :
    sharp(base.data, {raw:base.info}).composite([{input:layer}])).toBuffer());
}

function asset(input) {
  return cache.get(`asset:${input}`, () => processImage(() => jpeg(sharp(input, INPUT)
    .resize(CARD_WIDTH, Math.round(400 * CARD_SCALE), {fit:'cover', withoutEnlargement:true})).toBuffer()));
}

// Keep timing attached to the caller, including queue wait and cache hits.
const measured = fn => (...args) => metrics.measure('image_processing_ms', () => fn(...args));
module.exports = {CARD_WIDTH, CARD_SCALE, TEAM_WIDTH, QUALITY, ImageCache, limiter,
  download, responseBuffer, sprite:measured(sprite), render:measured(render), overlay:measured(overlay), asset:measured(asset)};
