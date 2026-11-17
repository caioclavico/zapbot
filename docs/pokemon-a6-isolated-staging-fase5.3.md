# A6 — Fase 5.3: Preparação do staging isolado

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-10-31  
**Modo:** preparação de arquivos + validação estática — **sem** deploy, migrate, backfill, restart, merge, escrita remota ou mensagens WhatsApp  
**Worktrees preservados:** `a6-save-slots`, `feature/a6-raid-readiness-fase5`, este WT  
**Antecedente:** Fase 5.2 → **BLOCKED** (piloto no Core A5.6 = volume `odisseu-core-a56-datomic` + `ALLOW_ALL_GROUPS=true`)

---

## Decisão: **CONDITIONAL**

A stack A6 isolada está **preparada e validada estaticamente**. A implantação futura é condicional ao **alvo de hospedagem** e à **confirmação explícita** de grupo/WhatsApp.

| Critério | Status |
| --- | --- |
| Compose/env exclusivos (≠ Core A5.6) | **PASS** |
| Volumes/system/db/portas/sessão WA distintos | **PASS** |
| Proteções fail-closed + raid tick OFF | **PASS** |
| Schemas 025–030 no instalador + imagem local | **PASS** |
| Suítes A6 (lobby/auto/homolog/reconcile/economia) | **PASS** |
| Sem containers/volumes a6-staging provisionados | **PASS** (não criados) |
| Core A5.6 intacto (sem restart/migrate) | **PASS** |
| RAM Core para Node+JVM A6 **junto** do Core | **CONDITIONAL** — apertado; preferir JVM-only ou VM separada |
| `STAGING_GROUP_CONFIRMED` / conta WA de teste | **CONDITIONAL** — permanece `0` até confirmação explícita |
| Deploy / migrate | **não executado** (restrito) |

---

## 1. Recursos (somente leitura)

### 1.1 Mac local

| Item | Valor |
| --- | --- |
| RAM | 16 GiB |
| Disco `/` | ~168 GiB livres |
| Portas `18091` / `3002` | livres |
| Volumes `*a6*` / a56 | nenhum volume odisseu ativo neste host |
| Containers A6 | nenhum |

**Adequação:** suficiente para stack completa (JVM + Node profile `with-node`) em homologação local.

### 1.2 VM Core (`odisseu-core`) — RO SSH

| Item | Valor |
| --- | --- |
| MemTotal | 5.8 GiB |
| MemAvailable | ~3.6 GiB |
| Swap | 2.0 GiB (~70 MiB usados) |
| Disco `/` | 48G · ~19G livres (61%) |
| Node live | `odisseu-core-node` · ~1.18 / 2.5 GiB |
| JVM live | `odisseu-core-jvm` · ~288 / 768 MiB |
| Portas | `127.0.0.1:18090`, `127.0.0.1:3001` **em uso** |
| Volumes A5.6 | `odisseu-core-a56-{datomic,journal,maint,data}` |
| System/DB live | `odisseu-m90-core` / `core` |
| Rede | `odisseu-core_core-internal` |

**Adequação:**

- **JVM-only A6** (`mem_limit` 512 MiB, porta `18091`): **viável** sem tocar volumes a56, desde que não se monte path Core.
- **Node + JVM A6 no mesmo host** (~2.5 GiB Node + 0.5 GiB JVM + Core existente): **arriscado** — pode pressionar MemAvailable; **recomendação: VM separada** para profile `with-node`.
- **Não** provisionar recursos automaticamente nesta fase (conforme restrição).

---

## 2. Configuração proposta (isolada)

### 2.1 Arquivos

| Arquivo | Função |
| --- | --- |
| `docker-compose.a6-staging.yml` | Projeto Compose `odisseu-a6-staging` |
| `.env.a6-staging.example` | Template fail-closed (nunca copiar `.env` do Core) |
| `scripts/a6-staging-static-audit.sh` | Auditoria estática de isolamento |
| `test/a6-staging-isolation.test.cjs` | Testes de configuração/autorização (estáticos) |

### 2.2 Identificadores (≠ Core)

| Dimensão | Core A5.6 (live) | A6 staging (proposto) |
| --- | --- | --- |
| Compose project | `odisseu-core` (+ overlays) | `odisseu-a6-staging` |
| System Datomic | `odisseu-m90-core` | **`odisseu-a6-staging`** |
| DB Pokémon | `core` | **`core-a6-staging`** |
| DB platform | (platform Core) | **`platform-a6-staging`** |
| Volume Datomic | `odisseu-core-a56-datomic` | **`odisseu-a6-staging-datomic`** |
| Journal / maint | `odisseu-core-a56-{journal,maint}` | **`odisseu-a6-staging-{journal,maint}`** |
| Bridge host port | `127.0.0.1:18090` | **`127.0.0.1:18091`→18090** |
| Node host port | `127.0.0.1:3001` | **`127.0.0.1:3002`→3001** (profile) |
| Rede | `odisseu-core_core-internal` | **`odisseu-a6-staging_internal`** |
| Sessão WA | `.wwebjs_auth_core` / `client_id=core` | **`.wwebjs_auth_a6_staging` / `a6-staging`** |

Nenhum mount do volume A5.6. Nenhuma escrita ao system/db Core. Sessão WhatsApp **não** compartilhada.

### 2.3 Proteções (defaults)

```text
APP_ENV=staging
ALLOW_ALL_GROUPS=false
POKEMON_RAID_AUTO_TICK=false
STAGING_GROUP_CONFIRMED=0          # até confirmação explícita
STAGING_STRICT_USER_ALLOWLIST=1
STAGING_BOOTSTRAP_GROUP=0
STAGING_ALLOW_QR=0
Bridge bind publicado só em 127.0.0.1
chat-autorizado? fail-closed (sem DEV_GROUP_ID + confirm → nega)
```

