#!/usr/bin/env bash
# M5.0 — harness seguro Cassandra sintético → Datomic Local.
# NÃO conecta produção / Oracle / Cassandra real.
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
  "${ROOT}/.datomic-local/m50-tests" \
  "${ROOT}/.datomic-local/m50-pipeline" \
  "${ROOT}/.datomic-local/m50-pipeline-bak" \
  "${ROOT}/.datomic-local/m50-migration" \
  "${ROOT}/fixtures/m5-0" \
  "${ROOT}/target/m5-0"

echo "==> M5.0 migration harness (sintético apenas)"
"${CLOJURE}" -M:test-m5-0-migration

echo "==> Artefatos em target/m5-0/ e fixtures/m5-0/"
echo "==> OK M5.0 (harness). M5 completo = NÃO — falta dados reais autorizados."
