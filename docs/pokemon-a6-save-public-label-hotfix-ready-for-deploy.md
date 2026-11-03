# A6 — READY FOR DEPLOY · Hotfix saves (nome público + `!pk novo save`)

**Decisão prep:** **READY FOR DEPLOY** · **sem deploy / sem migration / sem backfill / sem alteração em produção**  
**Escopo:** criação de save, renomeação de identificador público, listagem/seleção/`treinador`/coleção  
**Base produção:** 12.8.2 (Etapa A+B) · `distribution=uniform` piloto · Festival ativo  
**Fase RC:** `12.8.2-b`

---

## 1. Causas raiz corrigidas

1. **`!pk novo save`:** Node gerava `slot-<requestId>` com maiúsculas → `INVALID_SLOT_ID`.  
2. **Rename:** Datomic já gravava `display-name`, mas UI/seleção priorizavam `slot-id`; `save-profile` omitia `display-name`.

## 2. Modelo

| | Campo | Mutável |
| --- | --- | --- |
| ID interno | `:save/slot-id` | não |
| Nome público | `:save/display-name` | sim |

## 3. Comandos finais

| Comando | Efeito |
| --- | --- |
| `!pk saves` | Nomes públicos (✅ ativo / 📦 recuperado) |
| `!pk save` / `!pk save <nome\|n\|id>` | Ativo / selecionar |
| `!pk novo save [nome]` | Cria próximo slot; **não** troca ativo |
| `!pk renomear save <alvo> <novo>` | Só `display-name` |
| `!pk ativar <nome\|n\|id>` | Compatível |

## 4. Exemplos WhatsApp

```
📁 *SEUS SAVES*
✅ `aventura` — ativo _(id a)_
📦 `legado` — recuperado _(id recovery-v1)_
```

```
✅ Save criado: *aventura*.
Use `!pk save aventura` para selecionar.
(O save ativo não foi alterado.)
```

## 5. Testes (local, Datomic descartável)

| Suite | Resultado |
| --- | --- |
| `clojure -M:test-m2-trainer` | **PASS** 13 / 84 |
| `node --test test/pokemon-jvm-present.test.cjs` | **PASS** 20 / 0 fail |
| `clojure -M:test-raid-auto-cadence-fase1282` | **PASS** 65 / 356 |
| `clojure -M:test-fase1281` | **PASS** 24 / 239 |
| `clojure -M:test-fase128` | **PASS** 0 fail |
| `clojure -M:test-a6-economy-capacity` | **PASS** economy+capacity+idle |
| `clojure -M:test-raid-capture-rewards-fase126` | **PASS** 15 / 75 |
| `clojure -M:test-raid-save-slots-reconcile` | **PASS** 3 / 13 |

## 6. Artefatos RC

| | Valor |
| --- | --- |
| Commit código | `d153f1ff8e815f844ea198e11ccdc9dc7623c2c0` |
| Content hash | `167e7707e33edf19166bb7ca948812b5b9bab1704fbb82eebca2824d6b154993` |
| RC dir | `/Users/caioclavico/odisseu-recovery/a6-fase1282b-saves-rc-20261103T024704Z/` |
| JVM tag | `odisseu-jvm:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` |
| JVM digest | `sha256:8d4a5d7ef6c3a9dd035b05a1d2f63f2f44b35a23c6366f687b4fdf34609c558d` |
| Node tag | `odisseu-node:a6-fase1282b-20261103T024704Z-167e7707e33e-amd64` |
| Node digest | `sha256:75192ac2cf208718e295163d8f7690568b12a081f5f9e6db6787e67d5b07b4c6` |
| Arch | amd64 / amd64 |
| Labels | `fase=12.8.2-b` · mesmo git-sha/content-hash JVM=Node |

Tar SHA-256 (`$RC/checksums/IMAGES.sha256`):

| Tar | SHA-256 |
| --- | --- |
| JVM | `4aaf38515a5e8e21d3103da158abd70b83f0e30514626ae0c538379e21c1911d` |
| Node | `14147838d2b6ad1aefe0d2a000c77ac3b42a35d58034aad3a85f0488f6237075` |

Rollback produção atual (12.8.2):

| | Tag | Digest |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-fase1282-20261103T013255Z-276deceffe6d-amd64` | `sha256:1af532ece8523c91101b5b7033683c93fd6347c33587881b9fe51dbda0bfc3a8` |
| Node | `odisseu-node:a6-fase1282-20261103T013255Z-276deceffe6d-amd64` | `sha256:2a3090349054d2b1c4e6ee00d5e961fe17096068e41f4a94ab363907a2029204` |

## 7. Migration

**Não.** `:save/display-name` já existe (025). Sem backfill.

## 8. Plano de deploy (somente com GO definitivo)

1. Confirmar produção 12.8.2 healthy + WA READY + schedule rev/distribution intactos + Festival occurrence estável.  
2. Warm + cold backup A6.  
3. `docker load` JVM/Node RC · conferir digests/labels.  
4. Cutover compose → tags `12.8.2-b`.  
5. Restart Node se race JVM (padrão conhecido).  
6. Smoke: `!pk saves` / criar / renomear / selecionar por nome · **sem** PUT raid · **sem** mexer Festival.  
7. Confirmar `distribution=uniform`, rev schedule, occurrence Festival.

## 9. Rollback

Reverter compose para tags 12.8.2 autorizadas (`276deceffe6d…`).  
`display-name` eventualmente gravados permanecem (inofensivos no código antigo).  
**Não** rollback de imagem por bug só de UX sem evidência de corrupção.

## 10. Riscos residuais

| Risco | Mitigação |
| --- | --- |
| Jogador tenta selecionar por nome ambíguo | Resolução exige match único de display-name; slot-id tem prioridade |
| Create sem `!pk iniciar` | `PLAYER_NOT_FOUND` amigável |
| Race Node vs JVM no cutover | Restart Node após JVM healthy |

## 11. Proteções (não alterar no deploy)

Raid-auto uniform · Festival `…:1793654779033` · migration 032 · unknown-pending · Gemini/Groq · allowlist piloto · fences/recovery-v1

**Nenhum serviço foi reiniciado. Nenhum deploy/PUT/migration foi executado nesta preparação.**
