#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${A452_IMAGE:-odisseu-contract-tests}"
VOLUME="odisseu-a452-test-${RANDOM}-${RANDOM}"
SYSTEM="odisseu-a452-test"
DB="audit"
SNAP_HOST="$ROOT/.a452-before.edn"
SNAP="/app/.a452-before.edn"
PHASE="startup"
cleanup() {
  status=$?
  echo "A452 phase=${PHASE} volume=${VOLUME} exit=${status}" >&2
  docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  rm -f "$SNAP_HOST"
  exit "$status"
}
trap cleanup EXIT

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI não encontrado. Execute este script no host Mac com Docker Desktop." >&2
  exit 2
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon indisponível. Inicie o Docker Desktop e repita." >&2
  exit 2
fi
if [[ "$IMAGE" == *odisseu-core-* || "$VOLUME" == *odisseu-core-* ]]; then
  echo "referência a volume/imagem oficial recusada" >&2
  exit 3
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Imagem ausente: $IMAGE (construa Dockerfile.contract-tests antes de executar)" >&2
  exit 2
fi

# Carrega ambos os namespaces antes de criar qualquer volume. load-file evita
# depender de scripts/ estar em :paths da imagem de testes.
docker run --rm --network none -v "$ROOT:/app" "$IMAGE" \
  clojure -M -e \
  '(load-file "/app/scripts/odisseu-core-a452-fixture.clj")
    (load-file "/app/scripts/odisseu-core-a452-audit.clj")
    (println "A452_NAMESPACES_OK")'

PHASE="create-volume"
docker volume create "$VOLUME" >/dev/null
case "$VOLUME" in odisseu-a452-test-*) ;; *) echo "volume inválido" >&2; exit 3;; esac
docker volume inspect "$VOLUME" >/dev/null

PHASE="fixture"
docker run --rm --network none \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a452" "$IMAGE" \
  clojure -M -e \
  '(load-file "/app/scripts/odisseu-core-a452-fixture.clj")
    (odisseu.core-a452-fixture/-main "/data/audit-a452" "'"$SYSTEM"'" "'"$DB"'")'

PHASE="snapshot"
docker run --rm --network none \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a452" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh snapshot /data/audit-a452 "$SYSTEM" "$DB" "$SNAP"

PHASE="migration-020"
docker run --rm --network none -e ODISSEU_A452_VOLUME_VALIDATED=1 \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a452" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh install /data/audit-a452 "$SYSTEM" "$DB" "$SNAP"

PHASE="idempotency"
docker run --rm --network none -e ODISSEU_A452_VOLUME_VALIDATED=1 \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a452" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh install /data/audit-a452 "$SYSTEM" "$DB" "$SNAP"

echo "A452_LOCAL_OK"
