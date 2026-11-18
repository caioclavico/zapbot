'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const {verifyCanonicalWriterIsolation, TRAINER_TABLES, LEGACY_TABLE} = require('../runtime/canonical-writer-isolation.cjs');

function admin({legacyModify = false, runtimeLegacyAccess = false, probe = {casApplied:true,readBackConfirmed:true}} = {}) {
  const grants = {
    legacy: {[LEGACY_TABLE]: legacyModify ? ['SELECT','MODIFY'] : ['SELECT']},
    runtime: {
      [LEGACY_TABLE]: runtimeLegacyAccess ? ['SELECT'] : [],
      [TRAINER_TABLES.accounts]: ['SELECT','MODIFY'],
      [TRAINER_TABLES.groups]: ['SELECT','MODIFY'],
      [TRAINER_TABLES.control]: ['SELECT'],
      [TRAINER_TABLES.backups]: ['SELECT']
    },
    migrator: {
      [LEGACY_TABLE]: ['SELECT'],
      [TRAINER_TABLES.accounts]: ['SELECT','MODIFY'],
      [TRAINER_TABLES.groups]: ['SELECT','MODIFY'],
      [TRAINER_TABLES.control]: ['SELECT','MODIFY'],
      [TRAINER_TABLES.backups]: ['SELECT','MODIFY']
    }
  };
  return {
    query: async sql => {
      const roleMatch = sql.match(/ OF ([A-Za-z][A-Za-z0-9_]*)$/);
      if (sql.startsWith('LIST ROLES OF ')) {
        const role=sql.slice('LIST ROLES OF '.length);
        return {rows:[{role,super:false,is_superuser:false}]};
      }
      const table=sql.match(/ ON zapbot\.([a-z_]+) OF /)?.[1];
      const role=roleMatch?.[1];
      return {rows:(grants[role]?.[table] || []).map(permission => ({role,permission,resource:`<table zapbot.${table}>`}))};
    },
    probe: async () => probe
  };
}

async function audit(options = {}) {
  const fixture=admin(options);
  return verifyCanonicalWriterIsolation(fixture.query, {keyspace:'zapbot',legacyRole:'legacy',
    runtimeRole:'runtime',migratorRole:'migrator',transitionStorage:'dedicated',runtimeCasProbe:fixture.probe});
}

test('isolated roles deny legacy writes and confirm a runtime CAS probe', async () => {
  const result=await audit();
  assert.equal(result.verified,true);
  assert.equal(result.legacyModifyDenied,true);
  assert.equal(result.runtimeCasConfirmed,true);
  assert.match(result.evidenceHash,/^[a-f0-9]{64}$/);
});

test('audit fails closed for shared legacy transition storage or a legacy writer', async () => {
  const fixture=admin();
  await assert.rejects(verifyCanonicalWriterIsolation(fixture.query,{keyspace:'zapbot',legacyRole:'legacy',
    runtimeRole:'runtime',migratorRole:'migrator',transitionStorage:'shared-legacy',runtimeCasProbe:fixture.probe}),
  {code:'writer_isolation_shared_legacy_storage'});
  await assert.rejects(audit({legacyModify:true}),{code:'writer_isolation_forbidden_permission'});
});

test('audit rejects runtime legacy access and an unconfirmed CAS probe', async () => {
  await assert.rejects(audit({runtimeLegacyAccess:true}),{code:'writer_isolation_forbidden_permission'});
  await assert.rejects(audit({probe:{casApplied:true,readBackConfirmed:false}}),
    {code:'writer_isolation_runtime_cas_unconfirmed'});
});
