#!/usr/bin/env bash
# Operação só do serviço odisseu-jvm (M9.1). Não inicia Node/Chromium.
# Uso: ./scripts/odisseu-core-jvm.sh {up|down|restart|health|logs|ps|persist-check}
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

CMD="${1:-}"
[[ -n "$CMD" ]] || { echo "Uso: $0 {up|down|restart|health|logs|ps}" >&2; exit 2; }

case "$CMD" in
  up)
    odisseu_core_require_env "$ROOT"
    odisseu_core_compose "$ROOT" up -d --build odisseu-jvm
    exec "$0" health
    ;;
  down)
    # Para só a JVM; não remove volumes; não toca perfil with-node se estiver parado.
    if [[ -f "${ROOT}/${ODISSEU_CORE_ENV}" ]]; then
      odisseu_core_compose "$ROOT" stop odisseu-jvm
      odisseu_core_compose "$ROOT" rm -f odisseu-jvm
    else
      docker stop odisseu-core-jvm 2>/dev/null || true
      docker rm -f odisseu-core-jvm 2>/dev/null || true
    fi
    echo "==> odisseu-jvm parado (volumes preservados)"
    ;;
  restart)
    odisseu_core_require_env "$ROOT"
    odisseu_core_compose "$ROOT" restart odisseu-jvm
    exec "$0" health
    ;;
  health)
    echo "==> GET /health (sem Bearer — contrato bridge)"
    curl -fsS -m 5 http://127.0.0.1:18090/health
    echo
    ;;
  logs)
    odisseu_core_compose "$ROOT" logs --tail=120 -f odisseu-jvm
    ;;
  ps)
    odisseu_core_compose "$ROOT" ps
    docker ps --filter name=odisseu-core-node --format '{{.Names}} {{.Status}}' || true
    if docker ps --format '{{.Names}}' | grep -q '^odisseu-core-node$'; then
      echo "AVISO: odisseu-core-node está rodando (profile with-node ativo)." >&2
    else
      echo "OK: odisseu-core-node não está em execução."
    fi
    ;;
  *)
    echo "Uso: $0 {up|down|restart|health|logs|ps}" >&2
    exit 2
    ;;
esac
