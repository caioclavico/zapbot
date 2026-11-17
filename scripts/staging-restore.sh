#!/usr/bin/env bash
# Restaura backup Datomic para diretório descartável.
# Writer deve estar parado. Não sobrescreve m53/m55 nem produção.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC="${1:?Uso: $0 <backup-dir> [dest-dir]}"
DEST="${2:-.datomic-local/m62-staging-restore}"

assert_safe() {
  if echo "$1" | grep -Eqi 'm53-real-offline|m55-real-offline|prod|oracle|odisseu-live'; then
    echo "Path proibido: $1" >&2
    exit 3
  fi
}

assert_safe "$SRC"
assert_safe "$DEST"

if [[ ! -d "$SRC" ]]; then
  echo "Backup inexistente: $SRC" >&2
  exit 2
fi

if curl -fsS -m 1 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "AVISO: bridge :18090 ainda responde. Pare o writer antes do restore." >&2
  exit 4
fi

mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
cp -a "$SRC" "$DEST"
echo "Restore OK: $SRC -> $DEST"
echo "Para usar: ODISSEU_BRIDGE_STORAGE=$DEST ODISSEU_BRIDGE_SYSTEM=<novo-system> ./scripts/staging-up.sh jvm"
