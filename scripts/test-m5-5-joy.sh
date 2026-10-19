#!/usr/bin/env bash
# M5.5 — Joy importada (fixtures + reimport real descartável). Não toca m53-real-offline.
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
  "${ROOT}/.datomic-local/m55-tests" \
  "${ROOT}/.datomic-local/m55-real-offline" \
  "${HOME}/odisseu-recovery/m5-5-private"

export ODISSEU_M53_DUMP="${ODISSEU_M53_DUMP:-${HOME}/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn}"
export ODISSEU_M55_REPORT_DIR="${ODISSEU_M55_REPORT_DIR:-${HOME}/odisseu-recovery/m5-5-private}"

REF_SHA_BEFORE="$(shasum -a 256 "${ROOT}/.datomic-local/m53-real-offline/odisseu-m53-real-offline/m53-import/db.log" 2>/dev/null | awk '{print $1}' || true)"

echo "==> M5.5 Joy (fixtures + real descartável; m53-real-offline imutável)"
"${CLOJURE}" -M:m5-5-joy

if [[ -n "${REF_SHA_BEFORE}" ]]; then
  REF_SHA_AFTER="$(shasum -a 256 "${ROOT}/.datomic-local/m53-real-offline/odisseu-m53-real-offline/m53-import/db.log" | awk '{print $1}')"
  if [[ "${REF_SHA_BEFORE}" != "${REF_SHA_AFTER}" ]]; then
    echo "ERRO: referência m53-real-offline foi alterada!" >&2
    exit 3
  fi
  echo "==> ref m53-real-offline intacta (${REF_SHA_BEFORE})"
fi

echo "==> OK M5.5 (relatório em ${ODISSEU_M55_REPORT_DIR})"
