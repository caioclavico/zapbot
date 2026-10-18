#!/usr/bin/env bash
# M4.9 — raids no adaptador JVM + regressões M3.6/M4.8.
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
  "${ROOT}/.datomic-local/m49-tests" \
  "${ROOT}/.datomic-local/m49-smoke" \
  "${ROOT}/.datomic-local/m36-tests" \
  "${ROOT}/.datomic-local/m48-tests"

echo "==> M4.9 unit + smoke + regressões"
"${CLOJURE}" -M:test-m4-raid

echo "==> Unit Node (present + gym)"
node --test test/pokemon-jvm-gym.test.cjs test/pokemon-jvm-present.test.cjs \
  test/pokemon-jvm-raid.test.cjs

echo "==> OK M4.9"
