'use strict';

const {hash} = require('./restore-protocol.cjs');
const {stableJson, PROTOCOL_VERSION} = require('./canonical-trainer-store.cjs');
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const fail = code => Object.assign(new Error(code), {code});

async function verifyLegacyWriterFence(adminQuery, role, keyspace) {
  if (typeof adminQuery !== 'function' || !/^[A-Za-z][A-Za-z0-9_]*$/.test(role) ||
      !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_legacy_permission_audit');
  const roles = await adminQuery(`LIST ROLES OF ${role}`);
  const inherited = roles?.rows || [];
  if (!inherited.some(item => item.role === role) || inherited.some(item => item.super === true || item.is_superuser === true)) {
    throw fail('legacy_writer_superuser_or_role_missing');
  }
  const permissions = await adminQuery(`LIST ALL PERMISSIONS ON ${keyspace}.estado_particionado OF ${role}`);
  const writesLegacyTrainer = (permissions?.rows || []).some(item =>
    ['MODIFY','ALL'].includes(String(item.permission || '').toUpperCase()));
  if (writesLegacyTrainer) throw fail('legacy_writer_not_fenced');
  const evidence = {role, keyspace, auditedRoles: inherited.map(item => item.role).sort(),
    permissions: (permissions?.rows || []).map(item => ({role:item.role,permission:item.permission,resource:item.resource}))
      .sort((a,b) => `${a.role}:${a.resource}:${a.permission}`.localeCompare(`${b.role}:${b.resource}:${b.permission}`))};
  return {verified:true,legacyModifyDenied:true,role,permissionHash:hash(evidence)};
}

// Controlled one-player cutover. The caller must verify the old runtime role
// no longer has MODIFY on estado_particionado; absence of that proof is a hard
// stop. This module never writes the legacy row.
class CanonicalTrainerMigration {
  constructor({legacyExecute, backupExecute, canonicalStore, keyspace, verifyLegacyWriterRevoked}) {
    if (typeof legacyExecute !== 'function' || typeof backupExecute !== 'function' ||
        !canonicalStore || typeof verifyLegacyWriterRevoked !== 'function' ||
        !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_migration_setup');
    this.legacyExecute = legacyExecute;
    this.backupExecute = backupExecute;
    this.store = canonicalStore;
    this.keyspace = keyspace;
    this.verifyLegacyWriterRevoked = verifyLegacyWriterRevoked;
    this.backups = `${keyspace}.canonical_trainer_backups`;
  }

  async migrate(chat, player, migrationId) {
    if (typeof chat !== 'string' || !chat || typeof player !== 'string' || !player ||
        typeof migrationId !== 'string' || !migrationId) throw fail('invalid_migration_identity');
    const proof = await this.verifyLegacyWriterRevoked();
    if (!proof || proof.verified !== true || proof.legacyModifyDenied !== true ||
        typeof proof.role !== 'string' || !/^[A-Za-z][A-Za-z0-9_]*$/.test(proof.role) ||
        !/^[a-f0-9]{64}$/.test(proof.permissionHash || '')) throw fail('legacy_writer_not_fenced');
    const result = await this.legacyExecute(
      `SELECT valor FROM ${this.keyspace}.estado_particionado WHERE modulo = ? AND particao = ?`,
      ['treinador', chat], {consistency: 'SERIAL'});
    const legacy = row(result)?.valor;
    if (typeof legacy !== 'string') throw fail('legacy_trainer_missing');
    let group;
    try { group = JSON.parse(legacy); } catch { throw fail('legacy_trainer_corrupt'); }
    if (!group || typeof group !== 'object' || Array.isArray(group) ||
        !Object.hasOwn(group, player) || !group[player] || typeof group[player] !== 'object' || Array.isArray(group[player])) {
      throw fail('legacy_account_missing_or_invalid');
    }
    const state = group[player], stateJson = stableJson(state), sourceHash = hash(state);

    // Read twice around durable backup to detect an account change during the
    // cut. Security still depends on the verified Cassandra permission fence.
    const backupId = `${migrationId}:${sourceHash}`;
    await this.backupExecute(
      `INSERT INTO ${this.backups} (chat_id, player_id, backup_id, source_hash, source_json, created_at) VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
      [chat, player, backupId, sourceHash, stateJson, new Date()], {consistency: 'QUORUM', serialConsistency: 'SERIAL'});
    const backupResult = await this.backupExecute(
      `SELECT source_hash, source_json FROM ${this.backups} WHERE chat_id = ? AND player_id = ? AND backup_id = ?`,
      [chat, player, backupId], {consistency: 'SERIAL'});
    const backup = row(backupResult);
    if (!backup || backup.source_hash !== sourceHash || backup.source_json !== stateJson || hash(JSON.parse(backup.source_json)) !== sourceHash) {
      throw fail('immutable_backup_unconfirmed');
    }

    const checkResult = await this.legacyExecute(
      `SELECT valor FROM ${this.keyspace}.estado_particionado WHERE modulo = ? AND particao = ?`,
      ['treinador', chat], {consistency: 'SERIAL'});
    let checked;
    try { checked = JSON.parse(row(checkResult)?.valor || 'null'); } catch { throw fail('legacy_trainer_corrupt'); }
    if (!checked || !Object.hasOwn(checked, player) || hash(checked[player]) !== sourceHash) throw fail('legacy_account_changed_during_migration');

    const canonical = await this.store.importMigratedAccount(chat, player, state, migrationId);
    if (canonical.stateHash !== sourceHash) throw fail('canonical_hash_mismatch');
    return {chat, player, migrationId, protocolVersion: PROTOCOL_VERSION,
      generation: canonical.generation, stateHash: sourceHash, backupId};
  }

  async legacyGroups() {
    const query = `SELECT particao, valor FROM ${this.keyspace}.estado_particionado WHERE modulo = ? ALLOW FILTERING`;
    const groups = new Map();
    let pageState;
    do {
      const result = await this.legacyExecute(query, ['treinador'], {consistency:'SERIAL',fetchSize:500,pageState});
      for (const record of result?.rows || []) {
        if (record.particao === '__migrado__') continue;
        let value;
        try { value = JSON.parse(record.valor); } catch { throw fail('legacy_group_corrupt'); }
        if (!value || typeof value !== 'object' || Array.isArray(value)) throw fail('legacy_group_corrupt');
        groups.set(record.particao,value);
      }
      pageState = result?.pageState;
    } while (pageState);
    return groups;
  }

  async verifyAndActivate(migrationId) {
    const proof = await this.verifyLegacyWriterRevoked();
    if (!proof || proof.verified !== true || proof.legacyModifyDenied !== true ||
        typeof proof.role !== 'string' || !/^[A-Za-z][A-Za-z0-9_]*$/.test(proof.role) ||
        !/^[a-f0-9]{64}$/.test(proof.permissionHash || '')) throw fail('legacy_writer_not_fenced');
    const groups = await this.legacyGroups();
    const canonicalGroups = await this.store.readAllGroupsInState('migrating');
    if (stableJson(Object.keys(canonicalGroups).sort()) !== stableJson([...groups.keys()].sort())) {
      throw fail('canonical_migration_group_count_mismatch');
    }
    let accountCount = 0;
    const manifest = {};
    for (const [chat, legacyGroup] of groups) {
      const canonicalGroup = await this.store.readGroupInState(chat,'migrating');
      if (stableJson(Object.keys(canonicalGroup).sort()) !== stableJson(Object.keys(legacyGroup).sort())) {
        throw fail('canonical_migration_account_count_mismatch');
      }
      for (const [player, state] of Object.entries(legacyGroup)) {
        const sourceHash = hash(state), sourceJson = stableJson(state), backupId = `${migrationId}:${sourceHash}`;
        const backupRow = row(await this.backupExecute(
          `SELECT source_hash, source_json FROM ${this.backups} WHERE chat_id = ? AND player_id = ? AND backup_id = ?`,
          [chat,player,backupId],{consistency:'SERIAL'}));
        if (!backupRow || backupRow.source_hash !== sourceHash || backupRow.source_json !== sourceJson) {
          throw fail('canonical_migration_backup_missing');
        }
        if (hash(canonicalGroup[player]) !== sourceHash) throw fail('canonical_migration_hash_mismatch');
        manifest[`${hash(chat)}:${hash(player)}`] = sourceHash;
        accountCount++;
      }
    }
    if (!accountCount) throw fail('canonical_migration_empty');
    const manifestHash = hash(manifest);
    const activated = await this.backupExecute(
      `UPDATE ${this.keyspace}.canonical_trainer_control SET state = ?, updated_at = ? WHERE capability = ? IF state = ? AND protocol_version = ? AND migration_id = ?`,
      ['active',new Date(),'trainer-canonical','migrating',PROTOCOL_VERSION,migrationId],
      {consistency:'QUORUM',serialConsistency:'SERIAL'});
    if (!activated?.wasApplied?.()) {
      const observed = row(await this.backupExecute(
        `SELECT protocol_version, state, migration_id FROM ${this.keyspace}.canonical_trainer_control WHERE capability = ?`,
        ['trainer-canonical'],{consistency:'SERIAL'}));
      if (observed?.state !== 'active' || observed.protocol_version !== PROTOCOL_VERSION || observed.migration_id !== migrationId) {
        throw fail('canonical_migration_activation_uncertain');
      }
    }
    return {state:'active',protocolVersion:PROTOCOL_VERSION,groups:groups.size,accounts:accountCount,manifestHash};
  }
}

module.exports = {CanonicalTrainerMigration, verifyLegacyWriterFence};
