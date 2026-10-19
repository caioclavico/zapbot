#!/usr/bin/env bash
# M8.3 — durabilidade / journal independente. Sintético; sem produção / WhatsApp.
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
mkdir -p .datomic-local/m83-platform \
         .datomic-local/m83-platform-bak \
         .datomic-local/m83-pokemon \
         .datomic-local/m83-pokemon-bak \
         .datomic-local/m83-platform-recovered \
         .datomic-local/m83-pokemon-recovered \
         .datomic-local/m83-op-journal \
         target/m8-3

echo "==> M8.3 durability drill (sintético)"
"$(resolve_clojure)" -M:test-m8-3-durability
