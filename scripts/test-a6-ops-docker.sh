#!/usr/bin/env bash
# Somente Docker local, daemon novo sem rede externa e volume root descartável.
set -euo pipefail
cd "$(dirname "$0")/.."
suffix="$$-$(date +%s)"
daemon="a6-ops-dind-$suffix"
socket="a6-ops-socket-$suffix"
fixture="a6-ops-root-$suffix"
cleanup() {
  docker rm -f "$daemon" >/dev/null 2>&1 || true
  docker volume rm "$socket" "$fixture" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker image inspect docker:29.9.1-dind >/dev/null 2>&1 || docker pull docker:29.9.1-dind
docker image inspect alpine:3.20 >/dev/null 2>&1 || docker pull alpine:3.20
docker build --pull=false -f Dockerfile.a6-ops-tests -t zapbot-a6-ops-tests .
docker volume create "$socket" >/dev/null
docker volume create "$fixture" >/dev/null
docker run --pull=never -d --privileged --network none --name "$daemon" \
  -v "$socket:/run/a6-test" -e DOCKER_TLS_CERTDIR= docker:29.9.1-dind \
  dockerd --host=unix:///run/a6-test/docker.sock --bridge=none --iptables=false >/dev/null
for attempt in $(seq 1 60); do
  if docker exec "$daemon" docker -H unix:///run/a6-test/docker.sock info >/dev/null 2>&1; then break; fi
  sleep 1
done
docker exec "$daemon" mkdir -p /home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core
docker save alpine:3.20 | docker exec -i "$daemon" docker -H unix:///run/a6-test/docker.sock load >/dev/null
docker run --pull=never --rm --network none -v "$PWD:/work:ro" -v "$socket:/run/a6-test" \
  -v "$fixture:/fixture" -e DOCKER_HOST=unix:///run/a6-test/docker.sock zapbot-a6-ops-tests \
  sh -ec 'python scripts/test-a6-runtime-materialize.py; python scripts/test-a6-root-backup.py; python scripts/test-a6-admin-backup-client.py; bash scripts/test-a6-admin-privilege-boundary.sh; python scripts/test-a6-real-admin-resume.py; python scripts/test-a6-docker-materialize.py; python scripts/test-a6-operational-window.py; python scripts/test-a6-window-interruptions.py'
