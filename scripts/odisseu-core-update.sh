#!/usr/bin/env bash
# Atualiza imagens odisseu-core a partir do código no host (rebuild).
# Preserva volumes Datomic/journal/maint e bind da sessão.
# Não executa deploy na odisseu-zapbot.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

odisseu_core_require_env "$ROOT"

echo "==> Rebuild + recreate odisseu-core (volumes preservados)"
odisseu_core_compose "$ROOT" up -d --build --remove-orphans
odisseu_core_compose "$ROOT" ps
echo "==> update ok — verifique health em 127.0.0.1:18090 e :3001"
