const test = require('node:test');
const assert = require('node:assert/strict');
const { idempotencyKey, ready, render } = require('../scripts/lib/release-announcer.cjs');

const gates = {jvmHealthy:true,nodeHealthy:true,whatsappReady:true,datomicAccessible:true,versionConfirmed:true,rollbackComplete:true};

test('all operational gates, including rollback proof, are required', () => {
  assert.equal(ready(gates), true);
  for (const key of Object.keys(gates)) assert.equal(ready({...gates,[key]:false}), false, key);
  assert.equal(idempotencyKey({version:'v',contentHash:'h',chatId:'pilot'}), 'v:h:pilot');
});

test('render contains player-facing release information only', () => {
  const text = render({version:'12.8.31',contentHash:'private-hash',highlights:['Ajuda atualizada']});
  assert.match(text,/12\.8\.31/);
  assert.match(text,/Ajuda atualizada/);
  assert.doesNotMatch(text,/private-hash|sha256|docker|server/i);
});
