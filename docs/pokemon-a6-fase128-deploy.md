# A6 — Deploy GO definitivo: Release 12.8.0 (piloto)

**Decisão:** **SUCCESS**  
**Commit implantado:** `7d0325a0153e86a17f62eff9206f4bb106eb4733`  
**Content hash:** `a46a1464586cb53d9cf81e371fa3301256153bd9`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase128-rc-20261102T194643Z/`  
**Deploy meta:** `/home/caiohclavico/odisseu-recovery/a6-fase128-deploy-20261102T194945Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** odisseu-core (`35.192.113.146`)  
**Escopo:** missões UX + recompensas + Doces (rename) + clima PT + captura por raridade + Frambo caça + mochila diária ON · **somente** piloto  
**Migration / backfill / eventos temáticos / liberação global:** **nenhuma**

Referência: `docs/pokemon-a6-fase128-ready-for-go.md`

---

## 1. Imagens em execução

| | Valor |
| --- | --- |
| Git | `7d0325a0153e86a17f62eff9206f4bb106eb4733` |
| Content hash | `a46a1464586cb53d9cf81e371fa3301256153bd9` |
| JVM tag | `odisseu-jvm:a6-fase128-20261102T194042Z-a46a1464586c-amd64` |
| JVM digest | `sha256:f56d6da5b1522130b4cc6c2f33f678178cfd7d85d96f6ecca033d757798c7157` |
| Node tag | `odisseu-node:a6-fase128-20261102T194042Z-a46a1464586c-amd64` |
| Node digest | `sha256:a5bba1600dfc45e779c94d247358105ece0239bcc0185cab66bad73dea1df098` |
| Labels | `fase=12.8.0` · `git-sha=7d0325a0…` · `content-hash=a46a1464586c…` (JVM=Node) |
| Health | JVM **healthy** · Node **healthy** |

### Rollback (imagens anteriores — presentes na VM)

| | Valor |
| --- | --- |
| JVM | `odisseu-jvm:a6-turn-gym-level-ai-20261102T064554Z-9294b87ecc23-amd64` · `sha256:c691be66…e8105` |
| Node | `odisseu-node:a6-turn-gym-level-ai-20261102T064554Z-9294b87ecc23-amd64` · `sha256:3cbe0364…5229ae` |

**Não acionados.**

---

## 2. Backups (pré-cutover)

| Tipo | Path | Manifest-of-manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T194945Z-warm-fase128` | `20eb0c36a84d16ecc8531608f180a06d298d024661e375ecdb8f97a5af5bf6f8` |
| Cold | `…/a6-periodic-backup-20261102T194945Z-cold-fase128` | `115c195e41d84c244987e2ce384d89e932d8a9f012478b6abb08f30fd7ddbac4` |

---

## 3. Sequência

| UTC | Passo |
| --- | --- |
| `19:45Z` | Pré-check: JVM/Node turn-gym-level-ai healthy; schedule `dailyLimit=12` `specialReserve=2`; outbox pending `[]`; próximo spawn ~15h |
| `19:46–19:49Z` | Sync RC + `docker load` · digests = autorizados |
| `19:50Z` | Backups warm + cold |
| `19:51:06Z` | Cutover compose → tags 12.8.0 |
| `19:56–19:57Z` | Node restart após race JVM (ECONNREFUSED inicial) → WA Autenticado + READY · IA disponivel |
| `19:57–19:58Z` | `POKEMON_BAG_KIT_DAILY_ENABLED=true` · `POKEMON_THEMATIC_EVENTS_ENABLED=false` · Node recreate/restart |
| `19:58:42Z` | WhatsApp **READY** · bag env `true` no processo Node |
| `19:59Z` | Domain + present smoke **PASS** · schedule revision **16** intacta |

Sem migrate · sem backfill · sem force tick · sem PUT de cadence · sem liberação de outros grupos.

---

## 4. Flags

| Flag | Valor live |
| --- | --- |
| `POKEMON_BAG_KIT_DAILY_ENABLED` | **true** (confirmado via `printenv` no Node) |
| `POKEMON_THEMATIC_EVENTS_ENABLED` | **false** |
| `ALLOW_ALL_GROUPS` | false |
| `ALLOWED_GROUP_IDS` | `120363427684548487@g.us` |
| `POKEMON_BATTLE_LEAVE_ENABLED` | true (compose) |

---

## 5. Scheduler (piloto)

```
enabled=true  dailyLimit=12  specialReserve=2  intervalMs=3600000
nextAt=1793703600000  nextCategoria=mitico
revision=16
lastSpawnId=raid-auto:120363427684548487@g.us:1793638800000
```

Antes = depois (ponteiro preservado). Events pending: `[]`.

---

## 6. Smoke

| Gate | Resultado |
| --- | --- |
| Digests RC = autorizados | **PASS** |
| Labels git-sha / content-hash / fase=12.8.0 | **PASS** |
| WA AUTHENTICATED + READY | **PASS** |
| Gemini+Groq boot `disponivel=true` | **PASS** |
| `thematic-events-enabled?=false` · `active-events=[]` · 7× inactive | **PASS** |
| Aliases missões NFD (diária/semanal/evento) | **PASS** |
| Resumo ≤2+2 sugeridas | **PASS** |
| Doces label `Doce Charmander` · present “Doces Pokémon” | **PASS** |
| Clima PT sem IDs EN · influência 35/65 | **PASS** |
| Captura comum≫lendário · Frambo painel 40→60 | **PASS** |
| Present menu Frambo antes/depois | **PASS** |
| Capabilities `bag-kit` + `bag-daily` | **PASS** |
| Schedule revision 16 preservada | **PASS** |
| `!pk` WhatsApp humano (missões/mochila/caça) | **não forçado** — validar no grupo piloto |

---

## 7. Invariantes preservados

```
recovery-v1 + fences
allowTestRng=false
dailyLimit=12  specialReserve=2
schema/migration 031 — não tocada
POKEMON_BATTLE_LEAVE_ENABLED=true
ALLOW_ALL_GROUPS=false · allowlist piloto
eventos temáticos OFF
Gemini/Groq configurados
```

---

## 8. Incidentes / notas

| Item | Nota |
| --- | --- |
| Race Node→JVM no 1º up | Node iniciou antes do JVM healthy → ECONNREFUSED; corrigido com restart Node após JVM healthy |
| Compose `up node` recreou JVM | `depends_on` puxou JVM; após bag flag usamos `docker restart` só no Node |
| Rollback | não acionado |

---

## 9. Decisão

**GO 12.8.0 concluído com SUCCESS no piloto.** Sem liberação global. Eventos temáticos permanecem para 12.8.1.
