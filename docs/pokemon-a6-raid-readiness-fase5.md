# A6 — Fase 5: Readiness para piloto de raides

**Branch:** `feature/a6-raid-readiness-fase5`  
**Base de trabalho:** `feature/a6-raid-homolog-fase4` (working tree)  
**HEAD git compartilhado:** `1273fa4` (mesmo commit que `a6-save-slots`)  
**Worktrees preservados:** `zapbot-raid-lobby` (esta branch) + `zapbot` (`a6-save-slots`)  
**Data da auditoria:** 2026-10-31  

**Não executado:** deploy, migrate, backfill, merge/rebase/cherry-pick, alteração de config remota, escrita em banco real, ativação de tick/agenda.

---

## Classificação: **BLOCKED**

Isolamento e segurança do **alvo de staging live** não puderam ser demonstrados nesta fase (inspeção local + docs apenas).  
Há também **conflitos controláveis de integração** com `a6-save-slots` e código das fases 2–4 ainda **não commitado**.

| Dimensão | Status |
| --- | --- |
| Código + testes locais (Fases 2–4) | CONDITIONAL (OK em fixtures; pendente consolidação) |
| Schemas 007/029/030 (neste worktree) | CONDITIONAL (compatíveis entre si; merge com 026 do outro WT pendente) |
| Isolamento staging/Core vs produção | **BLOCKED** (não demonstrado no alvo live) |
| Piloto ativável agora | **BLOCKED** |

---

## 1. Auditoria de integração

### 1.1 Ancestralidade das fases

| Fase | Branch local | Commits dedicados | Estado real |
| --- | --- | --- | --- |
| 2 lobby | `feature/a6-raid-lobby-fase2` | nenhum além de `1273fa4` | working tree / untracked |
| 3 auto | `feature/a6-raid-auto-fase3` | idem | working tree |
| 3.1 hardening | `feature/a6-raid-auto-fase3.1` | idem | working tree |
| 4 homolog | `feature/a6-raid-homolog-fase4` | idem | working tree |
| 5 readiness | `feature/a6-raid-readiness-fase5` | idem | este relatório + exemplo env |

Todas as branches de raide apontam para o **mesmo** `1273fa4` que `a6-save-slots` / `origin/a6-save-slots`.  
A “ancestralidade” das fases 2→5 é **sequência de alterações locais no worktree**, não uma cadeia de commits publicável.

**Sem merge/rebase/cherry-pick** nesta entrega (conforme restrição).

### 1.2 Comparação com `a6-save-slots`

| Item | Evidência |
| --- | --- |
| Commit HEAD | idêntico (`1273fa4`) |
| Diff de commits | vazio nos dois sentidos |
| Paths dirty em comum | **12 arquivos BOTH-DIVERGED** (cada worktree editou a partir do mesmo blob base) |

Paths em conflito (ambos divergiram do HEAD):

- `modules/pokemon/domain/pokemon/domain/raid.clj`
- `modules/pokemon/infrastructure/pokemon/infrastructure/datomic/{raid_store,schema,store}.clj`
- `modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj`
- `modules/pokemon/application/pokemon/application/{bridge_reads,bridge_tx,ports}.clj`
- `scripts/lib/pokemon-jvm-{client,present}.cjs`
- `src/zapbot/{config,pokemon_http}.cljs`

Diferença de schema instalador:

| Worktree | Fim da cadeia |
| --- | --- |
| raid (este) | `025` → **`029`** → **`030`** (sem 026–028) |
| save-slots | `025` → **`026-battle-idle`** (sem 029/030) |

**Risco:** integração futura exige resolução manual (não automática) para incluir 026 **e** 029/030 sem perder attrs.

### 1.3 Schemas 007 / 029 / 030

Neste worktree (somente leitura + testes Fase 4):

- `007-raids.edn` — base M3.6 (raid, participantes, captura, ponteiro)
- `029-raid-lobby.edn` — aditivo (`criada-em`, min/max)
- `030-raid-auto.edn` — aditivo (agenda, outbox, `origem`)
- Instalador lista 007 + 029 + 030; teste `schemas-007-029-030-e-cadeia-a6` passou em Datomic local sintético

