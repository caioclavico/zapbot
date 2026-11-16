# Odisseu A6 — Relatório de integridade PR #96

**PR:** https://github.com/caioclavico/zapbot/pull/96  
**Branch:** `feat/a6-rpg-unified-ovos-pi-incubators`  
**Tip revisada:** `d2412c7` (sobre `6c0142c` + fixes)  
**Base:** `a6/live-baseline-8ef41c8` @ `4a721b0`  
**Quando:** 2026-11-16T19:10Z  
**Escopo:** revisão de diff, testes JVM/Node/PNG, idempotência PI/incubadoras/loja, ensaio RO Datomic isolado. **Sem merge/deploy deste tip.**

---

## Veredicto

| Gate | Resultado |
|------|-----------|
| Diff review + blockers corrigidos | **GO** (fix `d2412c7`) |
| Testes JVM domínio | **GO** — 45 tests / 294 asserts (egg+capture+pvp) |
| Testes Node + PNG | **GO** — 24/24 (egg-cards + PI + joy) |
| Idempotência PI / incubadora / preços loja | **GO** — `HARNESS_PASS` |
| Ensaio RO Datomic isolado | **GO** — `INTEGRITY_OK` |
| Live mutada por este PR | **NÃO** — live permanece tip `4a721b0` |
| READY merge #96 | **CONDICIONAL** — ver riscos residuais |
| READY deploy tip #96 | **NO-GO** até merge + cutover dedicado |

---

## Diff (resumo)

15 arquivos no commit feat + 9 no fix. Principais superfícies:

- `egg_activity.clj` — PI table v2, `game-day`, grants de incubadora, drain pending
- `shop_store.clj` / `shop.clj` — compra incubadora 200/400 sem mochila
- `egg-cards.cjs` / presenters — ovos = inventário; barras só em incubadoras
- `pokemon_http.cljs` — `resolveHatchEggIndex` para `!pk chocar N`

Já no tip live (fora deste PR): PvP ≤3, captura 62%/48%, PI em vitórias (#93–#95).

---

## Achados do review e correções

| Sev | Achado | Ação |
|-----|--------|------|
| Blocker | `!pk chocar N` usava índice de inventário | Fix: `resolveHatchEggIndex` |
| Blocker | `pending-incubator-grants` sem drain | Fix: `materialize-pending-incubators` + hook enrich/shop |
| Blocker | `suppress-wild-victory-pi?` zerava PI de captura | Fix: só anula `:wild-victory` |
| Major | Dia PI UTC→fuso jogo sem migração de `:pi-daily` legado | Residual — monitorar 1º dia pós-deploy |
| Major | Sem teste Datomic end-to-end de `shop-buy` incubadora | Residual — domínio coberto; CAS em ensaio de cutover |
| Minor | Bridge não expõe contagem de pending incubadoras | Residual UX |

---

## Testes

### JVM

```
egg-activity-a6 + egg-fixes-a6 + capture-rarity-frambo-a6
+ pvp-forced-switch-a6 + capture-m33
→ 45 tests, 294 assertions, 0 fail
```

### Node / PNG

```
pokemon-jvm-egg-cards + pi-victory + joy-eggs
→ 24 pass (Sharp PNG 640×820)
```

### Idempotência (harness)

- Replay `event-id` → PI 0 no 2º apply
- Captura com `suppress-wild-victory-pi?` → **15 PI**
- `try-grant-incubator` mesmo id → no-op
- Loja: extra **200**, especial **400**

---

## Ensaio Datomic isolado (RO)

| Campo | Valor |
|-------|--------|
| Método | `docker volume` cópia RO de `odisseu-a6-cutover-datomic` → `odisseu-a6-pr96-ro-datomic` |
| Imagem fingerprint | `odisseu-jvm:a6-rpg-tip-…-4a721b07629d-amd64` (live tip; **não** tip #96) |
| Rede | `none` |
| Resultado | `INTEGRITY_OK` |
| Contagens | pokemon **1138**, saves **86**, eggs **19**, coins **2498**, receipts **5507**, unversioned-resid-ge9 **1**, poke-level **2221**, lvl100 **3** |
| Fingerprint | `ace44dd6d7d2b6d5c100c68ca02fc384a385dfbc951f91ee2fc976498abfa9d9` |
| Evidência | `/home/caiohclavico/odisseu-recovery/a6-pr96-integrity-20261116T190912Z` |

**Nota:** o ensaio valida integridade do store live atual sob a tip **já implantada** (`4a721b0`). Não executa a tip #96 (`d2412c7`) em container — isso exige build+cutover após merge.

### Live (observação RO)

- JVM/Node: `a6-rpg-tip-…-4a721b07629d-amd64`, **healthy**
- WhatsApp: **READY**, chromium true
- Writer único: volume cutover (sem mutação neste ensaio)

---

## Riscos residuais antes do deploy #96

1. Build imagens a partir de `d2412c7` + ensaio isolado com **essa** tip (não só `4a721b0`).
2. Observar reset de `:pi-daily` no fuso `America/Sao_Paulo` na virada do 1º dia.
3. Teste de compra incubadora com CAS Datomic no cutover (domínio ok; store path novo).
4. Não reutilizar cold/fingerprint deste ensaio como baseline de cutover — gerar cold fresco no GO.

---

## Conclusão

**Integridade do store live (tip atual): PASS.**  
**PR #96 com fixes `d2412c7`: GO para merge condicional** (testes verdes + blockers fechados).  
**Deploy tip #96: NO-GO** até merge, build amd64 e cutover A6 com cold fresco.
