# M9.1 — Pré-deploy JVM/Datomic na `odisseu-core`

**Branch:** `refactor/modular-monolith-datomic`  
**Checkpoint base:** `f4e2607`  
**Gates:** `cutover-ready? = false` · `rollback-recon-available? = false`  
**Status:** correções + ensaio local; **deploy remoto NÃO executado**

---

## 1. Problemas encontrados e correções

| Achado | Correção |
|---|---|
| `up` default poderia subir Node+Chromium | Profile `with-node` no serviço Node; default = só `odisseu-jvm` |
| Docs M9.0 sugeriam journal como domínio de falha | Clarificado: volumes **lógicos**; mesmo disco ≠ falha independente |
| Dockerfile.jvm ENV ainda `m61-staging`/`staging` | Defaults alinhados a `odisseu-m90-core` / `core` |
| HEALTHCHECK start-period curto no 1º boot | `180s` (imagem + Compose) |
| Operação jvm-only pouco explícita | `scripts/odisseu-core-jvm.sh` + `up.sh jvm\|full` |
| `/health` + Bearer | **Confirmado:** `GET /health` **não** exige Authorization (`bridge.clj`) — healthcheck sem token está correto |
| `curl` no healthcheck | **Confirmado:** instalado no `Dockerfile.jvm` |
| Cassandra | Bridge não usa Cassandra; seed/reset default `false` |

Arquitetura da imagem: `eclipse-temurin:21-jdk-jammy` multi-arch. Preferir
**build na VM** (amd64 típico). Em Mac arm64: `--platform linux/amd64`.

---

## 2. Contrato `/health`

```text
GET http://127.0.0.1:18090/health
→ 200 {"ok":true,"data":{"status":"up","role":"pokemon-jvm-bridge",...}}
Sem header Authorization.
Demais rotas /v1/* exigem Bearer ${ODISSEU_BRIDGE_TOKEN}.
```

---

## 3. Comandos exatos na VM (após autorização)

```bash
cd /home/caiohclavico/odisseu-core   # path acordado
git fetch && git checkout <SHA-autorizado>

cp -n .env.odisseu-core.example .env.odisseu-core
chmod 600 .env.odisseu-core
# Definir ODISSEU_BRIDGE_TOKEN real (não change-me-*)
# ODISSEU_BRIDGE_SEED=false  ODISSEU_BRIDGE_RESET=false

./scripts/odisseu-core-validate.sh

# --- somente JVM (M9.1) ---
./scripts/odisseu-core-up.sh jvm
# equivalente: ./scripts/odisseu-core-jvm.sh up

./scripts/odisseu-core-jvm.sh health
./scripts/odisseu-core-jvm.sh ps          # deve NÃO listar odisseu-core-node running
./scripts/odisseu-core-jvm.sh logs        # Ctrl+C para sair

# Persistência: reinício do container, volume intacto
./scripts/odisseu-core-jvm.sh restart
./scripts/odisseu-core-jvm.sh health

# Parar só JVM (volumes permanecem)
./scripts/odisseu-core-jvm.sh down

# NÃO nesta etapa:
# ./scripts/odisseu-core-up.sh full
```

Firewall: não publicar `18090`/`3001` publicamente (Compose já usa `127.0.0.1`).

---

## 4. Critérios de sucesso JVM/Datomic

1. Container `odisseu-core-jvm` healthy.  
2. `GET /health` → `status=up`, `platformEnabled=true`.  
3. `odisseu-core-node` **ausente** (sem profile `with-node`).  
4. Volume `odisseu-core-datomic` contém dados após boot (FileLock + dbs `core`/`platform`).  
5. Após `restart`/`down`+`up`, health volta e dados sintéticos de lab (se criados) permanecem.  
6. Sem conexão Cassandra.  
7. `SEED=false` / `RESET=false` — sem wipe automático.

Ensaio local (host, sem Docker obrigatório):

```bash
./scripts/test-m9-1-jvm-datomic.sh
```

---

## 5. Journal / recon

Volumes `odisseu-core-journal` e `odisseu-core-maint` existem para wiring futuro.
Enquanto residirem no **mesmo disco** que o Datomic:

- **não** declarar domínio de falha independente;
- `rollback-recon-available? = false`;
- `cutover-ready? = false`.

---

## 6. Riscos restantes

| Risco | Nota |
|---|---|
| 1º `docker build` na VM lento | Prefetch deps no Dockerfile; start_period 180s |
| OOM se alguém subir `full` cedo | Profile with-node + docs |
| Token placeholder | `odisseu-core-up` rejeita `change-me*` |
| Deploy acidental prod | Scripts/docs vetam odisseu-zapbot |
| Persistência só validada em lab local até SSH | Autorizar deploy VM |

**Parar antes do deploy remoto — aguardar autorização.**
