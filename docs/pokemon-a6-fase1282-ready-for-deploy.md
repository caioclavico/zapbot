# A6 — READY FOR DEPLOY · Release 12.8.2 (cadência uniforme)

**Decisão prep:** **READY FOR DEPLOY** · **sem deploy / sem PUT / sem retarget / sem migration**  
**Escopo:** cadência de raids automáticas (`distribution=uniform` opt-in) + correções `lastSpawnId` / lembretes  
**Commit:** `0c10ae9014eda4af673fba99a0a4dab92e706824`  
**Content hash:** `276deceffe6d5897f2e05542df09c07b5d3881e1cdbcd27dff002f841ef58636`  
**RC:** `/Users/caioclavico/odisseu-recovery/a6-fase1282-rc-20261103T013255Z/`  
**Referência:** `docs/pokemon-a6-fase1282-ready-for-go.md`

---

## 1. Commit da release

```
0c10ae9014eda4af673fba99a0a4dab92e706824
feat(a6): release 12.8.2 — cadência uniforme de raids (flag interval por padrão)
9 files · +1256/−184
```

Branch: `hotfix/a6-raid-capture-rewards-fase12.6`  
**Sem push obrigatório** · **sem deploy**.

---

## 2. Imagens RC (mesma revisão)

| | Tag | Digest (Id) |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase1282-20261103T013255Z-276deceffe6d-amd64` | `sha256:1af532ece8523c91101b5b7033683c93fd6347c33587881b9fe51dbda0bfc3a8` |
| Node | `odisseu-node:a6-fase1282-20261103T013255Z-276deceffe6d-amd64` | `sha256:2a3090349054d2b1c4e6ee00d5e961fe17096068e41f4a94ab363907a2029204` |

Labels (JVM=Node): `fase=12.8.2` · `git-sha=0c10ae9014ed…` · `content-hash=276deceffe6d…`  
Tars: `$RC/images/*.tar` · checksums: `$RC/checksums/IMAGES.sha256`

| Tar SHA-256 | Arquivo |
| --- | --- |
| `233ddc884157…6010` | `odisseu-jvm-a6-fase1282-…-amd64.tar` |
| `fea8a60aa65e…9b6a` | `odisseu-node-a6-fase1282-…-amd64.tar` |

---

## 3. Content hash

`276deceffe6d5897f2e05542df09c07b5d3881e1cdbcd27dff002f841ef58636`

Árvore: `deps.edn` + `package*` + Dockerfiles + `src` + `apps` + `shared` + `modules` + `migrations` + `assets` + scripts de patch/health.

---

## 4. Resultados de testes

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-auto-cadence-fase1282` | **PASS** — 65 tests / 356 assertions (12.8.2 + 12.7.x) |
| `clojure -M:test-fase1281` | **PASS** — 24 / 239 (eventos temáticos) |
| `clojure -M:test-fase128` | **PASS** — 0 fail (missões/Doces/captura + regressões) |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** — 15 / 75 |
| `clojure -M:test-a6-economy-capacity` | **PASS** — economy+capacity+idle |
| Simulação 7d uniform | **PASS** — 84 spawns / 12 por dia |
| Transição interval→uniform (meio do dia, cap, meia-noite, rollback) | **PASS** (suite 12.8.2) |

Logs: `$RC/tests/*.log`

---

## 5. Estado esperado após Deploy técnico (Fase A)

| Item | Valor |
| --- | --- |
| Imagens | tags 12.8.2 acima |
| `distribution` piloto | **`interval`** (legado — sem mudança de cadência) |
| `dailyLimit` / `specialReserve` | 12 / 2 (inalterados) |
| Festival dos Dragões | **permanece ativo** (occurrence intacta) |
| Migration | **nenhuma** nesta release |
| Demais grupos | inalterados (`enabled=false` / sem uniform) |
| Saves / economia / Doces | sem rewrite |

Comportamento funcional do scheduler de raids = 12.8.1 (avanço por `intervalMs`), com correções já ativas de `lastSpawnId` em skip e lembretes anti-fantasma (benefícios seguros sem uniform).

---

## 6. Procedimento exato — Fase B (ativação `uniform`)

**Somente após GO específico de ativação.** Não fazer no mesmo passo do deploy técnico.

### Pré-checagens

1. GET `/v1/raids/auto/schedule?chatId=120363427684548487@g.us`
2. Anotar: `revision`, `nextAt`, `lastSpawnId`, `lastRaidId`, `dailyLimit`, spawns do dia (via diagnostics / Datomic).
3. GET thematic active — Festival ainda `active`, `occurrenceId` estável.
4. Contar spawns auto do dia BRT (`N`). Se `N >= 12`, só agendar 1º slot do **dia seguinte**.

### Cálculo de `nextAt`

```
agora = now
slots = day-slots(hoje, window 8–23, limit 12)
nextAt = primeiro slot >= agora
         senão primeiro slot de amanhã (08:00)
```

Não usar slots passados. Não comprimir. Não resetar orçamento (spawns já abertos continuam contando no dia).

### PUT (piloto apenas)

```json
{
  "chatId": "120363427684548487@g.us",
  "enabled": true,
  "distribution": "uniform",
  "windowStart": 8,
  "windowEnd": 23,
  "timezone": "America/Sao_Paulo",
  "dailyLimit": 12,
  "specialReserve": 2,
  "safeCadence": true,
  "nextAt": <epoch_ms do próximo slot futuro>
}
```

### Pós-ativação

1. GET schedule → `distribution=uniform`, `diagnostics.slotsToday` length 12.  
2. Confirmar `nextAt` = slot futuro; `revision` incrementou.  
3. Observar **2–3 slots** naturais (sem force tick de spawn).  
4. Confirmar: sem 13ª raid; Festival occurrenceId igual; sem anúncio duplicado de Festival.  
5. Demais grupos: GET spot-check `enabled=false` / sem cadence uniform.

### Ativação no meio do dia (orçamento parcial)

Ex.: 14:20 BRT com 6 spawns já abertos → `nextAt=15:30`; restantes ≤ 6 slots; cap impede ultrapassar 12.

### Cap já cheio

`nextAt` = 08:00 BRT do dia seguinte (skip-daily-cap se due cair no mesmo dia).

### Próximo da meia-noite BRT

Após 21:45 → `nextAt` = 08:00 do dia seguinte.

---

## 7. Plano de rollback

### Rollback de imagens (Fase A)

Reverter compose para produção atual:

| Serviço | Tag atual (pré-12.8.2) |
| --- | --- |
| JVM | `odisseu-jvm:a6-fase1281b-20261102T211916Z-5297ada03618-amd64` (`fase=12.8.1-b`, `git=175ba59a…`) |
| Node | `odisseu-node:a6-fase1281-20261102T203340Z-2e1c78445cb6-amd64` |

Health + WA READY + Festival active + raid schedule intacto.

### Rollback de cadência (Fase B)

PUT com `safeCadence=true`:

```json
{
  "chatId": "120363427684548487@g.us",
  "distribution": "interval",
  "windowEnd": 22,
  "safeCadence": true
}
```

Preserva `nextAt` futuro e especial planejado.

---

## 8. Invariantes de saves e economia

| Invariante | Status |
| --- | --- |
| Saves `recovery-v1` | sem migration / sem rewrite |
| Writer fences | intactos |
| Economia `unknown-pending` + ledger | suite economy PASS |
| Doces / mochila diária | não tocados nesta release |
| Migration 032 | já aplicada; 12.8.2 não adiciona schema |
| `battle leave` / allowlist piloto | inalterados |

---

## 9. Compatibilidade com Festival dos Dragões

| Garantia | Evidência |
| --- | --- |
| Schedulers independentes | thematic tick ≠ raid-auto tick; suites 12.8.1 PASS |
| Sem restart nesta prep | sem deploy |
| Occurrence / Doces / missões | código de eventos não alterado no commit 12.8.2 |
| Deploy Fase A | não PUT thematic; não cancel; não re-announce |
| Ativação uniform | não mexe em `thematic-events/schedule` |

---

## 10. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Ativar uniform sem realinhar `nextAt` | procedimento §6 obriga próximo slot futuro |
| Cap no mesmo dia → silêncio até amanhã | esperado; documentado |
| Node RC com layers cache | labels/git/content-hash conferidos iguais ao JVM |
| Retarget operacional inconsistente | `safeCadence` + skip-outside-window / skip-missed |
| Confundir `lastSpawnId` legado | novos skips não sobrescrevem; legado pré-fix permanece |

---

## Plano operacional em duas fases

### Fase A — Deploy técnico (próximo GO)

1. Backups warm+cold.  
2. Sync RC tars → `docker load` → conferir digests/labels.  
3. Cutover JVM+Node → tags 12.8.2.  
4. Health / WA READY / Gemini/Groq.  
5. **Não** PUT schedule · **não** mudar Festival.  
6. Smoke: schedule ainda `interval` · Festival active · bag/leave ON.

### Fase B — Ativação uniform (GO separado)

Seguir §6. Observar 2–3 slots. Sem force spawn.

---

**Nenhum serviço foi reiniciado. Nenhum deploy/PUT/retarget foi executado nesta preparação.**