Compatibilidade **intra-raide:** OK (aditivo).  
Compatibilidade **com save-slots (026):** não reconciliada — **pendência de merge futuro**.

---

## 2. Isolamento de staging (inspeção segura)

### 2.1 O que foi inspecionado (sem escrita remota)

| Fonte | Achado |
| --- | --- |
| `docker-compose.staging.yml` | system `odisseu-m61-staging`, db `staging`, volume `odisseu-staging-datomic`, WA `.wwebjs_auth_staging`, bridge só `127.0.0.1:18090` |
| `docker-compose.odisseu-core.yml` | system `odisseu-m90-core`, db `core`, volume `odisseu-core-datomic` / A5.6 live doc aponta `odisseu-core-a56-datomic` |
| `docker-compose.production.yml` | bot Node + `.wwebjs_auth` de produção; **sem** JVM Datomic Pokémon neste compose |
| `.env.staging.example` / `.env.odisseu-core.example` | tokens placeholder; WA path distinto de prod |
| `.env.staging` local (`zapbot/` worktree) | **só chaves/padrões** (valores secretos não reproduzidos) |
| Docs A6.4 Core | Core live existe; volume A5.6 com 0 treinadores Pokémon na época do relatório; SSH conhecido |

### 2.2 `.env.staging` local (padrões, sem valores)

| Check | Resultado |
| --- | --- |
| `APP_ENV` | `staging` |
| `WWEBJS_DATA_PATH` | `.wwebjs_auth_staging` (não path de prod) |
| `WWEBJS_CLIENT_ID` | `staging` |
| `POKEMON_ADAPTER` | `jvm` |
| `POKEMON_SERVICE_URL` | vazio (bom — evita segundo writer) |
| `DEV_GROUP_ID` | **EMPTY** |
| `STAGING_GROUP_CONFIRMED` | ausente |
| `POKEMON_RAID_AUTO_*` | ausente (default off no código) |
| Keyspace Cassandra | `zapbot_staging` (nome) |

### 2.3 O que **não** foi demonstrado

- Instância Datomic **live** do alvo do piloto (system/db/volume montado agora)
- Que o volume live ≠ produção Oracle / ≠ saves reais de jogadores
- Destino WhatsApp live (número/sessão) ≠ produção
- Segredos do alvo ≠ produção (tokens não comparados com prod)
- `DEV_GROUP_ID` preenchido e confirmado no alvo
- Que Core A5.6 (`odisseu-core-a56-datomic`) **não** seria o alvo acidental do piloto

### 2.4 Veredito de isolamento

**BLOCKED** — desenho local/compose sugere isolamento, mas **prova no alvo live ausente**.  
Escrita remota/SSH de verificação não autorizada nesta fase.

---

## 3. Plano de implantação (NÃO EXECUTAR)

Pré-condição absoluta: isolamento live **demonstrado** + autorização explícita + resolução de conflitos com `a6-save-slots`.

### 3.1 Backup

```bash
# Writer JVM parado
./scripts/staging-down.sh
# ou: docker compose -f docker-compose.staging.yml stop odisseu-jvm

./scripts/staging-backup.sh \
  .datomic-local/<storage-staging-alvo> \
  .datomic-local/raid-piloto-bak-$(date -u +%Y%m%dT%H%M%SZ)
```

Compose volume: documentar cópia Alpine do volume nomeado (`odisseu-staging-datomic` **ou** volume Core autorizado — nunca prod).

### 3.2 Migrations (só no alvo autorizado)

Ordem sugerida pós-merge de schemas:

1. Garantir cadeia inclui deps A6 anteriores + `025` (+ `026` se save-slots integrado)
2. Aplicar `029-raid-lobby`
3. Aplicar `030-raid-auto`
4. Boot JVM com `ODISSEU_BRIDGE_SEED` conforme política do ambiente (staging seed ≠ prod)

**Não** rodar migrate enquanto classificação for BLOCKED.

### 3.3 Deploy bridge / JVM / Node

1. Imagem/artefato com código consolidado (raides + save-slots reconciliados)
2. Compose staging (ou Core **se** isolamento comprovado)
3. Kill switches **OFF** no primeiro boot:
   - Bridge: `:raid-auto-tick-enabled? false` (default)
   - Node: `POKEMON_RAID_AUTO_TICK=false` (default)
