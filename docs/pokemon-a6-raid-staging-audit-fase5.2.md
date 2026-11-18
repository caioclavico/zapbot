# A6 — Fase 5.2: Auditoria de isolamento do staging

**Branch:** `feature/a6-raid-reconcile-fase5.1`  
**Worktree:** `/Users/caioclavico/repos/zapbot-raid-reconcile`  
**Data:** 2026-10-31 (UTC)  
**Modo:** somente leitura — sem deploy, migrate, backfill, merge, restart, escrita remota ou mensagens WhatsApp  
**Worktrees preservados:** `a6-save-slots`, `feature/a6-raid-readiness-fase5`, este WT

---

## Decisão: **BLOCKED**

Não é seguro executar o piloto de raides A6 no alvo live inspecionado sem comprometer o Core A5.6 e/ou sem risco de notificações em grupos externos.

| Critério | Status |
| --- | --- |
| Isolamento Datomic vs Core A5.6 | **FAIL** — volume live = `odisseu-core-a56-datomic` |
| Destino WhatsApp restrito ao grupo piloto | **FAIL** — `ALLOW_ALL_GROUPS=true` |
| Writers concorrentes no mesmo volume | OK (1 JVM) |
| `POKEMON_RAID_AUTO_TICK` off | OK (ausente → default false) |
| `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED` | OK (presentes) |
| Schemas 029/030 no live | **FAIL** — imagem só até `027`; sem caps raid-auto |
| Staging Mac local isolado | N/A — stack local down |

---

## 1. Ambiente (evidências)

### 1.1 Mac local (não é o alvo live)

Comandos:

```bash
docker ps -a
docker volume ls
docker compose ls
curl -m 2 http://127.0.0.1:18090/health
curl -m 2 http://127.0.0.1:3001/health
```

| Item | Resultado |
| --- | --- |
| Compose staging local | nenhum projeto ativo |
| `:18090` / `:3001` | DOWN |
| Containers relevantes | só Cassandra/CI antigos (exited/unrelated) |
| `.env.staging` (zapbot WT) | `APP_ENV=staging`, `DEV_GROUP_ID=EMPTY`, sem `STAGING_GROUP_CONFIRMED`, sem `POKEMON_RAID_AUTO_*` |

### 1.2 VM Core (live)

```bash
ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146
hostname; docker ps; docker volume ls
docker inspect odisseu-core-jvm|odisseu-core-node   # mounts + env filtrado
curl -fsS http://127.0.0.1:18090/health
```

| Campo | Valor efetivo |
| --- | --- |
| Host | `odisseu-core` |
| Node | `odisseu-core-node` · imagem `odisseu-node:a64-pvp-seats-stable-20261031T165616Z-amd64` · healthy · `127.0.0.1:3001` |
| JVM | `odisseu-core-jvm` · imagem `odisseu-jvm:a64-pvp-seats-xp-20261031T163814Z-amd64` · healthy · `127.0.0.1:18090` |
| Compose ativo | `docker-compose.odisseu-core.yml` + `release-battle-idle.yml` + **`allow-all-groups.yml`** |
| `APP_ENV` | `staging` |
| `ODISSEU_BRIDGE_SYSTEM` | `odisseu-m90-core` |
| `ODISSEU_BRIDGE_DB` | `core` |
| Storage | `/data/datomic-local` ← volume **`odisseu-core-a56-datomic`** (+ journal/maint a56) |
| Seed/Reset | `false` / `false` |
| Writers | **1** processo JVM Datomic |
| Volumes não montados | `odisseu-core-datomic`, `odisseu-core-journal`, `odisseu-core-maint` (legado) |

**Conclusão de compartilhamento:** o banco live do “staging” **é** o Core A5.6. Qualquer piloto de raide aqui escreve no mesmo volume de saves/plataforma do Core.

Health JVM: `readsOnly=false`, caps de raid manuais (`raid-read/join/leave/start/attack/capture`) presentes; **sem** capabilities de raid-auto/outbox de agenda.

---

## 2. WhatsApp e grupos

| Check | Efetivo | Nota |
| --- | --- | --- |
| Sessão | `WWEBJS_DATA_PATH=/app/.wwebjs_auth_core`, `WWEBJS_CLIENT_ID=core` | ≠ path de produção `.wwebjs_auth` |
| `DEV_GROUP_ID` | SET (`len=23`, sufixo `@g.us`) | valor omitido neste relatório |
| `STAGING_GROUP_CONFIRMED` | `1` | OK |
| `STAGING_WHATSAPP_CONFIRM` | `1` | OK |
| `STAGING_BOOTSTRAP_GROUP` | `0` | OK |
| `STAGING_ALLOWED_USERS` | vazio | |
| `STAGING_STRICT_USER_ALLOWLIST` | `0` | |
| **`ALLOW_ALL_GROUPS`** | **`true`** (via overlay compose) | **bloqueante** |
| `POKEMON_RAID_AUTO_TICK` | ausente | default código = **false** (não ativado) |

