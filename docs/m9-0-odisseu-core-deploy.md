# M9.0 — Preparar implantação na VM `odisseu-core`

**Branch:** `refactor/modular-monolith-datomic`  
**Status:** artefatos locais prontos; **deploy remoto NÃO executado**  
**Gates:** `cutover-ready? = false` · `rollback-recon-available? = false`

---

## 1. Objetivo

Preparar o monólito modular (Node + Chromium + Clojure/JVM + Datomic Local) para
a VM Google Cloud **`odisseu-core`**, isolada de:

| VM / serviço | Papel | M9.0 |
|---|---|---|
| `odisseu-zapbot` (GCP `35.238.24.225`) | Bot WhatsApp **produção** | **intocado** |
| `odisseu-pokemon` (GCP `34.68.189.66`) | Pokémon legado | **intocado** |
| Cassandra Oracle `144.22.248.79` | Persistência prod | **sem acesso** |
| `odisseu-core` | Novo monólito (teste/core) | alvo desta preparação |

---

## 2. Capacidade da VM (informada)

| Recurso | Valor |
|---|---|
| OS | Ubuntu 24.04.5 LTS |
| CPU | 2 vCPUs |
| RAM | 5,8 GiB |
| Swap | 2 GiB |
| Disco livre | ~45 GB |
| Docker | 29.9.1 + Compose 5.6.0 |
| JDK | OpenJDK 21 |

### Orçamento Compose (`docker-compose.odisseu-core.yml`)

| Serviço | `mem_limit` | `memswap_limit` | Notas |
|---|---|---|---|
| `odisseu-jvm` | 768 MiB | 1024 MiB | `-Xmx384m` |
| `odisseu-node` | 2560 MiB | 3584 MiB | Chromium + Node heap 384 MB |
| **Soma cgroup** | ~3,3 GiB | — | Margem SO ~2–2,5 GiB + swap 2 GiB |

**Viabilidade:** adequada para **um** stack Core (não simultâneo com o bot de
produção na mesma VM). Monitorar RSS Chromium sob carga; se OOM → reduzir
concorrência de mídia ou subir swap/RAM (fora do escopo M9.0 — sem recurso pago).

---

## 3. Artefatos criados

| Arquivo | Função |
|---|---|
| `docker-compose.odisseu-core.yml` | Stack jvm+node, ports loopback, volumes |
| `.env.odisseu-core.example` | Modelo de env (gitignored: `.env.odisseu-core`) |
| `scripts/odisseu-core-lib.sh` | Helpers + guards anti-produção |
| `scripts/odisseu-core-up.sh` | Build/up |
| `scripts/odisseu-core-down.sh` | Down (volumes preservados) |
| `scripts/odisseu-core-update.sh` | Rebuild/recreate |
| `scripts/odisseu-core-backup.sh` | Backup frio Datomic+journal+maint |
| `scripts/odisseu-core-restore.sh` | Restore para volumes nomeados |
| `scripts/odisseu-core-install.sh` | Procedimento impresso + check local |
| `scripts/odisseu-core-validate.sh` | Validação estática |
| `evaluate-m90-gates` | Gates explícitos `cutover-ready?=false` |

Reutiliza `Dockerfile` (Node/Chromium) e `Dockerfile.jvm` (bridge) existentes.

---

## 4. Persistência e isolamento

| Dado | Onde | Nota |
|---|---|---|
| Datomic Local | volume `odisseu-core-datomic` → `/data/datomic-local` | FileLock writer único |
| Journal | volume `odisseu-core-journal` → `/data/journal` | Domínio de falha distinto |
| Manutenção durable-op | volume `odisseu-core-maint` → `/data/maint` | |
| App data Node | volume `odisseu-core-data` | |
| Sessão WhatsApp | bind `.wwebjs_auth_core` + `clientId=core` | Nunca `.wwebjs_auth` prod |

