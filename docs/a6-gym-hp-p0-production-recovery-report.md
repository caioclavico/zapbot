# Relatório P0 — contenção, hotfix e reparação seletiva de HP (ginásios A6)

**Status:** SUCCESS  
**Desafios de ginásio:** **OFF** (`POKEMON_GYM_CHALLENGES_ENABLED=false`) — **sem reabertura**  
**Recompensas de permanência:** **OFF**  
**Operação (UTC):** 2026-11-06 ~09:51Z → ~10:25Z  
**Host:** `odisseu-core` / `35.192.113.146` / arch `x86_64` (AMD64)

Evidências host:  
`/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/gym-hp-p0-20261106T095130Z/`

---

## 1. Resultado executivo

| Critério | Resultado |
| --- | --- |
| Fluxo defeituoso bloqueado | OK — gate `gym_challenges_disabled` (HTTP 422) com maintenance `open` |
| Hotfix Node+JVM implantado | OK — tree `bf8f5c66e492…` |
| Backup cold + restore isolado | OK — schema 034 presente |
| 94 HPs elegíveis reparados | OK — zeros 96→2; 2 zeros legítimos preservados |
| Atributos não autorizados | OK — sem alteração de nível/espécie/moedas; 1 alerta XP histórico (falso positivo, ver §7) |
| Serviços saudáveis | OK — Node/JVM healthy · WA READY · bridge up · raids ON |
| Desafios permanecem OFF | OK |
| Treinador ambíguo | Intacto (fora do lote) |

---

## 2. Contenção

Runtime vulnerável **não** tinha `POKEMON_GYM_CHALLENGES_ENABLED`. Contenção imediata via **maintenance quiescence** (Node+JVM), bloqueando mutações inclusive desafios (`maintenance_active` 503), até o cutover do hotfix.

Pós-hotfix, contenção durável:

| Check | Evidência |
| --- | --- |
| Env JVM/Node | `POKEMON_GYM_CHALLENGES_ENABLED=false` |
| `POST /v1/gyms/challenge` | `gym_challenges_disabled` |
| Consultas ginásio | preservadas (gate só em desafio) |

---

## 3. Produção confirmada (pré)

| Item | Valor |
| --- | --- |
| Node (antes) | `odisseu-node:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` · `sha256:cdb25591680f8f63d33923282ad8c25a4ffeee6fc4f50da5847ac96d97c6c094` |
| JVM (antes) | `odisseu-jvm:a6-gym-20261105T222938Z-2ec5f97649ef-amd64` · `sha256:629391dc1930dea6c9360b503b929b78e856f10f1868517e9fb459031ed06d14` |
| Health | Node healthy · WA READY · JVM `up` · maintenance `open` · raid auto ON |
| Deploy concorrente | Não observado |

---

## 4. Quiescência + backup cold

| Etapa | Evidência |
| --- | --- |
| Node quiescent | `2026-11-06T09:53:26.987Z` · `activeOperations=0` |
| JVM quiescent | `2026-11-06T09:53:27.621Z` · `activeRequests=0` |
| Stop JVM | ordenado antes do cold |
| Cold | `/home/caiohclavico/odisseu-recovery/datomic-cold-backups/cold-20261106T100459Z` |
| Manifest digest | `0bd736c42c7b034876e57c28392a2221c609fa1909b5e87c2dfba047ee428b2e` (`MANIFEST.FILES.sha256`) |
| Backups anteriores | preservados (`cold-20261105T*` intactos) |
| Restore isolado | SHA check ALL_OK · bridge health up |
| Migration 034 | `:mid pokemon/034-gym-permanence-daily` · attr `:save/gym-permanence-daily-edn` · 033 presente |

---

## 5. Cutover hotfix

