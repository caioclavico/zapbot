#!/bin/bash
# Manutenção exclusiva só em volume de laboratório. O volume live é recusado.
# Quem administra o host ou o Docker continua capaz de montar o volume por fora.
set -euo pipefail

if [[ "${A6_ISOLATED:-}" != "1" ]]; then
  echo "manutenção exclusiva recusada fora de ensaio isolado" >&2
  exit 2
fi

volume="${1:-}"
case "$volume" in
  ""|*odisseu-a6-cutover*|*odisseu-core*)
    echo "volume live ou ausente recusado" >&2
    exit 2
    ;;
esac

holders="$(docker ps -a --filter "volume=$volume" --format '{{.Names}}')"
if [[ -n "$holders" ]]; then
  echo "o volume ainda está em outro container" >&2
  exit 2
fi

echo "exclusive volume=$volume holders=0"
