# A6 — Deploy GO definitivo: hotfix lobby + participação solo

**Decisão:** **SUCCESS**  
**Commit implantado:** `5e8375d2d2c763dcda9211a5d9f2385369a28b50`  
**Content hash:** `c947938ff0be9b33f12f638afbf60332f9726cc4f7e5210cbef562b63445843a`  
**RC / Deploy:** `/home/caiohclavico/odisseu-recovery/a6-fase-lobby-join-hotfix-deploy-20261102T055022Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** odisseu-core (`35.192.113.146`)  
**Escopo:** idle sweeper não mata lobby · mínimo real = 1 · **somente** piloto  
**Migration / force tick / alteração de agenda / liberação global:** **nenhuma**

Referência: `docs/pokemon-a6-raid-lobby-join-hotfix.md`

---

## 1. Imagens em execução

| | Valor |
| --- | --- |
| Git | `5e8375d2d2c763dcda9211a5d9f2385369a28b50` |
| Content hash | `c947938ff0be9b33f12f638afbf60332f9726cc4f7e5210cbef562b63445843a` |
| JVM tag | `odisseu-jvm:a6-lobby-join-hotfix-20261102T055022Z-c947938ff0be-amd64` |
| JVM digest | `sha256:1806afa3b228c3da26d08930f3fc3cd3b86db08e7583489ef79634d6588eefde` |
| Node tag | `odisseu-node:a6-lobby-join-hotfix-20261102T055022Z-c947938ff0be-amd64` |
| Node digest | `sha256:dfdff45688be86cf22ff240a96a659a0abc11a8dc3758955b84bed64b7fce07c` |
| Labels | `fase=lobby-join-hotfix` · `git-sha=5e8375d…` · `content-hash=c947938f…` |
| Health | JVM **healthy** · Node **healthy** |

### Rollback (revelação — imagens anteriores)

| | Valor |
| --- | --- |
| JVM | `odisseu-jvm:a6-raid-reveal-20261102T050642Z-824a7c054813-amd64` · `sha256:34a1b5d7…1e1b52` |
| Node | `odisseu-node:a6-raid-reveal-20261102T050642Z-824a7c054813-amd64` · `sha256:79a6ae27…d7ec33f` |

Presentes na VM; não acionados.

---

## 2. Backups (pré-cutover)

| Tipo | Path | Manifest-of-manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T055022Z-warm-lobbyjoin` | `a90a43aca214931310638fd5920d7fa5950f91acd47ffe7d0d93967ab9b86595` |
| Cold | `…/a6-periodic-backup-20261102T055022Z-cold-lobbyjoin` | `fe814f1bfd65c535f43e6909556ad357bc85681e3e40430610e46a9a62687a4d` |

Filecount warm/cold: **18** arquivos cada.

---

## 3. Sequência

| UTC | Passo |
| --- | --- |
| `05:51–05:54Z` | Backups warm/cold · digests RC · rollback revelação verificado |
| `05:54:42Z` | Início cutover (janela sem combate ativo) |
| `05:55:45Z` | `CUTOVER_OK` — JVM+Node nas tags hotfix · schedule `nextAt` intacto |
| `05:55:36Z` | WhatsApp **Autenticado** |
| `05:55:41Z` | WhatsApp **READY** (`/health` → `whatsapp:READY`) |
| `06:00:11Z` | Spawn natural lendário `raid-auto:…:1793599200000` (Moltres) · `result=spawn` único |
| `06:00:12Z` | Event `raid-opened` enfileirado com copy solo · depois **acked** (pending `[]`) |
| `06:06:11Z` | Lobby ainda `fase=inscricoes` · `ativa=true` · ~14 min restantes (prova idle≠lobby) |

Sem migrate · sem force tick · sem PUT de cadence.

---

## 4. Scheduler (piloto) — preservado / avançou só pelo spawn natural

**Pré-cutover (rev 4):**
```
enabled=true  dailyLimit=12  specialReserve=2  intervalMs=3600000
nextAt=1793599200000  nextCategoria=lendario
lastSpawnId=raid-auto:…:1793595600000
```

**Pós-spawn natural (rev 5):**
```
enabled=true  dailyLimit=12  specialReserve=2  intervalMs=3600000
nextAt=1793602800000  nextCategoria=epico
lastSpawnId=raid-auto:…:1793599200000
ginasioIndex=2
```

