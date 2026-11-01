# A6 — Fase 12.6: Captura, Fruta Frambo e recompensas de raides

**Data (UTC):** 2026-11-01  
**Branch:** `hotfix/a6-raid-capture-rewards-fase12.6`  
**Modo:** correção + feature + testes + doc — **sem** deploy, restart, migrate live, backfill ou mutação de dados reais.

---

## Decisão: **CONDITIONAL**

| Gate | Resultado |
| --- | --- |
| Causa raiz Ultra Bola “sempre falha” | **PASS** (RNG; ver §2) |
| Fix `roll` ausente → `rand-int 100` | **PASS** |
| Coleção cheia bloqueia antes de gastar bola | **PASS** |
| Frambo prepare + consumo na tentativa | **PASS** (código + testes) |
| Migration `031-raid-capture-frambo` | **pendente em prod** (arquivo pronto; **não** aplicada) |
| XP Pokémon na vitória (`:id-pokemon` no slim) | **PASS** |
| PE + moedas idempotentes | **PASS** (já existiam; UX detalhada) |
| Suite `raid-capture-rewards-fase126` | **44/44 PASS** |
| Regressão slim 12.5 | **30/30 PASS** |
| Hotfix 12.3.1 ledger | **28/28 PASS** |
| present Node | **19/19 PASS** |
| Deploy / migrate live / auto-raides / allowlist | **não executado** |

**CONDITIONAL** porque Frambo em produção exige aplicar migration `031` no GO de deploy. O fix de captura (RNG + coleção cheia + XP id + UX) **não** depende de migration e pode ir no mesmo GO.

---

## 1. Diagnóstico da captura (Prioridade 1)

### Sintoma

`!pk raide capturar ultra-bola` falhava sempre; após 3 tentativas → `RAID_CAPTURE_UNAVAILABLE`. Parecia “Ultra Bola quebrada”.

### Causa raiz (comprovada)

| Camada | Comportamento |
| --- | --- |
| Node WhatsApp | `raidCapture` **sem** campo `roll` |
| Bridge | só aceita `roll` se `allowTestRng=true` (prod = false) |
| `build-capture-tx` | `(or (:roll request) **100**)` |
| Sucesso | `(< roll chance)` com chance ≤ 95 (ultra/raro = 70) |

`roll = 100` ⇒ **nunca** `< chance` ⇒ falha + bola consumida. Testes passavam só porque injetavam `:roll 0`.

**Não** era probabilidade da Ultra Bola — qualquer bola falhava em produção.

### Regras atuais (inalteradas nas taxas-base)

| Raridade chefe | Base % |
| --- | --- |
| raro (default) | 35 |
| lendario | 8 |
| mitico | 4 |

| Bola | Mult | Cap |
| --- | --- | --- |
| pokebola | ×1 | 75% |
| grande-bola | ×1.5 | 88% |
| ultra-bola | ×2 | 95% |

Ex.: raro + ultra = `min(95, 70) = 70%`.

Tentativas máx. **3**; janela **30 min** pós-vitória; elegível se `dano > 0`.

### Correção

```clojure
roll (long (or (:roll request) (rand-int 100)))  ;; espelha captura selvagem
```

+ checagem `collection_full` **antes** de debitar bola (paridade caça).

---

## 2. Fruta Frambo (Prioridade 3)

### Proposta (documentada antes da implementação)

| Parâmetro | Valor | Motivo |
| --- | --- | --- |
| Item | `fruta` (Fruta Frambo da loja — já existe) | Reuso; gym `potenciar` permanece motivação |
| Mult captura | **1.5** sobre chance pós-bola | Razz-like; raro+ultra 70→95 (cap) |
| Cap | limite da bola | Não ultrapassa 95/88/75 |
| Prepare | `!pk raide frambo` | Não consome ainda |
| Consumo | 1× na tentativa válida seguinte | Erro/validação não consome |
| Combinado | `!pk raide capturar ultra frambo` | Mesma TX |

### Persistência

- Attr `:raid-capture/frambo-preparada` (migration `031-raid-capture-frambo.edn`)
- Prepare valida estoque (`qty>=1`) sem debitar; captura debita `fruta` se bônus ativo
- Após tentativa (sucesso ou falha), flag volta a `false`

### HTTP / Node

