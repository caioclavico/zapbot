#!/usr/bin/env bash
# M9.6 — onboarding Pokémon (kit + starters) no adaptador JVM/Datomic.
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
  "${ROOT}/.datomic-local/m96-tests" \
  "${ROOT}/.datomic-local/m96-smoke"

echo "==> M9.6 unit + smoke HTTP"
"${CLOJURE}" -M:test-m9-6-onboarding

echo "==> Unit Node (presenters menu/onboarding)"
node --test test/pokemon-jvm-present.test.cjs

echo "==> OK M9.6"
