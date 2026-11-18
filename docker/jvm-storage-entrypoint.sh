#!/bin/bash
# Repassa o comando da JVM. Com ODISSEU_STORAGE_NAMESPACE=1, o processo da
# bridge fica num mount namespace privado e o caminho compartilhado do
# Datomic passa a ser somente leitura para qualquer processo iniciado depois.
set -euo pipefail

if [[ "${ODISSEU_STORAGE_NAMESPACE:-}" != "1" ]]; then
  exec "$@"
fi

storage="${ODISSEU_BRIDGE_STORAGE:-/data/datomic-local}"
case "$storage" in
  /data/datomic-local) ;;
  *)
    echo "Storage da bridge fora de /data/datomic-local." >&2
    exit 2
    ;;
esac
if [[ ! -d "$storage" ]]; then
  echo "Diretório do Datomic ausente." >&2
  exit 2
fi

ready="$(mktemp)"
unshare --mount bash -c '
  set -euo pipefail
  mount --make-rprivate /
  echo ready > "$1"
  shift
  exec "$@"
' _ "$ready" "$@" &
bridge=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do
  if [[ "$(cat "$ready" 2>/dev/null || true)" == "ready" ]]; then
    break
  fi
  sleep 0.1
done
rm -f "$ready"
if ! mount -o remount,bind,ro "$storage"; then
  kill "$bridge" 2>/dev/null || true
  echo "Não foi possível fechar o storage compartilhado para leitura." >&2
  exit 2
fi
wait "$bridge"
