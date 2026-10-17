'use strict';

// Immutable, dormant save snapshots. This store is deliberately not wired to
// gameplay, trainer writers, gyms, HTTP routes, or a slot activation command.
const {RestoreError, validateSlotAccount, receiptRecord, hash} = require('./restore-protocol.cjs');

const MODULES = new Set([
  'pokemon-save-slots',
  'pokemon-save-slot-index',
  'pokemon-save-global-receipts'
]);
const MAX_ACCOUNT_BYTES = 8 * 1024 * 1024;
const MAX_INDEX_SLOTS = 128;
const MAX_CAS_ATTEMPTS = 12;
const FORMAT = 'json-account-v1';
const emptyIndex = () => ({schemaVersion: 1, slots: {}});
const ledgerValue = receipts => {
  const value = {schemaVersion: 1, receipts};
  return {...value, integrityHash: hash(value)};
};
const json = value => JSON.stringify(value);
const object = value => Boolean(value) && typeof value === 'object' && !Array.isArray(value);
const resultRow = result => typeof result?.first === 'function' ? result.first() : result?.rows?.[0];

function validIdentityPart(value) {
  return typeof value === 'string' && value.length > 0 && value.length <= 256 && !/[\u0000-\u001f\u007f]/.test(value);
}
function validSlotId(value) {
  return typeof value === 'string' && /^[a-z0-9][a-z0-9_-]{0,63}$/.test(value);
}
function validateSource(source) {
  const expected = {
    legacy_estado_particionado: '1',
    legacy_estado: '2'
  };
  if (!object(source) || !Object.hasOwn(expected, source.kind) || source.version !== expected[source.kind]) {
    throw new RestoreError('invalid_slot_source');
  }
  return {kind: source.kind, version: source.version};
}

class SaveSlotStore {
  constructor(execute, keyspace, {now = () => new Date().toISOString()} = {}) {
    if (!/^[a-z][a-z0-9_]*$/.test(keyspace)) throw new RestoreError('invalid_keyspace');
    if (typeof execute !== 'function') throw new RestoreError('invalid_executor');
    this.execute = execute;
    this.table = `${keyspace}.estado_particionado`;
    this.now = now;
  }

  identity(chat, player) {
    if (!validIdentityPart(chat) || !validIdentityPart(player)) throw new RestoreError('invalid_identity');
    return hash([chat, player]);
  }

  async read(module, partition) {
    if (!MODULES.has(module)) throw new RestoreError('invalid_module');
    const result = await this.execute(
      `SELECT valor FROM ${this.table} WHERE modulo = ? AND particao = ?`,
      [module, partition], {prepare: true, consistency: 'SERIAL'}
    );
    const row = resultRow(result);
    if (!row?.valor) return null;
    try {
      return {raw: row.valor, value: JSON.parse(row.valor)};
    } catch {
      throw new RestoreError('slot_storage_corrupt');
    }
  }

