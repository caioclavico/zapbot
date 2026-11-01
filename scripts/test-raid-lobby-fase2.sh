#!/usr/bin/env bash
# Fase 2 — lobby cooperativo (domínio + Datomic + present Node).
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
mkdir -p "${CLJ_CONFIG}" "${GITLIBS}" "${ROOT}/.datomic-local/raid-lobby-f2"

echo "==> JVM raid lobby fase 2"
"${CLOJURE}" -M:test-raid-lobby-fase2

echo "==> Node present lobby"
node --test test/pokemon-jvm-raid-lobby.test.cjs

echo "==> OK raid lobby fase 2"
