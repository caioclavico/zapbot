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
| `clojure -M:test-raid-save-slots-reconcile` | ver log RC |

## 6. Artefatos RC

Preenchidos após build (seção abaixo / `$RC/meta/IMAGES.txt`).

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
