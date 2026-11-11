# Odisseu A6 — Prep read-only: cold backup real + RC #66

**Quando:** 2026-11-11T00:39Z–00:50Z (UTC)  
**Host:** `odisseu-core` (us-central1-a)  
**Evidência:** `/home/caiohclavico/odisseu-recovery/a6-cold-backup-prep-20261111T003938Z`  
**Escopo:** preflight RO, inventário de writers, baseline de saves, ensaio físico descartável, auditoria de código PE/#66.  
**Não executado:** backup cold real, stop de Node/JVM live, migration 036, cutover RC #66, anúncios, alteração de hold/CI, mutação de saves/recibos/volumes.

---

## Vereditos

| | Decisão | Motivo principal |
| --- | --- | --- |
| **A. Solicitar autorização de backup cold real** | **NO-GO** | Drain/quiescência **não** comprovados: JVM `phase=open`, `ODISSEU_BRIDGE_RAID_AUTO_TICK=true`, eventos `raid_auto_*` recentes. |
| **B. Solicitar autorização de cutover RC #66** | **NO-GO** | Depende de A; schema 036 e JVM PE ainda não autorizados; hold/CI GitHub **UNVERIFIED** nesta sessão (`gh` token inválido). |

Backup real executado: **NÃO**.  
Restore descartável (r4-20261111): **PASS**.

---

## 1. Estado de produção (preflight)

