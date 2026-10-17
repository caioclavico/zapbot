#!/usr/bin/env bash
set -Eeuo pipefail

# Disposable, private Docker network. No port is published to the host.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PROJECT_DIR="$ROOT_DIR/pokemon-service"
NETWORK="zapbot-adventure-fence-net-$$"
CASSANDRA_CONTAINER="zapbot-adventure-fence-cassandra-test"
RUNNER_IMAGE="node:22-bookworm-slim"
CASSANDRA_IMAGE="cassandra:5.0.9"
CREATED_NETWORK=0
CREATED_CONTAINER=0
FAILED=1

cleanup() {
  if [[ "$FAILED" == 1 && "$CREATED_CONTAINER" == 1 ]]; then
    echo "Disposable Cassandra diagnostics (last 120 lines):" >&2
    docker logs --tail 120 "$CASSANDRA_CONTAINER" 2>&1 || true
  fi
  if [[ "$CREATED_CONTAINER" == 1 ]]; then
    docker rm -f -v "$CASSANDRA_CONTAINER" >/dev/null 2>&1 || true
  fi
  if [[ "$CREATED_NETWORK" == 1 ]]; then
    docker network rm "$NETWORK" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if docker container inspect "$CASSANDRA_CONTAINER" >/dev/null 2>&1; then
  echo "Refusing to replace an existing container named $CASSANDRA_CONTAINER." >&2
  exit 2
fi
if [[ ! -d "$PROJECT_DIR/node_modules/cassandra-driver" ]]; then
  echo "Install pokemon-service dependencies first (npm ci)." >&2
  exit 2
fi

docker image inspect "$CASSANDRA_IMAGE" >/dev/null 2>&1 || docker pull "$CASSANDRA_IMAGE"
docker image inspect "$RUNNER_IMAGE" >/dev/null 2>&1 || docker pull "$RUNNER_IMAGE"
docker network create --internal "$NETWORK" >/dev/null
CREATED_NETWORK=1
docker run -d --name "$CASSANDRA_CONTAINER" --network "$NETWORK" \
  --network-alias "$CASSANDRA_CONTAINER" \
  --memory=2g \
  -e MAX_HEAP_SIZE=1G -e HEAP_NEWSIZE=256M \
  -e CASSANDRA_RPC_ADDRESS=0.0.0.0 \
  --entrypoint /bin/bash "$CASSANDRA_IMAGE" -ec '
    sed -ri "s/^#?[[:space:]]*authenticator:.*/authenticator: org.apache.cassandra.auth.PasswordAuthenticator/" "$CASSANDRA_CONF/cassandra.yaml"
    sed -ri "s/^#?[[:space:]]*authorizer:.*/authorizer: org.apache.cassandra.auth.CassandraAuthorizer/" "$CASSANDRA_CONF/cassandra.yaml"
    exec /usr/local/bin/docker-entrypoint.sh cassandra -f
  ' >/dev/null
CREATED_CONTAINER=1

run_phase() {
  local phase="$1"
  docker run --rm --network "$NETWORK" \
    -e ADVENTURE_FENCE_CASSANDRA_ISOLATED=true \
    -e ADVENTURE_FENCE_CASSANDRA_HOST="$CASSANDRA_CONTAINER" \
    -e ADVENTURE_FENCE_CASSANDRA_PHASE="$phase" \
    -v "$ROOT_DIR:/work" -w /work/pokemon-service \
    "$RUNNER_IMAGE" node test/adventure-fence-cassandra.integration.cjs
}

echo "Waiting for a real CQL connection to disposable Cassandra 5.0.9..."
run_phase seed
echo "Restarting only the disposable Adventure-fence Cassandra test container..."
docker restart "$CASSANDRA_CONTAINER" >/dev/null
run_phase verify-restart
FAILED=0
echo "PASS: Adventure fence disposable Cassandra integration completed; container, volume, and private network will be removed."