4. Confirmar `APP_ENV=staging`, WA path `*_staging`/`*_core`, `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED=1`

### 3.4 Ativação grupo piloto (depois dos smokes manuais)

```text
1. POKEMON_RAID_AUTO_TICK permanece false
2. PUT /v1/raids/auto/schedule  { chatId: <DEV_GROUP_ID>, enabled: false }  # noop seguro
3. Abrir raide MANUAL no grupo piloto → validar lobby/combate
4. Só então: bridge raid-auto-tick-enabled?=true (restart controlado)
5. Node: POKEMON_RAID_AUTO_TICK=true
6. PUT schedule { enabled: true, nextAt: <agora+buffer>, intervalMs: >= 120000 }
7. Observar um ciclo; enabled=false ao terminar
```

### 3.5 Smoke (comandos — não executar agora)

```bash
./scripts/test-raid-homolog-fase4.sh
./scripts/test-raid-auto-fase3.sh
./scripts/test-raid-lobby-fase2.sh
./scripts/test-m4-raid.sh
# No alvo (após autorização):
# ./scripts/staging-smoke.sh
# Mensagens: !pk raide … no DEV_GROUP_ID apenas
```

### 3.6 Rollback / recuperação

| Situação | Ação |
| --- | --- |
| Pausar spawns | `enabled=false` na agenda **ou** `POKEMON_RAID_AUTO_TICK=false` **ou** bridge tick false |
| Combate em andamento | não cancelar via kill switch global de tick — deixa expirar/concluir |
| Regressão grave | stop Node+JVM → `./scripts/staging-restore.sh <bak>` → subir com ticks OFF |
| WA errado | não reutilizar `.wwebjs_auth` de produção; trocar path/sessão de teste |

---

## 4. Critérios de aprovação (mapa evidência → status)

| Critério | Evidência atual | Status |
| --- | --- | --- |
| Sem duplicação de efeitos econômicos | Fase 4: rewards/captura idempotentes (fixtures) | CONDITIONAL (falta piloto live) |
| Saves fora do piloto intactos | Isolamento live não provado; `DEV_GROUP_ID` local vazio | **BLOCKED** |
| Reinício recupera lobby/combate | Fase 4 testes reopen | CONDITIONAL |
| Scheduler + kill switches | Fase 3/3.1/4 + defaults OFF | CONDITIONAL |
| Logs sem dados sensíveis | allowlist `raid_auto_log` | CONDITIONAL |
| Outbox recibos idempotentes | Fase 3.1/4 ack/duplicate | CONDITIONAL |

---

## 5. Pendências para sair de BLOCKED

1. **Demonstrar isolamento live** (leitura autorizada): system/db/volume, WA path/sessão, tokens ≠ prod, saves ≠ prod.  
2. Preencher e confirmar `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED` no alvo.  
3. **Reconciliar** worktree `a6-save-slots` (026 + demais) com raides (029/030) — merge manual futuro.  
4. Commitar/publicar cadeia de fases (hoje só working tree).  
5. Autorização explícita para migrate 029/030 no alvo isolado.  
6. Canário com ticks OFF → manual → auto em um único chat.

---

## 6. Comandos futuros de ativação (referência — **não executar**)

```bash
# Kill switches (manter false até canário)
# export POKEMON_RAID_AUTO_TICK=false

# Agenda piloto (HTTP bridge autenticada)
# PUT /v1/raids/auto/schedule
#   {"chatId":"<DEV_GROUP>","enabled":true,"intervalMs":3600000,"nextAt":<epoch>}

# Tick manual controlado
# POST /v1/raids/auto/tick

# Pausar
# PUT ... {"enabled":false}
# ou POKEMON_RAID_AUTO_TICK=false + bridge raid-auto-tick-enabled?=false
```

---

## 7. Riscos residuais

- Core A5.6 é `APP_ENV=staging` com writer Datomic — risco de apontar piloto ao volume live errado.  
- Duplicata visual WhatsApp (send sem ack).  
- Gaps 026 vs 029/030 até merge.  
- Código raide não commitado: risco de perda/desalinhamento entre worktrees.
