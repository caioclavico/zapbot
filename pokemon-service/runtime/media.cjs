'use strict';
const fs = require('node:fs/promises');
const path = require('node:path');
const {createHash} = require('node:crypto');
class MediaStore {
  constructor(directory, {maxBytes = 10 * 1024 * 1024, ttlMs = 86400000} = {}) {
    this.directory = directory; this.maxBytes = maxBytes; this.ttlMs = ttlMs;
  }
  async put(media) {
    const buffer = media.buffer;
    if (!Buffer.isBuffer(buffer) || buffer.length > this.maxBytes) throw new Error('Mídia inválida ou grande demais');
    const mimeType = media.mime || 'application/octet-stream';
    const id = createHash('sha256').update(mimeType).update(buffer).digest('hex');
    await fs.mkdir(this.directory, {recursive:true, mode:0o700});
    const file = path.join(this.directory, id);
    await fs.writeFile(file, buffer, {mode:0o600});
    await fs.writeFile(file + '.json', JSON.stringify({mimeType, size:buffer.length}), {mode:0o600});
    return {mediaId:id, mimeType, filename:media.filename || 'pokemon.png'};
  }
  async get(id) {
    if (!/^[a-f0-9]{64}$/.test(id)) return null;
    try {
      const file = path.join(this.directory, id);
      const info = JSON.parse(await fs.readFile(file+'.json', 'utf8'));
      const buffer = await fs.readFile(file);
      return {...info, buffer};
    } catch (e) { if (e.code === 'ENOENT') return null; throw e; }
  }
  async prune(protectedIds = new Set()) {
    let files;
    try { files = await fs.readdir(this.directory); } catch (e) { if (e.code === 'ENOENT') return; throw e; }
    for (const id of files.filter(x=>/^[a-f0-9]{64}$/.test(x))) {
      if (protectedIds.has(id)) continue;
      const file = path.join(this.directory,id);
      const stat = await fs.stat(file).catch(()=>null);
      if (stat && Date.now()-stat.mtimeMs > this.ttlMs) {
        await fs.rm(file,{force:true}); await fs.rm(file+'.json',{force:true});
      }
    }
  }
}
module.exports = {MediaStore};
