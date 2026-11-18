# A6 — Deploy GO definitivo: Release 12.8.1 Etapa A (piloto)

**Decisão:** **SUCCESS**  
**Commit implantado:** `dc6ba2951c7925b2cdd67c13bdf511b642af9d88`  
**Content hash:** `2e1c78445cb6d2bda3597b7bb95aa7b768baedff17ac7b1b44a35fcac21c7362`  
**RC:** `/home/caiohclavico/odisseu-recovery/a6-fase1281-rc-20261102T204022Z/`  
**Deploy meta:** `/home/caiohclavico/odisseu-recovery/a6-fase1281-deploy-20261102T204022Z/`  
**Runtime:** `/home/caiohclavico/odisseu-recovery/a6-cutover-runtime-20261101T141408Z/`  
**VM:** odisseu-core (`35.192.113.146`)  
**Escopo:** código 12.8.1 + migration 032 · **eventos temáticos OFF** · somente piloto  
**Ativação Festival / agenda recorrente / Etapas B–C:** **não autorizadas / não executadas**

Referência: `docs/pokemon-a6-fase1281-ready-for-go.md`

---

## 1. Commit e digests implantados

| | Valor |
| --- | --- |
| Git | `dc6ba2951c7925b2cdd67c13bdf511b642af9d88` |
| Content hash | `2e1c78445cb6d2bda3597b7bb95aa7b768baedff17ac7b1b44a35fcac21c7362` |
| JVM tag | `odisseu-jvm:a6-fase1281-20261102T203340Z-2e1c78445cb6-amd64` |
| JVM digest | `sha256:04ba24f34bb0457a9d45c4383e6085a7145992250053ccf4744f848fee8a2d43` |
| Node tag | `odisseu-node:a6-fase1281-20261102T203340Z-2e1c78445cb6-amd64` |
| Node digest | `sha256:e4473417fab76772d0ca98cc9d365c3d3ca63eb04b521d697ab0e0e8222ccc50` |
| Labels | `fase=12.8.1` · `git-sha=dc6ba295…` · `content-hash=2e1c78445cb6…` (JVM=Node) |
| Health | JVM **healthy** · Node **healthy** · WhatsApp **READY** |

### Rollback 12.8.0 (imagens na VM — não acionado)

| | Valor |
| --- | --- |
| JVM | `odisseu-jvm:a6-fase128-20261102T194042Z-a46a1464586c-amd64` · `sha256:f56d6da5b1522130b4cc6c2f33f678178cfd7d85d96f6ecca033d757798c7157` |
| Node | `odisseu-node:a6-fase128-20261102T194042Z-a46a1464586c-amd64` · `sha256:a5bba1600dfc45e779c94d247358105ece0239bcc0185cab66bad73dea1df098` |

Schema 032 permanece aditivo se rollback de código for necessário.

---

## 2. Backups warm/cold (pré-cutover)

| Tipo | Path | Manifest-of-manifest |
| --- | --- | --- |
| Warm | `…/a6-periodic-backup-20261102T204022Z-warm-fase1281` | `8109d2f464591d38792e0e89f7b5b65652c06f6f98dc4f5aaddd31bc16ff8a37` |
| Cold | `…/a6-periodic-backup-20261102T204022Z-cold-fase1281` | `a59a7c7b66838538315cb8d7d4d8decda21b382566a7802a530a2aad584e207a` |
| Post-warm (verificação 032) | `…/a6-periodic-backup-20261102T204022Z-postwarm-fase1281` | `cf2d375eddc87d0f6b9ba4006564b056cc827afa5310c90cdb5af3483c2568a6` |

---

## 3. Migration 032

Aplicada automaticamente no boot JVM via `trainer/bootstrap!` → `schema/install!` (idempotente).

| Critério | Resultado |
| --- | --- |
| Execução | **PASS** — `pokemon/032-thematic-events` presente |
| Idempotência | **PASS** — attrs aditivos; re-boot seguro |
| Destrutivo / backfill | **PASS** — zero rows temáticas criadas |
| Saves / Pokémon / coins | **PASS** — 63 / 1092 / 366 (operacional 583) idênticos |
| Doces (`professor-cards`) | **PASS** — digest `b082e1ee…fea4` idêntico |
| Missões diárias/semanais/eventos | **PASS** — digests idênticos |
| `recovery-v1` + fences | **PASS** — slots a=22 b=22 recovery-v1=19; fence digest idêntico |
| Economia | **PASS** — `{trusted 6, unknown-pending 3}` |
| Ocorrências / schedules temáticos | **PASS** — `0` / `0` |

