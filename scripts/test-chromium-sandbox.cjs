'use strict';

// Run only in an isolated container with disposable mounts at the app data paths.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('/app/node_modules/puppeteer');

assert.equal(process.env.CHROMIUM_SANDBOX_TEST, 'yes', 'explicit disposable-test opt-in required');
assert.ok(process.getuid() > 0, 'Chromium must run as a non-root user');

const profileRoot = '/app/.wwebjs_auth/sandbox-fixture';
const dataRoot = '/app/data';
const sentinel = path.join(dataRoot, 'chromium-sandbox-volume-sentinel');
const mode = process.argv[2] || 'run';
assert.ok(['run', 'verify'].includes(mode));

if (mode === 'verify') {
  assert.equal(fs.readFileSync(sentinel, 'utf8'), 'sandbox-fixture-only');
  assert.ok(fs.readdirSync(profileRoot).length > 0, 'Chrome profile did not persist');
  console.log('RESULT disposable profile and data persisted across container recreation PASS');
  process.exit(0);
}

fs.mkdirSync(profileRoot, { recursive: true });
fs.mkdirSync(dataRoot, { recursive: true });
if (!fs.existsSync(sentinel)) fs.writeFileSync(sentinel, 'sandbox-fixture-only', { flag: 'wx' });
assert.equal(fs.readFileSync(sentinel, 'utf8'), 'sandbox-fixture-only');

const readStatus = pid => Object.fromEntries(
  fs.readFileSync(`/proc/${pid}/status`, 'utf8')
    .split('\n')
    .filter(line => /^(Name|Uid|Gid|NoNewPrivs|Seccomp|Seccomp_filters|CapEff):/.test(line))
    .map(line => {
      const [key, ...values] = line.split(/\s+/);
      return [key.replace(/:$/, ''), values.join(' ')];
    })
);
const namespaces = pid => Object.fromEntries(
  ['user', 'pid', 'net'].map(name => [name, fs.readlinkSync(`/proc/${pid}/ns/${name}`)])
);

(async () => {
  let browser;
  try {
    browser = await puppeteer.launch({
      headless: true,
      executablePath: '/usr/bin/chromium',
      userDataDir: path.join(profileRoot, 'chrome'),
      timeout: 25000,
      args: ['--disable-quic', '--disable-features=Quic', '--disable-extensions',
        '--disable-default-apps', '--no-first-run', '--disable-gpu']
    });
    const page = await browser.newPage();
    await page.goto('data:text/html,<title>chromium-sandbox-proof</title>', { timeout: 10000 });
    assert.equal(await page.title(), 'chromium-sandbox-proof');

    const cdp = await browser.target().createCDPSession();
    const processInfo = await cdp.send('SystemInfo.getProcessInfo');
    const renderer = processInfo.processInfo.find(item => item.type === 'renderer');
    assert.ok(renderer, 'CDP did not report a renderer');
    const browserPid = browser.process().pid;
    const rendererPid = renderer.id;
    const browserStatus = readStatus(browserPid);
    const rendererStatus = readStatus(rendererPid);
    const browserNamespaces = namespaces(browserPid);
    const rendererNamespaces = namespaces(rendererPid);
    const noSandboxFlag = browser.process().spawnargs.some(arg =>
      ['--no-sandbox', '--disable-setuid-sandbox', '--disable-seccomp-filter-sandbox'].includes(arg));

    await page.goto('chrome://sandbox', { waitUntil: 'domcontentloaded', timeout: 10000 });
    const sandboxReport = await page.$eval('body', body => body.innerText);
    const proof = {
      chrome: await browser.version(),
      uid: process.getuid(),
      browserPid,
      rendererPid,
      browserStatus,
      rendererStatus,
      browserNamespaces,
      rendererNamespaces,
      noSandboxFlag,
      sandboxReport
    };
    console.log(JSON.stringify(proof));

    assert.equal(noSandboxFlag, false);
    assert.equal(browserStatus.Uid.split(' ')[0], String(process.getuid()));
    assert.equal(rendererStatus.Uid.split(' ')[0], String(process.getuid()));
    for (const status of [browserStatus, rendererStatus]) {
      assert.equal(status.NoNewPrivs, '1');
      assert.equal(status.Seccomp, '2');
      assert.equal(status.CapEff, '0000000000000000');
    }
    assert.ok(Number(rendererStatus.Seccomp_filters) > Number(browserStatus.Seccomp_filters),
      'renderer must have Chromium BPF filter in addition to the container filter');
    for (const name of ['user', 'pid', 'net']) {
      assert.notEqual(rendererNamespaces[name], browserNamespaces[name], `${name} namespace is shared`);
    }
    assert.match(sandboxReport, /PID namespaces\s+Yes/);
    assert.match(sandboxReport, /Network namespaces\s+Yes/);
    assert.match(sandboxReport, /Seccomp-BPF sandbox\s+Yes/);
    assert.match(sandboxReport, /You are adequately sandboxed/);

    await browser.close();
    assert.equal(browser.process().exitCode, 0);
    assert.equal(browser.process().signalCode, null);
    assert.equal(fs.readFileSync(sentinel, 'utf8'), 'sandbox-fixture-only');
    console.log('RESULT namespace+Chromium-Seccomp-BPF+CDP+volume+clean-close PASS');
  } catch (error) {
    console.error('FAIL', error.stack || error.message);
    if (browser?.isConnected()) {
      try { await browser.close(); } catch {}
    }
    process.exitCode = 1;
  }
})();
