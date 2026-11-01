# A6 — Fase 12.5: Deploy join value-too-large (+ stack coerente leave)

**VM:** `odisseu-core` (`35.192.113.146`, hostname `odisseu-core`)  
**Branch:** `hotfix/a6-raid-join-value-too-large-fase12.5`  
**Código 12.5 (slim join):** `cc11dbf185a1f02849689b91b69e63a638309ef9`  
**Código leave-fix (superset live):** `a6cc367a9490fe30d43be5245d4f213b74abfe96` (ancestral inclui `cc11dbf`)  
**RC 12.5:** `~/odisseu-recovery/a6-fase12.5-rc-20261101T190850Z/`  
**Artefatos deploy 12.5:** `~/odisseu-recovery/a6-fase12.5-deploy-20261101T191839Z/`  
**Backup warm 12.5:** `~/odisseu-recovery/a6-periodic-backup-20261101T191839Z/` (alias `a6-fase12.5-backup-…`)  
**Leave paralelo:** `docs/pokemon-a6-battle-leave-enable-fase12.5.1.md`

---

## Status final: **SUCCESS**

Correção de join (`slim` + `encode-value-edn` em `:raid-participant/pokemon-edn`) **está live** via JVM `a6-battle-leave-fix-…1de61da40a57`, que já contém o ancestral 12.5. Flag `POKEMON_BATTLE_LEAVE_ENABLED=true` e fix de `:find` relacional do leave permanecem. Smoke pós-estabilização: leave `no_battle` **200**; open→join auto→cancel **sem** `DATOMIC_VALUE_TOO_LARGE`. WA READY sem QR; piloto exclusivo; auto-tick OFF.

**Não** foi necessário rebuild combinado nem recreate adicional após o leave-fix estabilizar — a stack coerente pedida (12.5 slim join + leave on + leave query fix) já é a live.

---

## 1. Horários UTC (síntese)

| Evento | UTC |
| --- | --- |
| Preflight RO + leave settled | `2026-11-01T19:18:39Z` |
| Backup warm 12.5 + restore≡pre-snap | `19:18:46Z` (`RESTORE_EQUIV_PRE=true`) |
| `docker load` RC 12.5 + SHA gate IDs | ~`19:19:20Z` **PASS** |
| Freeze Node → recreate JVM/Node 12.5 (1ª vez) | freeze `19:19:39Z` · JVM `19:19:41Z` · Node `19:20:21Z` |
| WA READY (1ª janela 12.5) | Autenticado `19:20:56Z` · READY `19:21:01Z` · sem QR |
| Smoke 12.5 open→join→cancel | ~`19:21:47Z`–`19:22:32Z` |
| Drift paralelo leave-fix (compose sobrescrito) | JVM leave-fix desde `19:20:36Z` / relocks `19:23`–`19:24` |
| Stack estabilizada leave-fix + Node 12.3.2 | WA READY `19:24:41Z` |
| Verificação coerente (este agente, sem mutate) | `19:26:24Z`–`19:26:26Z` |

---

## 2. Imagens live (estado coerente final)

| Container | Tag | Image ID |
| --- | --- | --- |
| `odisseu-a6-jvm` | `odisseu-jvm:a6-battle-leave-fix-20261101T191747Z-1de61da40a57-amd64` | `sha256:d5e7fe6a1e892b293f74147a6933fc2fdc4921ff9f57c5f1cc7f8c20a6f2d6c5` |
| `odisseu-a6-node` | `odisseu-node:a6-raid-hotfix1231-20261101T161034Z-1eede78e365c-amd64` | `sha256:f1833b2772459a7f7a79c55d15492eb4ab9053f539fcef8470467e28feb6ffbd` |

**Labels JVM live:** `io.zapbot.git-sha=a6cc367…` · `content-hash=1de61da40a57…` · `stamp=20261101T191747Z`

### Por que não ficaram as tags RC `a6-raid-join-vtl-…7ad66c1b42e3`

O RC 12.5 puro (`NODE/JVM …7ad66c1b42e3`) foi carregado e chegou a subir, mas o GO paralelo de leave-fix (necessário: flag sozinha → 500 no Client API) estabilizou a JVM em `a6-battle-leave-fix`. Verificação de ancestralidade:

```
git merge-base --is-ancestor cc11dbf a6cc367  → true
```

`raid_store` em `a6cc367` contém `encode-participant-pokemon-edn` (slim + gzip) idêntico ao 12.5. Portanto leave-fix **já é** 12.5 + leave fix.

