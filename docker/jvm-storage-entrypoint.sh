#!/bin/bash
# Confere o mount antes do Java. Não remonta e não pede CAP_SYS_ADMIN.
# ODISSEU_STORAGE_NAMESPACE ficou inválido: a estratégia antiga abre o
# processo antes de isolar e foi retirada.
set -euo pipefail

if [[ -n "${ODISSEU_STORAGE_NAMESPACE:-}" ]]; then
  echo "ODISSEU_STORAGE_NAMESPACE não isola mais o storage. Use o volume exclusivo." >&2
  exit 2
fi

storage="${ODISSEU_BRIDGE_STORAGE:-/data/datomic-local}"
if [[ ! -r /proc/self/mountinfo ]]; then
  echo "mountinfo ausente; a JVM não sabe se o volume foi montado." >&2
  exit 2
fi
if ! awk -v dest="$storage" '$5 == dest { found = 1 } END { exit found ? 0 : 1 }' /proc/self/mountinfo; then
  echo "O storage não está montado antes da JVM." >&2
  exit 2
fi

cap="$(awk '/^CapEff:/ { print $2 }' /proc/self/status)"
if (( (16#$cap & (1 << 21)) != 0 )); then
  echo "CAP_SYS_ADMIN está efetiva. A JVM não inicia com essa capability." >&2
  exit 2
fi

exec "$@"
