# Pokémon A6 — Plano de migração Species-HP (futuro)

**Status:** preflight final **GO** para pedir janela — **NÃO executar em produção sem autorização**.  
**Base executável:** `72bffdb63be62d8b35e61bcc75c0436949b6f189` · tree `8b793ae169ace916ec37c0b7350ce68e8404d3fa`  
**Imagens candidatas (Final):** Node `sha256:17520a1ec6fcb88687c2f208b8cd276c384944f01cde57a3b0793c217532e2a0` · JVM `sha256:c13140fa9965b8cb78faa7edc1d95a95a5abc3ed1287203554e65748e52683ee`  
**Proibido:** reutilizar `57a828ca…` / `e1bb8ac5…` (pré-aliases).  
**Preflight:** `/home/caiohclavico/odisseu-recovery/a6-species-hp-final-preflight-20261108T150846Z`  
**Prep host:** `/home/caiohclavico/odisseu-recovery/a6-species-hp-final-prep-20261108T145105Z` · inbox `…/rc-inbox/a6-species-hp-final-72bffdb/`  
**RO live (2026-11-08T15:11Z):** **986** Pokémon · 100% `legacy-unset` · v1=0 · fainted=20 · partial=14 · full=952 · raids=0 · battles=0.  
**GitHub:** hold **true** · `deploy.yml` **`disabled_manually`** · in_progress=0.  
**Disco:** ~8.55 G livres · pico estimado janela ~3.5 G · **suficiente**.

## Auditoria RO (dataset do ensaio)

Export anonimizado de cópia Datomic (sem writes live). Contagem **não** é garantia do live atual.

| Métrica | Valor |
|---------|-------|
| Pokémon no dataset | **984** |
| Antes | 100% `legacy-unset` |
| Raids/batalhas no export | 0 / 0 (ensaio injeta 2 skip-combat) |

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

## Ensaio final (Datomic descartável @ `72bffdb`)

| Item | Resultado |
|------|-----------|
| Schema 035 | **PASS** (aditivo; idempotente; legado sem attrs OK) |
| Convertidos | **982** |
| Skip combate | **2** |
| Quarentena | **0** |
| Idempotência 2ª passagem | `convert=0` · `noop=982` · quarantine=0 |
| Retomada pós-interrupção | **PASS** (partial 78 → resumed 904 → second noop 982) |
| Persistência amostra | **PASS** · 30/30 v1 · integrity-ok · fainted-remain-zero · raid boss **320** |
| Após | full 952 · partial 12 · fainted 20 · `legacy-unset` 2 (skip-combat) |
| Unit species-hp | **22 tests / 1421 asserts PASS** |
| Freeze + maintenance-gate | **PASS** |

---

## Schema 035 (só descartável)

Arquivo: `migrations/pokemon/035-species-hp-formula.edn` (presente na imagem JVM Final).

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

**Não** altera o gate de produção nesta tarefa. Smoke: `clojure -M:test-a6-species-hp-freeze`.

---

## Compatibilidade de versões / rollback

| Estado | Leitura | Escrita nova |
|--------|---------|--------------|
| Pré-cutover (One-Mon) | legado; **sem** conversão na leitura | HP legado |
| Pós-cutover, pré-backfill | legado intacto; v1 se já marcado | v1 (Node+JVM species-hp) |
| Pós-backfill | v1 | v1 |
| Rollback de **código** para One-Mon | registros v1: attrs `hp-base`/`hp-formula-version` ignorados se código antigo não lê; **HP numérico permanece** — risco de inconsistência se fórmula antiga recalcular | evitar recalcular max sem checar versão |

### Cenários de falha (ensaio descartável — homologados)

| Cenário | Estratégia | One-Mon rollback? |
|---------|------------|-------------------|
| **A** antes do 1º lote | Repin overlay One-Mon + compose | **Sim** |
| **B** mid-backfill | Restaurar JVM Species-HP + retomar lotes (`noop` nos já v1) | **Não** preferencial — forward-fix |
| **C** pós-backfill completo | Manter/restaurar Species-HP; cold só com GO explícito | **Não** assumir seguro |

Regras:

- Leitura **nunca** converte.  
- Backfill parcial: retomar lotes (idempotente); quarantine explícita.  
- **Nunca** restaurar cold antigo automaticamente sobre progresso novo.  
- Evitar cutover Node sem JVM (ou vice-versa).  
- Rollback de **imagens** pré-lote: Node `617525d9…` / JVM `4a75eaa2…`.  
- Freeze operacional = **maintenance-gate** (não só a checklist `species-hp-freeze`).

---

## Pré-requisitos produção futura

1. RC Final carregada no host (`72bffdb` / Image IDs acima) + VERIFY.  
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
| RC Final (build/pin @ `72bffdb`) | **GO** (sem deploy) |
| Prep operacional host | **GO** (feito) |
| Preflight final (hold/CI/disco/RO/freeze/ensaio) | **GO** |
| Pedir janela cutover+migração | **GO** (autorização humana) |
| Cutover / schema live / backfill | **NO-GO** até GO operacional da janela |