Bridge: `127.0.0.1:18090` · Health Node: `127.0.0.1:3001` · rede `core-internal`.  
Sem profile Cassandra. `POKEMON_SERVICE_URL=""`.

`APP_ENV=staging` mantém adaptadores JVM/Datomic e gates de chat
(`DEV_GROUP_ID`, `STAGING_GROUP_CONFIRMED`, `STAGING_ALLOWED_USERS`).

---

## 5. Procedimento exato na VM (após autorização)

```bash
cd /home/caiohclavico/odisseu-core   # ou path acordado
git clone <repo> . && git checkout refactor/modular-monolith-datomic

cp .env.odisseu-core.example .env.odisseu-core
chmod 600 .env.odisseu-core
# Editar: ODISSEU_BRIDGE_TOKEN (segredo real); alinhar PLATFORM/POKEMON tokens

mkdir -p .wwebjs_auth_core
./scripts/odisseu-core-validate.sh
./scripts/odisseu-core-up.sh

# Auth — só conta Business de TESTE
# Em .env.odisseu-core: STAGING_WHATSAPP_CONFIRM=1 STAGING_ALLOW_QR=1
# ou STAGING_BOOTSTRAP_GROUP=1 para descobrir DEV_GROUP_ID sem processar cmds
docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core \
  logs -f odisseu-node

# Após READY + confirmação humana do grupo:
# DEV_GROUP_ID=<id@g.us>
# STAGING_ALLOWED_USERS=<jids>
# STAGING_GROUP_CONFIRMED=1
# (remover BOOTSTRAP)
./scripts/odisseu-core-update.sh
```

### Backup periódico

```bash
./scripts/odisseu-core-down.sh          # JVM down = backup consistente
./scripts/odisseu-core-backup.sh        # → ~/odisseu-recovery/m9-0-private/backups/<stamp>
# Opcional sessão: ODISSEU_CORE_BACKUP_SESSION=1 ./scripts/odisseu-core-backup.sh
./scripts/odisseu-core-up.sh
```

### Restore

```bash
./scripts/odisseu-core-down.sh
./scripts/odisseu-core-restore.sh ~/odisseu-recovery/m9-0-private/backups/<stamp>
./scripts/odisseu-core-up.sh
```

---

## 6. Isolamento WhatsApp

1. Número Business de **teste** apenas (nunca o principal da `odisseu-zapbot`).  
2. Sessão em `.wwebjs_auth_core` / `session-core`.  
3. `DEV_GROUP_ID` + `STAGING_GROUP_CONFIRMED=1` antes de processar comandos.  
4. `STAGING_ALLOWED_USERS` recomendado na VM.  
5. Bootstrap (`STAGING_BOOTSTRAP_GROUP=1`) não responde mensagens.

---

## 7. Testes desta etapa

```bash
chmod +x scripts/odisseu-core-*.sh
./scripts/odisseu-core-validate.sh
./scripts/odisseu-core-install.sh check
```

Não inclui: SSH, `docker compose up` remoto, QR, cutover, smoke WhatsApp na VM.

---

## 8. Riscos

| Risco | Mitigação |
|---|---|
| OOM Chromium sob 5,8 GiB | Limits Compose; métricas; evitar 2 bots na mesma VM |
| Confundir sessão prod | Bind `_core` + guards nos scripts |
| Journal vazio ainda | Volumes prontos; wiring durable-op operacional = marco futuro |
| Deploy acidental prod | Scripts/docs vetam odisseu-zapbot; sem workflow GHCR nesta etapa |
| Backup a quente | Scripts exigem bridge down |

---

## 9. Próximo (requer autorização)

1. SSH read-only / install na `odisseu-core`.  
2. Build/up remoto.  
3. QR Business de teste + `DEV_GROUP_ID`.  
4. Smoke `!pk` + medição RSS.  
5. Só então avaliar promoção de gates (ainda **sem** cutover de jogadores reais).

**Parar aqui até autorização explícita de alteração remota.**
