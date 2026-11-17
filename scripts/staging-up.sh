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
    # Homebrew OpenJDK (macOS) — /usr/bin/java pode ser stub sem runtime.
    if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
      export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
      export PATH="${JAVA_HOME}/bin:${PATH}"
    fi
    export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
    export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
    # Defaults de staging — não herdar exports soltos de shells anteriores.
    export ODISSEU_BRIDGE_HOST="${ODISSEU_BRIDGE_HOST:-127.0.0.1}"
    export ODISSEU_BRIDGE_PORT="${ODISSEU_BRIDGE_PORT:-18090}"
    export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND="${ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND:-false}"
    export ODISSEU_BRIDGE_SYSTEM="${ODISSEU_BRIDGE_SYSTEM:-odisseu-m61-staging}"
    export ODISSEU_BRIDGE_STORAGE="${ODISSEU_BRIDGE_STORAGE:-.datomic-local/m61-staging}"
    export ODISSEU_BRIDGE_DB="${ODISSEU_BRIDGE_DB:-staging}"
    export ODISSEU_BRIDGE_SEED="${ODISSEU_BRIDGE_SEED:-true}"
    export ODISSEU_BRIDGE_ALLOW_TEST_RNG="${ODISSEU_BRIDGE_ALLOW_TEST_RNG:-true}"
    # Evita apontar storage de smoke/lock por acidente se .env não fixar.
    if echo "${ODISSEU_BRIDGE_STORAGE}" | grep -Eqi 'm62-lock|m53-real|m55-real|prod'; then
      echo "Storage recusado: ${ODISSEU_BRIDGE_STORAGE}" >&2
      exit 3
    fi
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
    echo "==> Node staging (requer JVM no ar + sessão WhatsApp de TESTE)"
    export APP_ENV=staging
    export POKEMON_ADAPTER=jvm
    export PLATFORM_ADAPTER="${PLATFORM_ADAPTER:-datomic}"
    export POKEMON_JVM_URL="${POKEMON_JVM_URL:-http://127.0.0.1:18090}"
    export POKEMON_JVM_TOKEN="${ODISSEU_BRIDGE_TOKEN}"
    export PLATFORM_JVM_URL="${PLATFORM_JVM_URL:-${POKEMON_JVM_URL}}"
    export PLATFORM_JVM_TOKEN="${PLATFORM_JVM_TOKEN:-${ODISSEU_BRIDGE_TOKEN}}"
    export POKEMON_SERVICE_URL=""
    export POKEMON_SERVICE_TOKEN=""
    export WWEBJS_DATA_PATH="${WWEBJS_DATA_PATH:-.wwebjs_auth_staging}"
    export WWEBJS_CLIENT_ID="${WWEBJS_CLIENT_ID:-staging}"
    # Evita cache Puppeteer do sandbox do Cursor (Chrome ausente).
    if [[ "${PUPPETEER_CACHE_DIR:-}" == *cursor-sandbox-cache* ]] || [[ -z "${PUPPETEER_CACHE_DIR:-}" ]]; then
      export PUPPETEER_CACHE_DIR="${HOME}/.cache/puppeteer"
    fi
    if [[ "${WWEBJS_DATA_PATH}" == ".wwebjs_auth" || "${WWEBJS_DATA_PATH}" == *"/.wwebjs_auth" ]]; then
      echo "Recusado: WWEBJS_DATA_PATH não pode ser o path de produção (.wwebjs_auth)." >&2
      exit 3
    fi
    if [[ "${STAGING_WHATSAPP_CONFIRM:-}" != "1" ]]; then
      echo "Recusado: defina STAGING_WHATSAPP_CONFIRM=1 após fornecer conta/sessão de TESTE." >&2
      echo "  Não inicia QR automaticamente. Ver docs/m6-3-whatsapp-staging.md" >&2
      exit 4
    fi
    SESSION_DIR="${WWEBJS_DATA_PATH}/session-${WWEBJS_CLIENT_ID}"
    # Com QR permitido: sessão vazia ou parcial (sem Default) deve ser recriada.
    if [[ "${STAGING_ALLOW_QR:-}" == "1" && -d "${SESSION_DIR}" ]]; then
      if [[ -z "$(ls -A "${SESSION_DIR}" 2>/dev/null || true)" ]] \
         || [[ ! -d "${SESSION_DIR}/Default" ]]; then
        echo "==> Removendo session incompleta ${SESSION_DIR} (QR de teste)"
        rm -rf "${SESSION_DIR}"
      fi
    fi
    if [[ ! -d "${SESSION_DIR}" && "${STAGING_ALLOW_QR:-}" != "1" ]]; then
      echo "Bloqueio M6.3: sem sessão de teste em ${SESSION_DIR}" >&2
      echo "  Forneça sessão de TESTE isolada OU (somente conta de teste) STAGING_ALLOW_QR=1" >&2
      exit 5
    fi
    if [[ "${STAGING_BOOTSTRAP_GROUP:-}" == "1" ]]; then
      echo "==> Modo bootstrap de grupo (sem processar mensagens; DEV_GROUP_ID opcional)"
    else
      if [[ -z "${DEV_GROUP_ID:-}" ]]; then
        echo "DEV_GROUP_ID obrigatório em staging com WhatsApp." >&2
        echo "  Descubra com: ./scripts/staging-bootstrap-group.sh" >&2
        exit 6
      fi
      if [[ "${STAGING_GROUP_CONFIRMED:-}" != "1" ]]; then
        echo "STAGING_GROUP_CONFIRMED=1 obrigatório após validar DEV_GROUP_ID." >&2
        exit 7
      fi
    fi
    mkdir -p "${WWEBJS_DATA_PATH}"
    if [[ ! -f target/main.js ]]; then
      echo "Compile primeiro: npm run build" >&2
      exit 2
    fi
    echo "==> WWEBJS_DATA_PATH=${WWEBJS_DATA_PATH} clientId=${WWEBJS_CLIENT_ID} PLATFORM_ADAPTER=${PLATFORM_ADAPTER}"
    exec node target/main.js
    ;;
  *)
    echo "Uso: $0 [jvm|compose|node]" >&2
    exit 2
    ;;
esac
