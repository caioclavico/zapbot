#!/usr/bin/env bash
# Encerra staging Compose (se ativo). Não afeta produção nem volumes M5.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
ENV_FILE="${STAGING_ENV_FILE:-.env.staging}"

if docker compose -f docker-compose.staging.yml ps -q 2>/dev/null | grep -q .; then
  echo "==> docker compose staging down (volumes preservados)"
  docker compose -f docker-compose.staging.yml --env-file "${ENV_FILE}" down
else
  echo "==> Nenhum compose staging ativo."
fi

# Mata bridge JVM local se estiver no ar (só porta staging padrão).
if command -v lsof >/dev/null 2>&1; then
  PIDS=$(lsof -tiTCP:18090 -sTCP:LISTEN 2>/dev/null || true)
  if [[ -n "${PIDS}" ]]; then
    echo "==> Encerrando processos na :18090 → ${PIDS}"
    kill ${PIDS} 2>/dev/null || true
  fi
fi
echo "==> staging-down ok"
