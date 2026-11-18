# A6.4 — Relatório: importação V1 restante em produção

**Status:** concluído · **GO autorizado** · 2026-10-29T01:12Z  
Artefatos: `~/odisseu-recovery/a64-reconcile/a64-full-remaining-import-report.json`  
Backup frio pré-escrita: `a64-prod-cold-20261029T010508Z`

---

## Resumo

| Item | Resultado |
|------|-----------|
| Jogadores adicionais migrados | **6** |
| Pokémon recuperados | **942** em `recovery-v1` |
| Pendentes (hold humano) | **3** / **365** Pokémon (IDs ambíguos) |
| Idempotência (retry mesmo batch) | **6/6** → `:duplicate` |
| Integridade dos 13 anteriores | **OK** — **158** Pokémon em `recovery-v1` (39+119) |
| Ativação automática de `recovery-v1` | **não** (novos ficaram com `active=a`) |
| Economia | **hold** (moedas recovery = 0 placeholder; loja não importada) |
| Cassandra | sem escrita |
| Catálogo recovery pós-ops | desmontado (`recoveryCatalogReady=false`) |

---

## Migrados (6)

| player-hash | Pokémon | active após | retry |
|-------------|--------:|-------------|-------|
| `e6e1377fbb0b` | 272 | `a` | duplicate |
| `d4cc1ead79c0` | 187 | `a` | duplicate |
| `c96b30db3100` | 306 | `a` | duplicate |
| `1b7f43d000c9` | 118 | `a` | duplicate |
| `b61f6b3ba75e` | 38 | `a` | duplicate |
| `9e91b990f5a2` | 21 | `a` | duplicate |

`c96b30db3100` já estava onboardado com `a/b` vazios; `a/b` preservados; só `recovery-v1` criado.

Batch estável: `a64-full-remaining|<player-hash>|<chat-hash>`.

Jogadores ativam com `!pk save recovery-v1`.

---

## Pendentes (3) — não forçados

| player-hash | Pokémon | Motivo |
|-------------|--------:|--------|
| `e49260a4dcfb` | 70 | IDs ambíguos (10) |
| `c9761f45d7d2` | 50 | IDs ambíguos (4) |
| `37391862b553` | 245 | IDs ambíguos (29) |

---

## Integridade dos 13

- 12 do lote original: contagens `recovery-v1` inalteradas; ativos seguem `recovery-v1`.
- Usuário: `recovery-v1=119`, `a=1` (starter), ativo `recovery-v1`.
- Soma recovery dos 13: **158** antes e depois.

---

## Ops

1. Backup frio `odisseu-core-a56-*` com JVM parado.  
2. Imagem JVM `odisseu-jvm:a64-full-remaining-amd64` (patch `compact-origin-edn` + recibo pós-chunks).  
3. Dump RO `authorized-dump-full-remaining-candidates.edn` sha256 `4bafaa79…b7df92`.  
4. Import admin dos 6; sem ativar recovery.  
5. Catálogo desmontado; Node permanece healthy.

Sem migrations extras, sem alteração de grupos/Cassandra, sem rollback destrutivo.
