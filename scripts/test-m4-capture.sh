#!/usr/bin/env bash
# M4.4 — captura real via bridge + regressões M2–M4.3.
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
  "${ROOT}/.datomic-local/m44-tests" \
  "${ROOT}/.datomic-local/m44-smoke" \
  "${ROOT}/.datomic-local/m41-tests" \
  "${ROOT}/.datomic-local/m42-tests" \
  "${ROOT}/.datomic-local/m42-contract" \
  "${ROOT}/.datomic-local/m41-contract"

echo "==> M4.4 unit + smoke HTTP + regressões M3.3/M3.4"
"${CLOJURE}" -M:test-m4-capture

echo "==> Regressão M4.1"
"${CLOJURE}" -M:test-m4-bridge

echo "==> Regressão M4.2"
"${CLOJURE}" -M:test-m4-tx

echo "==> Unit Node (cliente + present + comandos)"
node --test test/pokemon-jvm-client.test.cjs test/pokemon-jvm-present.test.cjs \
  test/pokemon-jvm-commands.test.cjs test/pokemon-jvm-capture.test.cjs

echo "==> Contratos Node↔JVM"
CLOJURE_BIN="${CLOJURE}" node scripts/test-pokemon-jvm-contract.cjs
CLOJURE_BIN="${CLOJURE}" node scripts/test-pokemon-jvm-tx-contract.cjs

echo "==> OK M4.4"
