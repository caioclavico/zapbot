#!/usr/bin/env bash
# Sobe odisseu-core. Default M9.1: SOMENTE odisseu-jvm (sem Node/Chromium/WhatsApp).
# Uso:
#   ./scripts/odisseu-core-up.sh           # jvm
#   ./scripts/odisseu-core-up.sh jvm
#   ./scripts/odisseu-core-up.sh full      # jvm + node (profile with-node)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

MODE="${1:-jvm}"
odisseu_core_require_env "$ROOT"
odisseu_core_assert_not_prod_paths "${WWEBJS_DATA_PATH:-.wwebjs_auth_core}"

echo "==> odisseu-core up mode=${MODE}"
echo "    system=odisseu-m90-core db=core storage=/data/datomic-local"
echo "    bridge=127.0.0.1:18090  cutover-ready=false"

case "${MODE}" in
  jvm|"")
    echo "    serviços: odisseu-jvm (Node NÃO iniciado)"
    odisseu_core_compose "$ROOT" up -d --build odisseu-jvm
    ;;
  full|with-node)
    mkdir -p .wwebjs_auth_core
    echo "    serviços: odisseu-jvm + odisseu-node (profile with-node)"
    odisseu_core_compose "$ROOT" --profile with-node up -d --build
    ;;
  *)
    echo "Uso: $0 [jvm|full]" >&2
    exit 2
    ;;
esac

odisseu_core_compose "$ROOT" ps
echo "==> Health JVM:"
for _ in $(seq 1 60); do
  if curl -fsS -m 2 http://127.0.0.1:18090/health >/dev/null 2>&1; then
    curl -fsS -m 2 http://127.0.0.1:18090/health | head -c 240
    echo
    exit 0
  fi
  sleep 2
done
echo "(health ainda não respondeu — veja: ./scripts/odisseu-core-jvm.sh logs)" >&2
exit 1
