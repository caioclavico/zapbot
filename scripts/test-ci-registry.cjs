'use strict';
// Execute the exact workflow registry lookup with fake fetch/fs, never GHCR.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const yaml = fs.readFileSync(path.join(__dirname, '../.github/workflows/deploy.yml'), 'utf8');
const section = yaml.split('      - name: Reuse existing SHA tag without overwriting it\n')[1];
assert.ok(section, 'registry lookup exists in workflow');
const source = section.split("          node <<'NODE'\n")[1].split('          NODE\n')[0].split('\n').map(line => line.slice(10)).join('\n');

async function lookup(status, digest, tokenStatus = 200) {
  const output = [], logs = [], calls = [];
  const env = {GITHUB_ACTOR: 'fixture', GITHUB_TOKEN: 'SECRET_CANARY', IMAGE_REPOSITORY: 'fixture/zapbot', GITHUB_SHA: 'a'.repeat(40), GITHUB_OUTPUT: 'fake-output'};
  const context = {
    require(name) { assert.equal(name, 'node:fs'); return {appendFileSync(_file, value) { output.push(value); }}; },
    process: {env, exitCode: undefined}, Buffer, URL, AbortSignal,
    console: {log(value) { logs.push(value); }, error(value) { logs.push(value); }},
    async fetch(url, options) {
      calls.push({url: String(url), options});
      if (calls.length === 1) return {ok: tokenStatus === 200, status: tokenStatus, json: async () => ({token: 'REGISTRY_TOKEN_CANARY'})};
      return {status, ok: status >= 200 && status < 300, headers: {get: () => digest}};
    },
  };
  await vm.runInNewContext(source, context);
  assert.ok(!logs.join('\n').includes('SECRET_CANARY'));
  assert.ok(!logs.join('\n').includes('REGISTRY_TOKEN_CANARY'));
  return {output: output.join(''), logs, calls, exitCode: context.process.exitCode};
}

test('only an authenticated missing tag permits first publication', async () => {
  const result = await lookup(404);
  assert.equal(result.output, 'exists=false\n');
  assert.equal(result.exitCode, undefined);
  assert.equal(result.calls[1].options.method, 'HEAD');
});
test('rerun reuses immutable image digest instead of overwriting tag', async () => {
  const digest = `sha256:${'b'.repeat(64)}`;
  const result = await lookup(200, digest);
  assert.equal(result.output, `exists=true\ndigest=${digest}\n`);
});
test('registry permissions and network errors fail closed', async () => {
  for (const status of [401, 403, 429, 500]) {
    const result = await lookup(status);
    assert.equal(result.exitCode, 1);
    assert.equal(result.output, '');
  }
  assert.equal((await lookup(404, undefined, 401)).exitCode, 1);
});
test('registry cannot inject an invalid digest into job outputs', async () => {
  const result = await lookup(200, 'sha256:bad\nexists=false');
  assert.equal(result.exitCode, 1);
  assert.equal(result.output, '');
});

test('workflow verifies embedded revision for new and reused images before fixtures/publication', () => {
  const section = yaml.split('      - name: Assert platform and exercise isolated deployment rollback\n')[1];
  assert.ok(section);
  const commands = section.split('        run: |\n')[1].split('      - name: Publish the exact tested image')[0]
    .split('\n').map(line => line.slice(10)).join('\n');
  const mocks = `docker() {
    case "$*" in
      *Architecture*) printf 'linux/amd64';;
      *org.opencontainers.image.revision*) printf '%s' "$FIXTURE_REVISION";;
      *) return 99;;
    esac
  }
  python3() { printf 'fixtures-executed\\n'; }
  `;
  for (const digest of ['', `sha256:${'b'.repeat(64)}`]) {
    for (const revision of ['a'.repeat(40), '', 'd'.repeat(40), 'SECRET_CANARY']) {
      const result = spawnSync('/bin/bash', ['-e', '-c', mocks + commands], {encoding:'utf8',
        env:{IMAGE_TAG:`ghcr.io/fixture/zapbot-pokemon:${'a'.repeat(40)}`, IMAGE_DIGEST:digest,
          GITHUB_SHA:'a'.repeat(40), SERVICE:'pokemon', FIXTURE_REVISION:revision}});
      assert.ifError(result.error);
      const valid = revision === 'a'.repeat(40);
      assert.equal(result.status, valid ? 0 : 1);
      assert.equal(result.stdout.includes('fixtures-executed'), valid);
      assert.ok(!result.stderr.includes('SECRET_CANARY'));
    }
  }
});
