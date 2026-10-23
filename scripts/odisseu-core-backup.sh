#!/usr/bin/env bash
# Backup consistente Datomic + journal + maint da odisseu-core.
# REQUER: odisseu-core-jvm parado (writer FileLock liberado).
# Destino default: ~/odisseu-recovery/m9-0-private/backups/<stamp>/
# Nunca aponta para storages de produção / M5 referência.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DEST_ROOT="${ODISSEU_CORE_BACKUP_DIR:-${HOME}/odisseu-recovery/m9-0-private/backups}"
DEST="${1:-${DEST_ROOT}/${STAMP}}"

odisseu_core_assert_not_prod_paths "$DEST"

if curl -fsS -m 1 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "AVISO: bridge :18090 responde. Pare o writer antes do backup consistente:" >&2
  echo "  ./scripts/odisseu-core-down.sh   # ou: docker compose ... stop odisseu-jvm" >&2
  exit 4
fi

mkdir -p "$DEST"/{datomic,journal,maint,meta}
echo "==> Exportando volumes Docker → ${DEST}"

# Copia via container auxiliar (volumes nomeados do projeto odisseu-core).
copy_vol() {
  local vol="$1" sub="$2"
  docker run --rm \
    -v "${vol}:/from:ro" \
    -v "${DEST}/${sub}:/to" \
    alpine:3.20 \
    sh -c 'cp -a /from/. /to/ && du -sh /to'
}

copy_vol odisseu-core-datomic datomic
copy_vol odisseu-core-journal journal
copy_vol odisseu-core-maint maint

# Manifesto verificável do backup frio (sem incluir metadados mutáveis).
(
  cd "$DEST"
  find datomic journal maint -type f -print0 \
    | sort -z \
    | xargs -0 sha256sum > meta/SHA256SUMS
)

# Metadados (sem secrets).
{
  echo "stamp=${STAMP}"
  echo "compose=${ODISSEU_CORE_COMPOSE}"
  echo "system=odisseu-m90-core"
  echo "host=$(hostname 2>/dev/null || echo unknown)"
  echo "git=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "note=Backup a frio (JVM down). Sessão WhatsApp NÃO incluída — backup separado se necessário."
} > "${DEST}/meta/backup.txt"

# Snapshot opcional da sessão (host bind) — só se pedido.
if [[ "${ODISSEU_CORE_BACKUP_SESSION:-}" == "1" && -d .wwebjs_auth_core ]]; then
  echo "==> Incluindo sessão .wwebjs_auth_core (pedido via ODISSEU_CORE_BACKUP_SESSION=1)"
  mkdir -p "${DEST}/session"
  cp -a .wwebjs_auth_core/. "${DEST}/session/"
fi

echo "Backup OK: ${DEST}"
du -sh "$DEST"/*
