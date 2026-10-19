#!/usr/bin/env bash
# Suite M6.2 de resiliência (sintético, sem WhatsApp).
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
REPORT_DIR="${ODISSEU_M62_REPORT_DIR:-${HOME}/odisseu-recovery/m6-2-private}"
mkdir -p .datomic-local/m62-resilience "${REPORT_DIR}"

echo "==> M6.2 resilience smoke"
OUT="${REPORT_DIR}/resilience-$(date -u +%Y%m%dT%H%M%SZ).txt"
"${CLOJURE}" -M:m6-2-resilience 2>&1 | tee "${OUT}"
echo "Relatório: ${OUT}"
