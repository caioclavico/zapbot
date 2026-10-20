#!/usr/bin/env bash
# Restaura backup M9.0 para volumes Docker odisseu-core.
# REQUER: stack down. Não toca produção.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

SRC="${1:?Uso: $0 <backup-dir>}"
odisseu_core_assert_not_prod_paths "$SRC"

if [[ ! -d "${SRC}/datomic" ]]; then
  echo "Backup inválido (falta ${SRC}/datomic)" >&2
  exit 2
fi

if curl -fsS -m 1 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "Pare a bridge antes do restore: ./scripts/odisseu-core-down.sh" >&2
  exit 4
fi

docker volume create odisseu-core-datomic >/dev/null
docker volume create odisseu-core-journal >/dev/null
docker volume create odisseu-core-maint >/dev/null

restore_vol() {
  local vol="$1" sub="$2"
  [[ -d "${SRC}/${sub}" ]] || return 0
  echo "==> Restore ${sub} → ${vol}"
  docker run --rm \
    -v "${vol}:/to" \
    -v "${SRC}/${sub}:/from:ro" \
    alpine:3.20 \
    sh -c 'rm -rf /to/* /to/.[!.]* 2>/dev/null; cp -a /from/. /to/'
}

restore_vol odisseu-core-datomic datomic
restore_vol odisseu-core-journal journal
restore_vol odisseu-core-maint maint

if [[ -d "${SRC}/session" ]]; then
  echo "==> Restore sessão → .wwebjs_auth_core"
  mkdir -p .wwebjs_auth_core
  cp -a "${SRC}/session/." .wwebjs_auth_core/
fi

echo "Restore OK. Suba com: ./scripts/odisseu-core-up.sh"
echo "Após volume loss: replay de journal (M8.3+) é manual / lab — cutover-ready?=false."
