#!/usr/bin/env bash
# Backup consistente do storage Datomic de staging.
# REQUER: writer JVM parado (ou compose stop odisseu-jvm) antes de copiar.
# Nunca aponta para m53/m55-real-offline. Sem produção.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SRC="${1:-.datomic-local/m61-staging}"
DEST="${2:-.datomic-local/m62-staging-bak-$(date -u +%Y%m%dT%H%M%SZ)}"

assert_safe() {
  if echo "$1" | grep -Eqi 'm53-real-offline|m55-real-offline|prod|oracle|odisseu-live'; then
    echo "Path proibido: $1" >&2
    exit 3
  fi
}

assert_safe "$SRC"
assert_safe "$DEST"

if [[ ! -d "$SRC" ]]; then
  echo "Origem inexistente: $SRC" >&2
  exit 2
fi

if curl -fsS -m 1 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "AVISO: bridge :18090 ainda responde. Pare o writer antes do backup consistente." >&2
  echo "  ./scripts/staging-down.sh   ou   docker compose -f docker-compose.staging.yml stop odisseu-jvm" >&2
  exit 4
fi

mkdir -p "$(dirname "$DEST")"
rm -rf "$DEST"
cp -a "$SRC" "$DEST"
echo "Backup OK: $SRC -> $DEST"
du -sh "$DEST"
