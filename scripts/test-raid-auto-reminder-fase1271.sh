#!/usr/bin/env bash
# Fase 12.7.1 — lembretes de raid automática (T-30/15/5).
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
mkdir -p "${CLJ_CONFIG}" "${GITLIBS}" "${ROOT}/.datomic-local/raid-auto-r1271"

echo "==> JVM raid auto reminder fase 12.7.1"
"${CLOJURE}" -M:test-raid-auto-reminder-fase1271

echo "==> Node media raid-egg"
node --test test/pokemon-jvm-raid-egg-reminder.test.cjs

echo "==> OK raid auto reminder fase 12.7.1"
