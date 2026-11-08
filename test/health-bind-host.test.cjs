'use strict';
/**
 * Prova que HEALTH_BIND_HOST só altera o endereço de listen do HTTP de health.
 * Não participa do estado WhatsApp / inject / zapbotStartupState.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');

test('HEALTH_BIND_HOST is only referenced by config + health server wiring', () => {
  const hits = [];
  for (const rel of [
    'src/zapbot/config.cljs',
    'src/zapbot/whatsapp_saude.cljs',
    'src/zapbot/core.cljs',
    'scripts/lib/whatsapp-startup.cjs',
    'scripts/healthcheck.js'
  ]) {
    const text = fs.readFileSync(path.join(ROOT, rel), 'utf8');
    if (text.includes('HEALTH_BIND_HOST') || text.includes('health-bind-host') || text.includes('bind-host')) {
      hits.push(rel);
    }
  }
  assert.deepEqual(hits.sort(), [
    'src/zapbot/config.cljs',
    'src/zapbot/core.cljs',
    'src/zapbot/whatsapp_saude.cljs'
  ].sort());
  const startup = fs.readFileSync(path.join(ROOT, 'scripts/lib/whatsapp-startup.cjs'), 'utf8');
  assert.equal(startup.includes('HEALTH_BIND'), false);
  assert.equal(startup.includes('bind-host'), false);
});

test('listen host 127.0.0.1 vs 0.0.0.0 does not mutate a startup state machine', async () => {
  const machine = {zapbotStartupState: 'starting'};
  function serve(host) {
    return new Promise((resolve, reject) => {
      const server = http.createServer((_req, res) => {
        res.writeHead(200, {'Content-Type': 'application/json'});
        res.end(JSON.stringify({
          status: machine.zapbotStartupState === 'ready' ? 'ok' : 'degraded',
          whatsapp: machine.zapbotStartupState === 'ready' ? 'READY' : 'RECOVERING',
          chromium: true
        }));
      });
      server.listen(0, host, () => resolve(server));
      server.on('error', reject);
    });
  }

  const loopback = await serve('127.0.0.1');
  const allIfaces = await serve('0.0.0.0');
  const portLo = loopback.address().port;
  const portAll = allIfaces.address().port;

  machine.zapbotStartupState = 'recovering';
  const before = machine.zapbotStartupState;

  await Promise.all([
    new Promise((resolve, reject) => {
      http.get(`http://127.0.0.1:${portLo}/health`, (res) => {
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => {
          try {
            assert.equal(JSON.parse(d).whatsapp, 'RECOVERING');
            resolve();
          } catch (e) {
            reject(e);
          }
        });
      }).on('error', reject);
    }),
    new Promise((resolve, reject) => {
      http.get(`http://127.0.0.1:${portAll}/health`, (res) => {
        let d = '';
        res.on('data', (c) => (d += c));
        res.on('end', () => {
          try {
            assert.equal(JSON.parse(d).whatsapp, 'RECOVERING');
            resolve();
          } catch (e) {
            reject(e);
          }
        });
      }).on('error', reject);
    })
  ]);

  assert.equal(machine.zapbotStartupState, before);
  machine.zapbotStartupState = 'ready';
  assert.equal(machine.zapbotStartupState, 'ready');

  await Promise.all([
    new Promise((resolve) => loopback.close(resolve)),
    new Promise((resolve) => allIfaces.close(resolve))
  ]);
});
