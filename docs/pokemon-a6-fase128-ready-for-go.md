# A6 — READY FOR GO · Release 12.8.0

**Decisão prep:** **READY FOR GO** → **cutover SUCCESS no piloto** (ver `docs/pokemon-a6-fase128-deploy.md`)  
**Escopo:** missões UX + recompensas diárias/semanais + Doces (rename) + clima PT + captura por raridade + Frambo caça + flag mochila preparada  
**Fora de escopo (→ 12.8.1):** ativação operacional de eventos temáticos / claim de Doces de evento  
**Commit:** `7d0325a0153e86a17f62eff9206f4bb106eb4733`  
**Content hash:** `a46a1464586cb53d9cf81e371fa3301256153bd9`  
**RC:** `/Users/caioclavico/odisseu-recovery/a6-fase128-rc-20261102T194042Z/`

---

## 1. Commit exclusivo 12.8.0

```
7d0325a0153e86a17f62eff9206f4bb106eb4733
feat(a6): release 12.8.0 — missões, Doces, clima, captura e Frambo na caça
39 files · +1803/−336
```

**Sem** migration · **sem** allowlist · **sem** push obrigatório · **sem** deploy.

---

## 2. Imagens RC (mesma revisão)

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase128-20261102T194042Z-a46a1464586c-amd64` | `sha256:f56d6da5b1522130b4cc6c2f33f678178cfd7d85d96f6ecca033d757798c7157` |
| Node | `odisseu-node:a6-fase128-20261102T194042Z-a46a1464586c-amd64` | `sha256:a5bba1600dfc45e779c94d247358105ece0239bcc0185cab66bad73dea1df098` |

Labels: `fase=12.8.0` · `git-sha=7d0325a0…` · `content-hash=a46a1464586c…` (JVM=Node)  
Tars: `$RC/images/*.tar` · checksums: `$RC/checksums/IMAGES.sha256`  
**Deploy:** não executado.

### Produção atual (inalterada)

Hotfix turno/ginásio/nível/IA (`cd03767` / imagens turn-gym-level-ai) permanece no piloto.

---

## 3. Flag mochila diária

| Item | Valor |
| --- | --- |
| Env | `POKEMON_BAG_KIT_DAILY_ENABLED` |
| Prep / exemplo | `false` |
| Processo | Node (`config/pokemon-bag-kit-daily-enabled?`) |
| Persistência | `bag-rewards` + Datomic; overflow → pending; 1×/dia |
| Live | **NÃO** ativada nesta etapa |

**Ativação no cutover (GO futuro):** `true` no `.env` Node piloto → recreate só Node → smoke `!pk mochila diario` 1× → 2ª chamada no mesmo dia nega.

---

## 4. Eventos — isolamento 12.8.0

| Controle | Evidência |
| --- | --- |
| 7 eventos com `inicio`/`fim` nil | `status-at` → `inactive` |
| `POKEMON_THEMATIC_EVENTS_ENABLED` default false | `active-events` → `[]` |
| `montar-plano` | aridade 3 (sem bias temático) |
| Claim Doces de evento | não wired (12.8.1) |
| Teste | `eventos-catalogo-tematico…` PASS |

Surto 6h legado preservado. Plano: `docs/pokemon-a6-fase1281-events-plan.md`.

---

## 5. Fórmula definitiva de captura (selvagem)

| Raridade | Chance base Pokébola |
| --- | --- |
| comum | 75–90 (~82) |
| incomum | 55–75 (~65) |
| raro | 30–50 (~40) |
| épico | 15–30 (~22) |
| lendário | 5–12 (~8) |
| mítico | 3–8 (~5) |

Pós-batalha: `round(status × espécie) + min(10, seq)` cap 95 (sem +25).  
Bolas ×1 / ×1,5 / ×2 · Frambo ×1,5 na próxima tentativa · caps 95.  
Texto = RNG. Raids: regressão 12.6 PASS.

---

## 6. Tabela real de recompensas (diárias/semanais)

| Missão | Recompensas base |
| --- | --- |
| Diária Explorador | 2 Poké + 1 Frambo + 2 PE |
| Diária Colecionador | 2 Super + 1 Frambo + 3 PE |
| Diária Desafiante | 1 Ultra + 1 cartão-presente + 3 PE |
| Diária Amigo | 2 Poké + 1 Frambo + 2 PE |
| Semanal Ginásios | 3 Super + 2 Frambo + 60 moedas |
| Semanal Tipos | 2 Ultra + 1 cartão-presente + 50 moedas |
| Semanal PvP | 2 Ultra + 1 cartão-presente + 60 moedas |
| Semanal Presentes | 3 Super + 2 Frambo + 50 moedas |

Mecanismos existentes. Doces = rename de cartões de família (professor).

---

## 7. Testes executados

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-fase128` | PASS |
| `clojure -M:test-m3-capture` | PASS |
| bag-kit-daily + bag-rewards | PASS |
| `clojure -M:test-a6-economy-capacity` | PASS |
| raid-capture-rewards-fase126 | PASS |
| raid-hotfix-fase1231 | PASS |
| Node missions/present/capture/hunt/battle-ui | PASS |
| Node raid hotfix + fase12 | PASS |

Datomic descartável: suites bag/capture/economy (`.datomic-local/…`).

---

## 8. Compatibilidade

Preservados: `recovery-v1`, fences, `unknown-pending`, ledger, migration 031, cooldowns, battle leave ON, raids auto (`dailyLimit=12`, `specialReserve=2`), lobbies/solo, ginásios por tipo, Frambo raid, PNGs, Gemini/Groq, allowlist piloto.

---

## 9. Cutover / rollback (após GO definitivo)

1. Backups warm/cold piloto (script `scripts/a6-periodic-backup-a6.sh` / runbook existente) — **não executados agora**.  
2. Aplicar tags RC no compose piloto (JVM+Node).  
3. Opcional: `POKEMON_BAG_KIT_DAILY_ENABLED=true`.  
4. Manter `POKEMON_THEMATIC_EVENTS_ENABLED=false`.  
5. Smoke: missoes, Doces, clima, caça+fruta, captura %, raids, mochila diario.  
6. Rollback: imagens `cd03767` / turn-gym-level-ai; flag diária → `false`.

---

## 10. Pendências 12.8.1

`docs/pokemon-a6-fase1281-events-plan.md`

---

**READY FOR GO 12.8.0.** Aguarda GO definitivo de cutover.  
**NÃO** deploy · **NÃO** flag live nesta etapa.
