#!/usr/bin/env bash
# Smoke M1: Clojure/JVM + Datomic Local (somente desenvolvimento).
# Não sobe Node, Chromium, Cassandra nem altera deploy.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

resolve_clojure() {
  if [[ -n "${CLOJURE_BIN:-}" && -x "${CLOJURE_BIN}" ]]; then
    echo "${CLOJURE_BIN}"
    return
  fi
  if [[ -x "${ROOT}/.tools/clojure/bin/clojure" ]]; then
    echo "${ROOT}/.tools/clojure/bin/clojure"
    return
  fi
  if command -v clojure >/dev/null 2>&1; then
    command -v clojure
    return
  fi
  echo "Clojure CLI não encontrado." >&2
  echo "Instale com: https://clojure.org/guides/install_clojure" >&2
  echo "Ou: scripts/bootstrap-clojure-cli.sh (instala em .tools/clojure, gitignored)." >&2
  exit 127
}

CLOJURE="$(resolve_clojure)"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"

# Config Clojure opcionalmente no repo (não exige ~/.clojure).
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
mkdir -p "${CLJ_CONFIG}" "${GITLIBS}"

echo "==> Java"
java -version
echo "==> Clojure CLI: ${CLOJURE}"
"${CLOJURE}" --version
echo "==> Smoke Datomic Local (heap -Xmx256m, storage .datomic-local/smoke)"
echo "    Processo isolado: sem Chromium, sem Cassandra, sem Node."

STORAGE_DIR="${ROOT}/.datomic-local/smoke"
mkdir -p "${STORAGE_DIR}"

# Garante storage sob o repo (não grava em ~/.datomic por padrão do smoke).
exec "${CLOJURE}" -J-Dodisseu.datomic.storage-dir="${STORAGE_DIR}" \
  -M:smoke-datomic
