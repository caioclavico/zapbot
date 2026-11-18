#!/usr/bin/env bash
# Checklist de instalação na VM odisseu-core (executar NA VM após autorização).
# Esta etapa M9.0 NÃO faz SSH remoto — imprime/valida pré-requisitos locais.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-check}" # check | print-remote

cat <<'EOF'
# M9.0 — instalação odisseu-core (procedimento)

Pré-requisitos da VM (informados): Ubuntu 24.04, 2 vCPU, 5,8 GiB RAM, 2 GiB swap,
Docker 29.x, Compose 5.x, OpenJDK 21, ~45 GB livres.

## Na VM (após autorização SSH)

```bash
# 1) Usuário e diretório (exemplo)
sudo mkdir -p /home/caiohclavico/odisseu-core
sudo chown "$USER:$USER" /home/caiohclavico/odisseu-core
cd /home/caiohclavico/odisseu-core

# 2) Código (branch refactor/modular-monolith-datomic)
git clone <repo-url> .
git checkout refactor/modular-monolith-datomic
# ou: git fetch && git checkout <SHA autorizado>

# 3) Env
cp .env.odisseu-core.example .env.odisseu-core
chmod 600 .env.odisseu-core
nano .env.odisseu-core   # token real; DEV_GROUP_ID após bootstrap

# 4) Sessão isolada
mkdir -p .wwebjs_auth_core
# NUNCA copiar .wwebjs_auth da odisseu-zapbot

# 5) Subir
./scripts/odisseu-core-up.sh

# 6) Auth (conta Business de TESTE)
# Edite .env.odisseu-core: STAGING_WHATSAPP_CONFIRM=1 STAGING_ALLOW_QR=1
# Ou bootstrap de grupo: STAGING_BOOTSTRAP_GROUP=1
# docker compose -f docker-compose.odisseu-core.yml --env-file .env.odisseu-core logs -f odisseu-node

# 7) Após READY + confirmação de grupo:
# DEV_GROUP_ID=...@g.us
# STAGING_ALLOWED_USERS=...
# STAGING_GROUP_CONFIRMED=1
# (remover STAGING_BOOTSTRAP_GROUP)
./scripts/odisseu-core-update.sh   # recreate node com gates

# 8) Backup periódico (JVM down)
./scripts/odisseu-core-down.sh
./scripts/odisseu-core-backup.sh
./scripts/odisseu-core-up.sh
```

## Firewall / exposição

- 18090 e 3001 apenas em 127.0.0.1 (Compose).
- Não abrir essas portas no Security Group / firewall da VM.
- SSH administrativo apenas; sem publish público da bridge.

## Não fazer

- Deploy nesta VM a partir do workflow de produção (odisseu-zapbot).
- Montar sessão WhatsApp principal.
- Apontar Cassandra de produção.
- Cutover / dual-write / migrar jogadores reais.
EOF

if [[ "$MODE" == "check" ]]; then
  echo
  echo "==> Validação local (host atual — não é a VM remota)"
  command -v docker >/dev/null && docker --version || echo "docker: ausente neste host"
  docker compose version 2>/dev/null || echo "compose: ausente"
  [[ -f docker-compose.odisseu-core.yml ]] && echo "compose file: ok"
  [[ -f .env.odisseu-core.example ]] && echo "env example: ok"
  if command -v docker >/dev/null; then
    docker compose -f docker-compose.odisseu-core.yml config >/dev/null \
      && echo "compose config: syntactically ok (sem .env secrets)" \
      || echo "compose config: precisa .env.odisseu-core para validar tokens"
  fi
  echo "cutover-ready?=false — M9.0 só prepara implantação."
fi
