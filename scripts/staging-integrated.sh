#!/usr/bin/env bash
# M6.3 — staging integrado Node+Chromium+JVM.
# NÃO inicia QR nem reutiliza sessão de produção automaticamente.
# Sem conta/sessão de TESTE: prepara medição JVM e encerra com bloqueio documentado.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${STAGING_ENV_FILE:-.env.staging}"
REPORT_DIR="${ODISSEU_M63_REPORT_DIR:-${HOME}/odisseu-recovery/m6-3-private}"
mkdir -p "${REPORT_DIR}" .wwebjs_auth_staging .datomic-local/m61-staging
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${REPORT_DIR}/integrated-${STAMP}.txt"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Crie ${ENV_FILE} a partir de .env.staging.example" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

export WWEBJS_DATA_PATH="${WWEBJS_DATA_PATH:-.wwebjs_auth_staging}"
export WWEBJS_CLIENT_ID="${WWEBJS_CLIENT_ID:-staging}"
SESSION_DIR="${WWEBJS_DATA_PATH}/session-${WWEBJS_CLIENT_ID}"

{
  echo "# M6.3 integrated run ${STARTED_AT}"
  echo "WWEBJS_DATA_PATH=${WWEBJS_DATA_PATH}"
  echo "WWEBJS_CLIENT_ID=${WWEBJS_CLIENT_ID}"
  echo "SESSION_DIR=${SESSION_DIR}"
  echo "session_exists=$([[ -d ${SESSION_DIR} ]] && echo yes || echo no)"
  echo "STAGING_WHATSAPP_CONFIRM=${STAGING_WHATSAPP_CONFIRM:-}"
  echo "DEV_GROUP_ID_set=$([[ -n ${DEV_GROUP_ID:-} ]] && echo yes || echo no)"
  echo "POKEMON_ADAPTER=${POKEMON_ADAPTER:-}"
  echo "POKEMON_SERVICE_URL_empty=$([[ -z ${POKEMON_SERVICE_URL:-} ]] && echo yes || echo no)"
} | tee "${OUT}"

# --- Parte simulada (sempre): JVM + fail-closed sem sessão ---
echo "==> [sim] subindo JVM bridge" | tee -a "${OUT}"
./scripts/staging-down.sh >/dev/null 2>&1 || true
# Força endpoints/storage de staging (ignora exports soltos do shell).
export ODISSEU_BRIDGE_HOST=127.0.0.1
export ODISSEU_BRIDGE_PORT=18090
export ODISSEU_BRIDGE_SYSTEM=odisseu-m61-staging
export ODISSEU_BRIDGE_STORAGE=.datomic-local/m61-staging
export ODISSEU_BRIDGE_DB=staging
export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=false
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
# Sobe JVM em background
./scripts/staging-up.sh jvm >"${REPORT_DIR}/jvm-${STAMP}.log" 2>&1 &
JVM_PID=$!
echo "jvm_pid=${JVM_PID}" | tee -a "${OUT}"

HEALTH_OK=0
for _ in $(seq 1 60); do
  if curl -fsS -m 2 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
       http://127.0.0.1:18090/health >/dev/null 2>&1; then
    HEALTH_OK=1
    break
  fi
  sleep 1
done
echo "jvm_health=${HEALTH_OK}" | tee -a "${OUT}"

if [[ "${HEALTH_OK}" -eq 1 ]]; then
  echo "==> [sim] smoke M6.1 (sintético, sem WhatsApp)" | tee -a "${OUT}"
  ./scripts/staging-smoke.sh >>"${OUT}" 2>&1 || echo "smoke_exit=$?" | tee -a "${OUT}"

  echo "==> [sim] fail-closed: derruba JVM e confirma bridge down (sem fallback Cassandra)" | tee -a "${OUT}"
  kill "${JVM_PID}" 2>/dev/null || true
  wait "${JVM_PID}" 2>/dev/null || true
  sleep 1
  if curl -fsS -m 2 http://127.0.0.1:18090/health >/dev/null 2>&1; then
    echo "FAIL: bridge ainda up após kill" | tee -a "${OUT}"
  else
    echo "jvm_down_confirmed=yes" | tee -a "${OUT}"
    echo "nota: adaptador Node JVM é fail-closed (pokemon_http.cljs); sem POKEMON_SERVICE_URL não há writer Cassandra Pokémon." | tee -a "${OUT}"
  fi
