#!/usr/bin/env bash
# Sobe staging local (host): JVM bridge + opcional Node.
# Não toca produção / m53 / m55. Sem publish público da bridge.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="${1:-jvm}" # jvm | compose | node
ENV_FILE="${STAGING_ENV_FILE:-.env.staging}"

resolve_clojure() {
  if [[ -n "${CLOJURE_BIN:-}" && -x "${CLOJURE_BIN}" ]]; then echo "${CLOJURE_BIN}"; return; fi
  if [[ -x "${ROOT}/.tools/clojure/bin/clojure" ]]; then echo "${ROOT}/.tools/clojure/bin/clojure"; return; fi
  if command -v clojure >/dev/null 2>&1; then command -v clojure; return; fi
  echo "Clojure CLI não encontrado." >&2; exit 127
}

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Crie ${ENV_FILE} a partir de .env.staging.example" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

mkdir -p .datomic-local/m61-staging "${HOME}/odisseu-recovery/m6-1-private"

case "${MODE}" in
  jvm)
    export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
    export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
    export ODISSEU_BRIDGE_HOST="${ODISSEU_BRIDGE_HOST:-127.0.0.1}"
    export ODISSEU_BRIDGE_PORT="${ODISSEU_BRIDGE_PORT:-18090}"
    export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND="${ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND:-false}"
    export ODISSEU_BRIDGE_SYSTEM="${ODISSEU_BRIDGE_SYSTEM:-odisseu-m61-staging}"
    export ODISSEU_BRIDGE_STORAGE="${ODISSEU_BRIDGE_STORAGE:-.datomic-local/m61-staging}"
    export ODISSEU_BRIDGE_DB="${ODISSEU_BRIDGE_DB:-staging}"
    export ODISSEU_BRIDGE_SEED="${ODISSEU_BRIDGE_SEED:-true}"
    export ODISSEU_BRIDGE_ALLOW_TEST_RNG="${ODISSEU_BRIDGE_ALLOW_TEST_RNG:-true}"
    if [[ -z "${ODISSEU_BRIDGE_TOKEN:-}" ]]; then
      echo "ODISSEU_BRIDGE_TOKEN ausente em ${ENV_FILE}" >&2
      exit 2
    fi
    echo "==> JVM bridge em ${ODISSEU_BRIDGE_HOST}:${ODISSEU_BRIDGE_PORT} storage=${ODISSEU_BRIDGE_STORAGE}"
    exec "$(resolve_clojure)" -M:bridge-http
    ;;
  compose)
    echo "==> docker compose staging (bridge só em 127.0.0.1:18090)"
    exec docker compose -f docker-compose.staging.yml --env-file "${ENV_FILE}" up -d --build
    ;;
  node)
    echo "==> Node staging (requer JVM no ar; sessão WhatsApp opcional)"
    export APP_ENV=staging
    export POKEMON_ADAPTER=jvm
    export POKEMON_JVM_URL="${POKEMON_JVM_URL:-http://127.0.0.1:18090}"
    export POKEMON_JVM_TOKEN="${ODISSEU_BRIDGE_TOKEN}"
    export POKEMON_SERVICE_URL=""
    export POKEMON_SERVICE_TOKEN=""
    if [[ ! -f target/main.js ]]; then
      echo "Compile primeiro: npm run build" >&2
      exit 2
    fi
    exec node target/main.js
    ;;
  *)
    echo "Uso: $0 [jvm|compose|node]" >&2
    exit 2
    ;;
esac
