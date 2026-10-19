#!/usr/bin/env bash
# M7.7 — staging sem Cassandra: regressões platform + smoke JVM/Datomic + compile Node.
# Não sobe Cassandra; contact points inválidos se alguém tentar conectar.
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
export CASSANDRA_CONTACT_POINTS="${CASSANDRA_CONTACT_POINTS:-203.0.113.1}"
export CASSANDRA_DATACENTER="${CASSANDRA_DATACENTER:-datacenter1}"
export APP_ENV="${APP_ENV:-staging}"
export PLATFORM_ADAPTER="${PLATFORM_ADAPTER:-datomic}"
export POKEMON_ADAPTER="${POKEMON_ADAPTER:-jvm}"

mkdir -p .datomic-local/m77-staging

echo "==> M7.7 JVM smoke (sem Cassandra)"
"$(resolve_clojure)" -M:test-m7-7-no-cassandra

echo "==> shadow-cljs compile app"
npx shadow-cljs compile app

echo "==> M7.7 OK (prova Node de Client: ver test/zapbot/armazenamento_test.cljs)"
