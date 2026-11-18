# A6 — READY FOR GO definitivo · Fase 12.7.1

**Decisão prep:** **READY FOR GO** (produção ainda em **12.7** — sem cutover)  
**Commit:** `12c16c33afdd94329859df9d10a294b3becc8a65`  
**Content hash:** `a66363a99af3be85723f94b66acbaffd1f81a53854f6140d963aed3f8083a52c`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase12.7.1-rc-20261102T033638Z/`  
**VM:** `odisseu-core` · produção **intacta** (imagens 12.7 ainda em execução)

---

## 1. Commit exclusivo

```
12c16c33afdd94329859df9d10a294b3becc8a65
feat(a6): lembretes ovo + lobby dinâmico + iniciar antecipado (12.7.1)
26 files · +1317/−66
```

Inclui: domínio/store/Node, 3 PNGs, Dockerfile COPY, testes, docs.  
**Sem** migration · **sem** mudança de allowlist · **sem** push obrigatório.

---

## 2. Imagens RC (mesma revisão)

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-raid-auto-1271-20261102T033638Z-a66363a99af3-amd64` | `sha256:6844e863c906553cfe2863dd196a4e53685e95c2812bfc043f90e06f76a9ef06` |
| Node | `odisseu-node:a6-raid-auto-1271-20261102T033638Z-a66363a99af3-amd64` | `sha256:b8d109e8c702612c66e714e28199cd5bea6624134716007a1bbbc2ee6be2db21` |

Labels: `io.zapbot.git-sha=12c16c3…` · `content-hash=a66363a99af3…` · `fase=12.7.1`  
Tars: `$RC/images/*.tar` + `$RC/checksums/IMAGES.*`

### Produção atual (inalterada)

| | Tag 12.7 |
| --- | --- |
| JVM | `…a6-raid-auto-127-20261102T025546Z-3f33e18e188d-amd64` (`sha256:1ffba740…`) |
| Node | `…a6-raid-auto-127-…` (`sha256:29fa2cded…`) |

---

## 3. Integridade dos PNGs na imagem Node

Extraídos de `/app/assets/` do RC Node (container create/cp — sem start do bot):

| Arquivo | Bytes | Magic |
| --- | ---: | --- |
| `raid-egg-30min.png` | 115175 | PNG_OK |
| `raid-egg-15min.png` | 114113 | PNG_OK |
| `raid-egg-5min.png` | 113932 | PNG_OK |

`Dockerfile` passa a fazer `COPY` explícito dos três assets.

---

## 4. Pipeline PNG + legenda (sem WhatsApp de produção)

`docker run --rm --entrypoint node <NODE_TAG>`:

```
RENDER_OK raid-reminder-30 … 135199
RENDER_OK raid-reminder-15 … 134174
RENDER_OK raid-reminder-5  … 133965
ENVELOPE_OK type=image mimeType=image/png caption=🥚… Faltam *30 minutos*
```

Não houve `sendMessage` para grupos reais. Fluxo = mesmo `renderRaidEggReminder` + caption usados por `entregar-evento-jvm!`.

---

## 5. Anti-retroativo (deploy entre T−30 e a raid)

Política no domínio (`reminder-late-grace-ms` = **2 min**):

- Se `agora − fireAt > 2 min` → **não enfileira** aquele offset  
- Ex.: cutover às 11:40 BRT → T−30 (11:30) descartado; T−15 (11:45) e T−5 (11:55) disparam normalmente  
- Evidência unitária em `plan-reminders-tres-janelas` + `$RC/meta/anti-retroactive-plan.txt`

---

## 6. Deduplicação / restart

- Outbox id `raid-reminder:{chat}:{nextAt}:{offsetMs}` (schema 030)  
- Existência (acked ou não) → `duplicate`  
- `enabled:false` / `nextAt` mudou → suppress sem WA  
- Testes restart + idempotência PASS

---

## 7. Scheduler piloto — invariantes

Antes e depois do build RC / warm backup (mesmo JSON):

```json
{
  "chatId": "120363427684548487@g.us",
  "enabled": true,
  "intervalMs": 21600000,
  "nextAt": 1793631600000,
  "revision": 1,
  "lastSpawnId": null,
  "lastRaidId": null
}
```

→ **12:00 BRT 2026-11-02** · 6 h · revision 1 · sem spawn forçado.

---

## 8. Lobby dinâmico + `!pk raide iniciar`

| Categoria | Lobby | Min | Max | Testes |
| --- | ---: | ---: | ---: | --- |
| raro | 5 | 1 | 5 | PASS |
| épico | 10 | 2 | 5 | PASS |
| lendário | 20 | 2 | 5 | PASS |
| mítico | 30 | 3 | 5 | PASS |

Início antecipado auto (qualquer inscrito, `criador nil`) + rejeição sem mín. + manual só criador — PASS.

---

## 9. Raids abertas / cutover

- Volumes Datomic **não** alterados pelo build RC  
- Cutover futuro = recreate JVM/Node com mesmos volumes → lobby/combate persistidos  
- Tick: expire → reminders → spawn; raid ativa bloqueia spawn (`skip-busy`)  
- Nenhum container 12.7.1 em execução na prep

---

## 10. Regressão reexecutada

| Suite | Resultado |
| --- | --- |
| `test-raid-auto-reminder-fase1271.sh` | **16/106 + 6 Node PASS** |
| `test-raid-auto-fase3.sh` | **19/81 PASS** |
| `test-raid-boss-fase127.sh` | **15/154 PASS** |

---

## 11. Backups e rollback

| Item | Valor |
| --- | --- |
| Warm | `/home/caiohclavico/odisseu-recovery/a6-periodic-backup-20261102T033638Z-warm1271` · manifest `c0447d8b…` |
| Cold | **Adiado** para o GO definitivo (evita stop do JVM agora) |
| Rollback 12.7 | tags/digests em `$RC/rollback/previous-versions.txt` |

---

## 12. Plano de implantação (após GO definitivo)

1. Cold backup (`A6_BACKUP_MODE=cold`)  
2. Patch compose → tags 12.7.1 · **não** mudar `nextAt`/env de allowlist  
3. Recreate JVM → healthy → recreate Node → WA READY  
4. Confirmar schedule idêntico (revision/`nextAt`)  
5. Smoke não destrutivo:  
   - PNGs no container Node  
   - GET schedule piloto only  
   - Sem force-tick · sem join/captura artificial  
   - Observar próximo offset natural (ou grace se cutover no meio)  
6. Rollback: `enabled:false` se crítico → imagens 12.7 se necessário · **não** apagar outbox/raids

### Não fazer no GO

- Migrations / backfills  
- `ALLOW_ALL_GROUPS=true`  
- `enabled` em outros grupos  
- Tick manual forçando spawn  

---

## 13. Riscos residuais

| Risco | Nota |
| --- | --- |
| Cold backup ainda pendente | Executar no GO definitivo |
| Cutover próximo de 11:30+ | Offsets perdidos >2 min não reenviam (intencional) |
| 1º smoke live de PNG no WA | Só após GO; pipeline RC já validado offline |

---

**READY FOR GO definitivo.** Produção permanece 12.7 até autorização explícita de cutover.
