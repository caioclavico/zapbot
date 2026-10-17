#!/usr/bin/env bash
# M4.1 — smoke JVM + contrato Node↔JVM + métricas RSS.
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
  "${ROOT}/.datomic-local/m41-tests" \
  "${ROOT}/.datomic-local/m41-contract" \
  "${ROOT}/.datomic-local/m41-bridge"

echo "==> Smoke JVM (odisseu.smoke.m4-bridge)"
"${CLOJURE}" -M:test-m4-bridge

echo "==> Testes unitários do cliente Node"
node --test test/pokemon-jvm-client.test.cjs

echo "==> Contrato e2e Node ↔ JVM (com seed fictício)"
CLOJURE_BIN="${CLOJURE}" node scripts/test-pokemon-jvm-contract.cjs

echo "==> OK M4.1"
echo "Nota: medição com Chromium é opcional/separada — ver docs/m4-1-node-jvm-bridge.md"