**Outro grupo controle** `120363401234567890@g.us`: `enabled:false` · sem spawn.

Allowlist Node: `ALLOW_ALL_GROUPS=false` · `ALLOWED_GROUP_IDS=120363427684548487@g.us`.

---

## 5. Resultado dos testes / smoke

| Gate | Resultado |
| --- | --- |
| Commit autorizado único `5e8375d` | **PASS** |
| Content-hash / tags / digests JVM+Node | **PASS** |
| Domínio live: min=1 (raro/épico/lendário/mítico) + recomendados 1/2/2/3 | **PASS** |
| `find-combat-raid-ids` presente; lobby fora do idle | **PASS** (código no container) |
| Format opened: “Jogadores recomendados” + “sozinho” | **PASS** |
| Copy Node: `Precisa de pelo menos 1` · `pode começar com 1` | **PASS** |
| Spawn natural único · sem duplicata | **PASS** (1× `result "spawn"`) |
| Lobby vivo após >5 min idle (`inscricoes`, ponteiro ativo, `expira` intacto) | **PASS** @ `06:06Z` |
| `!pk raide …` no texto do evento (alias compat) | **PASS** |
| Solo UX no `raid-opened` (`1+ inscrito`, recomendados 2 lendário) | **PASS** |
| 0 participantes → batalha não iniciada (lobby vazio ainda em inscricoes) | **PASS** |
| PNG ovo + `renderRaidOpenedReveal` no Node | **PASS** (assets ~96–98KB) |
| Caps `raid-frambo` / `battle-leave` · `BATTLE_LEAVE=true` | **PASS** |
| `allowTestRng=false` · `raidAutoTick=true` | **PASS** |
| Demais grupos OFF | **PASS** |
| Join/start com recurso de jogador real | **não executado** (política: não consumir recursos em teste artificial) |

Provas de ponteiro / active-raid: `GET /v1/raids/active` para dois saves `recovery-v1` do piloto — `raidId=…1793599200000`, `fase=inscricoes`, `ativa=true` antes e depois da janela de 5 min.

---

## 6. WhatsApp

```
status=ok
whatsapp=READY
chromium=true
Autenticado @ 05:55:36Z
READY @ 05:55:41Z
```

Events pending pós-entrega: `[]` (Node consumiu `raid-opened`).

---

## 7. Evidência — lobby permanece ativo

- Spawn `06:00:11Z` · idle legado mataria ~`06:05Z`.
- Em `06:06:11Z`: mesma raid, `fase=inscricoes`, `nParticipantes=0`, `ativa=true`.
- Nenhum log de `idle-timeout` / expire sobre essa raid na janela.
- `mins_left` ≈ 14 no momento da prova (lobby 20 min lendário).

---

## 8. Evidência — participação solo

- Parâmetros live: todas as categorias `min-jogadores=1`.
- Evento Moltres: `Jogadores recomendados: 2` · `Você pode tentar sozinho…` · `!pk raide iniciar — começar agora (1+ inscrito)`.
- Chefe inalterado: HP 144 · raridade lendário · ginásio água · liga bronze.
- Encerramento 0 inscritos: lobby segue aberto até `expira` (não entrou em combate).

---

## 9. Demais grupos

Schedule controle `enabled:false`. Raids automáticas continuam exclusivas do piloto.

---

## 10. Invariantes preservados

```
recovery-v1 + fences (fenceGeneration observados 2 / 4)
allowTestRng=false
dailyLimit=12  specialReserve=2  intervalMs=3600000
schema/migration 031 — não tocada
POKEMON_BATTLE_LEAVE_ENABLED=true
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
catálogo dinâmico / economia unknown-pending — não alterados neste hotfix
```

---

## 11. Riscos residuais / incidentes

| Item | Nota |
| --- | --- |
| Join/iniciar humanos no WhatsApp | Não forçado em smoke; ponteiro+copy+código validam o hotfix. Observar 1º `entrar` / `iniciar` solo real. |
| Logs Node pouco verbosos | Entrega inferida por ack do outbox; confirmar visualmente PNG+caption no grupo se necessário. |
| Lobby atual sem inscritos | Cancelamento natural ao fim dos 20 min — esperado. |
| Incidentes | **Nenhum.** Rollback não acionado. |

---

## 12. Decisão

**GO hotfix concluído com SUCCESS no piloto.** Sem liberação global. Sem alterações adicionais.
