#!/usr/bin/env bash
# M4.2 — regressões M2/M3.2/M4.1 + smoke tx + contrato Node.
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
  "${ROOT}/.datomic-local/m2-tests" \
  "${ROOT}/.datomic-local/m32-tests" \
  "${ROOT}/.datomic-local/m41-tests" \
  "${ROOT}/.datomic-local/m42-tests" \
  "${ROOT}/.datomic-local/m42-contract"

echo "==> Regressão M2 trainer"
"${CLOJURE}" -M:test-m2-trainer

echo "==> Regressão M3.2 reward/fence"
"${CLOJURE}" -M:test-m3-reward

echo "==> Regressão M4.1 bridge reads"
"${CLOJURE}" -M:test-m4-bridge

echo "==> Smoke M4.2 tx"
"${CLOJURE}" -M:test-m4-tx

echo "==> Unit Node cliente JVM"
node --test test/pokemon-jvm-client.test.cjs

echo "==> Contrato M4.1 (leituras)"
CLOJURE_BIN="${CLOJURE}" node scripts/test-pokemon-jvm-contract.cjs

echo "==> Contrato M4.2 (tx)"
CLOJURE_BIN="${CLOJURE}" node scripts/test-pokemon-jvm-tx-contract.cjs

echo "==> OK M4.2"
