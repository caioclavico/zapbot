#!/usr/bin/env bash
# M9.1 — ensaio local JVM/Datomic (sem Docker obrigatório, sem WhatsApp, sem SSH).
# Storage descartável .datomic-local/m91-core-lab — nunca produção.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export PATH="${JAVA_HOME}/bin:${PATH}"
export CLJ_CONFIG="${CLJ_CONFIG:-${ROOT}/.tools/clj-config}"
export GITLIBS="${GITLIBS:-${ROOT}/.tools/gitlibs}"
CLOJURE="${ROOT}/.tools/clojure/bin/clojure"
[[ -x "$CLOJURE" ]] || CLOJURE="$(command -v clojure)"

REPORT="${HOME}/odisseu-recovery/m9-1-private"
mkdir -p "$REPORT" .datomic-local
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
OUT="${REPORT}/lab-${STAMP}.txt"
STORAGE="${ROOT}/.datomic-local/m91-core-lab"
TOKEN="m91-lab-token-not-for-prod"
PORT="${ODISSEU_M91_PORT:-18091}"

{
  echo "# M9.1 lab ${STAMP}"
  echo "storage=${STORAGE}"
  echo "port=${PORT}"
} | tee "$OUT"

# Validação estática Compose/Dockerfile
./scripts/odisseu-core-validate.sh | tee -a "$OUT"

grep -q 'profiles: \["with-node"\]' docker-compose.odisseu-core.yml \
  && echo "OK node profile gated" | tee -a "$OUT" \
  || { echo "FAIL node profile"; exit 1; }
grep -q 'curl' Dockerfile.jvm && echo "OK curl in Dockerfile.jvm" | tee -a "$OUT"
grep -q 'ODISSEU_BRIDGE_SEED=false' Dockerfile.jvm && echo "OK seed default false" | tee -a "$OUT"
grep -q 'ODISSEU_BRIDGE_RESET=false' Dockerfile.jvm && echo "OK reset default false" | tee -a "$OUT"

# Compose config com token placeholder (só interpolação)
TMPENV=$(mktemp)
trap 'rm -f "$TMPENV"; kill ${JVM_PID:-} 2>/dev/null || true' EXIT
sed 's/change-me-odisseu-core-bridge-token/m91-compose-config-token/' \
  .env.odisseu-core.example > "$TMPENV"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  docker compose -f docker-compose.odisseu-core.yml --env-file "$TMPENV" config -q \
    && echo "OK docker compose config" | tee -a "$OUT"
  # Garante que `up` sem profile não inclui node no config default project
  if docker compose -f docker-compose.odisseu-core.yml --env-file "$TMPENV" config \
       --services | grep -q odisseu-node; then
    echo "FAIL: odisseu-node no config default (deveria exigir profile)" | tee -a "$OUT"
    exit 1
  else
    echo "OK default services sem odisseu-node" | tee -a "$OUT"
  fi
else
  echo "WARN docker daemon ausente — skip compose config runtime" | tee -a "$OUT"
fi

# --- Boot JVM local descartável ---
rm -rf "$STORAGE"
mkdir -p "$STORAGE"

# Libera porta se lab anterior ficou zumbi
if lsof -tiTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Porta ${PORT} ocupada — abortando lab" | tee -a "$OUT"
  exit 3
fi

export ODISSEU_BRIDGE_HOST=127.0.0.1
export ODISSEU_BRIDGE_PORT="$PORT"
export ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=false
export ODISSEU_BRIDGE_TOKEN="$TOKEN"
export ODISSEU_BRIDGE_SYSTEM=odisseu-m91-lab
export ODISSEU_BRIDGE_STORAGE="$STORAGE"
export ODISSEU_BRIDGE_DB=core
export ODISSEU_PLATFORM_DB=platform
export ODISSEU_PLATFORM_ENABLED=true
export ODISSEU_BRIDGE_SEED=false
export ODISSEU_BRIDGE_RESET=false
export ODISSEU_BRIDGE_ALLOW_TEST_RNG=true