Comparação RO: cold pré-cutover (31 migrations) × post-warm (32 migrations) → `MIGRATION_032_APPLIED_DATA_STABLE`.

---

## 4. Sequência

| UTC | Passo |
| --- | --- |
| `20:38Z` | Pré-check: 12.8.0 healthy; WA READY; schedule rev 16; outbox `[]`; thematic=false |
| `20:40–20:42Z` | Sync RC + `docker load` · digests = autorizados |
| `20:42–20:43Z` | Backups warm + cold |
| `20:43:59Z` | Cutover compose → tags 12.8.1 |
| `20:44Z` | JVM healthy · digests live = autorizados · flags false/true |
| `20:44:42Z` | Restart Node pós-JVM (evita ECONNREFUSED) |
| `20:45:04Z` | WhatsApp **READY** · Gemini/Groq `disponivel=true` |
| `20:45–20:46Z` | Smoke thematic OFF · schedule preservado · probe 032 |

Sem force Festival · sem enable agenda · sem PUT schedule temático · sem liberação de outros grupos.

---

## 5. Flags e isolamento

| Flag | Valor live |
| --- | --- |
| `POKEMON_THEMATIC_EVENTS_ENABLED` | **false** (JVM + Node `printenv`) |
| `POKEMON_BAG_KIT_DAILY_ENABLED` | **true** |
| `POKEMON_BATTLE_LEAVE_ENABLED` | **true** |
| `ALLOW_ALL_GROUPS` | false |
| `ALLOWED_GROUP_IDS` | `120363427684548487@g.us` |

| Gate eventos | Resultado |
| --- | --- |
| Catálogo 7 eventos | **PASS** (via `/v1/thematic-events/schedule`) |
| `active` / `events=[]` | **PASS** |
| `schedule=null` (nada agendado) | **PASS** |
| Tick | `{"note":"thematic-events-flag-off","processed":[]}` |
| History | `[]` |
| Outbox pending | `[]` · sem anúncios temáticos |
| Anúncios WA Festival | **nenhum** nos logs |

---

## 6. Scheduler de raids (piloto)

```
enabled=true  dailyLimit=12  specialReserve=2  intervalMs=3600000
nextAt=1793703600000  nextCategoria=mitico
revision=16
lastSpawnId=raid-auto:120363427684548487@g.us:1793638800000
```

Antes = depois (ponteiro preservado). Demais grupos: allowlist inalterada (só piloto).

---

## 7. Smoke pós-deploy

| Gate | Resultado |
| --- | --- |
| Digests/labels RC = autorizados | **PASS** |
| WA AUTHENTICATED + READY | **PASS** (`whatsapp=READY`) |
| Gemini+Groq `disponivel=true` | **PASS** |
| Flag OFF após restart | **PASS** |
| Migration 032 + dados estáveis | **PASS** |
| Schedule raids rev 16 | **PASS** |
| Bag daily + bag kit capabilities | **PASS** |
| `clojure -M:test-fase1281` retest | **PASS** — 23/222 |
| Recursos reais de jogadores | **não consumidos** |

Caça/clima/Frambo/missões: regressão coberta por suíte fase128/fase1281; smoke live limitado a leituras + flag OFF (sem hunts/claims artificiais).

---

## 8. Rollback disponível

1. Recreate compose → tags 12.8.0 (`f56d6da5…` / `a5bba160…`).  
2. Manter `POKEMON_THEMATIC_EVENTS_ENABLED=false`.  
3. **Não** remover attrs 032.  
4. Warm/cold Etapa A + post-warm disponíveis para restore extremo.

---

## 9. Riscos residuais / incidentes

| Item | Nota |
| --- | --- |
| Race Node→JVM no up | Esperado; corrigido com restart Node após JVM healthy (mesmo padrão 12.8.0) |
| ECONNREFUSED durante cold backup | Transitório; JVM voltou healthy antes do cutover |
| Etapas B/C | Bloqueadas até novo GO |

---

## 10. Decisão

**GO 12.8.1 Etapa A concluído com SUCCESS no piloto.**  
Código + migration 032 em produção · **eventos temáticos desativados**.  
Aguarda autorização explícita para Etapa B (Festival piloto) e Etapa C (agenda recorrente).
