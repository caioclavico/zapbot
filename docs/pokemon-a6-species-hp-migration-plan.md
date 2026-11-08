# Pokémon A6 — Plano de migração Species-HP (futuro)

**Status:** proposta + ensaio descartável concluído — **NÃO executar em produção**.  
**Base:** código `5ed39b0` · imagens Node `57a828ca…` / JVM `e1bb8ac5…`.  
**Ensaio:** 2026-11-08T14:17Z · evidência local `artifacts/a6-species-hp-migrate-rehearsal/` · host audit `…/a6-species-hp-storage-audit-20261108T140316Z`.

## Auditoria RO atualizada (cópia Datomic, sem writes live)

| Métrica | Valor |
|---------|-------|
| Pokémon totais | **984** |
| `legacy-unset` | **984** (100%) |
| `species-hp-v1` / com `hp-base` | **0** / **0** |
| HP cheio / parcial / desmaiado | **952** / **12** / **20** |
| Raids ativas | **0** |
| Batalhas ativas | **0** |

---

## Resultados do ensaio (Datomic descartável)

| Item | Resultado |
|------|-----------|
| Dataset | export RO anonimizado da cópia do volume live (`984` registros) |
| Convertidos | **960** |
| Quarentena | **22** (formas/variantes fora do catálogo canônico) |
| Skip combate (injetados no ensaio) | **2** |
| Tempo lotes (40/reg) | **~0,28 s** |
| 2ª passagem | `convert=0` · idempotente |
| Retomada pós-falha simulada | **PASS** |
| Persistência amostra (30) | v1 + faint=0 + integridade stats **PASS** |
| Unit species-hp | **19/1337 PASS** |
| Raid boss HP | **320** preservado |

### Quarentena (agregado — sem PII)

Formas/variantes sem alias no catálogo (exemplo): `wishiwashi-solo`, `pyroar-male`, `gourgeist-average`, `tatsugiri-curly`, `frillish-male`, `mimikyu-disguised`, `indeedee-male`, `enamorus-incarnate`, `jellicent-male`, `lycanroc-midday`, `zygarde-50`, `pumpkaboo-average`, `darmanitan-standard`, `deoxys-normal`, `meowstic-male`, `minior-red-meteor`.

**Bloqueio residual:** antes da migração live, acrescentar aliases de forma no catálogo (sem inventar base).

---

## 1. Pré-requisitos (produção futura)

1. Cutover Node+JVM species-hp estável (WA READY, healthy, ≥20 m).
2. Schema **035** aplicado **uma vez** com GO explícito.
3. Aliases de forma para as 22 quarentenas (ou lista aceita de quarantine permanente).
4. Backup cold Datomic + sessão + overlay/flags **antes** dos lotes.
5. Revalidar `active-raids` / `active-battles` = 0 (ou exclusão confiável por `id-pokemon`).
6. Margem de disco ≥8–10 G (atingida em 2026-11-08 após limpeza `/tmp`; revalidar).

---

## 2. Backup

| Artefato | Ação |
|----------|------|
| Datomic hot→cold | `cp -a` volume `odisseu-a6-cutover-datomic` |
| Sessão WA | cold copy (sem locks live) |
| Overlay/flags | snapshot compose + env (sem secrets em logs) |
| Checksums | `sha256sum` |

Rollback de volume **somente** na mesma janela sem progresso pós-migração significativo + GO humano.

---

## 3. Lotes + idempotência

1. Lotes **25–50** (`migrar-lote` / `plano-seguro`).
2. Status: `:convert` · `:noop` · `:quarantine` · `:skip-combat`.
3. `:convert` inclui upgrade só de metadados (`hp-base` / `hp-formula-version`) quando números coincidem.
4. Retry → `:noop` se já `species-hp-v1`.
5. Nunca inventar `hp-base`.

---

## 4. Política de HP

| Caso | Regra |
|------|-------|
| Cheio | `hp-atual = hp-max` novo |
| Parcial | proporção preservada (±1 arredondamento) |
| Desmaiado | permanece **0** |
| Combate/raid | `:skip-combat` até leave/fim |

Fórmula (inalterável sem GO):  
`hp-max = round(hp-ref(n) × clamp((hp-base/80)^0.85, 0.60, 2.20))`

---

## 5. Proteção de combates

1. Query RO raids `lobby|combate|captura` + `battle/active?=true`.
2. Filtrar `id-pokemon` ocupados; se identificação não for confiável → **abortar** migração.
3. Snapshots de combate não são reescritos mid-fight.

No ensaio: identificação por flag injetada **PASS**; em produção exige wiring confiável save↔battle/raid.

---

## 6. Falha e retomada

Recibo por lote `{batch-id, offset, convert, noop, quarantine, skip-combat}`.  
Falha → não avançar cursor; reexecutar (idempotente). Ensaio de falha no 3º lote + retomada: **PASS**.

---

## 7. Verificação pós-migração

| Check | Esperado |
|-------|----------|
| Versões | quase tudo `species-hp-v1` + quarantine explícita |
| Fainted | `hp-atual=0` |
| Partial | razão ≈ pré |
| Owner/XP/golpes/nível | intactos |
| Raid boss | 320 |

---

## 8. Janela operacional futura (proposta — não executar)

1. Maintenance/drain → cutover Node+JVM species-hp → observe READY.  
2. Schema 035.  
3. Novos spawns já nascem em v1; legados ainda legados na leitura (sem conversão implícita).  
4. Batch migrate com freeze de combates.  
5. Verify + reopen.  
6. Evitar janela longa mista sem plano de leitura legado (já garantido: leitura não converte).

---

## 9. GO futuro

| Item | Veredito |
|------|----------|
| Ensaio descartável | **GO** (mecanismo validado) |
| Executar migração live agora | **NO-GO** |
| Pedir janela cutover+migração | **GO condicional** — aliases de forma + schema 035 + freeze combates + GO humano |

Este documento **não** autoriza cutover, deploy, schema live nem backfill.
