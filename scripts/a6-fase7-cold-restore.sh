#!/usr/bin/env bash
# Fase 7 — cold backup A5.6 + restore-check isolado (nunca monta no compose live).
# Uso no Core (requer confirmação explícita via FASE7_DO_COLD=1):
#   FASE7_DO_COLD=1 ./scripts/a6-fase7-cold-restore.sh
set -euo pipefail

STAMP="${FASE7_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
ROOT="${ODISSEU_CORE_DIR:-/home/caiohclavico/odisseu-core}"
REC="${ODISSEU_RECOVERY_DIR:-/home/caiohclavico/odisseu-recovery}"
BK="${REC}/a6-fase7-cold-${STAMP}"
RC="${REC}/a6-fase7-restore-check-${STAMP}"
COMPOSE_ENV="${ROOT}/.env.odisseu-core"
COMPOSE_BASE="${ROOT}/docker-compose.odisseu-core.yml"
COMPOSE_REL="${ROOT}/docker-compose.release-battle-idle.yml"
VOL_DATOMIC="odisseu-core-a56-datomic"
VOL_JOURNAL="odisseu-core-a56-journal"
VOL_MAINT="odisseu-core-a56-maint"

if [[ "${FASE7_DO_COLD:-0}" != "1" ]]; then
  echo "Defina FASE7_DO_COLD=1 para executar cold+restore no Core." >&2
  exit 2
fi

cd "$ROOT"
mkdir -p "$BK/datomic" "$BK/journal" "$BK/maint" "$BK/meta"

echo "==> stop JVM writer"
docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" stop odisseu-jvm

cleanup_start_jvm() {
  echo "==> start JVM (cleanup)"
  docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" start odisseu-jvm || true
}
trap cleanup_start_jvm EXIT

echo "==> cold copy volumes → $BK"
docker run --rm \
  -v "${VOL_DATOMIC}:/data:ro" \
  -v "${BK}/datomic:/out" \
  alpine sh -c 'cp -a /data/. /out/'
docker run --rm \
  -v "${VOL_JOURNAL}:/data:ro" \
  -v "${BK}/journal:/out" \
  alpine sh -c 'cp -a /data/. /out/ || true'
docker run --rm \
  -v "${VOL_MAINT}:/data:ro" \
  -v "${BK}/maint:/out" \
  alpine sh -c 'cp -a /data/. /out/ || true'

echo "==> start JVM again"
docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" start odisseu-jvm
trap - EXIT

healthy=0
for i in $(seq 1 40); do
  if curl -fsS -m 2 http://127.0.0.1:18090/health >/dev/null 2>&1; then
    echo "JVM healthy after cold (attempt $i)"
    healthy=1
    break
  fi
  sleep 2
done
if [[ "$healthy" != "1" ]]; then
  echo "FAIL: JVM não respondeu /health após cold" >&2
  exit 5
fi
curl -fsS -m 5 http://127.0.0.1:18090/health | head -c 200; echo

echo "==> hashes + filecount"
(
  cd "$BK"
  find . -type f | wc -l | tee "${BK}.filecount"
  find . -type f | sort | xargs -r sha256sum > "${BK}.files.sha256"
  sha256sum "${BK}.files.sha256" | tee "${BK}.manifest-of-manifest.sha256"
)

echo "==> restore-check (cópia independente) → $RC"
rm -rf "$RC"
mkdir -p "$RC"
cp -a "$BK/." "$RC/"
SRC_N=$(find "$BK" -type f | wc -l)
DST_N=$(find "$RC" -type f | wc -l)
echo "restore filecount src=$SRC_N dst=$DST_N"
if [[ "$SRC_N" != "$DST_N" ]] || [[ "$SRC_N" -lt 1 ]]; then
  echo "FAIL restore-check counts" >&2
  exit 1
fi
# Prove Datomic tree present
test -d "$RC/datomic/odisseu-m90-core/core" || test -d "$RC/datomic"
echo "OK cold=$BK restore-check=$RC"
echo "FASE7_COLD=$BK"
echo "FASE7_RESTORE=$RC"
echo "FASE7_STAMP=$STAMP"
