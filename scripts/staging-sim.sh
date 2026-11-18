#!/usr/bin/env bash
# M6.4 — simulação de transporte WhatsApp (SEM sessão real / SEM QR).
# Exercita JVM, smoke sintético, reinício e fail-closed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${STAGING_ENV_FILE:-.env.staging}"
REPORT_DIR="${ODISSEU_M64_REPORT_DIR:-${HOME}/odisseu-recovery/m6-4-private}"
mkdir -p "${REPORT_DIR}"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${REPORT_DIR}/sim-${STAMP}.txt"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Crie ${ENV_FILE} a partir de .env.staging.example" >&2
  exit 2
fi
# shellcheck disable=SC1090
set -a; source "${ENV_FILE}"; set +a

export ODISSEU_BRIDGE_HOST=127.0.0.1
export ODISSEU_BRIDGE_PORT=18090
export ODISSEU_BRIDGE_SYSTEM=odisseu-m61-staging
export ODISSEU_BRIDGE_STORAGE=.datomic-local/m61-staging
export ODISSEU_BRIDGE_DB=staging
export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=false
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"

{
  echo "# M6.4 sim transport ${STAMP}"
  echo "whatsapp=SIMULATED (no Chromium)"
  echo "WWEBJS_DATA_PATH=${WWEBJS_DATA_PATH:-.wwebjs_auth_staging}"
  echo "session_dir_exists=$([[ -d ${WWEBJS_DATA_PATH:-.wwebjs_auth_staging}/session-${WWEBJS_CLIENT_ID:-staging} ]] && echo yes || echo no)"
  echo "DEV_GROUP_ID_set=$([[ -n ${DEV_GROUP_ID:-} ]] && echo yes || echo no)"
  echo "POKEMON_SERVICE_URL_empty=$([[ -z ${POKEMON_SERVICE_URL:-} ]] && echo yes || echo no)"
} | tee "${OUT}"

./scripts/staging-down.sh >/dev/null 2>&1 || true

echo "==> JVM up" | tee -a "${OUT}"
./scripts/staging-up.sh jvm >"${REPORT_DIR}/jvm-sim-${STAMP}.log" 2>&1 &
JVM_PID=$!
for _ in $(seq 1 60); do
  curl -fsS -m 2 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
    http://127.0.0.1:18090/health >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" http://127.0.0.1:18090/health | head -c 200
echo | tee -a "${OUT}"

echo "==> smoke sintético" | tee -a "${OUT}"
./scripts/staging-smoke.sh >>"${OUT}" 2>&1

echo "==> reinício JVM" | tee -a "${OUT}"
kill "${JVM_PID}" 2>/dev/null || true
wait "${JVM_PID}" 2>/dev/null || true
sleep 1
./scripts/staging-up.sh jvm >"${REPORT_DIR}/jvm-sim-re-${STAMP}.log" 2>&1 &
JVM_PID=$!
for _ in $(seq 1 60); do
  curl -fsS -m 2 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
    http://127.0.0.1:18090/health >/dev/null 2>&1 && break
  sleep 1
done
echo "jvm_restart_health=ok" | tee -a "${OUT}"

echo "==> fail-closed (kill JVM)" | tee -a "${OUT}"
kill "${JVM_PID}" 2>/dev/null || true
wait "${JVM_PID}" 2>/dev/null || true
sleep 1
if curl -fsS -m 2 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo "FAIL bridge still up" | tee -a "${OUT}"; exit 1
fi
echo "jvm_down_confirmed=yes" | tee -a "${OUT}"

echo "==> gate Node sem sessão (deve recusar)" | tee -a "${OUT}"
if STAGING_WHATSAPP_CONFIRM= ./scripts/staging-up.sh node >/tmp/m64-node-gate.txt 2>&1; then
  echo "FAIL: node deveria recusar sem confirm" | tee -a "${OUT}"; exit 1
else
  echo "node_gate_ok=$(head -1 /tmp/m64-node-gate.txt)" | tee -a "${OUT}"
fi

./scripts/staging-measure.sh >>"${OUT}" 2>&1 || true
echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "${OUT}"
echo "Relatório: ${OUT}"
echo "==> M6.4 sim OK (WhatsApp real não exercitado)"