- `POST /v1/raids/frambo`
- Capability `raid-frambo`
- Subcomandos: `frambo` / `framb` / `razz`

---

## 3. XP, PE e recompensas (Prioridade 2)

### Significado canônico A6

| Atributo | Significado |
| --- | --- |
| **PE** (`:save/pe-raids`) | Pontos de treinador (componente de `xp-treinador-total`) |
| **XP Pokémon** | XP do mon participante via `apply-pokemon-xp-tx` |
| **Moedas** | +50 ledger operacional (`source "raid"`) |

Vitória (já existente, por elegível `dano>0`): **+50 moedas · +6 PE · +6 XP Pokémon**.  
Captura do chefe: mon nv.1 na coleção — **sem** crédito econômico extra.

### Bug colateral (12.5 slim)

`slim-pokemon-for-battle` **dropava** `:id-pokemon` ⇒ liquidação não achava o mon vivo ⇒ **XP Pokémon = 0** na vitória.

**Fix:** preservar `:id-pokemon` no slim (UUID pequeno; não reabre value-too-large). Regressão 12.5 verde.

### UX WhatsApp

- Vitória lista recompensas confirmadas (`moedas` / `PE` / `XP Pokémon`) por `player-id`
- `!pk raide capturar` sem args → painel (chefe, tentativas, bolas, Frambo, chances)
- Distinção falha de captura vs erro técnico (`FEATURE_DISABLED` / `INSUFFICIENT_ITEM` / etc.)

---

## 4. Arquivos alterados

| Área | Arquivos |
| --- | --- |
| Domínio | `capture.clj`, `battle.clj` (id-pokemon), `ops.clj` |
| Store | `raid_store.clj`, `store.clj`, `schema.clj` |
| Migration | `031-raid-capture-frambo.edn` |
| Bridge | `bridge.clj`, `bridge_tx.clj`, `bridge_reads.clj`, `ports.clj`, `raid.clj` |
| Node | `pokemon_http.cljs`, `pokemon-jvm-client.cjs`, `pokemon-jvm-present.cjs` |
| Testes | `raid_capture_rewards_fase126_test.clj`, smoke, `deps.edn` |

---

## 5. Impacto econômico / segurança

- Ledger e recibos `raid-reward|…` inalterados na regra
- Captura: recibo `raid-capture`; Frambo prep: `raid-frambo-prep`
- Sem promoção de saldos `unknown-pending`
- Sem backfill de capturas antigas
- Slim join 12.5 preservado (+ id-pokemon)

---

## 6. Testes e evidências

```
clojure -M:test-raid-capture-rewards-fase126  → 44/44
clojure -M:test-raid-join-value-too-large-fase125 → 30/30
clojure -M:test-raid-hotfix-fase1231 → 28/28
node --test test/pokemon-jvm-present.test.cjs → 19/19
```

Cobertura: RNG default ≠100, ultra sucesso/falha, Frambo prepare/ausente/combinada, recompensas detalhadas, captura-ui.

---

## 7. Compatibilidade Datomic / migration

| Mudança | Migration? |
| --- | --- |
| RNG + collection_full + UX + id-pokemon slim | **Não** |
| `:raid-capture/frambo-preparada` | **Sim** — `031` |

Instalações novas (testes) aplicam `031` via `schema/install!`.  
Prod A6: aplicar `031` no GO **antes** ou **junto** do recreate JVM.

---

## 8. Plano de deploy / rollback (aguardando GO)

1. Backup warm A6.  
2. Aplicar migration `031` na JVM alvo (ou imagem que auto-instala schema no boot — confirmar runbook).  
3. Deploy imagens Node+JVM desta branch.  
4. Manter: leave ON, auto-raides OFF, piloto exclusivo, `ALLOW_ALL_GROUPS=false`.  
5. Gate: captura sem `roll` → `roll∈[0,99]`; prepare Frambo; vitória com XP>0.  
6. Rollback: imagens anteriores; attr `frambo-preparada` órfão é inofensivo se flag off.

**Não** corrigir inventários/capturas históricas automaticamente.

---

## 9. Relação com value-too-large (12.5)

Join continua com `encode-participant-pokemon-edn` slim+gzip.  
Única alteração no slim: manter `:id-pokemon`. Suite 12.5 permanece verde.