Node permanece em 12.3.2: o fix VTL é JVM-side; a única delta Node do 12.5 é mensagem amigável em `pokemon-jvm-present.cjs` (não bloqueante).

### RC 12.5 autorizado (referência / rollback parcial)

| | Tag | ID |
| --- | --- | --- |
| JVM | `odisseu-jvm:a6-raid-join-vtl-20261101T190850Z-7ad66c1b42e3-amd64` | `sha256:64cd11ab0c652553c1148dbcef676dcd44c1ae6582b845c392f3c84300be086f` |
| Node | `odisseu-node:a6-raid-join-vtl-20261101T190850Z-7ad66c1b42e3-amd64` | `sha256:60c18addeaae8ec83d6a63649b9948e1b6017c4fd2a798c11d9a52caf19ae653` |

SHA gate dos tars + IDs carregados: **PASS** (meta `tar-sha-gate.txt`, `loaded-images.txt`).

---

## 3. Compose / runtime

| Campo | Valor |
| --- | --- |
| working_dir | `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z` |
| compose | `docker-compose.a6-cutover-runtime.yml` |
| env | `.env.a6-cutover` |
| projeto | `odisseu-a6-cutover` |

### Env mantido

```env
ALLOW_ALL_GROUPS=false
ALLOWED_GROUP_IDS=120363427684548487@g.us
STRICT_USER_ALLOWLIST=0
POKEMON_RAID_AUTO_TICK=false
ODISSEU_BRIDGE_RAID_AUTO_TICK=false
POKEMON_JVM_ALLOW_DOCKER_HOST=true
POKEMON_BATTLE_LEAVE_ENABLED=true
```

---

## 4. Gates

| Gate | Resultado |
| --- | --- |
| Hostname `odisseu-core` + stack `odisseu-a6-*` | **PASS** |
| Tar/image IDs RC 12.5 ≡ autorizados | **PASS** (load) |
| Backup warm + `RESTORE_EQUIV_PRE` | **PASS** |
| Health `up`, `raidAutoTickEnabled=false` | **PASS** |
| Cap `battle-leave` presente | **PASS** |
| Leave `POST /v1/battles/leave` → **200** `status=no_battle` | **PASS** (`19:26:24Z`) |
| Open→join auto→cancel sem VTL | **PASS** (`19:26:24Z`) |
| WA READY sem QR (janela estável) | **PASS** `19:24:41.074Z` |
| Sem `down -v` / migrate / touch `a56-*` / auto-heal / rewards artificiais | **PASS** |

---

## 5. Drift paralelo (lição)

Dois agents mutaram o mesmo compose quase ao mesmo tempo (12.5 tags ↔ leave-fix). Efeito: Node down intermitente; compose oscilou; smokes de lobby se cruzaram. Recuperação pelo agent leave + espera de estabilidade neste agent; **sem** terceira recreate após `19:24`.

**Regra:** serializar mutações de compose em `odisseu-core`; preferir JVM leave-fix (superset) a forçar tags 12.5 puras se leave-fix já inclui o ancestral.

---

## 6. Backup / rollback

| Item | Path |
| --- | --- |
| Backup warm 12.5 | `~/odisseu-recovery/a6-periodic-backup-20261101T191839Z/` |
| MoM SHA-256 | `3545e5ab561c5cf22ba11cb09c4bc57ded5efb93becb9940267fd0a024febe9d` |
| Backup leave enable | `~/odisseu-recovery/a6-periodic-backup-20261101T191306Z/` |
| Rollback 12.5 (pré-mutate) | `…/a6-fase12.5-deploy-…/rollback/` (imagens 12.3.2 + compose/env) |

Rollback de código: **não** restaurar backup Datomic sobre progresso novo; só reverter tags de imagem se necessário. Preferir manter leave-fix JVM (não voltar a 12.5 puro sem o fix de leave).

---

## 7. Restrições respeitadas

- Sem migrations / Cassandra / expansão de grupos  
- Sem ligar raides automáticas  
- Sem `down -v` / volumes A5.6  
- Sem auto-heal de raide histórica / recompensas artificiais  
- Leave flag **preservada** no estado final  

---

## Evidências

`~/odisseu-recovery/a6-fase12.5-deploy-20261101T191839Z/meta/`:

- `preflight-live.txt`, `tar-sha-gate.txt`, `loaded-images.txt`, `backup-mom.txt`, `backup-proof/RESTORE_PROOF.txt`  
- `freeze-node.txt`, `compose-after-patch.yml`, `sibling-overwrite-detected.txt`  
- `smoke-*.json`, `verify-leave.json`, `verify-join.json`, `final-coherent-status.txt`
