'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const {createHash, randomUUID} = require('node:crypto');
class MediaStore {
  constructor(directory, {maxBytes = 10 * 1024 * 1024, ttlMs = 86400000} = {}) {
    this.directory = directory; this.maxBytes = maxBytes; this.ttlMs = ttlMs;
    // Only active operation promises are retained, never media buffers.
    this.operations = new Map();
  }
  async withId(id, action) {
    const previous = this.operations.get(id) || Promise.resolve();
    const next = previous.catch(() => {}).then(action);
    this.operations.set(id, next);
    try { return await next; }
    finally { if (this.operations.get(id) === next) this.operations.delete(id); }
  }
  async stat(file) {
    try { return await fs.lstat(file); }
    catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  }
  async publish(file, content) {
    const temporary = file + '.' + randomUUID() + '.tmp';
    try {
      await fs.writeFile(temporary, content, {mode:0o600, flag:'wx'});
      await fs.rename(temporary, file);
    } finally {
      await fs.rm(temporary, {force:true});
    }
  }
  async put(media) {
    const buffer = media.buffer;
    if (!Buffer.isBuffer(buffer) || buffer.length > this.maxBytes) throw new Error('Mídia inválida ou grande demais');
    const mimeType = media.mime || 'application/octet-stream';
    const id = createHash('sha256').update(mimeType).update(buffer).digest('hex');
    await this.withId(id, async () => {
      await fs.mkdir(this.directory, {recursive:true, mode:0o700});
      const file = path.join(this.directory, id);
      const bytes = await this.stat(file);
      if (!bytes?.isFile() || bytes.size !== buffer.length) await this.publish(file, buffer);
      const metadata = file + '.json';
      let info = null;
      if ((await this.stat(metadata))?.isFile()) {
        try { info = JSON.parse(await fs.readFile(metadata, 'utf8')); }
        catch (error) {
          if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error;
        }
      }
      // Publishing metadata last makes it the marker for a complete media pair.
      if (info?.mimeType !== mimeType || info?.size !== buffer.length) {
        await this.publish(metadata, JSON.stringify({mimeType, size:buffer.length}));
      }
      const now = new Date();
      await fs.utimes(file, now, now);
      await fs.utimes(metadata, now, now);
    });
    return {mediaId:id, mimeType, filename:media.filename || 'pokemon.png'};
  }
  async get(id) {
    if (!/^[a-f0-9]{64}$/.test(id)) return null;
    return this.withId(id, async () => {
      try {
        const file = path.join(this.directory, id);
        const info = JSON.parse(await fs.readFile(file+'.json', 'utf8'));
        const buffer = await fs.readFile(file);
        return {...info, buffer};
      } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
    });
  }
  async prune(protectedIds = new Set()) {
    let files;
    try { files = await fs.readdir(this.directory); } catch (e) { if (e.code === 'ENOENT') return; throw e; }
    for (const id of files.filter(x=>/^[a-f0-9]{64}$/.test(x))) {
      await this.withId(id, async () => {
        if (protectedIds.has(id)) return;
        const file = path.join(this.directory,id);
        const stat = await this.stat(file);
        if (stat?.isFile() && Date.now()-stat.mtimeMs > this.ttlMs && !protectedIds.has(id)) {
          await fs.rm(file+'.json',{force:true}); await fs.rm(file,{force:true});
        }
      });
    }
  }
}
module.exports = {MediaStore};
