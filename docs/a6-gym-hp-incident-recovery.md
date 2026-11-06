# Incidente P0 — wipe de HP em ginásios + plano de recuperação

**Status operacional:** CONTENÇÃO **proposta** (não aplicada) · HOTFIX **validado** · REPARO **ensaiado** · RC **pronto** · DEPLOY/REPARO PROD **aguardam GO**  
**Produção viva:** Node/JVM `a6-gym-20261105T222938Z-2ec5f97649ef-amd64` (ainda vulnerável)  
**RC hotfix:** `a6-gym-hp-hotfix-20261106T094456Z-bf8f5c66e492-amd64` (Node+JVM; sem deploy)  
**Período do incidente:** cutover `2026-11-05T22:56:56Z` → contenção efetiva (pendente)  
**Wipe principal observado:** TX `13194139535629` @ `2026-11-06T09:18:21Z`

Evidências host:  
`/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/gym-hp-incident-20261106T093516Z/`

---

## 1. Contenção imediata (sem alterar produção nesta sessão)

### Mecanismo recomendado

Nova env **`POKEMON_GYM_CHALLENGES_ENABLED`** (código no tip local):

| Valor | Efeito |
| --- | --- |
| ausente / true | desafios liberados (default) |
| `false` / `0` / `off` / `no` | `POST /v1/gyms/challenge` → `:gym_challenges_disabled`; `desafioHabilitado=false` nas leituras |

**Preserva:** `GET /v1/gyms*`, ranking, histórico, raids, temáticos, bag, trocas, Pokédex, time, announcer, maintenance.

**Não** reinicia containers se o compose já injeta a env e o processo a lê no request (JVM lê `System/getenv` a cada chamada). Em produção atual a env **ainda não existe** — requer recreate JVM/Node **com GO** (ou injeção pontual autorizada).

### Alternativas rejeitadas / piores

| Opção | Motivo |
| --- | --- |
| Maintenance gate global | Derruba mutações/raids/temáticos |
| Parar Node | Derruba WhatsApp inteiro |
| Flag econômica de permanência | Não bloqueia desafios |

### Estado atual da contenção

**NÃO APLICADA** em produção (sem GO). Bug ainda explorável via `!pk gin desafiar`.

---

## 2. Alcance do incidente (auditoria RO)

Varredura: 73 saves no Datomic (cópia live RO). Critério de suspeita: `zero_share ≥ 0.5` e `zeros ≥ 5`.

| player_hash | save | n | zeros | zero_share | Classificação |
| --- | --- | --- | --- | --- | --- |
| `6635c7f7bf15` | `recovery-v1` | 97 | 96 | 0.9897 | **CONFIRMADO** (wipe ginásio pós-cutover) |
| `e30c22f2109f` | `recovery-v1` | 10 | 9 | 0.9000 | **AMBÍGUO** (TX `…33434` em **2026-10-28**, pré-cutover; sem HP>0 anterior no histórico) |
| outros | — | — | ≤6 | ≤0.2 | não suspeitos (nocautes isolados) |

### Confirmado (`6635c7f7bf15`)

- 2 derrotas pós-cutover (`pedra`, `agua`).
- TX bulk `13194139535629` zerou **94** Pokémon de uma vez.
- **96** com HP 0 agora; **94** restorable a partir do último HP>0 antes da wipe; **2** já estavam 0 por nocaute legítimo na batalha (não restaurar).
- Soma HP a recuperar (ensaio): **6685**.

### Ambíguo (`e30c22f2109f`)

- Sem `prev_before_wipe` positivo.
- Fora do período do RC defeituoso.
- **Não incluir** no primeiro lote de reparo; revisão manual.

### Totais

| Métrica | Valor |
| --- | --- |
| Treinadores comprovadamente afetados pelo bug do RC | **1** |
| Treinadores ambíguos | **1** |
| Pokémon comprovadamente a restaurar (lote 1) | **94** |
| Pokémon legítimos a 0 (mesmo save) | **2** |

---

## 3. Hotfix validado (local)

| Commit | Conteúdo |
| --- | --- |
| `5461375` | sync HP só no time 3×3; team-edn com imagens; rehydrate Node |
| `b97a52f` | docs commit hash |
| `bf8f5c6` | gate `POKEMON_GYM_CHALLENGES_ENABLED` + testes + ensaio reparo |

