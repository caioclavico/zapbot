#!/bin/bash
# Prova isolada de que um processo posterior não grava nem remonta o storage.
# O diretório usado é temporário. Caminho de volume de produção é recusado.
set -euo pipefail

for arg in "$@"; do
  case "$arg" in
    *odisseu-a6-cutover*|/var/lib/docker/volumes/*)
      echo "prova recusou caminho de produção" >&2
      exit 2
      ;;
  esac
done

if ! command -v unshare >/dev/null 2>&1 || ! command -v mount >/dev/null 2>&1 || ! command -v capsh >/dev/null 2>&1; then
  echo "kernel_namespace_unavailable" >&2
  exit 3
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/a6-storage-proof.XXXXXX")"
cleanup() {
  umount "$work/storage" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT
mkdir -p "$work/storage"
echo preserved > "$work/storage/save"
mount --bind "$work/storage" "$work/storage"
mkfifo "$work/ctl"

unshare --mount bash -c '
  set -euo pipefail
  mount --make-rprivate /
  echo ready > "$1"
  read -r cmd < "$1"
  if [[ "$cmd" != "write" ]]; then
    echo "comando inesperado" >&2
    exit 1
  fi
  echo bridge >> "$2/save"
  echo wrote > "$1"
' _ "$work/ctl" "$work/storage" &
bridge=$!

read -r ready < "$work/ctl"
if [[ "$ready" != "ready" ]]; then
  echo "bridge não isolou o mount" >&2
  exit 1
fi
mount -o remount,bind,ro "$work/storage"
echo write > "$work/ctl"
read -r wrote < "$work/ctl"
wait "$bridge"
if [[ "$wrote" != "wrote" ]]; then
  echo "a bridge não gravou depois do fechamento compartilhado" >&2
  exit 1
fi
if echo later >> "$work/storage/save" 2>/dev/null; then
  echo "processo posterior gravou no storage" >&2
  exit 1
fi
if capsh --drop=cap_sys_admin -- -c "mount -o remount,bind,rw '$work/storage'"; then
  echo "processo posterior reabriu o storage para escrita" >&2
  exit 1
fi
grep -q preserved "$work/storage/save"
grep -q bridge "$work/storage/save"
echo "laterProcessWrite=erofs"
echo "ownerCanRearmWrite=false"
echo "othersSeeReadOnly=true"
echo "bridgeMountPrivate=true"
echo "capSysAdmin=false"
