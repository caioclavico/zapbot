# Odisseu A6 — Relatório de integridade PR #96

**PR:** https://github.com/caioclavico/zapbot/pull/96  
**Branch:** `feat/a6-rpg-unified-ovos-pi-incubators`  
**Commits validados:** `d2412c7` (fixes) · tip docs `18d1f66`  
**Base live:** `a6/live-baseline-8ef41c8` @ **`4a721b0`** (não alterada)  
**Revalidação:** 2026-11-16T19:27–19:31Z  
**Escopo:** blockers, suítes JVM/Node/PNG, idempotência/concorrência/restart/recovery, RO Datomic.  
**Proibido neste ciclo:** merge automático, deploy, migração, backfill, escrita no Datomic live, restart de containers.

---

## Veredicto final

| Decisão | Resultado |
|---------|-----------|
| **MERGE_GO** | **SIM** — blockers revalidados; suítes relevantes verdes; tip live preservada |
| **DEPLOY_GO** | **NÃO (`DEPLOY_NO_GO`)** — tip #96 sem imagem amd64; live ≠ tip PR; cold fresco + cutover obrigatórios |

### Justificativas

- Três blockers de `d2412c7` confirmados por harness + código + testes.
- Não existe suíte “294 testes”: o número **294 era `:pass` (asserções)**, não `(:test …)`.
- Fingerprint live **divergiu** do baseline `ace44dd6…` por gameplay contínuo em `4a721b0` (1143 Pokémon agora) — **não** por deploy #96.
- Residuais: sem E2E Datomic de `shop-buy` incubadora; migração de `:pi-daily` UTC→fuso do jogo não ensaiada.

---

## 1. Blockers (`d2412c7`)

| # | Requisito | Evidência | Status |
|---|-----------|-----------|--------|
| 1 | `chocar N` só no universo de ovos prontos; sem índice de inventário | `pokemon_http.cljs:731` → `resolveHatchEggIndex`; Node test `resolveHatchEggIndex só lista prontos`; harness índice size=2 | **PASS** |
| 2 | Drain incubadoras sem perda/duplicação | `materialize-pending-incubators` + teste `pending-incubator-drena-quando-ha-vaga`; harness drain once + 2ª pass 0 | **PASS** |
| 3 | PI de captura não zerado por `suppress-wild-victory-pi?` | `egg_activity.clj` só anula `:wild-victory`; teste `captura-nao-zera-pi-com-suppress-wild-victory`; harness PI=15 + replay 0 | **PASS** |

Comando blockers:

```bash
clojure -M:test -e '(… harness BLOCKERS_VERIFIED …)'
# log: /tmp/pr96-blockers.log → BLOCKERS_VERIFIED
```

---

## 2. Esclarecimento 45 vs 294

Relatório anterior dizia **“45 tests / 294 asserts”**.

| Interpretação errada | Correção |
|----------------------|----------|
| “294 testes aprovados” | **294 = asserções (`:pass`)**, não `(:test)` |
| “45 de 294 executados” | Foram **45 testes** (deftest) naquele lote; depois +1 teste → **46** |

Contagens reexecutadas nesta validação:

| Lote | Comando | `:test` | `:pass` (asserts) | fail/error |
|------|---------|--------:|------------------:|:----------:|
| A — 5 ns PR (egg+capture+pvp) | `clojure -M:test -e '(run-tests egg-activity egg-fixes capture-rarity pvp-forced-switch capture-m33)'` | **46** | **295** | 0/0 |
| B — alias canônico ovos | `clojure -M:test-a6-eggs-incubation` | **39** | **264** | 0/0 |
| Node+PNG | `node --test test/pokemon-jvm-egg-cards.test.cjs test/pokemon-jvm-pi-victory.test.cjs test/pokemon-jvm-joy-eggs.test.cjs` | **24** | — | 0 |

Namespaces do lote A (46 deftest):  
`egg_activity_a6` (12) + `egg_fixes_a6` (9) + `capture_rarity_frambo_a6` (5) + `pvp_forced_switch_a6` (4) + `capture_m33` (16) = **46**.

