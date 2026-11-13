# Odisseu A6 — Reconciliação XP ambíguo → flat-9-v2

**Estado:** auditoria + implementação + ensaio isolado concluídos. **Sem backfill/merge/deploy em produção.**
**PR:** #86 · tip de trabalho sobre `60ee8d3` + commits desta reconciliação.
**Base live:** `5f3b716` (#85).
**CI tip anterior flat-9:** `43499558958` SUCCESS (pré-reconciliação).
**CI pós-reconciliação:** `43516693405` SUCCESS (head `ef4f568`; growth + classify). Commit final tip: `155cca7` (docs CI note).

## Contagem

| Momento | Ambíguos (unversioned residual ≥9) |
|---|---:|
| Ensaio anterior (repair-reh) | 19 |
| Live RO / cold atual (`20261113T215231Z`) | **18** |
| Após reconciliação no restore | **0** |

O 19º anterior (`Salamence`) já estava `growth-v1` na tip live (residual 1) — resolvido naturalmente pelo tip #85, sem esta reconciliação.

## Classificação histórica

Todos os **18** restantes: **B — residual oficial de growth-v1 persistido sem versão**.

Evidência objetiva por registro:

- Sem `xp-progress-version`.
- Residual bruto **estritamente menor** que `level-span(rate, nível)` da espécie.
- Residual ≥9 (logo não é flat-9 legítimo 0..8).
- Conversão proposta: `floor((raw/span)*9)` ∈ 0..8, **nível preservado**.

Nenhum registro ficou **UNRESOLVED** neste inventário (`unresolved 0`, `comprovado 18`).

Fingerprint do inventário pré-apply:
`e250d39265fc9b95384b670505ff9e35a0538575ce3d52044db32fafa280aeae`

## Matriz (resumo público)

| ID (curto) | Espécie | Nv | XP bruto | flat-9 | Confiança | Ação |
|---|---|---:|---:|---:|---|---|
| `0424fa9b-…` | Primarina | 4 | 23 | 5 | comprovado | Converter |
| `06b97c0b-…` | arcanine | 4 | 20 | 2 | comprovado | Converter |
| `26c67e61-…` | ducklett | 3 | 24 | 5 | comprovado | Converter |
| `2a9bb4ab-…` | Seismitoad | 2 | 28 | 5 | comprovado | Converter |
| `2f8c57cc-…` | Carkol | 5 | 26 | 5 | comprovado | Converter |
| `39ed98d8-…` | Greninja | 4 | 23 | 5 | comprovado | Converter |
| `4fe5edcf-…` | Staryu | 13 | 478 | 6 | comprovado | Converter |
| `54bc5eb2-…` | Quagsire | 3 | 22 | 5 | comprovado | Converter |
| `65b9bc87-…` | Calyrex | 2 | 13 | 5 | comprovado | Converter |
| `7dd125ba-…` | goodra | 3 | 41 | 7 | comprovado | Converter |
| `84f86044-…` | arcanine | 9 | 161 | 4 | comprovado | Converter |
| `def03dda-…` | Nidoqueen | 21 | 153 | 1 | comprovado | Converter |
| `e68c3724-…` | raichu | 11 | 352 | 7 | comprovado | Converter |
| `ea143a44-…` | Chinchou | 12 | 15 | 0 | comprovado | Converter |
| `f1078268-…` | hydreigon | 3 | 15 | 2 | comprovado | Converter |
| `f989fba9-…` | Shiftry | 4 | 23 | 5 | comprovado | Converter |
| `fae80690-…` | Iron-boulder | 3 | 28 | 5 | comprovado | Converter |
| `fe9fd2ab-…` | Torterra | 4 | 34 | 7 | comprovado | Converter |

Manifesto privado (IDs completos / recibos / evidência): fora do git em

- `/tmp/flat9-ambig-private-20261113T215231Z/reconciliation-manifest.edn`
- durable: `…/odisseu-recovery/a6-cold-live-private/flat9-ambig-private-20261113T215231Z/`

(Nota operacional: um audit pós-apply no mesmo `A6_PRIVATE_DIR` zerou o manifesto; reconstruído a partir de `01-audit.log` + recibos do apply. O script de audit agora **preserva** manifesto não-vazio quando o inventário pós-reconciliação é 0.)

## Implementação (PR #86)

- `growth/propose-flat9-from-official-residual`
- `growth/reconcile-ambiguous-entry` — CAS lógico de nível/residual, idempotente, nunca sobe nível
- `scripts/a6_flat9_ambiguous_audit.clj` — inventário + histórico RO (+ preservação de manifesto)
- `scripts/a6_flat9_ambiguous_reconcile_apply.clj` — aplica manifesto privado com recibo `flat9-ambig-recon:<id>`
- Fail-closed de grant/evolução para ambíguos **permanece** até o registro deixar de ser ambíguo

Valores reconstruídos **não** estão hardcoded no código-fonte.

## Ensaio isolado

| Item | Valor |
|---|---|
| Cold | `cold-flat9-ambig-20261113T215231Z` (verify OK, 44 360 731 logical bytes, 4 volumes) |
| Cold path | `…/a6-cold-live-private/cold-flat9-ambig-20261113T215231Z` |
| Cold `manifest.json` SHA-256 | `691377f0cf8e6333cbad6e99841c72eee18e42b3e51b3b829ed02d5577303565` |
| Restore | `odisseu-a6-restore-flat9ambig-20261113T215231Z-*` |
| Storage ensaio | `/tmp/flat9-ambig-reh-storage-20261113T215231Z` |
| Rede | `none` (sem WhatsApp / raid scheduler / writer live) |
| Dry-run | 18× `:dry-applied`, level-changes **0** |
| Apply | 18× `:applied`, level-changes **0** |
| Retry | 18× `:skipped` `:receipt-exists` |
| Ambíguos pós | **0** |

### Integridade antes → depois

| Métrica | Antes | Depois |
|---|---:|---:|
| Pokémon | 1082 | 1082 |
| Players / saves | 28 / 79 | 28 / 79 |
| poke-level Σ | 2231 | 2231 |
| coins | 3691 | 3691 |
| inv-qty | 137 | 137 |
| eggs | 7 | 7 |
| incubators | 0 | 0 |
| lvl100 | 5 | 5 |
| receipts | 4296 | 4314 (+18) |
| unversioned residual≥9 | 18 | **0** |
| datoms | 214954 | 215206 |

| Fingerprint | Valor |
|---|---|
| Pokémon antes | `25ca62673467afd0cfde1bd104fad5df0180a0fe18914a6abad8f90eb08af0a7` |
| Pokémon depois | `dbbf973258246a3920df61372a8858a9e6690925cea147fd4276103b2ce4e711` |

Diferença de XP: apenas residual dos 18 (bruto → flat-9 0..8); Σ níveis idêntico; fingerprint muda só por XP/versão.

### Reparados #85 (inalterados)

- Blastoise **74** (`growth-v1`)
- Tyranitar **90** (`growth-v1`)
- Crobat **5** (`growth-v1`)
- Amaura **4** (`growth-v1`)

### Gameplay no restore

- Staryu reconciliado (`flat-9-v2`, residual 6, legacy 478): vitória +3 → L14 / 0; derrota +1 → L14 / 1.
- Registro sintético UNRESOLVED: grant aborta com `:flat9-ambiguous-legacy-xp` sem mutação.
- Moedas inalteradas (3691).
- Resultado: `:GAMEPLAY_OK`

## Testes automatizados

- `clojure -M:test-a6-pokemon-growth` → **62 tests / 1124 assertions / 0 fail**
  (inclui reconcile idempotente + rejeição de drift)

## GO/NO-GO

| Gate | Status |
|---|---|
| Inventário live atual | GO (18) |
| Classificação com evidência | GO (18/18 B, 0 UNRESOLVED) |
| Reconciliação no restore | GO (18 applied) |
| Zero Δ nível | GO |
| Integridade economia/saves | GO |
| Recibos únicos + retry idempotente | GO |
| Fail-closed residual | GO |
| Código no PR #86 | GO (após push deste commit) |
| **Backfill live dos 18** | **NO-GO sem GO explícito** |
| **Merge/deploy PR #86** | **NO-GO sem GO explícito separado** |

## READY?

**READY para autorização separada** de:

1. merge/deploy do código flat-9-v2 + reconciler, e
2. execução do apply com manifesto privado em produção (janela drain/freeze).

Não executar (1) ou (2) sem GO explícito. Produção permanece no tip #85 até lá.
Live ainda contém os 18 ambíguos (somente o restore foi alterado).