### Testes

| Suite | Resultado |
| --- | --- |
| `gym-collection-hp-integrity-a6-test` | 6/6 |
| `gym-challenges-gate-a6-test` | 4/4 |
| `clojure -M:test-m4-gym-battle` | OK (m48/m47/m35 + HTTP) |
| PNGs `docs/evidence/a6-gym-hotfix/` | líder sem URL rehidratado sem caixa preta |

---

## 4. Ensaio de reparação (Datomic descartável)

Path: `datomic-hp-repair-rehearsal-20261106T094235Z`  
Resultado: `repair-rehearsal-result.edn`

```
{:ok true
 :restored 94
 :zeros_before 96
 :zeros_after 2
 :legitimate_zeros_left 2
 :control_other_zeros 9   ;; outro save NÃO alterado
 :idempotent true}
```

Critérios cumpridos: só afetados do wipe; HP anterior; legítimos a 0 preservados; idempotência; controle cruzado.

**Script:** `scripts/a6-gym-hp-repair-rehearsal.clj` (ensaio; não rodar em live).

---

## 5. Plano de implantação + reparação (sequência)

1. **GO contenção** — set `POKEMON_GYM_CHALLENGES_ENABLED=false` no par Node/JVM (recreate mínimo autorizado) **ou** implantar RC hotfix já com flag false.
2. Backup **cold** restaurável (quiescência global).
3. Deploy RC hotfix (Node+JVM mesmo tree) com desafios **ainda off**.
4. Verificação health / WA READY / raids / temáticos.
5. **GO reparo** — aplicar lote 1 (94 IDs) em produção via procedimento homologado no ensaio.
6. Auditoria pós-reparo (contagens + amostra history).
7. Homologação piloto isolada (desafio em save descartável / smoke).
8. Reabrir desafios: `POKEMON_GYM_CHALLENGES_ENABLED=true` (GO separado).

Não restaurar cold antigo sobre live. Não curar `hpmax` às cegas. Flag econômica permanece `false`.

---

## 6. RC preparado (sem deploy)

Dir: `rc-artifacts/a6-gym-hp-hotfix-rc-20261106T094456Z/`  
Tree: `bf8f5c66e492f96a5bc12fd9b0425c8abc01487b` · platform `linux/amd64`

| Artefato | Tag | Image ID |
| --- | --- | --- |
| Node | `odisseu-node:a6-gym-hp-hotfix-20261106T094456Z-bf8f5c66e492-amd64` | `sha256:5023873836935272c7fcea4a58a7b00de683121cf571c329a6e2e4081277a42b` |
| JVM | `odisseu-jvm:a6-gym-hp-hotfix-20261106T094456Z-bf8f5c66e492-amd64` | `sha256:f63800695178bfb8d7295ae035c4f9497a728d6ef67eda09924e30a189b4969d` |

| Tarball | SHA-256 |
| --- | --- |
| `odisseu-node-amd64.tar` | `75d44f48ca7b667fc190fdb0b3ec55abb853a125e12f2e5401dac220d5e73a14` |
| `odisseu-jvm-amd64.tar` | `84a9e8f6c11274047269f8eaa6a1934798d436c35fef5b639b1a48477d630a6b` |

Manifesto: `MANIFEST.md` · meta: `BUILD_META.env` · fontes: `checksums/sources/sources.sha256`.

**Rollback:** tags prod `a6-gym-20261105T222938Z-2ec5f97649ef-amd64`. Preferir manter `POKEMON_GYM_CHALLENGES_ENABLED=false` se rollback de código for necessário antes do reparo.

---

## 7. Riscos

- Contenção ainda **off** em produção → novas derrotas podem corromper mais saves.
- Save ambíguo `e30c22f2109f` não entra no lote 1.
- Ocupações atuais sem URLs de sprite: Node rehydrate cobre até nova ocupação com team-edn corrigido.
- Reparo exige writer JVM — coordenar com drain.

---

## 8. Próxima operação necessária

1. **GO imediato de contenção** (`POKEMON_GYM_CHALLENGES_ENABLED=false`) — prioridade.  
2. GO cutover RC hotfix.  
3. GO reparação seletiva lote 1 (94).  
4. GO reabertura de desafios.