| Artefato | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-gym-hp-hotfix-20261106T094456Z-bf8f5c66e492-amd64` | `sha256:5023873836935272c7fcea4a58a7b00de683121cf571c329a6e2e4081277a42b` |
| JVM | `odisseu-jvm:a6-gym-hp-hotfix-20261106T094456Z-bf8f5c66e492-amd64` | `sha256:f63800695178bfb8d7295ae035c4f9497a728d6ef67eda09924e30a189b4969d` |

Tar SHA-256 conferidos no host antes do `docker load`:

- Node: `75d44f48ca7b667fc190fdb0b3ec55abb853a125e12f2e5401dac220d5e73a14`
- JVM: `84a9e8f6c11274047269f8eaa6a1934798d436c35fef5b639b1a48477d630a6b`

Compose backup:  
`docker-compose.a6-cutover-runtime.yml.bak-pre-gym-hp-hotfix-20261106T101318Z`

Commits efetivos: `5461375` (HP) · `b97a52f` (docs) · `bf8f5c6` (gate).  
Migration adicional: **não**. Economia permanência: **false**.

---

## 6. Alcance e reparação (lote 1)

### Revalidação pré-reparo (cold / snap)

| Métrica | Valor |
| --- | --- |
| Saves auditados | 73 |
| Treinador confirmado | hash `6635c7f7bf15` · save `recovery-v1` · n=97 · zeros=96 |
| Planos elegíveis | **94** (TX wipe `13194139535629`) |
| Zeros legítimos | **2** |
| Ambíguo | hash `e30c22f2109f` · n=10 · zeros=9 · **não reparado** |
| Novos afetados pós-manifesto | **0** |

### Reparação live

Ferramenta: `scripts/a6-gym-hp-repair-rehearsal.clj` (ensaio homologado; `-main` no volume live com JVM parada).

```
:plans 94 :before_zeros 96
:after {:restored 94, :still_zero_in_plan 0, :zeros_after 2, :zeros_after_idempotent 2, :ok true}
```

| Métrica | Valor |
| --- | --- |
| Reparados | **94** |
| Ignorados (legítimos a 0) | **2** (`9feed7a44946`, `ce453409ca9f`) |
| Conflitos forçados | **0** |
| Ambíguo | intacto (zeros 9) |
| Moedas (confirmado) | **110** pré = **110** pós |

---

## 7. Integridade pós-reparo

| Check | Resultado |
| --- | --- |
| HP restaurado = último >0 pré-wipe | 94/94 |
| Nível / espécie | sem divergência vs histórico pré-wipe |
| Moedas | inalteradas (110) |
| XP | 93/94 alinhados; **1** Dodrio (`f392fa17927f`) com XP atual 4 vs leitura histórica pré-wipe 0 — **HP ok (13)**; reparo **não** escreveu XP; classificado como ambiguidade de histórico (retract/add), não regressão do lote |
| Time / IDs / inventário | não alvos do TX; sem evidência de alteração colateral |

---

## 8. Estado operacional final

| Item | Estado |
| --- | --- |
| Node/JVM | healthy · imagens hotfix acima |
| WhatsApp | READY |
| Bridge | up · maintenance **open** |
| Desafios ginásio | **OFF** |
| Consultas ginásio | disponíveis |
| Raids automáticas | ON |
| Eventos temáticos / bag / Pokédex / Time / trocas | preservados (sem mudança de flags além do gate de desafios) |
| Release Announcer | OFF |
| Permanência economia | OFF |

---

## 9. Riscos residuais / próximos testes (reabertura)

- Reabertura de desafios exige **GO explícito** separado.
- Homologação WA pós-hotfix (desafio em save descartável): HP só no time 3×3; sprites do defensor sem caixa preta.
- Revisar manualmente o caso XP Dodrio se desejado (não bloqueia o lote).
- Treinador ambíguo `e30c22f2109f` permanece fora de escopo.

---

## 10. Rollback (se necessário)

Não retornar à imagem vulnerável com desafios habilitados. Preferir manter `POKEMON_GYM_CHALLENGES_ENABLED=false`. Compose/imagens anteriores e cold `cold-20261106T100459Z` disponíveis; **não** restaurar cold sobre live sem GO de emergência.
