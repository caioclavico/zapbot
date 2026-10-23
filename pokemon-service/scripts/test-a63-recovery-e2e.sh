#!/usr/bin/env bash
set -Eeuo pipefail

# Executa somente Cassandra/Datomic descartáveis. Nenhum volume oficial é usado.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NETWORK="odisseu-a63-recovery-net-$$"
CASSANDRA="odisseu-a63-cassandra-$$"
OUT_DIR="${A63_OUTPUT_DIR:-$ROOT_DIR/target/a63-recovery-$$}"
OUT_FILE="$OUT_DIR/historical.edn"
REL_OUTPUT="${OUT_FILE#$ROOT_DIR/}"
CREATED_NETWORK=0
CREATED_CONTAINER=0
FAILED=1

cleanup() {
  if [[ "$CREATED_CONTAINER" == 1 ]]; then
    if [[ "$FAILED" == 1 ]]; then docker logs --tail 100 "$CASSANDRA" >&2 || true; fi
    docker rm -f -v "$CASSANDRA" >/dev/null 2>&1 || true
  fi
  if [[ "$CREATED_NETWORK" == 1 ]]; then docker network rm "$NETWORK" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

command -v docker >/dev/null || { echo "Docker CLI ausente" >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo "Daemon Docker indisponível" >&2; exit 2; }
docker image inspect odisseu-contract-tests >/dev/null 2>&1 || {
  echo "Construa odisseu-contract-tests antes deste ensaio." >&2; exit 2;
}
mkdir -p "$OUT_DIR"
docker network create --internal "$NETWORK" >/dev/null
CREATED_NETWORK=1
docker run -d --name "$CASSANDRA" --network "$NETWORK" --network-alias cassandra-a63 \
  --memory=2g cassandra:5.0.9 >/dev/null
CREATED_CONTAINER=1

docker run --rm --network "$NETWORK" \
  -e CASSANDRA_CONTACT_POINTS=cassandra-a63 \
  -e CASSANDRA_LOCAL_DC=datacenter1 \
  -e RECOVERY_CHAT_ID=a63-chat \
  -e RECOVERY_PLAYER_ID=a63-player@c.us \
  -e RECOVERY_VERSIONS=both \
  -e RECOVERY_OUTPUT=/work/$REL_OUTPUT \
  -v "$ROOT_DIR:/work" -w /work/pokemon-service node:22-bookworm-slim \
  node scripts/test-historical-recovery-cassandra.cjs

test -s "$OUT_FILE"
docker run --rm --network none \
  -e RECOVERY_OUTPUT=/app/$REL_OUTPUT \
  -v "$ROOT_DIR:/app" -w /app \
  odisseu-contract-tests clojure -M:test -m pokemon.tests.recovery-a63-export-e2e-test

sha256sum "$OUT_FILE" 2>/dev/null || shasum -a 256 "$OUT_FILE"
FAILED=0
echo "A63_RECOVERY_E2E_OK output=$OUT_FILE"
