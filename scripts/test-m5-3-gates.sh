#!/usr/bin/env bash
# M5.3B gates — fixtures representativas. Sem dados reais / sem produção.
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
  "${ROOT}/.datomic-local/m53-tests" \
  "${ROOT}/.datomic-local/m51-tests" \
  "${ROOT}/.datomic-local/m50-tests"

echo "==> M5.3B gates (fixtures sintéticas)"
"${CLOJURE}" -M:test-m5-3-gates
echo "==> OK gates. Import real requer script privado + autorização."
