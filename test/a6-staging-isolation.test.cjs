'use strict';
/**
 * Validação estática de isolamento A6 staging (sem Docker up / migrate / WA).
 */
const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '..');
const COMPOSE = path.join(ROOT, 'docker-compose.a6-staging.yml');
const ENV_EX = path.join(ROOT, '.env.a6-staging.example');
const SCHEMA = path.join(
  ROOT,
  'modules/pokemon/infrastructure/pokemon/infrastructure/datomic/schema.clj'
);
const HTTP_SERVER = path.join(
  ROOT,
  'apps/odisseu/src/odisseu/bridge/http_server.clj'
);

describe('A6 isolated staging — arquivos de stack', () => {
  it('compose e env.example existem', () => {
    assert.ok(fs.existsSync(COMPOSE), 'docker-compose.a6-staging.yml');
    assert.ok(fs.existsSync(ENV_EX), '.env.a6-staging.example');
  });

  it('identificadores Datomic exclusivos e sem montagem a56/core', () => {
    const y = fs.readFileSync(COMPOSE, 'utf8');
    assert.match(y, /ODISSEU_BRIDGE_SYSTEM:\s*odisseu-a6-staging/);
    assert.match(y, /ODISSEU_BRIDGE_DB:\s*core-a6-staging/);
    assert.match(y, /ODISSEU_PLATFORM_DB:\s*platform-a6-staging/);
    assert.match(y, /odisseu-a6-staging-datomic/);
    assert.match(y, /odisseu-a6-staging-journal/);
    assert.match(y, /odisseu-a6-staging-maint/);
    assert.match(y, /127\.0\.0\.1:18091:18090/);
    assert.match(y, /127\.0\.0\.1:3002:3001/);
    assert.doesNotMatch(y, /odisseu-core-a56-datomic/);
    assert.doesNotMatch(y, /wwebjs_auth_core/);
    assert.match(y, /WWEBJS_CLIENT_ID:\s*a6-staging/);
    assert.match(y, /\.wwebjs_auth_a6_staging/);
  });

  it('proteções fail-closed e kill switches OFF no compose/env', () => {
    const y = fs.readFileSync(COMPOSE, 'utf8');
    const e = fs.readFileSync(ENV_EX, 'utf8');
    assert.match(y, /APP_ENV:\s*staging/);
    assert.match(y, /ALLOW_ALL_GROUPS:\s*"false"/);
    assert.match(y, /POKEMON_RAID_AUTO_TICK:\s*"false"/);
    assert.match(e, /^APP_ENV=staging$/m);
    assert.match(e, /^ALLOW_ALL_GROUPS=false$/m);
    assert.match(e, /^STAGING_GROUP_CONFIRMED=0$/m);
    assert.match(e, /^POKEMON_RAID_AUTO_TICK=false$/m);
    assert.match(e, /^STAGING_STRICT_USER_ALLOWLIST=1$/m);
    assert.match(e, /^WHATSAPP_TRANSPORT=disabled$/m);
    assert.match(y, /WHATSAPP_TRANSPORT:/);
  });

  it('node só sob profile with-node (JVM default)', () => {
    const y = fs.readFileSync(COMPOSE, 'utf8');
    assert.match(y, /odisseu-a6-node:[\s\S]*profiles:\s*\["with-node"\]/);
  });
});

describe('A6 isolated staging — schemas e guardrail storage', () => {
  it('instalador lista 025→030 em ordem', () => {
    const s = fs.readFileSync(SCHEMA, 'utf8');
    const files = [
      '025-save-slot-metadata.edn',
      '026-battle-idle.edn',
      '027-bag-daily.edn',
      '028-economy-ledger.edn',
      '029-raid-lobby.edn',
      '030-raid-auto.edn',
    ];
    let last = -1;
    for (const f of files) {
      const idx = s.indexOf(`migrations/pokemon/${f}`);
      assert.ok(idx > last, `ordem/presença de ${f}`);
      assert.ok(
        fs.existsSync(path.join(ROOT, 'migrations/pokemon', f)),
        `arquivo ${f}`
      );
      last = idx;
    }
  });

  it('assert-storage-safe! recusa paths a56 / system core', () => {
    const src = fs.readFileSync(HTTP_SERVER, 'utf8');
    assert.match(src, /a56-datomic/);
    assert.match(src, /odisseu-core-a56/);
    assert.match(src, /odisseu-m90-core/);
  });
});

describe('A6 isolated staging — política chat fail-closed (código)', () => {
  it('chat-autorizado? documentado: allow-all default false; bootstrap nega; multi-grupo', () => {
    const cfg = fs.readFileSync(
      path.join(ROOT, 'src/zapbot/config.cljs'),
      'utf8'
    );
    assert.match(cfg, /allow-all-groups\?/);
    assert.match(cfg, /staging-bootstrap-group\?/);
    assert.match(cfg, /env-flag-on\? "ALLOW_ALL_GROUPS"/);
    assert.match(cfg, /STAGING_GROUP_CONFIRMED/);
    assert.match(cfg, /ALLOWED_GROUP_IDS/);
    assert.match(cfg, /group-allowlist\.cjs/);
  });
});
