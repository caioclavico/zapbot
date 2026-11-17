#!/usr/bin/env bash
# M5.4 — valida gameplay com saves reais (cópia descartável; referência M5.3B imutável).
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
  "${ROOT}/.datomic-local/m54-gameplay" \
  "${HOME}/odisseu-recovery/m5-4-private"

export ODISSEU_M53_DUMP="${ODISSEU_M53_DUMP:-${HOME}/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn}"
export ODISSEU_M54_REPORT_DIR="${ODISSEU_M54_REPORT_DIR:-${HOME}/odisseu-recovery/m5-4-private}"

if [[ ! -f "${ODISSEU_M53_DUMP}" ]]; then
  echo "Dump offline ausente: ${ODISSEU_M53_DUMP}" >&2
  exit 2
fi
if [[ ! -d "${ROOT}/.datomic-local/m53-real-offline" ]]; then
  echo "Referência M5.3B ausente: .datomic-local/m53-real-offline" >&2
  exit 2
fi

echo "==> M5.4 gameplay (cópia descartável; sem tocar produção)"
"${CLOJURE}" -M:m5-4-gameplay

echo "==> OK M5.4 (ver relatório privado em ${ODISSEU_M54_REPORT_DIR})"
