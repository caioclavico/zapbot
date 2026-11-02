# A6 — Deploy GO definitivo 12.7.3 (piloto)

**Decisão:** **SUCCESS**  
**Commit implantado:** `5dc963c95e3873d2145fce8b440ff2e90786de52`  
**Content hash:** `b1d8cf14d3fb6e630ea6903ec3848b294556015f461126b774f628c51b2e149d`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.3-rc-20261102T044514Z/`  
**Deploy artefatos:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.3-deploy-20261102T044514Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** `odisseu-core` (`35.192.113.146`)  
**Escopo:** planejamento antecipado de intent/ovos — raids automáticas **somente** piloto

---

## 1. Imagens em execução

| | Valor |
| --- | --- |
| Git | `5dc963c95e3873d2145fce8b440ff2e90786de52` |
| Content hash | `b1d8cf14d3fb…b2e149d` |
| JVM tag | `odisseu-jvm:a6-raid-auto-1273-20261102T044514Z-b1d8cf14d3fb-amd64` |
| JVM digest | `sha256:3a9f0872736273a04ead2f3295263ccdbdb916579842d7092534c20303e945ed` |
| Node tag | `odisseu-node:a6-raid-auto-1273-20261102T044514Z-b1d8cf14d3fb-amd64` |
| Node digest | `sha256:4ed96ce44883fa11d256dec005d71623e66693832cabd80242d957881d16f454` |
| Labels | `fase=12.7.3` · git-sha · content-hash OK |
| Migration | **Nenhuma** |

Rollback 12.7.2: JVM/Node `…1272-…a5b0020f28b2` · digests `9d60916a…` / `eedc32cc…` · compose.bak `docker-compose.a6-cutover-runtime.yml.bak-1273-20261102T044514Z`.

---

## 2. Backups

| Tipo | Path | Manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T044514Z-warm1273` | `e7e5d5b7…` |
| Cold | `…/a6-periodic-backup-20261102T044514Z-cold1273` | `86f87695…` |

Agenda pré-cutover: `$RC/meta/schedule-before-*.json` · intent ausente pré-deploy · cadence intacta.

---

## 3. Sequência

| UTC | Passo |
| --- | --- |
| `04:44Z` | Gates: commit `5dc963c` · testes 47/262 · PNG local |
| `04:45Z` | Sync archive + content-hash `b1d8cf14…` |
| `04:47–04:50Z` | Build RC JVM+Node · labels · tars · PNG_OK |
| `04:50Z` | Warm backup OK |
| `04:50–04:52Z` | Cold backup · schedule preservada (`nextAt` 01:00 CG) |
| `04:52–04:54Z` | Cutover compose → JVM/Node healthy · WA **READY** `04:54:05Z` |
| `04:54Z` | Tick planejou intent **mitico** (`raid_auto_intent_planned`) |
| `04:55Z` | Outbox T−5 enfileirado (T−30/T−15 já fora da grace) |

---

## 4. Scheduler efetivo (piloto)

```
chatId=120363427684548487@g.us
enabled=true
intervalMs=3600000
dailyLimit=12
specialReserve=2
nextAt=1793595600000        # 2026-11-02 01:00 CG / 02:00 BRT (PRESERVADO)
revision=3
nextCategoria=mitico        # planejado pós-cutover (12.7.3)
nextReminders=true
lastSpawnId=null            # sem spawn antecipado no smoke
```

- Tick: `expire → suppress → ensure intent → reminders → spawn`  
- Outro grupo (controle): `enabled:false`  
- Allowlist: só piloto · `ALLOW_ALL_GROUPS=false`  
- `POKEMON_BATTLE_LEAVE_ENABLED=true` · `allowTestRng=false` · ticks ON  

### Lembretes do slot atual

| Offset | Estado |
| --- | --- |
| T−30 / T−15 | omitidos (janela já passada + grace) — esperado no cutover tardio |
| T−5 | **enqueued** `raid-reminder-5` · legenda ovo sem spoiler |

---

## 5. Smoke pós-deploy

| Gate | Resultado |
| --- | --- |
| Containers healthy 12.7.3 | **PASS** |
| Labels git/fase/hash | **PASS** |
| WA AUTH + READY | **PASS** `04:54:05Z` |
| `nextAt` / cadence preservados | **PASS** |
| Intent antecipado (mitico) | **PASS** |
| Sem spawn prematuro | **PASS** |
| T−5 ovo sem spoiler | **PASS** |
| Demais grupos OFF | **PASS** |
| `raid-frambo` / join / start | **PASS** |
| Sem migrate / force tick | **PASS** |

Testes locais pré-deploy: `clojure -M:test-raid-auto-intent-fase1273` → 47 tests · 262 pass.

---

## 6. Riscos residuais

| Item | Nota |
| --- | --- |
| Cutover ~T−6 | T−30/T−15 deste slot não recuperáveis (by design) |
| 1º spawn ~01:00 CG | deve honrar `mitico`; intent seguinte gravado no spawn |
| Rollback 12.7.2 | intents 12.7.3 são leftover compatível (enrich por `next-at`) |

---

## 7. Não feito (fora do GO)

- Liberar scheduler global / outros grupos  
- Migration / backfill  
- Force tick / spawn manual  
- Alterar allowlist  
