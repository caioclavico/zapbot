#!/usr/bin/env bash
# Encerra Compose odisseu-core. Volumes e sessão no host são preservados.
# Não afeta odisseu-zapbot / odisseu-pokemon / Cassandra remoto.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

if [[ -f "${ROOT}/${ODISSEU_CORE_ENV}" ]]; then
  odisseu_core_compose "$ROOT" down
else
  docker compose -f "${ROOT}/${ODISSEU_CORE_COMPOSE}" down 2>/dev/null || true
fi
echo "==> odisseu-core down (volumes preservados)"
