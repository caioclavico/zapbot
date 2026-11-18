# A6 — READY FOR GO · Release 12.8.1 (eventos temáticos)

**Decisão prep:** **READY FOR GO** · **sem deploy / sem migration em produção / flag OFF**  
**Escopo:** scheduler + missões de evento + claim Doces + bias caça/clima + anúncios outbox + admin HTTP + migration 032  
**Commit:** `dc6ba2951c7925b2cdd67c13bdf511b642af9d88`  
**Content hash:** `2e1c78445cb6d2bda3597b7bb95aa7b768baedff17ac7b1b44a35fcac21c7362`  
**RC:** `/Users/caioclavico/odisseu-recovery/a6-fase1281-rc-20261102T203340Z/`  
**Rehearsal 032:** `/Users/caioclavico/odisseu-recovery/a6-fase1281-032-rehearsal-20261102T202917Z/`

Referência de implementação: `docs/pokemon-a6-fase1281-events-ready.md`

---

## 1. Commit exclusivo 12.8.1

```
dc6ba2951c7925b2cdd67c13bdf511b642af9d88
feat(a6): release 12.8.1 — eventos temáticos, missões, Doces e migration 032
27 files · +2162/−120
```

**Sem** push obrigatório · **sem** deploy · **sem** enable de agenda.

---

## 2. Imagens RC (mesma revisão)

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase1281-20261102T203340Z-2e1c78445cb6-amd64` | `sha256:04ba24f34bb0457a9d45c4383e6085a7145992250053ccf4744f848fee8a2d43` |
| Node | `odisseu-node:a6-fase1281-20261102T203340Z-2e1c78445cb6-amd64` | `sha256:e4473417fab76772d0ca98cc9d365c3d3ca63eb04b521d697ab0e0e8222ccc50` |

Labels: `fase=12.8.1` · `git-sha=dc6ba295…` · `content-hash=2e1c78445cb6…` (JVM=Node)  
Tars: `$RC/images/*.tar` · checksums: `$RC/checksums/IMAGES.sha256`  
Config inicial: `POKEMON_THEMATIC_EVENTS_ENABLED=false`

### Produção atual (inalterada)

12.8.0 piloto (`7d0325a` / digests fase128) permanece. Eventos OFF.

| Rollback 12.8.0 | Digest |
| --- | --- |
| JVM `…a46a1464586c-amd64` | `sha256:f56d6da5b1522130b4cc6c2f33f678178cfd7d85d96f6ecca033d757798c7157` |
| Node `…a46a1464586c-amd64` | `sha256:a5bba1600dfc45e779c94d247358105ece0239bcc0185cab66bad73dea1df098` |

---

## 3. Auditoria migration 032

| Critério | Resultado |
| --- | --- |
| Exclusivamente aditiva | **PASS** — só `db/ident` novos (schedule + occurrence) |
| Idempotente | **PASS** — reapply → `noop` / `OK_032_ADDITIVE_IDEMPOTENT_DATA_STABLE` |
| Sem destrutivo | **PASS** — zero retract/alter de attrs existentes |
| Compatível 031 | **PASS** — 031 permanece; 032 acrescenta |
| Saves `recovery-v1` | **PASS** — slots `a=21 b=21 recovery-v1=19` estáveis |
| Fences | **PASS** — `fence-digest` idêntico |
| Economia `unknown-pending` + ledger | **PASS** — status `{trusted 4, unknown-pending 1}`; coins/ops iguais |
| Doces (`professor-cards-edn`) | **PASS** — digest idêntico |
| Missões diárias/semanais/eventos EDN | **PASS** — digests idênticos |
| Sem rows runtime | **PASS** — `thematic-schedules=0` · `occurrences=0` · outbox=0 |

Script: `scripts/a6_032_thematic_rehearsal.clj`  
Fonte: cópia de `a6-fase12.6.1-postjvm-probe` (21 players / 61 saves / 1075 Pokémon).

---

## 4. Comparação before/after (descartável)

| Métrica | Before | After |
| --- | --- | --- |
| migrations | 31 (até 031) | 32 (+032) |
| players / saves / pokemon | 21 / 61 / 1075 | idem |
| coins-sum / operational | 274 / 244 | idem |
| recovery-v1 slots | 19 | 19 |
| professor-cards digest | `b082e1ee…fea4` | idem |
| missoes-*-digest | 3 digests | idem |
| fence-digest | `1e5ba4f2…1a20` | idem |
| thematic entities | 0 | 0 |

---

## 5. Testes

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-fase1281` | **PASS** — 23 tests / 222 assertions |
| `clojure -M:test-fase128` | **PASS** — 0 fail / 0 error (12.8.0 regressão) |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** — 15 / 75 |
| Rehearsal 032 apply+reapply | **PASS** |

Cobertura claim: concorrente mesmo `operation-id`, retry CAS, mochila cheia→pending, `unknown-pending` preservado, claim pós-encerramento, Doces família.

---

## 6. Isolamento flag OFF

| Controle | Evidência |
| --- | --- |
| `POKEMON_THEMATIC_EVENTS_ENABLED` default false | `thematic-events-enabled?=false` |
| `active-events` | `[]` |
| Tick | `{:note "thematic-events-flag-off"}` |
| Caça 3-args / bias | 35/65 clima/bioma intacto (teste fase128) |
| Agenda `enabled` default | `false` no ensure-schedule |

---

## 7. Plano de deploy (Etapa A — após GO definitivo)

1. Backups warm + cold do runtime live (`scripts/a6-periodic-backup-a6.sh`).  
2. Sync RC tars · `docker load` · conferir digests/labels = este relatório.  
3. Cutover compose JVM+Node → tags 12.8.1.  
4. Health JVM/Node · WA READY · Gemini/Groq.  
5. **Aplicar migration 032** (install! idempotente).  
6. Manter `POKEMON_THEMATIC_EVENTS_ENABLED=false`.  
7. Smoke: flag off · active=[] · schedule piloto intacto (`dailyLimit=12` `specialReserve=2`) · bag daily ON · leave ON · allowlist piloto.

**Não** habilitar agenda · **não** forçar Festival.

---

## 8. Rollback compatível com schema 032

1. Recreate imagens → tags 12.8.0 (`f56d6da5…` / `a5bba160…`).  
2. Flag permanece false.  
3. **Não** remover attrs 032 (aditivos; código 12.8.0 os ignora).  
4. Warm/cold da Etapa A disponíveis para restore extremo.

---

## 9. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Bias de caça desbalanceado | Etapa B com 1 evento curto; métricas |
| `raid-won` só no writer do golpe final | Aceito; missões de join cobrem participação |
| Admin HTTP na bridge | Loopback + bearer; sem exposição pública |
| Schema 032 sem down-migration | Rollback = código anterior; attrs órfãos ok |

---

## 10. Ativação do primeiro evento piloto (Etapa B — novo GO)

Grupo: `120363427684548487@g.us`

1. Confirmar Etapa A estável.  
2. `POKEMON_THEMATIC_EVENTS_ENABLED=true` só no JVM piloto.  
3. `PUT /v1/thematic-events/schedule` com `enabled=true`, `catalogId=festival-dragoes`, `nextAt=agora`.  
4. `POST /v1/thematic-events/tick` (ou poll Node).  
5. Validar anúncio start · bias dragão · `!pk missoes evento` · claim Doces · encerramento automático.  
6. Manter outros grupos `enabled=false`.

**Etapa C** (agenda recorrente / expansão): somente após novo GO.

---

**Nenhum serviço foi reiniciado. Nenhum deploy foi executado. Migration 032 não foi aplicada em produção.**
