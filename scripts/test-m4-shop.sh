#!/usr/bin/env bash
# M4.11 — loja/itens no adaptador JVM + regressão M4.10.
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
  "${ROOT}/.datomic-local/m411-tests" \
  "${ROOT}/.datomic-local/m411-smoke" \
  "${ROOT}/.datomic-local/m410-tests"

echo "==> M4.11 unit + smoke + regressões"
"${CLOJURE}" -M:test-m4-shop

echo "==> Unit Node (loja + regressões present)"
node --test test/pokemon-jvm-shop.test.cjs test/pokemon-jvm-joy-eggs.test.cjs \
  test/pokemon-jvm-present.test.cjs

echo "==> OK M4.11"
