#!/usr/bin/env bash
# Fase 12 — correção crítica de turnos / lobby 5 min / apresentação.
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
mkdir -p "${CLJ_CONFIG}" "${GITLIBS}"

echo "==> JVM raid fase 12 (domínio + JSON)"
"${CLOJURE}" -M:test-raid-fase12

echo "==> Node present/media fase 12"
node --test test/pokemon-jvm-raid-fase12.test.cjs test/pokemon-jvm-raid-lobby.test.cjs test/pokemon-jvm-raid.test.cjs

echo "==> OK raid fase 12"
