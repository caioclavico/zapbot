#!/usr/bin/env bash
# M8.1 — ensaio offline ponta a ponta. Sem produção / WhatsApp / Oracle / dual-write.
# Lê dump autorizado via ODISSEU_M53_DUMP (default: odisseu-recovery privado).
# Nunca toca .datomic-local/m53-real-offline nem m55-real-offline.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

resolve_clojure() {
  if [[ -n "${CLOJURE_BIN:-}" && -x "${CLOJURE_BIN}" ]]; then echo "${CLOJURE_BIN}"; return; fi
  if [[ -x "${ROOT}/.tools/clojure/bin/clojure" ]]; then echo "${ROOT}/.tools/clojure/bin/clojure"; return; fi
  if command -v clojure >/dev/null 2>&1; then command -v clojure; return; fi
  echo "Clojure CLI não encontrado." >&2; exit 127
}

export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
export ODISSEU_M53_DUMP="${ODISSEU_M53_DUMP:-/Users/caioclavico/odisseu-recovery/m5-3-private/artifacts/dump-offline.edn}"
export ODISSEU_M81_OUT="${ODISSEU_M81_OUT:-/Users/caioclavico/odisseu-recovery/m8-1-private}"

mkdir -p .datomic-local/m81-rehearsal-platform \
         .datomic-local/m81-rehearsal-platform-bak \
         .datomic-local/m81-rehearsal-pokemon \
         .datomic-local/m81-rehearsal-pokemon-bak \
         .datomic-local/m81-failure-gates \
         .datomic-local/m81-failure-poke \
         target/m8-1 \
         "${ODISSEU_M81_OUT}"

if [[ ! -f "${ODISSEU_M53_DUMP}" ]]; then
  echo "Dump autorizado ausente: ${ODISSEU_M53_DUMP}" >&2
  exit 2
fi

if echo "${ODISSEU_M53_DUMP}" | grep -Eqi 'prod|oracle|odisseu-live'; then
  echo "Path de dump suspeito de produção — abortando." >&2
  exit 3
fi

echo "==> M8.1 rehearsal (offline; dump=${ODISSEU_M53_DUMP})"
"$(resolve_clojure)" -M:test-m8-1-rehearsal
