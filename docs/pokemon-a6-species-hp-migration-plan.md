# Pokémon A6 — Plano de migração Species-HP (futuro)

**Status:** proposta apenas — **NÃO executar**.  
**Base:** prep operacional 2026-11-08 · código `5ed39b0` · RC tip `6747bee`.  
**Auditoria RO:** 983 Pokémon, 100% `legacy-unset`, 0 com `hp-base` / `species-hp-v1`.

---

## 1. Pré-requisitos

1. Cutover das imagens species-hp já estável (WA READY, healthy, observação ≥20m).
2. Schema 035 aplicado **uma vez** com GO explícito (atributos `hp-base` / `hp-formula-version`).
3. Backup cold Datomic + cold sessão + overlay/flags **antes** de qualquer batch.
4. Janela sem raids em combate (auditoria: `active-raids-approx=0` no momento da prep; revalidar).
5. Batalhas wild/PvP/ginásio ativas: drain graceful ou esperar término (`active-battles` revalidar).

---

## 2. Backup

| Artefato | Ação |
|----------|------|
| Datomic hot→cold | `cp -a` volume `odisseu-a6-cutover-datomic` para evidência timestamped |
| Sessão WA | cold copy `.wwebjs_auth*` (sem tocar locks live) |
| Overlay/flags | snapshot compose + `printenv` Node/JVM |
| Checksums | `sha256sum` dos dumps |

Rollback de migração = restaurar cold Datomic **somente** se cutover de dados for revertido na mesma janela; nunca sobrescrever progresso pós-migração sem GO.

---

## 3. Lotes pequenos + idempotência

1. Selecionar até N Pokémon por TX (sugerido **25–50**), ordenados por `id-pokemon`.
2. Para cada entidade: `species-hp-migrate/plano-registro`.
   - `:noop` se já `species-hp-v1` → skip.
   - `:convert` → transact `hp`, `hp-atual`, `hp-base`, `hp-formula-version`.
3. Sem `hp-base` no registro: resolver via `species-base/base-hp` (catálogo); se espécie desconhecida → **quarantine list** (não inventar).
4. Retry: releitura; se versão já v1 → `:noop` (idempotente).
5. Não escrever em paths de leitura (`list-collection` / GET).

---

## 4. Política de HP

| Caso | Regra |
|------|-------|
| HP cheio | novo max; `hp-atual = hp-max` |
| HP parcial | `converter-proporcional` (razão preservada) |
| Desmaiado (`hp-atual=0`) | permanece **0**; max recalculado |
| Em combate / raid party | **excluir** do lote até leave/fim |

---

## 5. Proteção de raids / batalhas

1. Query RO: raids `lobby|combate|captura` e `battle/active?=true`.
2. Se >0: abortar lote ou filtrar `id-pokemon` presentes em snapshots.
3. Snapshots de combate usam HP do encontro — não reescrever mid-fight.

---

## 6. Falha e retomada

1. Cada lote = uma TX Datomic com receipt/`operation-id` opcional.
2. Logar `{batch-id, converted, noop, quarantine, errors}`.
3. Falha → não avançar cursor; reexecutar mesmo batch (idempotente).
4. Timeout → retry com backoff; nunca “force write” sem plano.

---

## 7. Verificação pós-migração

| Check | Esperado |
|-------|----------|
| `by-hp-formula-version` | quase tudo `species-hp-v1` |
| Quarantine | lista explícita; 0 writes inventados |
| Sample fainted | `hp-atual=0` |
| Sample partial | razão ≈ pré-migração (±1 por arredondamento) |
| Raid boss HP | continua 320 (fora do batch) |
| WA / containers | sem restart implícito |

---

## 8. Rollback sem apagar progresso

1. Preferir **forward-fix** quarantine + reprocess.
2. Rollback de volume só se a janela ainda não teve jogabilidade significativa **e** GO humano.
3. Não restaurar cold sobre saves que já receberam captura/XP pós-migração.

---

## 9. GO futuro

Este documento **não** autoriza execução. Exige GO separado de migração de dados após cutover de imagens.
