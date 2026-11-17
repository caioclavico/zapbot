#!/usr/bin/env bash
# M8.2 — recuperação pós-cutover sintético. Sem produção / WhatsApp / dual-write.
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
mkdir -p .datomic-local/m82-recovery-platform \
         .datomic-local/m82-recovery-platform-bak \
         .datomic-local/m82-recovery-pokemon \
         .datomic-local/m82-recovery-pokemon-bak \
         .datomic-local/m82-recovery-platform-recovered \
         .datomic-local/m82-recovery-pokemon-recovered \
         .datomic-local/m82-recovery-pokemon-verify \
         target/m8-2

echo "==> M8.2 recovery drill (sintético)"
"$(resolve_clojure)" -M:test-m8-2-recovery