Com `ALLOW_ALL_GROUPS=true`, `chat-autorizado?` libera qualquer `@g.us` mesmo em `APP_ENV=staging`. Outbox/anúncios automáticos de raide **poderiam** atingir grupos externos se o tick/agenda fossem ligados neste Node.

Nenhuma mensagem WhatsApp foi enviada nesta auditoria.

---

## 3. Persistência e migrations

### 3.1 Imagem live (arquivos no container)

```text
migrations/pokemon/ … 001 … 027-bag-daily.edn
# sem 028/029/030 na listagem do filesystem da imagem em execução
```

Caps confirmam raid M3.6/M4.9 básico; **não** há stack Fase 3 auto (029/030) neste build.

### 3.2 Repo reconciliado (sintético)

Cadeia pretendida: `025→026→027→028→029→030` (Fase 5.1).  
Diferença live vs sintético: live **sem** 029/030 (e possivelmente sem 028 no artefato da imagem).

### 3.3 Backup / restore (estratégia observada)

- Backups frios em `~/odisseu-recovery/` (ex.: `a56-cold-*`, `a64-028-cold-*`, restore-check).  
- Scripts locais do repo: `scripts/staging-backup.sh` / `staging-restore.sh` (paths proíbem `prod`/`oracle` no nome).  
- **Não** executado backup nem restore nesta fase.

---

## 4. Operacional

| Item | Evidência |
| --- | --- |
| Health JVM | HTTP 200, `status=up` |
| Health Node `:3001` | falhou com reset (container marcado healthy no Docker) — observar |
| Outbox raid-auto | não no build live; `GET /v1/events/pending` → **401** sem token (fail-closed auth) |
| Tick auto | off (`POKEMON_RAID_AUTO_TICK` ausente) |
| Pausar scheduler | N/A no live atual; no código reconciliado: `enabled=false` / tick false / bridge flag — combate em andamento não é cancelado pelo kill switch |
| Rollback | trocar compose release + restaurar volume a partir de cold backup (requer stop controlado — **não** feito) |
| Logs recentes Node | métricas de recursos; sem amostragem de PII exportada |

---

## 5. Pendências para sair de BLOCKED

1. **Alvo Datomic dedicado** ao piloto (volume/system/db ≠ `odisseu-core-a56-datomic` / ≠ prod).  
2. Remover **`ALLOW_ALL_GROUPS`** do compose do piloto; manter só `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED=1` (+ allowlist de usuários se desejado).  
3. Deploy do artefato reconciliado (025–030) **somente** no alvo isolado, com ticks OFF.  
4. Provar que outbox Node só entrega em chats autorizados (teste controlado pós-autorização).  
5. Backup frio do alvo imediatamente antes de qualquer migrate 029/030.

---

## 6. Comandos de leitura executados (resumo)

```bash
# Local
docker ps -a; docker volume ls; docker compose ls
curl -m 2 http://127.0.0.1:18090/health; curl -m 2 http://127.0.0.1:3001/health

# Core (BatchMode SSH, sem escrita)
ssh -o IdentitiesOnly=yes -i ~/.ssh/odisseu_core caiohclavico@35.192.113.146 \
  'hostname; docker ps; docker volume ls;
   docker inspect odisseu-core-jvm odisseu-core-node;
   curl -fsS http://127.0.0.1:18090/health;
   docker exec odisseu-core-jvm ls migrations/pokemon;
   curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18090/v1/events/pending'
```

Segredos (tokens/API keys) inspecionados apenas como `SET(secret)`; `DEV_GROUP_ID` reportado só como `SET(len=23)`.

---

## 7. Riscos residuais

- Pilotar no Core atual = escrever raides no volume A5.6 (mesmo que saves Pokémon já existam ou venham a existir).  
- `ALLOW_ALL_GROUPS=true` anula a proteção de grupo para mensagens.  
- Código Fase 3/5.1 ainda não está no live; ativar tick sem build/migrate corretos é inviável e perigoso.  
- Health HTTP do Node no host falhou nesta janela apesar do status Docker healthy.
