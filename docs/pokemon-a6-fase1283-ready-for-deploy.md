# A6 — READY FOR DEPLOY · Release 12.8.3 (PE/XP em derrota de raid)

**Decisão prep:** **READY FOR DEPLOY** · **sem deploy / sem PUT / sem migration / sem alteração em produção**  
**Escopo:** recompensas de participação (PE/XP) em raids derrotadas + copy WhatsApp  
**Base produção atual:** **12.8.2-b** (hotfix saves) · `distribution=uniform` piloto · Festival ativo  
**Commit release:** `19de29fe580b3e6f1b1bdcf90a66374a15450f05`  
**Content hash:** `54887d863f43c561899cf8604ba3c6bf60e9586eb3b38ce8f2ffb2daa73559c5`  
**RC:** `/Users/caioclavico/odisseu-recovery/a6-fase1283-rc-20261103T140611Z/`  
**Referência funcional:** `docs/pokemon-a6-fase1283-ready-for-go.md`

---

## 1. Baseline real de produção

Fonte: `odisseu-recovery/a6-fase1282b-saves-deploy-20261103T025225Z/DECISION.txt` (SUCCESS 2026-11-03T02:58:42Z).

| Item | Valor em produção |
|------|-------------------|
| Fase | **12.8.2-b** |
| Commit | `d153f1ff8e815f844ea198e11ccdc9dc7623c2c0` |
| Content hash | `167e7707e33edf19166bb7ca948812b5b9bab1704fbb82eebca2824d6b154993` |
| JVM | `odisseu-jvm:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` · `sha256:8d4a5d7e…558d` |
| Node | `odisseu-node:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` · `sha256:75192ac2…b4c6` |
| Schedule | `distribution=uniform` · rev **20** · janela 08–23 · `dailyLimit=12` · `specialReserve=2` |
| Festival | **active** · `…:festival-dragoes:1793654779033` · `disableOnEnd=true` |
| Health | JVM/Node healthy · WhatsApp READY |
| Migrations nesta sessão | nenhuma (032 já aplicada em 12.8.1) |

---

## 2. Confirmação hotfix 12.8.2-b na release

- `d153f1f` é **ancestral** de `19de29f` (release 12.8.3).
- Código de `display-name` / `public-save-label` / criação de slot permanece intacto.
- Nenhuma alteração de saves, fences, economia ou recovery-v1 nesta fase.

---

## 3. Commit da release

```
19de29fe580b3e6f1b1bdcf90a66374a15450f05
feat(a6): release 12.8.3 — PE/XP de participação em raids derrotadas
11 files · +846/−26
```

Branch: `hotfix/a6-raid-capture-rewards-fase12.6`  
**Sem push obrigatório** · **sem deploy**.

---

## 4. Imagens RC (mesma revisão)

