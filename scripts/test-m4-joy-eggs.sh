#!/usr/bin/env bash
# M4.10 — Joy + ovos no adaptador JVM + regressão M3.7.
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
  "${ROOT}/.datomic-local/m410-tests" \
  "${ROOT}/.datomic-local/m410-smoke" \
  "${ROOT}/.datomic-local/m37-tests"

echo "==> M4.10 unit + smoke + regressões"
"${CLOJURE}" -M:test-m4-joy-eggs

echo "==> Unit Node (joy/ovos + regressões present)"
node --test test/pokemon-jvm-joy-eggs.test.cjs test/pokemon-jvm-raid.test.cjs \
  test/pokemon-jvm-present.test.cjs

echo "==> OK M4.10"
