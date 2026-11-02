# A6 — Deploy GO definitivo 12.7.2 (piloto)

**Decisão:** **SUCCESS**  
**Commit implantado:** `0d54e6c0863f86ddda2131a004226bb2c8a6f184`  
**Content hash:** `a5b0020f28b28c32fd3dd25a73ebf5b6a05408fa59e1b10987f9dd1140e56d71`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.2-rc-20261102T041756Z/`  
**Deploy artefatos:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.2-deploy-20261102T041756Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** `odisseu-core` (`35.192.113.146`)  
**Escopo:** cadência dinâmica + cap 12/dia — raids automáticas **somente** piloto

---

## 1. Imagens em execução

| | Valor |
| --- | --- |
| Git | `0d54e6c0863f86ddda2131a004226bb2c8a6f184` |
| Content hash | `a5b0020f28b2…0e56d71` |
| JVM tag | `odisseu-jvm:a6-raid-auto-1272-20261102T041756Z-a5b0020f28b2-amd64` |
| JVM digest | `sha256:9d60916a53ef62f4b2302efe53cf7b55d8211356bd42ef3f6a87dcd93b01242a` |
| Node tag | `odisseu-node:a6-raid-auto-1272-20261102T041756Z-a5b0020f28b2-amd64` |
| Node digest | `sha256:eedc32cccdc9c8b729ae065de8307c5a425ed9db07311046e862ab7925887d24` |
| Labels | `fase=12.7.2` · git-sha · content-hash OK |
| Migration | **Nenhuma** |

Rollback 12.7.1: JVM/Node `…1271-…a66363a99af3` · digests `6844e863…` / `b8d109e8…` · compose.bak `docker-compose.pre-1272.yml`.

---

## 2. Backups

| Tipo | Path | Manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T041756Z-warm1272` | `25c98fb0…` (revalidado OK) |
| Cold | `…/a6-periodic-backup-20261102T041756Z-cold1272` | `5de85756…` (revalidado OK) |

Agenda pré-cutover: `$RC/meta/schedule-before-rc.json` · pós-cold intacta · pós-PUT em `$DEPLOY/meta/schedule-*.json`.

---

## 3. Sequência

| UTC | Passo |
| --- | --- |
| `04:17Z` | Gates locais: SHA `0d54e6c` · testes 31/184 · PNG magic |
| `04:18Z` | Rsync + snapshot schedule 12.7.1 (`nextAt` 12:00 BRT · 6h · rev 1) |
| `04:19–04:21Z` | Build RC JVM+Node · labels · tars · PNG_OK |
| `04:22Z` | Warm backup OK |
| `04:22–04:23Z` | Cold backup · JVM healthy · schedule preservada |
| `04:23–04:24Z` | Patch compose → recreate JVM healthy → Node healthy |
| `04:24:32Z` | WhatsApp **READY** |
| `04:25Z` | `PUT safeCadence` piloto · smoke |

---

## 4. Scheduler efetivo (piloto)

```
chatId=120363427684548487@g.us
enabled=true
intervalMs=3600000          # 1h
dailyLimit=12
specialReserve=2
nextAt=1793631600000        # 2026-11-02 12:00:00 BRT (PRESERVADO)
revision=2
lastSpawnId=null
categoryIntervals=raro1h/epico3h/lendario12h/mitico24h
```

- **Sem** spawn imediato / retroativo / tick artificial  
- Outbox pendente **vazia**  
- Outro grupo (controle): `enabled:false`  
- Allowlist: só piloto · `ALLOW_ALL_GROUPS=false`

### Próximo evento conhecido

| Campo | Valor |
| --- | --- |
| `nextAt` | **2026-11-02 12:00 BRT** |
| Categoria planejada | ainda `null` (intent após 1º spawn; lembretes legado ≥3h **não** — interval já 1h → ovos só quando intent especial) |

---

## 5. Smoke pós-deploy

| Gate | Resultado |
| --- | --- |
| Containers healthy 12.7.2 | **PASS** |
| Labels git/fase/hash | **PASS** |
| WA READY | **PASS** `04:24:32Z` |
| `nextAt` preservado no PUT | **PASS** |
| Cap/dailyLimit/reserve | **PASS** |
| Outbox vazia / sem force-tick | **PASS** |
| PNGs + render live | **PASS** |
| Demais grupos OFF | **PASS** |
| Leave ON · ticks ON · allowTestRng false | **PASS** |
| capability `raid-frambo` | **PASS** |
| Sem migrate | **PASS** |

---

## 6. Invariantes

```
POKEMON_BATTLE_LEAVE_ENABLED=true
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
raidAutoTickEnabled=true
allowTestRng=false
schema 031 / recovery-v1 / fences — não alterados
```

---

## 7. Riscos / rollback

| Item | Nota |
| --- | --- |
| 1º spawn auto ~12:00 BRT | Observar categoria + ausência de ovo se não-especial |
| Intent `nextCategoria` | Preenchido após spawns; especiais ganham PNG |
| Rollback | imagens 12.7.1 + `safeCadence`/`intervalMs=21600000` · **não acionado** |

Procedimento: ticks off → imagens 12.7.1 → WA READY · **não** apagar outbox/raids.

---

## 8. Escopo

- Sem liberação global  
- Sem migration/backfill  
- Sem raids artificiais  
- GO 12.7.2 **piloto only** — **SUCCESS**