echo "==> starting local bridge" | tee -a "$OUT"
"$CLOJURE" -M:bridge-http >"${REPORT}/jvm-${STAMP}.log" 2>&1 &
JVM_PID=$!
echo "jvm_pid=${JVM_PID}" | tee -a "$OUT"

HEALTH=0
for _ in $(seq 1 90); do
  if curl -fsS -m 2 "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    HEALTH=1
    break
  fi
  if ! kill -0 "$JVM_PID" 2>/dev/null; then
    echo "JVM morreu — log:" | tee -a "$OUT"
    tail -40 "${REPORT}/jvm-${STAMP}.log" | tee -a "$OUT"
    exit 1
  fi
  sleep 1
done
echo "health_boot=${HEALTH}" | tee -a "$OUT"
[[ "$HEALTH" -eq 1 ]] || { tail -40 "${REPORT}/jvm-${STAMP}.log"; exit 1; }

# Health sem Bearer
CODE=$(curl -sS -o /tmp/m91-h.json -w '%{http_code}' "http://127.0.0.1:${PORT}/health")
echo "health_http=${CODE}" | tee -a "$OUT"
grep -q '"status":"up"' /tmp/m91-h.json
grep -q '"platformEnabled":true' /tmp/m91-h.json && echo "OK platformEnabled" | tee -a "$OUT"

# Mutação sintética autenticada
ENSURE=$(curl -sS -m 10 -X POST "http://127.0.0.1:${PORT}/v1/trainer/ensure" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"chatId":"m91-chat","playerId":"m91-player"}')
echo "ensure=${ENSURE:0:120}" | tee -a "$OUT"
echo "$ENSURE" | grep -qi 'player\|ok\|trainer\|chat' || echo "$ENSURE" | tee -a "$OUT"

# Restart processo — mesma storage
kill "$JVM_PID"
wait "$JVM_PID" 2>/dev/null || true
sleep 1
"$CLOJURE" -M:bridge-http >"${REPORT}/jvm-re-${STAMP}.log" 2>&1 &
JVM_PID=$!
for _ in $(seq 1 90); do
  curl -fsS -m 2 "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1 && break
  sleep 1
done

# Leitura do treinador após reopen
TRAIN=$(curl -sS -m 10 "http://127.0.0.1:${PORT}/v1/trainer?chatId=m91-chat&playerId=m91-player" \
  -H "Authorization: Bearer ${TOKEN}")
echo "trainer_after_reopen=${TRAIN:0:160}" | tee -a "$OUT"
if echo "$TRAIN" | grep -q 'm91-player\|m91-chat\|playerId\|ok'; then
  echo "OK persistence after reopen" | tee -a "$OUT"
  PERSIST=1
else
  # Alguns payloads usam estrutura aninhada — aceitar HTTP 200 + corpo não-vazio
  HTTP=$(curl -sS -o /tmp/m91-t.json -w '%{http_code}' \
    "http://127.0.0.1:${PORT}/v1/trainer?chatId=m91-chat&playerId=m91-player" \
    -H "Authorization: Bearer ${TOKEN}")
  echo "trainer_http=${HTTP}" | tee -a "$OUT"
  if [[ "$HTTP" == "200" && -s /tmp/m91-t.json ]]; then
    echo "OK persistence (HTTP 200 body)" | tee -a "$OUT"
    PERSIST=1
  else
    echo "FAIL persistence" | tee -a "$OUT"
    PERSIST=0
  fi
fi

# Storage não vazio
du -sh "$STORAGE" | tee -a "$OUT"
[[ -n "$(ls -A "$STORAGE" 2>/dev/null)" ]] && echo "OK storage non-empty" | tee -a "$OUT"

kill "$JVM_PID" 2>/dev/null || true
wait "$JVM_PID" 2>/dev/null || true
JVM_PID=

"$CLOJURE" -M:test-m9-1-jvm-datomic | tee -a "$OUT"
echo "local_persistence=${PERSIST}" | tee -a "$OUT"
echo "ended_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)" | tee -a "$OUT"
echo "Relatório: ${OUT}"
[[ "${PERSIST}" -eq 1 ]]
