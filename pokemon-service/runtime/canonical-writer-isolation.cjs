'use strict';

// Read-only preflight for the operational cutover of canonical trainer
// writers. Cassandra permissions are table-scoped: a role which can MODIFY
// estado_particionado can issue an unconditional legacy upsert, regardless of
// application-side fencing. This checker deliberately refuses the cutover
// while transition state still shares that table.
const {hash} = require('./restore-protocol.cjs');

const TRAINER_TABLES = Object.freeze({
  accounts: 'canonical_trainer_accounts',
  groups: 'canonical_trainer_groups',
  control: 'canonical_trainer_control',
  backups: 'canonical_trainer_backups'
});
const LEGACY_TABLE = 'estado_particionado';
const validRole = value => typeof value === 'string' && /^[A-Za-z][A-Za-z0-9_]*$/.test(value);
const validKeyspace = value => typeof value === 'string' && /^[a-z][a-z0-9_]*$/.test(value);
const fail = code => Object.assign(new Error(code), {code});
const rows = result => Array.isArray(result?.rows) ? result.rows : [];

function permissionsFor(result) {
  return new Set(rows(result).map(item => String(item.permission || '').toUpperCase()));
}

function has(permissions, permission) {
  return permissions.has('ALL') || permissions.has(permission);
}

async function assertNonSuperuser(adminQuery, role) {
  const membership = rows(await adminQuery(`LIST ROLES OF ${role}`));
  if (!membership.some(item => item.role === role) ||
      membership.some(item => item.super === true || item.is_superuser === true)) {
    throw fail('writer_isolation_role_superuser_or_missing');
  }
  return membership.map(item => item.role).sort();
}

async function tablePermissions(adminQuery, keyspace, role, table) {
  return permissionsFor(await adminQuery(`LIST ALL PERMISSIONS ON ${keyspace}.${table} OF ${role}`));
}

async function auditRole(adminQuery, keyspace, role) {
  const memberships = await assertNonSuperuser(adminQuery, role);
  const permissions = {};
  for (const table of [LEGACY_TABLE, ...Object.values(TRAINER_TABLES)]) {
    permissions[table] = [...await tablePermissions(adminQuery, keyspace, role, table)].sort();
  }
  return {role, memberships, permissions};
}

function requirePermissions(audit, table, required) {
  const actual = new Set(audit.permissions[table] || []);
  if (!required.every(permission => has(actual, permission))) throw fail('writer_isolation_runtime_permission_missing');
}

function denyPermissions(audit, table, denied) {
  const actual = new Set(audit.permissions[table] || []);
  if (denied.some(permission => has(actual, permission))) throw fail('writer_isolation_forbidden_permission');
}

// `runtimeCasProbe` must run under the candidate runtime credential against a
// disposable or supervised target. It proves both an LWT write and its read
// back; permission inspection alone cannot prove the actual service login.
async function verifyCanonicalWriterIsolation(adminQuery, {
  keyspace, legacyRole, runtimeRole, migratorRole, transitionStorage,
  runtimeCasProbe
} = {}) {
  if (typeof adminQuery !== 'function' || !validKeyspace(keyspace) ||
      ![legacyRole, runtimeRole, migratorRole].every(validRole) ||
      typeof runtimeCasProbe !== 'function') throw fail('invalid_writer_isolation_audit');
  if (transitionStorage !== 'dedicated') throw fail('writer_isolation_shared_legacy_storage');

  const [legacy, runtime, migrator] = await Promise.all([
    auditRole(adminQuery, keyspace, legacyRole),
    auditRole(adminQuery, keyspace, runtimeRole),
    auditRole(adminQuery, keyspace, migratorRole)
  ]);

  // Legacy processes have neither canonical access nor legacy MODIFY. The
  // latter is what makes an old unconditional trainer upsert impossible.
  denyPermissions(legacy, LEGACY_TABLE, ['MODIFY']);
  for (const table of Object.values(TRAINER_TABLES)) denyPermissions(legacy, table, ['SELECT', 'MODIFY']);

  // The runtime writes only versioned trainer tables. It must not retain a
  // route to the shared legacy table after the cutover.
  denyPermissions(runtime, LEGACY_TABLE, ['SELECT', 'MODIFY']);
  requirePermissions(runtime, TRAINER_TABLES.accounts, ['SELECT', 'MODIFY']);
  requirePermissions(runtime, TRAINER_TABLES.groups, ['SELECT', 'MODIFY']);
  requirePermissions(runtime, TRAINER_TABLES.control, ['SELECT']);
  requirePermissions(runtime, TRAINER_TABLES.backups, ['SELECT']);

  // Migration has read-only access to the legacy source and isolated write
  // access to canonical tables/backups/control. It is never a game runtime.
  requirePermissions(migrator, LEGACY_TABLE, ['SELECT']);
  denyPermissions(migrator, LEGACY_TABLE, ['MODIFY']);
  for (const table of Object.values(TRAINER_TABLES)) requirePermissions(migrator, table, ['SELECT', 'MODIFY']);

  const probe = await runtimeCasProbe();
  if (probe?.casApplied !== true || probe?.readBackConfirmed !== true) {
    throw fail('writer_isolation_runtime_cas_unconfirmed');
  }
  const evidence = {keyspace, transitionStorage, legacy, runtime, migrator,
    probe:{casApplied:true,readBackConfirmed:true}};
  return {verified:true, evidenceHash:hash(evidence), legacyModifyDenied:true,
    runtimeCasConfirmed:true, audits:{legacy,runtime,migrator}};
}

module.exports = {verifyCanonicalWriterIsolation, TRAINER_TABLES, LEGACY_TABLE};
