# M8.1 — Ensaio offline ponta a ponta do cutover

**Checkpoint base:** `3c7a015` (M8.0)  
**Escopo:** rehearsal descartável com dump Pokémon offline autorizado (M5.2–M5.5) + Platform sintético.  
**Fora de escopo:** produção, Oracle/GCP, Cassandra remoto, WhatsApp, deploy, cutover real, Platform real.

> **Migração real NÃO concluída.** `cutover-ready? = false`.

---

## 1. Resultado do ensaio

Runner: `./scripts/test-m8-1-rehearsal.sh` → `odisseu.smoke.m8-1-rehearsal`

| Leg | Storage | Fonte |
|---|---|---|
| Platform | `.datomic-local/m81-rehearsal-platform` | `fixtures/m8-0` (sintético) |
| Pokémon | `.datomic-local/m81-rehearsal-pokemon` | dump autorizado via `ODISSEU_M53_DUMP` |
| Failure gates | `.datomic-local/m81-failure-*` | sintético |

**Referências M5.3/M5.5 (`m53-real-offline`, `m55-real-offline`) não são reutilizadas nem modificadas.**

Artefatos privados (fora do Git): `$ODISSEU_M81_OUT` (default `~/odisseu-recovery/m8-1-private/`).  
Redatado público: `target/m8-1/report-redacted.edn`.

---

## 2. Equivalência

### Pokémon (dump histórico 14/10 — evidência, não produção atual)

| Critério | Esperado |
|---|---|
| Save A coleção | 19 |
| Save A Joy beds | 7 |
| Save A total IDs | 26 |
| Save B | 119 |
| Interseção A∩B | 0 |
| Moedas A / B | 198 / 437 |
| Voltorb shiny em B | 1 |
| Deadlines Joy | preservados |
| A→B→A | sem mistura |
| Schema 011/012 | `location`, `pronto-em`, `legado/origin-edn`, `enfermaria-bed/*` |
| ensure-trainer pré-import | **não** |
| gameplay / WhatsApp no import | **não** |
| Reimport mesmo batch | `:duplicate` |
| JVM reopen + backup/restore | equivalência mantida |

### Platform (8 chaves sintéticas)

`bloqueio`, `admins-conhecidos`, `enquetes`, `rank`, `quiz-historico`, `lembretes`, `bugs`, `participantes` — equivalência por domínio, isolamento multi-chat, reimport idempotente, interrupt/resume via journal. **Poller de lembretes não inicia.**

---

## 3. Tempos (ensaio local recente)

| Etapa | ms (aprox.) |
|---|---|
| Platform import | ~230 |
| Platform backup+restore | ~9 |
| Pokémon import (26+119) | ~190 |
| Pokémon backup | ~2 |
| Pokémon restore | ~1 |
| Total ensaio | ~5,2 s |

Valores exatos em `$ODISSEU_M81_OUT/REPORT-M81.edn` e `target/m8-1/report-redacted.edn`.

---

## 4. Falhas exercitadas (gates)

| Gate | Comportamento esperado |
|---|---|
| Checksum inválido | `verify-checksums!` lança |
| Meta incompleta | `load-dump!` recusa |
| Colisão de identidade | `block-import?` |
| Batch parcial | resume → `:duplicate` (não reaplica) |
| Reimportação | `:duplicate` |
| Destino não vazio | `import_player_already_exists` |
| Divergência de equivalência | impede go |
| Abort pré-writer | `datomic-writer-activated? = false` |

Qualquer falha de gate → **ativação impedida**.

---

## 5. Estado dos gates de cutover

Checklist M8.0 (`rollback/evaluate-gates`) permanece **no-go** enquanto:

- WhatsApp autenticado em staging não validado
- Snapshot de produção atualizado pós-freeze ausente
- Reconciliação pós-escrita Datomic não comprovada
- Autorização explícita ausente

`cutover-ready?` forçado `false` mesmo com ensaio ok.

---

## 6. Snapshot histórico vs migração final futura

| | Histórico 14/10 (este ensaio) | Cutover futuro |
|---|---|---|
| Papel | Evidência / compatibilidade | Estado atual pós-freeze |
| Platform | **Não** importado do real | Snapshot Cassandra atual |
| Pokémon | Dump offline autorizado | Novo export pós-quiescência |
| Writers | N/A (offline) | Freeze legado → writer Datomic único |
| Rollback | Drill em storage descartável | Exige reconciliação comprovada |

Não tratar o backup de 14/10 como substituto do estado atual de produção.

---

## 7. Bloqueios restantes para produção

1. Staging com WhatsApp autenticado.
2. Freeze de writers + snapshot Cassandra atual.
3. Autorização humana para matching/proveniência ambígua.
4. Mecanismo de reconciliação pós-escrita (senão rollback inseguro).
5. Decisão sobre journals de restauração / ginásio WIP (track separado).
6. Flags/volumes/deploy — fora deste marco.

---

## Auditoria de release

- WIP ginásio (`store.clj`, `gym_m48_test.clj`) permanece separado.
- Staging: `cassandra-boot-enabled?` desliga C* com `PLATFORM_ADAPTER=datomic` + `POKEMON_ADAPTER=jvm`.
- Produção força adapters legados.
- Nenhum dado privado no Git (dump/relatório detalhado só em `odisseu-recovery`).

## Compatibilidade M5.5 ↔ M8.0

- IR Pokémon M5 (`transform` / `import-ir!` / receipts `m50-import|batch|save-key`) reutilizado.
- Joy materializada como `enfermaria-bed` (migrations `011`/`012`).
- Platform IR M7 (`export-modules` / `import-ir!`) + journal M8 (`m80-platform|batch`).
- Quiescência multi-partição Cassandra continua documentada (não atômica).
