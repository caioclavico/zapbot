#!/usr/bin/env bash
# Testes M3.6 — concorrência de ginásios + raids + regressões.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

resolve_clojure() {
  if [[ -n "${CLOJURE_BIN:-}" && -x "${CLOJURE_BIN}" ]]; then echo "${CLOJURE_BIN}"; return; fi
  if [[ -x "${ROOT}/.tools/clojure/bin/clojure" ]]; then echo "${ROOT}/.tools/clojure/bin/clojure"; return; fi
  if command -v clojure >/dev/null 2>&1; then command -v clojure; return; fi
  echo "Clojure CLI não encontrado." >&2; exit 127
}

CLOJURE="$(resolve_clojure)"
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
mkdir -p "${CLJ_CONFIG}" "${GITLIBS}" \
  "${ROOT}/.datomic-local/m36-tests" \
  "${ROOT}/.datomic-local/m35-tests" \
  "${ROOT}/.datomic-local/m34-tests" \
  "${ROOT}/.datomic-local/m33-tests" \
  "${ROOT}/.datomic-local/m32-tests" \
  "${ROOT}/.datomic-local/m31-tests" \
  "${ROOT}/.datomic-local/m2-tests"

echo "==> M3.6 raids + ginásio concorrente (-Xmx256m)"
exec "${CLOJURE}" -M:test-m3-raid
