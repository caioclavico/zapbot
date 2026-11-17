# Relatório — Migração controlada schema 028 (Odisseu Core)

**Quando:** 2026-10-31 ~06:09–06:14 UTC  
**Host:** `odisseu-core` · `35.192.113.146`  
**GO:** exclusivamente schema `pokemon/028-economy-ledger`  
**Não feito:** deploy de aplicação · backfill · Cassandra/aliases/grupos

---

## 1. Pré-migração

| Item | Resultado |
|------|-----------|
| Doc ledger | Revisado (`docs/pokemon-a6-4-economy-ledger.md`) |
| Atomicidade | 1 `transact` com attrs + `:odisseu.schema/migration-id` |
| Idempotência | Reaplica detecta `has-028?` → noop; dry-run pós-apply confirma |
| Concorrência | JVM writer parado durante apply (FileLock exclusivo) |
| App em produção | `odisseu-jvm/node:a64-leave-fullbag-20261030T200619Z-amd64` (inalterada) |
| Health pré | JVM/Node **healthy** · `recoveryCatalogReady=false` |

### Backup recuperável

| Item | Valor |
|------|-------|
| Cold | `~/odisseu-recovery/a64-028-cold-20261031T060914Z/` |
| Volumes | datomic + journal + maint (JVM down) |
| File count | **18** |
| Manifest-of-manifest | `baeb23523d5013ba5f3fcb70a55ee073a08c31f8ac7e368a008a5a50fa6683a5` |
| Restore check | datomic **17 = 17** em `…/a64-028-restore-check-20261031T060914Z/` |

Script: `scripts/a64_apply_028_schema_only.clj` (schema-only; sem backfill).

---

## 2. Migração

| Passo | Resultado |
|-------|-----------|
| Dry-run | OK (027 presente; 028 ausente) |
| Apply `028-economy-ledger.edn` | **OK-APPLIED** |
| Verify re-read | `has-028? true` · 28 migrations |
| Deploy app | **não** |
| Backfill / dual-write saves | **não** |

### Atributos instalados (15)

`:save/economy-status`, `:save/coins-operational`, `:save/coins-operational-earned`, `:save/coins-operational-spent`, `:save/coins-historical-status`, `:save/coins-historical-amount`, `:coin-ledger/key`, `:coin-ledger/save`, `:coin-ledger/operation-id`, `:coin-ledger/role`, `:coin-ledger/kind`, `:coin-ledger/source`, `:coin-ledger/amount`, `:coin-ledger/balance-after`, `:coin-ledger/at`

---

## 3. Integridade (before → after)

| Métrica | Antes | Depois |
|---------|-------|--------|
| players | 20 | 20 |
| saves | 59 | 59 |
| pokemon / id-pokemon | 1108 | 1108 |
| coins-sum (`:save/coins`) | 29 | 29 |
| economy-status entities | 0 | 0 |
| coins-operational entities | 0 | 0 |
| coin-ledger entities | 0 | 0 |
| migrations | 27 (até 027) | **28** (+028) |

Nenhum saldo alterado. Nenhum registro operacional criado. Saves intactos (só schema).

> Nota: 1108 é o total de entidades Pokémon no Datomic live (todos os slots/histórico). O recorte histórico **13 treinadores / 158 Pokémon** do lote recovery permanece como invariante de negócio; esta migração não tocou dados de coleção.

---

## 4. Pós-migração / app

| Check | Resultado |
|-------|-----------|
| JVM | **healthy** · mesma imagem leave-fullbag |
| Node | **healthy** · mesma imagem |
| Compatibilidade | Schema **aditivo**; app atual ignora attrs 028 → compatível |
| Anomalia prévia | 1º dry-run falhou (`:db.error/not-an-entity` ao contar attrs inexistentes). Script corrigido (`attr-exists?` antes do count). JVM religada; cold backup já existia **antes** de qualquer escrita de schema. 2ª tentativa OK. |

---

## 5. Recomendação

| Item | Veredito |
|------|----------|
| Schema 028 em produção | **Concluído com sucesso** |
| Deploy da aplicação (wiring ledger) | **NO-GO** até autorização explícita |
| Backfill trusted/recovery | **NO-GO** até autorização explícita |

Aguardo GO explícito para deploy da aplicação.