else
  echo "jvm_health_failed" | tee -a "${OUT}"
  kill "${JVM_PID}" 2>/dev/null || true
fi

# --- Gate Chromium READY ---
BLOCK_REASON=""
if [[ "${WWEBJS_DATA_PATH}" == ".wwebjs_auth" ]]; then
  BLOCK_REASON="path_producao_recusado"
elif [[ "${STAGING_WHATSAPP_CONFIRM:-}" != "1" ]]; then
  BLOCK_REASON="sem_STAGING_WHATSAPP_CONFIRM"
elif [[ ! -d "${SESSION_DIR}" && "${STAGING_ALLOW_QR:-}" != "1" ]]; then
  BLOCK_REASON="sem_sessao_teste"
elif [[ -z "${DEV_GROUP_ID:-}" ]]; then
  BLOCK_REASON="DEV_GROUP_ID_vazio"
elif [[ "${STAGING_GROUP_CONFIRMED:-}" != "1" ]]; then
  BLOCK_REASON="STAGING_GROUP_CONFIRMED_ausente"
fi

if [[ -n "${BLOCK_REASON}" ]]; then
  {
    echo
    echo "## BLOQUEIO M6.3 — Chromium READY não executado"
    echo "reason=${BLOCK_REASON}"
    echo "chromium_ready_tests=NOT_RUN"
    echo "simulated_without_session=RUN"
    echo "acao: fornecer conta/sessão WhatsApp de TESTE em ${SESSION_DIR}"
    echo "      preencher DEV_GROUP_ID; export STAGING_WHATSAPP_CONFIRM=1"
    echo "      (QR só com conta de teste: STAGING_ALLOW_QR=1 — nunca produção)"
    echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } | tee -a "${OUT}"
  echo "Relatório: ${OUT}"
  exit 10
fi

# --- Caminho READY (só com sessão/confirm autorizados) ---
echo "==> [ready] iniciando Node com sessão de teste" | tee -a "${OUT}"
# Re-sobe JVM
./scripts/staging-up.sh jvm >"${REPORT_DIR}/jvm-ready-${STAMP}.log" 2>&1 &
JVM_PID=$!
for _ in $(seq 1 60); do
  curl -fsS -m 2 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
    http://127.0.0.1:18090/health >/dev/null 2>&1 && break
  sleep 1
done

export STAGING_WHATSAPP_CONFIRM=1
./scripts/staging-up.sh node >"${REPORT_DIR}/node-${STAMP}.log" 2>&1 &
NODE_PID=$!
echo "node_pid=${NODE_PID}" | tee -a "${OUT}"

READY=0
for _ in $(seq 1 120); do
  if curl -fsS -m 2 http://127.0.0.1:3001/health 2>/dev/null | grep -q '"whatsapp":"READY"'; then
    READY=1
    break
  fi
  sleep 2
done
echo "chromium_ready=${READY}" | tee -a "${OUT}"

if [[ "${READY}" -eq 1 ]]; then
  ./scripts/staging-measure.sh >>"${OUT}" 2>&1 || true
  echo "chromium_ready_tests=PARTIAL_MANUAL — execute comandos !pk no DEV_GROUP_ID" | tee -a "${OUT}"
else
  echo "chromium_ready_tests=FAILED_TIMEOUT" | tee -a "${OUT}"
fi

echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "${OUT}"
echo "Relatório: ${OUT}"
# Deixa processos para inspeção do usuário; não mata automaticamente em sucesso READY.
exit 0
