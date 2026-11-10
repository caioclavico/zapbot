'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const dockerfile = fs.readFileSync(path.join(root, 'Dockerfile'), 'utf8');
const core = fs.readFileSync(path.join(root, 'src/zapbot/core.cljs'), 'utf8');

test('Node runtime uses a digest-pinned amd64 base and UID 1000', () => {
  assert.match(dockerfile, /FROM node:22-bookworm-slim@sha256:[a-f0-9]{64}/);
  assert.match(dockerfile, /USER 1000:1000\s*\nCMD/);
});

test('Chromium launch does not disable its namespace or seccomp sandbox', () => {
  assert.doesNotMatch(core, /--no-sandbox/);
  assert.doesNotMatch(core, /--disable-setuid-sandbox/);
});