  async insertImmutable(module, partition, value) {
    if (!MODULES.has(module)) throw new RestoreError('invalid_module');
    const serialized = json(value);
    try {
      await this.execute(
        `INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
        [module, partition, serialized], {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'}
      );
    } catch {
      // A lost response is resolved by a SERIAL read; never overwrite the row.
    }
    const confirmed = await this.read(module, partition);
    if (!confirmed) throw new RestoreError('durability_unconfirmed');
    if (hash(confirmed.value) !== hash(value)) throw new RestoreError('slot_conflict');
    return confirmed.value;
  }

  async updateCas(module, partition, change, validateValue) {
    if (!MODULES.has(module)) throw new RestoreError('invalid_module');
    for (let attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      const before = await this.read(module, partition);
      const old = before?.value ?? null;
      const next = change(old);
      validateValue(next);
      if (old === null) {
        try {
          await this.execute(
            `INSERT INTO ${this.table} (modulo, particao, valor) VALUES (?, ?, ?) IF NOT EXISTS`,
            [module, partition, json(next)], {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'}
          );
        } catch {
          // Re-read with SERIAL below. A retry is safe only from observed state.
        }
      } else {
        try {
          const result = await this.execute(
            `UPDATE ${this.table} SET valor = ? WHERE modulo = ? AND particao = ? IF valor = ?`,
            [json(next), module, partition, before.raw],
            {prepare: true, consistency: 'QUORUM', serialConsistency: 'SERIAL'}
          );
          if (result?.wasApplied?.()) {
            const confirmed = await this.read(module, partition);
            if (confirmed && hash(confirmed.value) === hash(next)) return confirmed.value;
            throw new RestoreError('write_unconfirmed');
          }
        } catch (error) {
          if (error instanceof RestoreError) throw error;
          // The next SERIAL read distinguishes an applied CAS from an old row.
        }
      }
      const observed = await this.read(module, partition);
      if (observed && hash(observed.value) === hash(next)) return observed.value;
      // A CAS conflict or a timeout proven unapplied may be recomputed against
      // the fresh value. If SERIAL cannot resolve it, read() throws and we stop.
    }
    throw new RestoreError('slot_index_contention');
  }

  validateIndex(index) {
    if (!object(index) || index.schemaVersion !== 1 || !object(index.slots) || Object.keys(index.slots).length > MAX_INDEX_SLOTS) {
      throw new RestoreError('slot_index_corrupt');
    }
    for (const [id, entry] of Object.entries(index.slots)) {
      if (!validSlotId(id) || !object(entry) || typeof entry.contentHash !== 'string' || !/^[a-f0-9]{64}$/.test(entry.contentHash) ||
          typeof entry.createdAt !== 'string' || !Number.isFinite(Date.parse(entry.createdAt))) {
        throw new RestoreError('slot_index_corrupt');
      }
    }
  }

  validateLedger(ledger) {
    if (!object(ledger) || ledger.schemaVersion !== 1 || !object(ledger.receipts) ||
        Buffer.byteLength(json(ledger)) > MAX_ACCOUNT_BYTES) throw new RestoreError('global_receipts_corrupt');
    const receipts = receiptRecord(ledger.receipts);
    if (!receipts.ok) throw new RestoreError('global_receipts_corrupt');
    const check = {...ledger}; delete check.integrityHash;
    if (ledger.integrityHash !== hash(check)) throw new RestoreError('global_receipts_corrupt');
  }

  async mergeGlobalReceipts(identity, sourceReceipts) {
    return this.updateCas('pokemon-save-global-receipts', identity, old => {
      const ledger = old ?? ledgerValue({});
      this.validateLedger(ledger);
      const receipts = {...ledger.receipts};
      for (const [eventId, pokemonId] of Object.entries(sourceReceipts)) {
        if (receipts[eventId] && receipts[eventId] !== pokemonId) throw new RestoreError('global_receipt_conflict');
        receipts[eventId] = pokemonId;
      }
      return ledgerValue(receipts);
    }, value => this.validateLedger(value));
  }

  slotPartition(identity, slotId) { return `${identity}:${slotId}`; }

  makeRecord(identity, slotId, source, snapshot, validation, createdAt) {
    const sourceReceipts = snapshot['adventure-entregas'] ?? {};
    const account = {...snapshot};
    delete account['adventure-entregas'];
    const receiptSnapshot = Object.entries(sourceReceipts)
      .map(([id, pokemonId]) => ({eventHash: hash(id), valueHash: hash(pokemonId)}))
      .sort((a, b) => a.eventHash.localeCompare(b.eventHash));
    const receiptHash = hash(receiptSnapshot);
    const contentHash = hash({account, receiptHash});
    const metadata = {
      source: source.kind,
      sourceVersion: source.version,
      sourceHash: validation.sourceHash,
      contentHash,
      createdAt,
      format: FORMAT,
      pokemonCount: validation.pokemonCount,
      globalReceiptCount: validation.receiptCount,
      globalReceiptHash: receiptHash
    };
    const record = {
      schemaVersion: 1,
      identityHash: identity,
      slotId,
      metadata,
      account,
      globalReceipts: {module: 'pokemon-save-global-receipts', partition: identity,
        snapshotHash: receiptHash, snapshot: receiptSnapshot}
    };
    record.integrityHash = hash(record);
    return record;
  }

  assertRecord(record, identity, slotId) {
    if (!object(record) || record.schemaVersion !== 1 || record.identityHash !== identity || record.slotId !== slotId ||
        !object(record.account) || !object(record.metadata) || record.metadata.format !== FORMAT ||
        !['legacy_estado_particionado', 'legacy_estado'].includes(record.metadata.source) ||
        record.metadata.sourceVersion !== (record.metadata.source === 'legacy_estado_particionado' ? '1' : '2') ||
        !Number.isFinite(Date.parse(record.metadata.createdAt)) ||
        !Number.isInteger(record.metadata.pokemonCount) || record.metadata.pokemonCount < 0 ||
        !Number.isInteger(record.metadata.globalReceiptCount) || record.metadata.globalReceiptCount < 0 ||
        !/^[a-f0-9]{64}$/.test(record.metadata.contentHash) ||
        !/^[a-f0-9]{64}$/.test(record.metadata.globalReceiptHash) ||
        record.globalReceipts?.module !== 'pokemon-save-global-receipts' || record.globalReceipts.partition !== identity ||
        record.globalReceipts.snapshotHash !== record.metadata.globalReceiptHash ||
        !Array.isArray(record.globalReceipts.snapshot) || record.globalReceipts.snapshot.length !== record.metadata.globalReceiptCount ||
        record.globalReceipts.snapshotHash !== hash(record.globalReceipts.snapshot) ||
        record.globalReceipts.snapshot.some(item => !object(item) || !/^[a-f0-9]{64}$/.test(item.eventHash) ||
          !/^[a-f0-9]{64}$/.test(item.valueHash))) throw new RestoreError('slot_corrupt');
    const supplied = record.integrityHash;
    const check = {...record}; delete check.integrityHash;
    if (supplied !== hash(check) || record.metadata.contentHash !== hash({account: record.account, receiptHash: record.metadata.globalReceiptHash}) ||
        !/^[a-f0-9]{64}$/.test(record.metadata.sourceHash)) {
      throw new RestoreError('slot_corrupt');
    }
    return record;
  }

  sameImport(existing, proposed) {
    this.assertRecord(existing, proposed.identityHash, proposed.slotId);
    return existing.metadata.source === proposed.metadata.source &&
      existing.metadata.sourceVersion === proposed.metadata.sourceVersion &&
      existing.metadata.sourceHash === proposed.metadata.sourceHash &&
      existing.metadata.contentHash === proposed.metadata.contentHash &&
      existing.metadata.format === proposed.metadata.format &&
      hash(existing.account) === hash(proposed.account) &&
      hash(existing.globalReceipts) === hash(proposed.globalReceipts);
  }

  async importValidated({chat, player, slotId, source: sourceInput, account: inputAccount, currentAccount = {}}) {
    if (!validSlotId(slotId)) throw new RestoreError('invalid_slot_id');
    const identity = this.identity(chat, player);
    const source = validateSource(sourceInput);
    let snapshot;
    try { snapshot = JSON.parse(json(inputAccount)); }
    catch { throw new RestoreError('invalid_slot_format'); }
    if (!object(snapshot) || Buffer.byteLength(json(snapshot)) > MAX_ACCOUNT_BYTES) throw new RestoreError('source_too_large');
    const validation = validateSlotAccount(snapshot);
    const receipts = receiptRecord(snapshot['adventure-entregas']);
    if (!receipts.ok) throw new RestoreError('invalid_global_receipts');
    const currentReceipts = receiptRecord(currentAccount?.['adventure-entregas']);
    if (!currentReceipts.ok) throw new RestoreError('invalid_global_receipts');
    const globalReceipts = {...currentReceipts.receipts};
    for (const [eventId, pokemonId] of Object.entries(receipts.receipts)) {
      if (globalReceipts[eventId] && globalReceipts[eventId] !== pokemonId) throw new RestoreError('global_receipt_conflict');
      globalReceipts[eventId] = pokemonId;
    }
    const partition = this.slotPartition(identity, slotId);

    // Receipts are monotonic global anti-duplication state, never slot data.
    // A later failure may conservatively retain these IDs; it cannot create a
    // reward and a retry of this same immutable import is safe.
    await this.mergeGlobalReceipts(identity, globalReceipts);

    const proposed = this.makeRecord(identity, slotId, source, snapshot, validation, this.now());
    const existing = await this.read('pokemon-save-slots', partition);
    let record;
    if (existing) {
      record = this.assertRecord(existing.value, identity, slotId);
      if (!this.sameImport(record, proposed)) throw new RestoreError('slot_conflict');
    } else {
      try {
        record = await this.insertImmutable('pokemon-save-slots', partition, proposed);
      } catch (error) {
        // IF NOT EXISTS can race another identical import; SERIAL read and
        // exact logical comparison distinguish it from a conflicting slot.
        const raced = await this.read('pokemon-save-slots', partition);
        if (!raced) throw error;
        record = this.assertRecord(raced.value, identity, slotId);
        if (!this.sameImport(record, proposed)) throw new RestoreError('slot_conflict');
      }
    }

    await this.verifyGlobalReceiptReferences(record);
    await this.updateCas('pokemon-save-slot-index', identity, old => {
      const index = old ?? emptyIndex();
      this.validateIndex(index);
      const prior = index.slots[slotId];
      if (prior && prior.contentHash !== record.metadata.contentHash) throw new RestoreError('slot_conflict');
      return {schemaVersion: 1, slots: {...index.slots, [slotId]: {
        contentHash: record.metadata.contentHash, createdAt: record.metadata.createdAt
      }}};
    }, value => this.validateIndex(value));
    return this.publicMetadata(record);
  }

  publicMetadata(record) {
    this.assertRecord(record, record.identityHash, record.slotId);
    return {slotId: record.slotId, ...record.metadata};
  }

  async verifyGlobalReceiptReferences(record) {
    const ledgerRow = await this.read('pokemon-save-global-receipts', record.identityHash);
    if (!ledgerRow) throw new RestoreError('global_receipts_unconfirmed');
    const ledger = ledgerRow.value;
    this.validateLedger(ledger);
    const available = new Set(Object.entries(ledger.receipts)
      .map(([eventId, pokemonId]) => `${hash(eventId)}:${hash(pokemonId)}`));
    if (record.globalReceipts.snapshot.some(item => !available.has(`${item.eventHash}:${item.valueHash}`))) {
      throw new RestoreError('global_receipt_reference_invalid');
    }
  }

  async list(chat, player) {
    const identity = this.identity(chat, player);
    const row = await this.read('pokemon-save-slot-index', identity);
    if (!row) return [];
    this.validateIndex(row.value);
    const result = [];
    for (const [slotId, indexed] of Object.entries(row.value.slots)) {
      const slot = await this.read('pokemon-save-slots', this.slotPartition(identity, slotId));
      if (!slot) throw new RestoreError('slot_index_inconsistent');
      const record = this.assertRecord(slot.value, identity, slotId);
      await this.verifyGlobalReceiptReferences(record);
      if (record.metadata.contentHash !== indexed.contentHash || record.metadata.createdAt !== indexed.createdAt) {
        throw new RestoreError('slot_index_inconsistent');
      }
      result.push(this.publicMetadata(record));
    }
    return result.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.slotId.localeCompare(b.slotId));
  }

  async metadata(chat, player, slotId) {
    if (!validSlotId(slotId)) throw new RestoreError('invalid_slot_id');
    const identity = this.identity(chat, player);
    const index = await this.read('pokemon-save-slot-index', identity);
    if (!index) return null;
    this.validateIndex(index.value);
    const indexed = index.value.slots[slotId];
    if (!indexed) return null;
    const slot = await this.read('pokemon-save-slots', this.slotPartition(identity, slotId));
    if (!slot) throw new RestoreError('slot_index_inconsistent');
    const record = this.assertRecord(slot.value, identity, slotId);
    await this.verifyGlobalReceiptReferences(record);
    if (record.metadata.contentHash !== indexed.contentHash || record.metadata.createdAt !== indexed.createdAt) {
      throw new RestoreError('slot_index_inconsistent');
    }
    return this.publicMetadata(record);
  }
}

module.exports = {SaveSlotStore, FORMAT, MODULES};
