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

MODE="${1:-jvm}"
echo "==> Rebuild odisseu-core mode=${MODE} (volumes preservados)"
case "${MODE}" in
  jvm|"")
    odisseu_core_compose "$ROOT" up -d --build --remove-orphans odisseu-jvm
    ;;
  full|with-node)
    odisseu_core_compose "$ROOT" --profile with-node up -d --build --remove-orphans
    ;;
  *)
    echo "Uso: $0 [jvm|full]" >&2
    exit 2
    ;;
esac
odisseu_core_compose "$ROOT" ps
echo "==> update ok — health JVM: curl -fsS http://127.0.0.1:18090/health"
