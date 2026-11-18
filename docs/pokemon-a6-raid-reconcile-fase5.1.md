# A6 — Fase 5.1: Reconciliação segura raides × save-slots

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree isolado:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Base comum:** `1273fa4`  
**Worktrees originais preservados:**  
- `/Users/caioclavico/repos/zapbot` → `a6-save-slots` (146 dirty)  
- `/Users/caioclavico/repos/zapbot-raid-lobby` → `feature/a6-raid-readiness-fase5` (39 dirty)  

**Não feito:** deploy, migrate, backfill, merge em `master`, escrita remota, alteração de conteúdo dos WT originais.

---

## 1. Auditoria dos 12 BOTH-DIVERGED

Integração **explícita** via 3-way (`base` / `raid` / `save`) + resolução manual dos conflitos (sem `--theirs` / overwrite).

| # | Arquivo | Decisão |
| --- | --- | --- |
| 1 | `bridge_reads.clj` | Merge limpo: save-slots (`active-save`, resumos) + leituras de raid intactas |
| 2 | `bridge_tx.clj` | Merge limpo: `cancel-raid!` + `claim-bag-daily!` + `leave-battle!` |
| 3 | `ports.clj` | Merge limpo: ports de raid-auto/outbox **e** bag-daily/idle/leave |
| 4 | `domain/raid.clj` | **Manual:** lobby Fase 2 (`duracao=2min`, `boss-do-ginasio`, `expirar-inscricoes`) **+** `sair-combate` (save-slots) |
| 5 | `raid_store.clj` | **Manual:** requires raid-auto + idle/economy; attrs `origem` **e** idle; TX auto-spawn/outbox **e** rewards via economy ledger |
| 6 | `schema.clj` | **Manual:** cadeia `025→026→027→028→029→030` |
| 7 | `store.clj` | Merge limpo: tick/spawn auto + bag/leave/idle/economy |
| 8 | `http/bridge.clj` | Merge limpo: rotas `/v1/raids/auto`, `/v1/events` + bag/leave/idle |
| 9 | `pokemon-jvm-client.cjs` | Merge limpo: `cancelRaid`/`tickRaidAuto` + `leaveBattle` |
| 10 | `pokemon-jvm-present.cjs` | Merge limpo: formatadores de raide + save/bag |
| 11 | `config.cljs` | **Manual:** `POKEMON_RAID_AUTO_TICK*` + idle timeout + bag-kit flag |
| 12 | `pokemon_http.cljs` | **Manual (tail):** outbox unificado (`:texto` raide + `:text` idle) + tick raid-auto + leave/bag do save |

### Schemas / migrations

| ID | Origem | Papel |
| --- | --- | --- |
| 007 | comum | Motor M3.6/M4.9 (raid, participantes, captura, ponteiro) |
| 026 | save-slots | battle idle |
| 027 | save-slots | bag daily |
| 028 | save-slots | economy ledger |
| 029 | raides | lobby (`criada-em`, min/max) |
| 030 | raides | agenda auto + outbox + `origem` |

Ordem determinística no instalador: **026 → 027 → 028 → 029 → 030** (após 025).  
Deps: 029/030 não dependem de attrs 026–028; 026–028 não dependem de 029/030 — coexistência aditiva.

Além dos 12: ~134 paths **só save-slots** copiados; artefatos **só raide** (029/030, `raid_auto*`, testes Fase 2–4) preservados.

---

## 2. Testes executados

| Suite | Resultado |
| --- | --- |
| `test-raid-save-slots-reconcile` (3 / 13) | OK |
| `test-raid-lobby-fase2` | OK |
| `test-raid-auto-fase3` (19 / 76) | OK |
| `test-raid-homolog-fase4` (13 / 114) | OK |
| `test-a6-economy-capacity` (economy 9/37, capacity 9/72, idle 10/47) | OK |
| `test-m4-raid` | OK |
| `npm run build` | OK (`[:app] Build completed`) |

### Anti-interferência (novo)

`pokemon.tests.raid-save-slots-reconcile-test`:

1. `switch-save` no lobby não apaga ponteiro nem save carimbado na inscrição  
2. `switch-save` no combate → ataque com save errado falha; sem `rewards-paid`; moedas intactas  
3. `create-save-slot` + `rename-save` preservam lobby e recibo `raid-open`

---

## 3. Riscos residuais

- Código ainda **não commitado** nos três worktrees; consolidação só neste WT.  
- `switch-save` durante combate eleva fence → participante carimbado fica stale (comportamento existente M4.9; documentado no teste).  
- Outbox JVM unificado: eventos idle e raide compartilham poll; IDs/kinds distintos, mas falha de entrega de um tipo atrasa o ciclo.  
- Gaps históricos 026–028 vs branches de raide antigas: migrate live ainda **BLOCKED** até isolamento (Fase 5).  
- `scripts/lib/scripts/` (dir anômalo no save-slots) **não** copiado.

---

## 4. Comandos de verificação (locais)

```bash
cd /Users/caioclavico/repos/zapbot-raid-reconcile
./scripts/test-raid-save-slots-reconcile.sh
./scripts/test-raid-lobby-fase2.sh
./scripts/test-raid-auto-fase3.sh
./scripts/test-raid-homolog-fase4.sh
./scripts/test-a6-economy-capacity.sh
./scripts/test-m4-raid.sh
npm run build
```
