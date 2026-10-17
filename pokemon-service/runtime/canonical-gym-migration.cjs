'use strict';

const {hash} = require('./restore-protocol.cjs');
const {stableJson, PROTOCOL_VERSION} = require('./canonical-gym-store.cjs');
const row = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];
const fail = code => Object.assign(new Error(code), {code});
const MODULES = ['ginasios', 'ginasios-estatisticas'];
const object = value => value && typeof value === 'object' && !Array.isArray(value);

// Offline cutover helper. The caller must supply independently verified
// backup durability and exclusive-writer evidence; this class never deletes
// or rewrites legacy rows.
class CanonicalGymMigration {
  constructor({legacyExecute, controlExecute, backupExecute, canonicalStore, keyspace,
    assertExclusiveWriters, now = () => new Date()}) {
    if (![legacyExecute, controlExecute, backupExecute, assertExclusiveWriters].every(fn => typeof fn === 'function') ||
        !canonicalStore || !/^[a-z][a-z0-9_]*$/.test(keyspace)) throw fail('invalid_canonical_gym_migration_setup');
    this.legacyExecute = legacyExecute;
    this.controlExecute = controlExecute;
    this.backupExecute = backupExecute;
    this.store = canonicalStore;
    this.keyspace = keyspace;
    this.assertExclusiveWriters = assertExclusiveWriters;
    this.now = now;
    this.backups = `${keyspace}.canonical_gym_backups`;
    this.control = `${keyspace}.canonical_gym_control`;
  }

  async begin(migrationId) {
    if (typeof migrationId !== 'string' || !/^[a-zA-Z0-9_-]{8,80}$/.test(migrationId)) throw fail('invalid_canonical_gym_migration_id');
    const result = await this.controlExecute(
      `INSERT INTO ${this.control} (capability, protocol_version, state, migration_id, updated_at) VALUES (?, ?, ?, ?, ?) IF NOT EXISTS`,
      ['gym-canonical', PROTOCOL_VERSION, 'migrating', migrationId, this.now()], {consistency:'QUORUM',serialConsistency:'SERIAL'});
    if (result?.wasApplied?.()) return {alreadyActive:false};
    const current = row(await this.controlExecute(
      `SELECT protocol_version, state, migration_id FROM ${this.control} WHERE capability = ?`,
      ['gym-canonical'], {consistency:'SERIAL'}));
    if (current?.protocol_version === PROTOCOL_VERSION && current.migration_id === migrationId &&
        current.state === 'active') return {alreadyActive:true};
    if (current?.protocol_version === PROTOCOL_VERSION && current.migration_id === migrationId &&
        current.state === 'migrating') return {alreadyActive:false};
    throw fail('canonical_gym_migration_already_owned');
  }

  async verifyActiveMigration(migrationId) {
    const backups = [];
    for (const module of MODULES) {
      const source = await this.readLegacyModule(module);
      const canonical = await this.store.readAllInState(module, 'active');
      const expected = Object.fromEntries([...source].filter(([, value]) => Object.keys(value).length));
      if (stableJson(canonical) !== stableJson(expected)) throw fail('canonical_gym_migration_hash_mismatch');
      for (const [chat, snapshot] of source) {
        const sourceJson = stableJson(snapshot), sourceHash = hash(snapshot);
        const backupId = `${migrationId}:${sourceHash}`;
        const persisted = row(await this.backupExecute(
          `SELECT source_hash, source_json FROM ${this.backups} WHERE chat_id = ? AND module = ? AND backup_id = ?`,
          [chat, module, backupId], {consistency:'SERIAL'}));
        if (!persisted || persisted.source_hash !== sourceHash || persisted.source_json !== sourceJson ||
            hash(JSON.parse(persisted.source_json)) !== sourceHash) throw fail('canonical_gym_backup_unconfirmed');
        backups.push({chatHash:hash(chat),module,backupId,sourceHash});
      }
    }
    return {state:'active',migrationId,modules:MODULES.length,groups:backups.length,
      backups:backups.length,manifestHash:hash(Object.fromEntries(backups.map(item=>[
        `${item.chatHash}:${item.module}`,item.sourceHash]).sort()))};
  }

  async readLegacyModule(module) {
    const query = `SELECT particao, valor FROM ${this.keyspace}.estado_particionado WHERE modulo = ? ALLOW FILTERING`;
    const groups = new Map();
    let pageState;
    do {
      const result = await this.legacyExecute(query, [module], {consistency:'SERIAL',fetchSize:500,pageState});
      for (const item of result?.rows || []) {
        if (item.particao === '__migrado__') continue;
        let value;
        try { value = JSON.parse(item.valor); } catch { throw fail('canonical_gym_legacy_corrupt'); }
        if (!object(value)) throw fail('canonical_gym_legacy_corrupt');
        groups.set(item.particao, value);
      }
      pageState = result?.pageState;
    } while (pageState);
    return groups;
  }

