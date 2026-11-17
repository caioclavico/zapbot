#!/usr/bin/env bash
# M4.7 — ginásios (consultas/time/heal) + regressões.
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
  "${ROOT}/.datomic-local/m47-tests" \
  "${ROOT}/.datomic-local/m47-smoke" \
  "${ROOT}/.datomic-local/m35-tests"

echo "==> M4.7 unit + smoke + regressão M3.5"
"${CLOJURE}" -M:test-m4-gym

echo "==> Unit Node (gym + present)"
node --test test/pokemon-jvm-gym.test.cjs test/pokemon-jvm-present.test.cjs

echo "==> Regressão caça/captura Node"
node --test test/pokemon-jvm-hunt.test.cjs test/pokemon-jvm-capture.test.cjs \
  test/pokemon-jvm-media.test.cjs

echo "==> OK M4.7"
