# A6 — Deploy GO definitivo 12.7.1 (piloto)

**Decisão:** **SUCCESS**  
**Commit implantado:** `12c16c33afdd94329859df9d10a294b3becc8a65`  
**Content hash:** `a66363a99af3be85723f94b66acbaffd1f81a53854f6140d963aed3f8083a52c`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.1-rc-20261102T033638Z/`  
**Deploy artefatos:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.1-deploy-20261102T033638Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** `odisseu-core` (`35.192.113.146`)  
**Escopo:** lembretes/lobby/`iniciar` — raids automáticas **somente** piloto

---

## 1. Imagens em execução

| | Valor |
| --- | --- |
| Git | `12c16c33afdd94329859df9d10a294b3becc8a65` |
| Content hash | `a66363a99af3…8083a52c` |
| JVM tag | `odisseu-jvm:a6-raid-auto-1271-20261102T033638Z-a66363a99af3-amd64` |
| JVM digest | `sha256:6844e863c906553cfe2863dd196a4e53685e95c2812bfc043f90e06f76a9ef06` |
| Node tag | `odisseu-node:a6-raid-auto-1271-20261102T033638Z-a66363a99af3-amd64` |
| Node digest | `sha256:b8d109e8c702612c66e714e28199cd5bea6624134716007a1bbbc2ee6be2db21` |
| Labels | `fase=12.7.1` · git-sha · content-hash OK |
| Migration | **Nenhuma** |

Rollback 12.7 registrado: JVM/Node `…127-…3f33e18e188d` · digests `1ffba740…` / `29fa2cded…`.

---

## 2. Backups

| Tipo | Path | Manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T033638Z-warm1271` | `c0447d8b…` (revalidado OK) |
| Cold | `…/a6-periodic-backup-20261102T033638Z-cold1271` | `d0bba1fb…` (pré-cutover) |

---

## 3. Sequência

| UTC | Passo |
| --- | --- |
| `03:44Z` | Pré-gates 12.7 + RC digests + PNGs PASS |
| `03:44–03:45Z` | Cold backup · JVM healthy · schedule intacto |
| `03:45Z` | Patch compose → tags 12.7.1 |
| `03:45–03:46Z` | Recreate JVM healthy |
| `03:46Z` | Recreate Node healthy · WA **READY** `03:46:49.574Z` |
| `03:47Z+` | Smoke: schedule/`nextAt`/outbox/PNGs/allowlist |

---

## 4. Scheduler / lembretes

```
enabled=true
intervalMs=21600000
nextAt=1793631600000 → 2026-11-02 12:00:00 BRT
revision=1
lastSpawnId=null
```

| Lembrete | Fire BRT | Status pós-deploy |
| --- | --- | --- |
| T−30 | 11:30 | futuro · ainda não due |
| T−15 | 11:45 | futuro |
| T−5 | 11:55 | futuro |

- Outbox pendente **vazia** após ≥1 tick (sem retroativo)  
- Grace 2 min ativa no domínio  
- Outro grupo controle: `enabled:false`  
- Allowlist: só `120363427684548487@g.us` · `ALLOW_ALL_GROUPS=false`

### PNGs no Node live

`/app/assets/raid-egg-{30,15,5}min.png` presentes · `RENDER_LIVE_OK` ×3 (sem send WA de teste).

---

## 5. Lobby / iniciar (código 12.7.1)

| Categoria | Lobby | Min | Max |
| --- | ---: | ---: | ---: |
| raro | 5 | 1 | 5 |
| épico | 10 | 2 | 5 |
| lendário | 20 | 2 | 5 |
| mítico | 30 | 3 | 5 |

`!pk raide iniciar` em auto: qualquer inscrito (`criador nil`); cheio ≠ auto-start. Coberto por suite pré-GO.

---

## 6. Smoke pós-deploy

| Gate | Resultado |
| --- | --- |
| Containers healthy 12.7.1 | **PASS** |
| WA READY | **PASS** `03:46:49Z` |
| `nextAt`/`revision`/6h preservados | **PASS** |
| Outbox sem duplicata/retroativo | **PASS** |
| PNGs + render | **PASS** |
| Demais grupos OFF | **PASS** |
| Leave ON · ticks ON · allowTestRng false | **PASS** |
| capability `raid-frambo` | **PASS** |
| Sem migrate | **PASS** |
| Sem force-tick / sem consumo artificial | **PASS** |

---

## 7. Invariantes

```
POKEMON_BATTLE_LEAVE_ENABLED=true
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
raidAutoTickEnabled=true
allowTestRng=false
schema 031 / recovery-v1 / fences — não alterados
```

---

## 8. Riscos / rollback

| Item | Nota |
| --- | --- |
| 1º lembrete live às 11:30 BRT | Observar PNG+caption no piloto |
| Rollback disponível | imagens 12.7 + compose.bak · **não acionado** |

Procedimento rollback: ticks off → imagens 12.7 → WA READY · **não** apagar outbox/raids.

---

## 9. Escopo

- Sem liberação global  
- Sem migration/backfill  
- Sem raids artificiais  
- GO 12.7.1 **piloto only** — SUCCESS
