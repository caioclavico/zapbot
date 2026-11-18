#!/usr/bin/env bash
# M9.0 — validação local (artefatos + gates). Sem SSH / deploy / cutover.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

chmod +x scripts/odisseu-core-*.sh scripts/odisseu-core-lib.sh 2>/dev/null || true
./scripts/odisseu-core-validate.sh

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"

CLOJURE="${CLOJURE_BIN:-}"
if [[ -z "$CLOJURE" && -x "${ROOT}/.tools/clojure/bin/clojure" ]]; then
  CLOJURE="${ROOT}/.tools/clojure/bin/clojure"
elif command -v clojure >/dev/null 2>&1; then
  CLOJURE="$(command -v clojure)"
else
  echo "Clojure CLI ausente — validate.sh ok; skip smoke JVM" >&2
  exit 0
fi

"$CLOJURE" -M:test-m9-0-odisseu-core
echo "M9.0 local OK — cutover-ready?=false; sem deploy remoto."
