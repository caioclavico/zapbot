'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const root = path.resolve(__dirname, '..');

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

test('drain de produção está presente e idêntico ao da imagem ativa', () => {
  const maintenance = path.join(root, 'scripts/lib/a6-maintenance.cjs');
  const shutdown = path.join(root, 'scripts/lib/a6-shutdown.cjs');
  assert.equal(fs.existsSync(maintenance), true, 'a6-maintenance.cjs ausente');
  assert.equal(fs.existsSync(shutdown), true, 'a6-shutdown.cjs ausente');
  assert.equal(
    sha256(maintenance),
    '04b5b61d082a5dc8669c3633f0c8e611fa7e2740c7dab714f21964e90e7e4d80'
  );
  assert.equal(
    sha256(shutdown),
    '16dae2a86eb98437316bdc67406c230095e395fb9cfaf048ad79ec6c2383ad68'
  );
});

test('o runtime não volta a desligar o sandbox do Chromium', () => {
  const core = fs.readFileSync(path.join(root, 'src/zapbot/core.cljs'), 'utf8');
  const dockerfile = fs.readFileSync(path.join(root, 'Dockerfile'), 'utf8');
  assert.equal(core.includes('--no-sandbox'), false);
  assert.equal(core.includes('--disable-setuid-sandbox'), false);
  assert.equal(dockerfile.includes('--no-sandbox'), false);
  assert.equal(dockerfile.includes('--disable-setuid-sandbox'), false);
});

test('a imagem final declara USER 1000:1000', () => {
  const dockerfile = fs.readFileSync(path.join(root, 'Dockerfile'), 'utf8');
  assert.match(dockerfile, /^USER 1000:1000$/m);
});
