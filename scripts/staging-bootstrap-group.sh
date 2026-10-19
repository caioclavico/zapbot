#!/usr/bin/env bash
# M6.3C — autenticação de staging + descoberta segura do DEV_GROUP_ID.
# Não processa comandos, não responde mensagens, não envia notificações.
# Nunca usa .wwebjs_auth de produção. Sem cutover/deploy.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${STAGING_ENV_FILE:-.env.staging}"
REPORT_DIR="${ODISSEU_M63C_REPORT_DIR:-${HOME}/odisseu-recovery/m6-3c-private}"
mkdir -p "${REPORT_DIR}" .wwebjs_auth_staging .datomic-local/m61-staging
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${REPORT_DIR}/bootstrap-${STAMP}.txt"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Crie ${ENV_FILE} a partir de .env.staging.example" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

export APP_ENV=staging
export POKEMON_ADAPTER="${POKEMON_ADAPTER:-jvm}"
export PLATFORM_ADAPTER="${PLATFORM_ADAPTER:-datomic}"
export WWEBJS_DATA_PATH="${WWEBJS_DATA_PATH:-.wwebjs_auth_staging}"
export WWEBJS_CLIENT_ID="${WWEBJS_CLIENT_ID:-staging}"
export STAGING_WHATSAPP_CONFIRM=1
export STAGING_ALLOW_QR=1
export STAGING_BOOTSTRAP_GROUP=1
export STAGING_GROUP_DISCOVERY_PATH="${STAGING_GROUP_DISCOVERY_PATH:-${REPORT_DIR}/groups.json}"
export POKEMON_SERVICE_URL=""
export POKEMON_SERVICE_TOKEN=""
export ODISSEU_BRIDGE_HOST=127.0.0.1
export ODISSEU_BRIDGE_PORT=18090
export ODISSEU_BRIDGE_SYSTEM=odisseu-m61-staging
export ODISSEU_BRIDGE_STORAGE=.datomic-local/m61-staging
export ODISSEU_BRIDGE_DB=staging
export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=false
if [[ -z "${JAVA_HOME:-}" && -x /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/java ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi
# Chrome: ignorar cache sandbox do Cursor.
if [[ "${PUPPETEER_CACHE_DIR:-}" == *cursor-sandbox-cache* ]] || [[ -z "${PUPPETEER_CACHE_DIR:-}" ]]; then
  export PUPPETEER_CACHE_DIR="${HOME}/.cache/puppeteer"
fi
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"

{
  echo "# M6.3C bootstrap ${STAMP}"
  echo "APP_ENV=${APP_ENV}"
  echo "POKEMON_ADAPTER=${POKEMON_ADAPTER}"
  echo "PLATFORM_ADAPTER=${PLATFORM_ADAPTER}"
  echo "WWEBJS_DATA_PATH=${WWEBJS_DATA_PATH}"
  echo "WWEBJS_CLIENT_ID=${WWEBJS_CLIENT_ID}"
  echo "STAGING_BOOTSTRAP_GROUP=1"
  echo "DEV_GROUP_ID_set=$([[ -n ${DEV_GROUP_ID:-} ]] && echo yes || echo no)"
  echo "POKEMON_SERVICE_URL_empty=$([[ -z ${POKEMON_SERVICE_URL:-} ]] && echo yes || echo no)"
  echo "discovery_path=${STAGING_GROUP_DISCOVERY_PATH}"
} | tee "${OUT}"

if [[ "${WWEBJS_DATA_PATH}" == ".wwebjs_auth" || "${WWEBJS_DATA_PATH}" == *"/.wwebjs_auth" ]]; then
  echo "Recusado: path de produção" | tee -a "${OUT}"
  exit 3
fi

# Isolamento: bridge local Datomic; sem Cassandra de produção.
./scripts/staging-down.sh >/dev/null 2>&1 || true
echo "==> JVM bridge (Datomic Local staging)" | tee -a "${OUT}"
./scripts/staging-up.sh jvm >"${REPORT_DIR}/jvm-bootstrap-${STAMP}.log" 2>&1 &
JVM_PID=$!
echo "jvm_pid=${JVM_PID}" | tee -a "${OUT}"

HEALTH_OK=0
for _ in $(seq 1 90); do
  if curl -fsS -m 2 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
       http://127.0.0.1:18090/health >/dev/null 2>&1; then
    HEALTH_OK=1
    break
  fi
  sleep 1
done
echo "jvm_health=${HEALTH_OK}" | tee -a "${OUT}"
if [[ "${HEALTH_OK}" -ne 1 ]]; then
  echo "Bridge JVM não subiu — abortando bootstrap." | tee -a "${OUT}"
  kill "${JVM_PID}" 2>/dev/null || true
  exit 8
fi

# Confirma que não há Cassandra staging/prod no caminho obrigatório.
CASS_LISTEN="$(lsof -iTCP:9042 -sTCP:LISTEN 2>/dev/null | wc -l | tr -d ' ' || true)"
CASS_STAGING="$(lsof -iTCP:9043 -sTCP:LISTEN 2>/dev/null | wc -l | tr -d ' ' || true)"
echo "cassandra_9042_listeners=${CASS_LISTEN:-0}" | tee -a "${OUT}"
echo "cassandra_9043_listeners=${CASS_STAGING:-0}" | tee -a "${OUT}"
echo "nota: bootstrap Node não abre Cassandra (PLATFORM_ADAPTER=datomic + POKEMON_ADAPTER=jvm)." | tee -a "${OUT}"

if [[ ! -f target/main.js ]] || [[ src/zapbot/core.cljs -nt target/main.js ]] \
   || [[ src/zapbot/config.cljs -nt target/main.js ]]; then
  echo "==> npm run build" | tee -a "${OUT}"
  npm run build >>"${REPORT_DIR}/build-bootstrap-${STAMP}.log" 2>&1
fi

echo "==> Node bootstrap (QR da conta de TESTE)" | tee -a "${OUT}"
echo "Escaneie o QR com o WhatsApp Business de TESTE (não o número principal)." | tee -a "${OUT}"
# Log dedicado (não pipe) para o PID ser do node, não do tee.
./scripts/staging-up.sh node >"${REPORT_DIR}/node-bootstrap-${STAMP}.log" 2>&1 &
NODE_PID=$!
echo "node_pid=${NODE_PID}" | tee -a "${OUT}"
echo "node_log=${REPORT_DIR}/node-bootstrap-${STAMP}.log" | tee -a "${OUT}"

READY=0
QR_SEEN=0
# Até ~10 min: QR precisa ser escaneado pelo usuário na conta de TESTE.
for i in $(seq 1 300); do
  if [[ ! -f "${REPORT_DIR}/node-bootstrap-${STAMP}.log" ]]; then
    sleep 1
    continue
  fi
  if [[ "${QR_SEEN}" -eq 0 ]] && grep -q 'QR Code gerado\|Escaneie o QR' "${REPORT_DIR}/node-bootstrap-${STAMP}.log" 2>/dev/null; then
    QR_SEEN=1
    echo "qr_seen=yes — escaneie o QR no log Node (conta de TESTE)." | tee -a "${OUT}"
    echo "node_log=${REPORT_DIR}/node-bootstrap-${STAMP}.log" | tee -a "${OUT}"
  fi
  if grep -q 'authentication_transition\|Could not find Chrome' "${REPORT_DIR}/node-bootstrap-${STAMP}.log" 2>/dev/null \
     && ! kill -0 "${NODE_PID}" 2>/dev/null; then
    echo "node_failed=yes — veja o log" | tee -a "${OUT}"
    break
  fi
  if curl -fsS -m 2 http://127.0.0.1:3001/health 2>/dev/null | grep -q '"whatsapp":"READY"'; then
    READY=1
    break
  fi
  if [[ -f "${STAGING_GROUP_DISCOVERY_PATH}" ]]; then
    READY=1
    break
  fi
  sleep 2
done
echo "chromium_ready=${READY}" | tee -a "${OUT}"
echo "qr_seen=${QR_SEEN}" | tee -a "${OUT}"

if [[ -f "${STAGING_GROUP_DISCOVERY_PATH}" ]]; then
  GROUP_COUNT="$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("groups",[])))' \
    "${STAGING_GROUP_DISCOVERY_PATH}" 2>/dev/null || echo '?')"
  echo "groups_file=${STAGING_GROUP_DISCOVERY_PATH}" | tee -a "${OUT}"
  echo "groups_count=${GROUP_COUNT}" | tee -a "${OUT}"
  echo "acao: escolha o grupo de teste, grave DEV_GROUP_ID no .env.staging," | tee -a "${OUT}"
  echo "      STAGING_GROUP_CONFIRMED=1, remova STAGING_BOOTSTRAP_GROUP, reinicie." | tee -a "${OUT}"
else
  echo "groups_file_missing=yes — aguarde READY/QR ou inspecione o log Node." | tee -a "${OUT}"
fi

echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "${OUT}"
echo "Relatório: ${OUT}"
echo "JVM_PID=${JVM_PID} NODE_PID=${NODE_PID} (deixados no ar para inspeção)"
exit 0