Namespaces do lote B (39):  
`egg_activity` + `egg_fixes` + `adventure_m37` + `joy_eggs_m410` (sem capture/pvp).

**Não executados neste ciclo (não contar como pass):**  
suítes amplas `:test-a6-lote-*`, `:test-m8-*`, `:test-a6-trainer-progression`, E2E `shop-buy` incubadora em Datomic, build/imagem tip `d2412c7`.

Logs: `/tmp/pr96-jvm-5ns.log`, `/tmp/pr96-jvm-eggs-full.log`, `/tmp/pr96-node-png.log`.

---

## 3. Idempotência / concorrência / restart / recovery

Comando: harness em `/tmp/pr96-idem-conc.log` → **`IDEM_CONC_RESTART_RECOVERY_PASS`**

| Cenário | Resultado |
|---------|----------|
| Replay mesmo `event-id` PI | 2º apply → 0 |
| Dois applies puros em paralelo no mesmo base | idênticos; após “commit” do 1º, 2º → 0 (CAS de recibo permanece obrigação do store) |
| Restart (serialize `:receipts`/shell + reapply) | 2º → 0 |
| Falha após defer incubadora + drain ao liberar slot | entrega 1×; 2º drain 0 |
| Preços loja extra/especial | 200 / 400 |

---

## 4. Integridade RO live (sem escrita)

Baseline anterior (ensaio 19:10Z): pokemon **1138**, fp **`ace44dd6d7d2b6d5c100c68ca02fc384a385dfbc951f91ee2fc976498abfa9d9`**.

Recheck RO (19:29Z), cópia descartável `odisseu-a6-pr96-ro2-datomic` (src `odisseu-a6-cutover-datomic:ro`):

```
INTEGRITY_OK {:pokemon 1143, :saves 86, :eggs 19, :coins 2508,
              :receipts 5513, :poke-level 2236, :lvl100 3,
              :unversioned-resid-ge9 1}
fingerprint a2e2fecef5ee34ce05cce083e71eb3e20fed29256005092025f981690e60e609
```

| Campo | Baseline 19:10Z | Recheck 19:29Z | Δ |
|-------|----------------:|---------------:|---|
| pokemon | 1138 | 1143 | +5 |
| coins | 2498 | 2508 | +10 |
| receipts | 5507 | 5513 | +6 |
| poke-level | 2221 | 2236 | +15 |
| fingerprint | ace44dd6… | a2e2fece… | mudou |

**Interpretação:** store internamente íntegro (`INTEGRITY_OK`). Drift vs baseline = **gameplay live na tip `4a721b0`**, não efeito do PR #96 (ainda não implantado). Live continua:

- `odisseu-jvm/node:a6-rpg-tip-…-4a721b07629d-amd64` **healthy**
- WA **READY**, chromium true  
- Evidência: `/home/caiohclavico/odisseu-recovery/a6-pr96-integrity-ro-20261116T192920Z`  
- Volume RO descartável removido após ensaio; **nenhuma escrita no volume live**.

---

## 5. Blockers / riscos residuais

| Item | Severidade | Notas |
|------|------------|-------|
| Sem imagem tip `d2412c7` / `18d1f66` | Deploy | Impede DEPLOY_GO |
| Sem E2E Datomic `shop-buy` incubadora | Major | Domínio ok; path store novo |
| `:pi-daily` UTC legado → `game-day` | Major | Monitorar 1º reset pós-deploy |
| Fingerprint live móvel | Ops | Cold fresco obrigatório no cutover |
| Concorrência real multi-writer | Residual | Domínio puro + CAS de recibo no store — não stress-testado com 2 JVMs |

---

## 6. Conclusão operacional

```
MERGE_GO
DEPLOY_NO_GO
live_tip=4a721b0 (preservada)
pr_tip=d2412c7 (+ docs 18d1f66)
```

Próximos passos **somente com autorização explícita**: merge #96 → build amd64 da tip mergeada → cutover A6 com cold fresco → novo fingerprint (não reutilizar `ace44dd6` nem `a2e2fece` como baseline de cutover).
