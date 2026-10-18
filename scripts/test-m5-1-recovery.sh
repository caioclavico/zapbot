#!/usr/bin/env bash
# M5.1 — inventário/matching offline (sintético). Sem produção.
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
  "${ROOT}/.datomic-local/m51-tests" \
  "${ROOT}/fixtures/m5-0" \
  "${ROOT}/target/m5-0" \
  "${ROOT}/target/m5-1"

echo "==> M5.1 recovery prep (offline sintético)"
"${CLOJURE}" -M:test-m5-1-recovery

echo "==> Artefatos: target/m5-1/ inventory + match; fixtures/m5-0/cassandra-dump.edn"
echo "==> OK M5.1 prep. Acesso a dados reais = autorização expressa (ver docs)."
