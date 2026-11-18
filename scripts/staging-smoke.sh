#!/usr/bin/env bash
# Smoke M6.1: suite sintético JVM + (opcional) health da bridge já no ar.
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
mkdir -p .datomic-local/m61-staging-smoke "${HOME}/odisseu-recovery/m6-1-private"

echo "==> M6.1 smoke sintético (sem Chromium/WhatsApp)"
"${CLOJURE}" -M:m6-1-staging

if curl -fsS http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "==> Bridge externa :18090 healthy"
  curl -fsS http://127.0.0.1:18090/health | head -c 400; echo
else
  echo "==> Nenhuma bridge externa em :18090 (ok se só rodou smoke in-process)"
fi

echo "==> OK staging-smoke"
