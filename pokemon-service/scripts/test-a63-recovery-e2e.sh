#!/usr/bin/env bash
set -Eeuo pipefail

# Executa somente Cassandra/Datomic descartáveis. Nenhum volume oficial é usado.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NETWORK="odisseu-a63-recovery-net-$$"
CASSANDRA="odisseu-a63-cassandra-$$"
NODE_TEST_IMAGE="odisseu-a63-recovery-node:local"
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
echo "Construindo imagem Node de fixture com npm ci: $NODE_TEST_IMAGE" >&2
docker build --pull=false -f "$ROOT_DIR/pokemon-service/Dockerfile.historical-recovery-tests" \
  -t "$NODE_TEST_IMAGE" "$ROOT_DIR/pokemon-service" >/dev/null
docker network create --internal "$NETWORK" >/dev/null
CREATED_NETWORK=1
docker run -d --name "$CASSANDRA" --network "$NETWORK" --network-alias cassandra-a63 \
  --memory=2g cassandra:5.0.9 >/dev/null
CREATED_CONTAINER=1

wait_for_cassandra() {
  local attempt=1
  local state
  while (( attempt <= 120 )); do
    state="$(docker inspect -f '{{.State.Running}}' "$CASSANDRA" 2>/dev/null || true)"
    if [[ "$state" != true ]]; then
      echo "Cassandra saiu durante o startup (state=${state:-unknown})" >&2
      docker logs --tail 100 "$CASSANDRA" >&2 || true
      return 1
    fi
    # This query runs inside Cassandra and proves that the CQL service is up,
    # rather than merely proving that the container process is running.
    if docker exec "$CASSANDRA" /opt/cassandra/bin/cqlsh 127.0.0.1 9042 \
      -e 'SELECT cluster_name FROM system.local;' >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  echo "Cassandra não ficou pronto após 120s" >&2
  docker logs --tail 100 "$CASSANDRA" >&2 || true
  return 1
}

wait_for_cassandra

network_members="$(docker network inspect "$NETWORK" --format '{{json .Containers}}')"
if [[ "$network_members" != *"$CASSANDRA"* ]]; then
  echo "Cassandra não está na rede esperada: $NETWORK" >&2
  echo "$network_members" >&2
  exit 1
fi

# Verify DNS and TCP reachability from the same Node image/network used by the
# fixture. This catches alias/network regressions before running any writes.
docker run --rm --network "$NETWORK" --entrypoint node "$NODE_TEST_IMAGE" -e '
  const dns = require("node:dns");
  const net = require("node:net");
  dns.lookup("cassandra-a63", (dnsError, address) => {
    if (dnsError) { console.error(`cassandra_dns_failed:${dnsError.message}`); process.exit(1); }
    const socket = net.createConnection({host: "cassandra-a63", port: 9042});
    socket.setTimeout(5000);
    socket.once("connect", () => { console.log(`cassandra_ready_address:${address}`); socket.destroy(); });
    socket.once("timeout", () => { console.error("cassandra_tcp_timeout"); socket.destroy(); process.exit(1); });
    socket.once("error", error => { console.error(`cassandra_tcp_failed:${error.message}`); process.exit(1); });
  });
'

docker run --rm --network "$NETWORK" \
  -e CASSANDRA_CONTACT_POINTS=cassandra-a63 \
  -e CASSANDRA_LOCAL_DC=datacenter1 \
  -e RECOVERY_CHAT_ID=a63-chat \
  -e RECOVERY_PLAYER_ID=a63-player@c.us \
  -e RECOVERY_VERSIONS=both \
  -e RECOVERY_OUTPUT=/out/historical.edn \
  -v "$OUT_DIR:/out" \
  "$NODE_TEST_IMAGE"

test -s "$OUT_FILE"
docker run --rm --network none \
  -e RECOVERY_OUTPUT=/app/$REL_OUTPUT \
  -v "$ROOT_DIR:/app" -w /app \
  odisseu-contract-tests clojure -M:test -m pokemon.tests.recovery-a63-export-e2e-test

sha256sum "$OUT_FILE" 2>/dev/null || shasum -a 256 "$OUT_FILE"
FAILED=0
echo "A63_RECOVERY_E2E_OK output=$OUT_FILE"