  async backupAndImport(chat, module, snapshot, migrationId) {
    const sourceJson = stableJson(snapshot), sourceHash = hash(snapshot), backupId = `${migrationId}:${sourceHash}`;
    await this.backupExecute(
      `INSERT INTO ${this.backups} (chat_id, module, backup_id, source_hash, source_json, created_at) VALUES (?, ?, ?, ?, ?, ?) IF NOT EXISTS`,
      [chat, module, backupId, sourceHash, sourceJson, this.now()], {consistency:'QUORUM',serialConsistency:'SERIAL'});
    const persisted = row(await this.backupExecute(
      `SELECT source_hash, source_json FROM ${this.backups} WHERE chat_id = ? AND module = ? AND backup_id = ?`,
      [chat, module, backupId], {consistency:'SERIAL'}));
    if (!persisted || persisted.source_hash !== sourceHash || persisted.source_json !== sourceJson ||
        hash(JSON.parse(persisted.source_json)) !== sourceHash) throw fail('canonical_gym_backup_unconfirmed');
    await this.store.importModuleSnapshot(chat, module, snapshot, migrationId);
    return {backupId, sourceHash};
  }

  async migrate(migrationId) {
    const proof = await this.assertExclusiveWriters();
    if (!proof || proof.verified !== true || proof.activeLegacyWriters !== 0 ||
        !/^[a-f0-9]{64}$/.test(proof.permissionHash || '')) throw fail('canonical_gym_writers_not_exclusive');
    const begun = await this.begin(migrationId);
    if (begun.alreadyActive) return this.verifyActiveMigration(migrationId);
    const source = {};
    const backups = [];
    for (const module of MODULES) {
      source[module] = await this.readLegacyModule(module);
      for (const [chat, snapshot] of source[module]) {
        backups.push({chatHash:hash(chat), module, ...(await this.backupAndImport(chat, module, snapshot, migrationId))});
      }
    }

    // Re-read legacy source after import. The proof remains mandatory: the
    // hash comparison detects changes, while the external fence prevents a
    // writer from racing this final verification.
    for (const module of MODULES) {
      const checked = await this.readLegacyModule(module);
      if (stableJson([...checked.entries()].sort()) !== stableJson([...source[module].entries()].sort())) {
        throw fail('canonical_gym_legacy_changed_during_migration');
      }
      const canonical = await this.store.readAllInState(module, 'migrating');
      // readAll intentionally omits partitions with no records. Normalize the
      // source the same way so an empty legacy map does not cause a false
      // mismatch after a successful migration.
      const expected = Object.fromEntries([...source[module]].filter(([, value]) => Object.keys(value).length));
      if (stableJson(canonical) !== stableJson(expected)) throw fail('canonical_gym_migration_hash_mismatch');
    }
    const finalProof = await this.assertExclusiveWriters();
    if (!finalProof || finalProof.verified !== true || finalProof.activeLegacyWriters !== 0 ||
        finalProof.permissionHash !== proof.permissionHash) throw fail('canonical_gym_writer_fence_changed');
    const manifest = Object.fromEntries(backups.map(item => [`${item.chatHash}:${item.module}`, item.sourceHash]).sort());
    const manifestHash = hash(manifest);
    const activated = await this.controlExecute(
      `UPDATE ${this.control} SET state = ?, updated_at = ? WHERE capability = ? IF state = ? AND protocol_version = ? AND migration_id = ?`,
      ['active', this.now(), 'gym-canonical', 'migrating', PROTOCOL_VERSION, migrationId],
      {consistency:'QUORUM',serialConsistency:'SERIAL'});
    if (!activated?.wasApplied?.()) {
      const current = row(await this.controlExecute(
        `SELECT protocol_version, state, migration_id FROM ${this.control} WHERE capability = ?`,
        ['gym-canonical'], {consistency:'SERIAL'}));
      if (current?.protocol_version !== PROTOCOL_VERSION || current.state !== 'active' || current.migration_id !== migrationId) {
        throw fail('canonical_gym_migration_activation_unknown');
      }
    }
    return {state:'active', migrationId, modules:MODULES.length, groups:backups.length,
      backups:backups.length, manifestHash, writerProofHash:proof.permissionHash};
  }
}

module.exports = {CanonicalGymMigration};
