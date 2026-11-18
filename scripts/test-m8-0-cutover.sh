#!/usr/bin/env bash
# M8.0 — cutover prep sintético. Sem produção / WhatsApp / Oracle / dual-write.
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
mkdir -p .datomic-local/m80-cutover .datomic-local/m80-cutover-backup \
         .datomic-local/m80-cutover-poke .datomic-local/m80-cutover-poke-backup \
         target/m8-0

echo "==> M8.0 cutover prep (sintético)"
"$(resolve_clojure)" -M:test-m8-0-cutover
