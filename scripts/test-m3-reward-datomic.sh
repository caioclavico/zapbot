#!/usr/bin/env bash
# Testes M3.2 — recompensas com fence + regressões.
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
  "${ROOT}/.datomic-local/m32-tests" \
  "${ROOT}/.datomic-local/m31-tests" \
  "${ROOT}/.datomic-local/m2-tests"

echo "==> M3.2 reward security (-Xmx256m)"
exec "${CLOJURE}" -M:test-m3-reward
