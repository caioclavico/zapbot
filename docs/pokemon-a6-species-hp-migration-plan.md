# Pokémon A6 — Plano de migração Species-HP (futuro)

**Status:** bloqueios de forma fechados em homologação local — **NÃO executar em produção**.  
**Base:** código species-hp + `species-form-aliases` · imagens candidatas Node `57a828ca…` / JVM `e1bb8ac5…` (RC anterior; nova RC após merge).  
**Ensaio v2:** 2026-11-08T14:26Z · `artifacts/a6-species-hp-migrate-rehearsal/`.

## Auditoria RO (dataset do ensaio)

Export anonimizado de cópia Datomic (sem writes live). Contagem **não** é garantia do live atual.

| Métrica | Valor |
|---------|-------|
| Pokémon no dataset | **984** |
| Antes | 100% `legacy-unset` |
| Raids/batalhas no export | 0 / 0 |

---

## Formas — quarentena resolvida

Aliases explícitos em `pokemon.domain.species-form-aliases` (sem strip heurístico).

| Resultado | Qtd |
|-----------|-----|
| Formas do ensaio resolvidas | **22/22** (16 identificadores) |
| Quarentena restante no ensaio | **0** |
| Formas com HP distinto (overrides, não alias) | `indeedee-female`, `zygarde-10/complete`, `gourgeist-{small,large,super}`, `pumpkaboo-{small,large,super}` |

Exemplos resolvidos via canônico: `wishiwashi-solo`→`wishiwashi`, `pyroar-male`→`pyroar`, `gourgeist-average`→`gourgeist`, …

---

## Ensaio v2 (Datomic descartável)

| Item | Resultado |
|------|-----------|
| Convertidos | **982** |
| Skip combate (injetados) | **2** |
| Quarentena | **0** |
| Idempotência 2ª passagem | `convert=0` |
| Retomada pós-falha | **PASS** |
| Persistência amostra | **PASS** · faint=0 · raid boss 320 |
| Unit species-hp | **22 tests / 1421 asserts PASS** |
| Schema 035 (descartável) | **PASS** (`:ok true`) |
| Freeze + maintenance-gate | **PASS** |

---

## Schema 035 (só descartável)

Arquivo: `migrations/pokemon/035-species-hp-formula.edn`

| Check | Resultado |
|-------|-----------|
| Attrs | `:pokemon/hp-base` (long) · `:pokemon/hp-formula-version` (string) |
| Cardinalidade | one |
| Índice / unique | **ausentes** (legado sem attrs OK) |
| Destrutivo | **não** (só add) |
| Reaplicação | idempotente via `:odisseu.schema/migration-id` |
| Produção | **NÃO aplicado** |

Smoke: `clojure -M:test-a6-species-hp-schema035`

---

## Freeze de combates

Módulo: `pokemon.domain.species-hp-freeze` + reuse do **maintenance-gate** existente.

1. `enter` → draining → quiescent (não mata TX em voo).  
2. Bridge `acquire!` falha → 503 `maintenance_active` (bloqueia hunt/raid/pvp/gym/evo/…).  
3. Classificação pura: `hp-mutating-op?` / `allowed-during-freeze?`.  
4. Revalidar RO: battles/raids ativas = 0.  
5. Schema 035 + batches.  
6. `resume` só após verify.

**Não** altera o gate de produção nesta tarefa. Smoke: `clojure -M:test-a6-species-hp-freeze` + `odisseu.smoke.maintenance-quiescence`.

---

## Compatibilidade de versões / rollback

| Estado | Leitura | Escrita nova |
|--------|---------|--------------|
| Pré-cutover (One-Mon) | legado; **sem** conversão na leitura | HP legado |
| Pós-cutover, pré-backfill | legado intacto; v1 se já marcado | v1 (Node+JVM species-hp) |
| Pós-backfill | v1 | v1 |
| Rollback de **código** para One-Mon | registros v1: attrs `hp-base`/`hp-formula-version` ignorados se código antigo não lê; **HP numérico permanece** — risco de inconsistência se fórmula antiga recalcular | evitar recalcular max sem checar versão |

Regras:

- Leitura **nunca** converte.  
- Backfill parcial: retomar lotes (idempotente); quarantine explícita.  
- Rollback de **dados**: cold Datomic só na mesma janela sem progresso significativo + GO.  
- Evitar cutover Node sem JVM (ou vice-versa) na janela species-hp.

---

## Pré-requisitos produção futura

1. Nova RC com aliases + freeze docs (após merge).  
2. Cutover Node+JVM estável ≥20 m.  
3. Schema 035 com GO.  
4. Freeze via maintenance-gate + zero combates ativos.  
5. Backup cold.  
6. Batches 25–50.

---

## GO futuro

| Item | Veredito |
|------|----------|
| Homologação local bloqueios | **GO** |
| Nova RC (build/pin) | **GO** (sem deploy) |
| Cutover / schema live / backfill | **NO-GO** até GO operacional |