| | Tag | Digest (Id) |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase1283-20261103T140611Z-54887d863f43-amd64` | `sha256:1ca1600364d413ec17765440b59dde392303f8942aaf8525126183413ed43a02` |
| Node | `odisseu-node:a6-fase1283-20261103T140611Z-54887d863f43-amd64` | `sha256:92a6d4f0f6f28469d1de468aae751258b0376d23f07aa7b5af854826cfbe3410` |

Labels (JVM=Node): `fase=12.8.3` · `git-sha=19de29fe580b…` · `content-hash=54887d863f43…`  
Arch: **amd64 / amd64**  
Tars: `$RC/images/*.tar` · checksums: `$RC/checksums/IMAGES.sha256`

| Tar SHA-256 | Arquivo |
| --- | --- |
| `1e34dd8bb9fc…c55f` | `odisseu-jvm-a6-fase1283-…-amd64.tar` |
| `ec43f0244761…c557` | `odisseu-node-a6-fase1283-…-amd64.tar` |

---

## 5. Content hash

`54887d863f43c561899cf8604ba3c6bf60e9586eb3b38ce8f2ffb2daa73559c5`

Árvore: `deps.edn` + `package*` + Dockerfiles + `src` + `apps` + `shared` + `modules` + `migrations` + `assets` + scripts de patch/health/lib.

---

## 6. Resultados de testes (local, Datomic descartável)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-raid-defeat-rewards-fase1283` | **PASS** 15 / 80 |
| `node --test` raid fase12 + raid + lobby | **PASS** 20 / 0 fail |
| `node --test test/pokemon-jvm-present.test.cjs` | **PASS** 20 / 0 fail |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** 15 / 75 |
| `clojure -M:test-raid-auto-cadence-fase1282` | **PASS** 65 / 356 |
| `clojure -M:test-fase1281` | **PASS** 24 / 239 |
| `clojure -M:test-fase128` | **PASS** 0 fail |
| `clojure -M:test-m2-trainer` (saves 12.8.2-b) | **PASS** |
| `clojure -M:test-raid-save-slots-reconcile` | **PASS** 3 / 13 |
| `clojure -M:test-a6-economy-capacity` | **PASS** economy+capacity+idle |

Logs: `$RC/tests/*.log`

### Recompensas revalidadas

| Caso | Moedas | PE | XP |
|------|--------|----|----|
| Vitória | 50 | 6 | 6 |
| Derrota elegível (`dano>0`) | 0 | 3 | 3 |
| Lobby / abandono / idle-timeout | 0 | 0 | 0 |

Captura só em `fase=vitoria`. Missões: `raid-participou` ≠ `raid-venceu`. WhatsApp usa DTO persistido.

### Idempotência

- `rewards-paid?` + recibo `raid-reward\|{raid-id}\|{player-id}`
- CAS `:raid/revision` + `:save/revision`
- Retry `operation-id` → `duplicate`
- Concorrência de dois ataques finais → 1 crédito
- Restart pós-derrota → sem reaplicar

---

## 7. Migration

**Não.** Schema 032 já em produção desde 12.8.1. Sem attrs novos. Sem backfill.

---

## 8. Plano de backup e rollback (somente no GO definitivo)

### Backup (antes do cutover)

1. Warm + cold via `scripts/a6-periodic-backup-a6.sh` (ou procedimento A6_PERIODIC_BACKUP vigente).
2. Registrar manifests SHA-256 no diretório de deploy (`meta/warm-manifest.txt`, `meta/cold-manifest.txt`).
3. Confirmar health JVM/Node + WA READY + schedule rev/distribution + Festival occurrence **antes** de trocar tags.

### Cutover (JVM-first → Node)

1. `docker load` dos tars RC · conferir digests/labels (`fase=12.8.3`, mesmo git-sha/content-hash).
2. Atualizar compose → tags 12.8.3.
3. Restart **JVM primeiro**; validar health `:18092` (ou porta vigente).
4. Restart **Node**; se race ECONNREFUSED, retry Node (padrão 12.8.2-b).
5. **Sem** PUT schedule · **sem** mexer Festival · **sem** migration.

### Rollback

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` | `sha256:8d4a5d7ef6c3a9dd035b05a1d2f63f2f44b35a23c6366f687b4fdf34609c558d` |
| Node | `odisseu-node:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` | `sha256:75192ac2cf208718e295163d8f7690568b12a081f5f9e6db6787e67d5b07b4c6` |

Compatível com saves/economia atuais. PE/XP já creditados em derrota **permanecem** (sem ledger de moedas a desfazer).

---

## 9. Proteções preservadas

- `recovery-v1`, fences, ponteiros ativos  
- Economia `unknown-pending` + ledger  
- Doces Pokémon / mochila diária  
- Scheduler `distribution=uniform`, `dailyLimit=12`, `specialReserve=2`  
- Festival + `disableOnEnd`  
- Allowlist piloto · Gemini/Groq  
- Probabilidades de captura / dificuldade de chefes **inalteradas**

---

## 10. Riscos residuais

| Risco | Mitigação |
|-------|-----------|
| Race Node↔JVM no restart | Cutover JVM-first (provado em 12.8.2-b) |
| Copy WhatsApp multiplayer | Mentions por participante; valores do DTO |
| Expectativa de moedas na derrota | Mensagem explícita `Moedas: 0` |
| Idle-timeout sem PE | Intencional (igual abandono) |

---

## 11. Smoke pós-deploy (sintético)

Identidade nova tipo `smoke-raid-defeat-1283@piloto` — **não** alterar saves reais.

1. Health JVM/Node + WA READY.  
2. GET schedule: `distribution=uniform`, rev estável (ou +0), limites 12/2.  
3. Festival: mesma `occurrenceId` (ou encerrado automaticamente se passou `fim`).  
4. Raid sintética frágil → **derrota** → PE=3, XP=3, moedas=0, mensagem derrota.  
5. Raid sintética → **vitória** → 50/6/6 + captura disponível.  
6. `!pk saves` / display-name intactos (regressão 12.8.2-b).  
7. Sem PUT · sem migration.

---

## 12. Veredito

**READY FOR DEPLOY** — artefatos RC + testes locais OK.  
**Deploy / migration / backfill / PUT / produção: bloqueados até GO DEFINITIVO explícito.**