Guardrail adicional em `assert-storage-safe!`: recusa paths contendo `a56-datomic`, `odisseu-core-a56`, `odisseu-m90-core`.

### 2.4 Perfis de execução

1. **Default (JVM only):** `docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging up -d --build`  
   — adequado à VM Core se RAM for o gargalo; sem WhatsApp.
2. **`with-node`:** acrescentar `--profile with-node`  
   — preferir Mac local ou **VM separada**; conta WA de **teste** + QR/sessão própria.

---

## 3. Build reconciliado (schemas 025–030)

Instalador (`schema.clj`) em ordem:

1. `025-save-slot-metadata.edn`  
2. `026-battle-idle.edn`  
3. `027-bag-daily.edn`  
4. `028-economy-ledger.edn`  
5. `029-raid-lobby.edn`  
6. `030-raid-auto.edn`  

`Dockerfile.jvm` faz `COPY migrations ./migrations`. Imagem local construída:

- Tag: `odisseu-jvm:a6-staging-local`
- Verificado via `docker run --rm --entrypoint ls …` que os seis EDNs 025–030 estão na imagem
- **Nenhum** `ensure-db!` / migrate contra volume a56 ou Core
- Volumes `odisseu-a6-staging-*` **não** foram criados nesta fase

Migrations só serão aplicadas no **primeiro** start do bridge A6 contra o volume **novo** `odisseu-a6-staging-datomic` (banco vazio `core-a6-staging`) — procedimento futuro, fora desta fase.

---

## 4. Validação estática executada

| Check | Resultado |
| --- | --- |
| `docker compose … config` | PASS |
| `scripts/a6-staging-static-audit.sh` | PASS (FAIL=0) |
| `node --test test/a6-staging-isolation.test.cjs` | 7/7 PASS |
| Comparação IDs Datomic A6 vs Core | distintos |
| Build `odisseu-a6-jvm` | PASS |
| Migrations na imagem | 025–030 presentes |
| `clojure -M:test-raid-lobby-fase2` | 23/23 · 76 assert |
| `clojure -M:test-raid-auto-fase3` | 19/19 · 76 assert |
| `clojure -M:test-raid-homolog-fase4` | 13/13 · 114 assert · `staging-touched? false` |
| `clojure -M:test-raid-save-slots-reconcile` | 3/3 · 13 assert |
| `clojure -M:test-a6-economy-capacity` | economy+capacity+idle PASS |

---

## 5. Procedimento de implantação futura (não executar agora)

### Pré-requisitos

1. Escolher alvo: **Mac local**, **VM dedicada**, ou **Core JVM-only**.  
2. Se Core + Node: **reavaliar RAM** ou provisionar VM separada (manual).  
3. `cp .env.a6-staging.example .env.a6-staging`  
4. Gerar **token novo** (`openssl rand -hex 32`) — **nunca** reutilizar token/credenciais do Core.  
5. Manter `STAGING_GROUP_CONFIRMED=0` e `POKEMON_RAID_AUTO_TICK=false` no primeiro boot.  
6. Cold backup do Core A5.6 **antes** de qualquer operação no mesmo host (mesmo sem montar a56).

### Passos (futuros)

```bash
# Validar de novo
bash scripts/a6-staging-static-audit.sh
ODISSEU_BRIDGE_TOKEN=… docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging config

# Subir só JVM (recomendado primeiro)
docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging up -d --build

# Health loopback A6 (≠ 18090 do Core)
curl -fsS http://127.0.0.1:18091/health

# Só depois, se alvo tiver RAM + conta WA de teste:
# 1) descobrir DEV_GROUP_ID no grupo de TESTE
# 2) STAGING_GROUP_CONFIRMED=1 sob autorização explícita
# 3) --profile with-node
# 4) POKEMON_RAID_AUTO_TICK permanece false até liberação de piloto
```

### Proibições permanentes neste piloto

- Montar `odisseu-core-a56-*`  
- Apontar `ODISSEU_BRIDGE_SYSTEM/DB` para Core  
- Compartilhar `.wwebjs_auth_core`  
- `ALLOW_ALL_GROUPS=true`  
- Ligar tick automático sem checklist Fase 5.x  
- Migrar/escrever no Datomic A5.6 existente

### Rollback (futuro)

- `docker compose -f docker-compose.a6-staging.yml --env-file .env.a6-staging down`  
- Remover volumes **somente** `odisseu-a6-staging-*` se desejar wipe do lab  
- Core A5.6 permanece intocado

---

## 6. Evidências de isolamento

- Compose renderizado sem strings `odisseu-core-a56`, `a56-datomic`, `odisseu-m90-core`, `wwebjs_auth_core`.  
- Portas A6 (`18091`/`3002`) ≠ Core (`18090`/`3001`).  
- Rede e volumes com prefixo `odisseu-a6-staging`.  
- Homolog fase 4 reportou `staging-touched? false` nos labs sintéticos.  
- Nenhum container/volume `a6-staging` existia após o build (só imagem local).  
- Core live inspecionado em RO; containers Core **não** reiniciados.

---

## 7. Classificação e próximos passos

**CONDITIONAL** — preparação completa; go-live do piloto exige:

1. Alvo com capacidade (Mac / VM separada / ou JVM-only no Core).  
2. `.env.a6-staging` com secrets **novos**.  
3. Confirmação explícita de `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED=1` antes do Node.  
4. Manter `POKEMON_RAID_AUTO_TICK=false` até checklist de liberação.  
5. Executar deploy/migrate **somente** contra volumes `odisseu-a6-staging-*`.

Itens 1–5 estão **fora** desta fase (restritos).
