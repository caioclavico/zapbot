#!/usr/bin/env bash
# Prepara/verifica um ensaio A4.5.2 somente em cópia Datomic.
# Não cria backup, não para serviços e nunca referencia o volume oficial.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PHASE="${1:?Uso: $0 <snapshot|install> <storage-audit-dir> <system> <db> <snapshot.edn> [clojure-cmd]}"
STORAGE="${2:?Uso: $0 <snapshot|install> <storage-audit-dir> <system> <db> <snapshot.edn> [clojure-cmd]}"
SYSTEM="${3:?Uso: $0 <snapshot|install> <storage-audit-dir> <system> <db> <snapshot.edn> [clojure-cmd]}"
DB="${4:?Uso: $0 <snapshot|install> <storage-audit-dir> <system> <db> <snapshot.edn> [clojure-cmd]}"
SNAPSHOT="${5:?Uso: $0 <snapshot|install> <storage-audit-dir> <system> <db> <snapshot.edn> [clojure-cmd]}"
CLOJURE="${6:-clojure}"

case "$STORAGE" in
  *odisseu-core-datomic*|*prod*|*staging*) echo "storage de auditoria recusado: $STORAGE" >&2; exit 3;;
esac
case "$STORAGE" in *audit*|*a451*) ;; *) echo "use diretório audit-* ou a451-*" >&2; exit 3;; esac
if [[ -L "$STORAGE" ]]; then
  echo "storage de auditoria não pode ser link simbólico: $STORAGE" >&2
  exit 3
fi
if [[ ! -d "$STORAGE" ]]; then
  echo "storage de auditoria deve existir e ser uma cópia previamente restaurada: $STORAGE" >&2
  exit 3
fi
if [[ "$PHASE" == "install" && "${ODISSEU_A452_VOLUME_VALIDATED:-}" != "1" ]]; then
  echo "install exige ODISSEU_A452_VOLUME_VALIDATED=1 após validar a origem do volume" >&2
  exit 3
fi
CANONICAL_STORAGE="$(realpath -e "$STORAGE")"
case "$CANONICAL_STORAGE" in
  *odisseu-core-datomic*|*prod*|*staging*) echo "storage canônico recusado: $CANONICAL_STORAGE" >&2; exit 3;;
esac

mkdir -p "$(dirname "$SNAPSHOT")"
export CLASSPATH="${ROOT}/scripts:${CLASSPATH:-}"
exec "$CLOJURE" -M -e \
  "(load-file \"${ROOT}/scripts/odisseu-core-a452-audit.clj\") (odisseu.core-a452-audit/-main \"$PHASE\" \"$CANONICAL_STORAGE\" \"$SYSTEM\" \"$DB\" \"$SNAPSHOT\")"