| Item | Valor |
| --- | --- |
| Node | `healthy`, image `sha256:250155bae3ba5236feb676c289d285967b9867d087fe3d9750adf5e7acd2f476` (`odisseu-node:a6-pr71-final-250155bae3ba-amd64`), restart policy `no` |
| JVM | `healthy`, image `sha256:5cfca31b18c4cbf652280a01df39deaa2744cf0a6c93cff2cdb292d24aaa94f2` (`odisseu-jvm:release-version-count-20261109-amd64`), restart policy `no` |
| WhatsApp | `READY`, Chromium `true` (`GET :3003/health`) |
| Schema na imagem live | migrations até `035-species-hp-formula.edn` (sem `036`) |
| Schema 036 | presente só na JVM candidata PE; **não aplicada** |
| Release Announcer | `suspended` (`reason=node-only-deploy-pr71`, `publicationInFlight=false`) |
| Node maintenance file | `phase=quiescent` (residual do deploy PR #71) |
| JVM maintenance | `phase=open`, `activeRequests=0` |
| Disco | ~20 GiB livres (71% usado); inodes ~4% |
| Operações de manutenção concorrentes | nenhum container `backup/restore/migrate` ativo; rehearsal descartável isolado |

### Volumes ativos (confirmados nos mounts)

| Volume | Consumidor | Tamanho aprox. |
| --- | --- | --- |
| `odisseu-a6-cutover-datomic` | `odisseu-a6-jvm` → `/data/datomic-local` | 36 MiB |
| `odisseu-a6-cutover-journal` | `odisseu-a6-jvm` → `/data/journal` | vazio |
| `odisseu-a6-cutover-maint` | `odisseu-a6-jvm` → `/data/maint` | vazio |
| `odisseu-a6-cutover-data` | `odisseu-a6-node` → `/app/data` | 44 KiB |

Volumes `odisseu-core-*` / `odisseu-core-a56-*` existem no host e **não** estão montados nos containers live. LocalAuth permanece no bind `a6-cutover-session-from-core` (fora do arquivo de 4 volumes).

### Imagens PE (#66) no host

| Papel | Digest |
| --- | --- |
| JVM candidata | `sha256:c60eeb31f0e66948f92e8ff5e03b04c055c381b3cf10c59ed5698f175ef59c20` (`…a6-pe-69277d2…`) |
| JVM rollback compatível 036 | `sha256:6523b0f59ccc960dafb23f11bce58617f438e3f8912ef795cd9761e839f5bc84` |
| Helper default do `a6_cutover_backup.py` | mesmo digest da candidata PE |

### Deploy hold / CI

Leitura administrativa GitHub **UNVERIFIED** nesta sessão (`gh auth` keyring inválido; API 403). Não houve alteração de hold, workflows ou variables. Revalidar com operador autorizado imediatamente antes de qualquer janela.

---

## 2. Writers e drain

### Writers identificados

1. **JVM bridge** — maintenance `open`; 89 capabilities incluindo mutações de save/batalha/loja/missão/raid/recovery.
2. **Raid auto tick** — `ODISSEU_BRIDGE_RAID_AUTO_TICK=true`; schedule `windowOpen=true`, `revision=96`; logs com `raid_auto_lobby_expired` / `raid_auto_started`.
3. **Node** — WhatsApp READY; arquivo `a6-maintenance.json` em `quiescent`, mas isso **não** drena a JVM nem o raid auto.
4. **Capacidades de background** — `thematic-events-tick`, `raid-auto-schedule`, missões/eventos, etc.
5. **Release Announcer** — suspenso; **não** equivale a freeze do jogo.

Únicos containers montando volumes cutover: `odisseu-a6-jvm` e `odisseu-a6-node`.

### Drain comprovado?

**NÃO.**

Condições faltantes para GO de cold:

- JVM `phase=quiescent` com zero in-flight após enter autenticado.
- Raid auto / schedulers contidos (tick off ou maintenance efetiva).
- Node admission fechada de forma durável **e** alinhada com JVM.
- Containers writers parados (`restart=no`) antes do helper `a6_cutover_backup.py backup`.
- Contexto sanitizado do helper (hold, CI, exclusive operator, gates drained).

Announcer `suspended` + WhatsApp `READY` **não** provam ausência de writes.

---

## 3. Procedimento cold (preparado, não executado)

Fonte canônica: commit `1771ef9` (`docs/a6-cutover-cold-backup-and-drain.md`, `scripts/a6_cutover_backup.py`) — presente em `rc/a6-release-announcer`, **ainda não** em `master`.

Sequência resumida:

1. Hold/CI/exclusividade revalidados; Announcer `suspended` + `publicationInFlight=false`.
2. Fechar admissão Node → drain → marker quiescent.
3. Enter JVM maintenance → quiescent + schedulers contidos.
4. Stop Node depois JVM (graceful, containers preservados, `restart=no`).
5. `sudo python3 scripts/a6_cutover_backup.py backup <dest> --context-json … --helper-image sha256:c60eeb31…`
6. `verify` → `restore-isolated` com prefixo `odisseu-a6-restore-<id>` (nunca nomes cutover/core).
7. JVM isolada `--network none`, aggregate RO, sem WhatsApp/credenciais live.
8. Restart live JVM→Node; manter Announcer suspenso; reabrir admission só após health/READY.

O helper recusa volumes fora dos quatro `odisseu-a6-cutover-*`, writers ativos e overwrite de destino.

---

## 4. Baseline atual dos saves (RO HTTP)

Gerado em `26-live-baseline-ro.json` a partir do roster privado (18 jogadores), **sem mutação**. Contagens históricas 20/59/1072/181 **não** foram usadas como obrigatórias.

| Agregado | Valor |
| --- | --- |
| Jogadores com saves | 18 |
| Saves | 54 |
| Pokémon (ativos/coleção lida) | 759 |
| Σ HP atual / HP máx | 46 578 / 49 216 |
| Σ XP Pokémon (`xpDesdeNivel`) | 478 |
| Σ nível Pokémon | 1 038 |
| Σ nível treinador (ativos) | 62 |
| Σ PE/XP treinador (ativos) | 609 |
| Σ moedas (todos os slots listados) | 333 |
| Σ qty inventário (ativos) | 77 |
| Σ capacidade mochila / storage | 1 000 / 1 150 |
| Saves com recompensas pendentes | 0 |
| Eventos pendentes bridge | 0 |
| Fingerprint canônico SHA-256 | `29b3285b035d7a26d76c020a6a8b41024b2647117e48238d533cf8a709d60d86` |

Observações: endpoints de ginásio/raid por chat retornaram 400/404 nesta sonda; schedule auto de raid respondeu. Baseline é agregada/anonimizada (player = SHA-12).

---

## 5. Ensaio de restore descartável

| Check | Resultado |
| --- | --- |
| Unit tests `test_a6_cutover_backup.py` | **9/9 PASS** (host + local) |
| Rehearsal `r4-20261111` | **PASS** |
| Backup→verify→restore-isolated | 4 volumes `odisseu-a6-restore-r4-20261111-*` |
| JVM restaurada | healthy, maintenance `quiescent`, `active=0`, `--network none` |
| Aggregate RO | DB core+platform legíveis; fixture vazia (0 saves/pokémon) — prova caminho físico, não semântica live |
| Live após ensaio | Node/JVM healthy, mesmas images, WhatsApp READY, volumes cutover só nos containers live |

Ensaio anterior `r3-20261109` também existia no host; este ciclo revalidou o procedimento.

---

## 6. Compatibilidade RC #66 (código, sem deploy)

Base: merge `69277d2` / commit `90efc5c` + `docs/a6-trainer-pe-progression-rewards.md`.

| Tema | Avaliação |
| --- | --- |
| Curva PE | `10 + 5×(nível−1)`; carry-over; multi-level na mesma tx |
| Legado | Níveis preservados; residual histórico 5,7,9… só para init sem `trainer-xp-level-progress` |
| Recompensas | Pacote determinístico `SHA-256(operation-id\|level)`; sem moedas; capacidade/pendências |
| Idempotência | Ledger `:trainer-level-reward/key` = `save-key\|level-reward\|level` |
| Schema 036 | Aditivo (`trainer-xp-level-progress` + attrs de ledger) |
| Rollback | JVM pré-progressão **incompatível** após primeira escrita 036; rollback pinado `6523b0f5…` obrigatório |
| Recovery V1 / inventário | Consolidação de qty na mesma tx; testes direcionados PASS na homologação do PR |

Não aplicar 036 / não trocar JVM nesta etapa.

---

## 7. Plano de cutover (futuro; sem autorização)

1. Cold real concluído + verify.  
2. Restore isolado com aggregates canônicos vs baseline §4.  
3. Manifesto de saves aprovado.  
4. Digests PE `c60eeb31…` e rollback `6523b0f5…` reconfirmados.  
5. Hold/CI exclusivos confirmados por operador.  
6. Janela autorizada; drain comprovado; writers stopped.  
7. Schema 036 controlado.  
8. Troca JVM candidata.  
9. Testes de progressão (1 nível, multi-nível, retry idempotente, capacidade).  
10. Comparação pós-cutover vs baseline.  
11. Retomada do jogo; Announcer permanece suspenso até GO separado.  

Após a primeira escrita 036, não usar JVM incompatível com esse schema.

---

## 8. Git

- Tooling cold (`1771ef9`) ainda **fora** de `master` (ancestral em `rc/a6-release-announcer`).  
- Este relatório: branch `docs/a6-cold-backup-prep-rc66-readiness` (worktree `/private/tmp/zapbot-a6-cold-prep-docs`).  
- Push/PR dependem de autenticação GitHub válida no ambiente do operador (`gh` local estava inválido nesta sessão).

---

## Riscos operacionais restantes

1. **Writes live ativos** (JVM open + raid auto) — bloqueiam cold.  
2. Node `quiescent` residual **desalinhado** com JVM `open` — risco de interpretação errada de “já drenado”.  
3. Hold/CI **não** revalidados tecnicamente agora.  
4. Tooling cold não mergeado em `master` — precisar da árvore que contém `a6_cutover_backup.py` na janela.  
5. Baseline RO cobre roster conhecido; ginásios/raids não entraram no fingerprint canônico desta sonda.  
6. Restore descartável não prova fidelidade semântica dos saves live (fixture vazia).  
7. Após 036, rollback exige imagem compatível; rollback para `5cfca31b…` fica proibido.

---

## Resumo executivo

| Campo | Status |
| --- | --- |
| Produção Node/JVM/WA | Saudável / READY |
| Volumes | `odisseu-a6-cutover-*` corretos |
| Announcer | suspended |
| Drain | **NÃO** |
| Backup real | **NÃO** |
| Restore descartável | **PASS** |
| Baseline saves | Capturada (fingerprint acima) |
| Datomic live | Volumes íntegros; imagem schema ≤035; 036 ausente |
| RC #66 | Código/imagens prontos para janela futura; cutover **NO-GO** |
| **A** | **NO-GO** solicitar cold |
| **B** | **NO-GO** solicitar cutover #66 |
