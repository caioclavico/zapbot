#!/usr/bin/env bash
# End-to-end disposable physical-volume rehearsal. Never pass production volume names.
set -euo pipefail

PREFIX="${1:?usage: $0 <unique-suffix> e.g. r2-20261109}"
[[ "$PREFIX" =~ ^r[0-9]+-[0-9]{8}$ ]] || { echo "invalid-disposable-suffix" >&2; exit 2; }
BASE="odisseu-a6-rehearsal-${PREFIX}-"
RESTORE="odisseu-a6-restore-${PREFIX}"
ROOT="/tmp/a6-rehearsal-${PREFIX}"
IMAGE="sha256:c60eeb31f0e66948f92e8ff5e03b04c055c381b3cf10c59ed5698f175ef59c20"
BRIDGE_TOKEN="disposable-bridge-token"
MAINT_TOKEN="disposable-maintenance-token"
JVM="odisseu-a6-rehearsal-jvm-${PREFIX}"
JVM_RESTORED="odisseu-a6-rehearsal-restored-${PREFIX}"

case "$BASE" in *odisseu-a6-cutover-*|*odisseu-core-*) echo "forbidden-volume-prefix" >&2; exit 2;; esac
for role in datomic journal maint data; do
  volume="${BASE}${role}"
  if sudo -n docker volume ls -q | grep -Fxq "$volume"; then
    echo "refusing-existing-volume:$volume" >&2; exit 2
  fi
done
mkdir -m 700 "$ROOT" "$ROOT/backups"
for role in datomic journal maint data; do
  sudo -n docker volume create "${BASE}${role}" >/dev/null
done

start_jvm() {
  local name="$1" datomic="$2" journal="$3" maint="$4" data="$5"
  sudo -n docker run -d --name "$name" --restart=no --network none \
    --memory=1g --cpus=1.5 --tmpfs /tmp:rw,nosuid,size=256m \
    -e APP_ENV=staging \
    -e ODISSEU_BRIDGE_HOST=127.0.0.1 -e ODISSEU_BRIDGE_PORT=18090 \
    -e ODISSEU_BRIDGE_TOKEN="$BRIDGE_TOKEN" \
    -e ODISSEU_BRIDGE_SYSTEM="odisseu-a6-rehearsal-${PREFIX}" \
    -e ODISSEU_BRIDGE_STORAGE=/data/datomic-local \
    -e ODISSEU_BRIDGE_DB=core -e ODISSEU_PLATFORM_DB=platform \
    -e ODISSEU_PLATFORM_ENABLED=true -e ODISSEU_BRIDGE_SEED=false \
    -e ODISSEU_BRIDGE_RESET=false -e ODISSEU_BRIDGE_RAID_AUTO_TICK=false \
    -e ODISSEU_BRIDGE_MAINTENANCE_DIR=/data/datomic-local/.a6-maintenance \
    -e ODISSEU_BRIDGE_MAINTENANCE_TOKEN="$MAINT_TOKEN" \
    -e ODISSEU_JOURNAL_DIR=/data/journal -e ODISSEU_MAINT_DIR=/data/maint \
    -v "$datomic:/data/datomic-local" -v "$journal:/data/journal" \
    -v "$maint:/data/maint" -v "$data:/data/node-data" \
    "$IMAGE" >/dev/null
}

wait_healthy() {
  local name="$1"
  for _ in $(seq 1 120); do
    if sudo -n docker exec "$name" curl -fsS -m 2 http://127.0.0.1:18090/health 2>/dev/null \
       | python3 -c 'import json,sys; d=json.load(sys.stdin).get("data",{}); sys.exit(0 if d.get("status")=="up" else 1)' 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "jvm-health-timeout:$name" >&2
  exit 1
}

stop_cleanly() {
  local name="$1"
  sudo -n docker stop --time 60 "$name" >/dev/null
  local state
  state=$(sudo -n docker inspect --format '{{.State.Status}}|{{.State.OOMKilled}}|{{.HostConfig.RestartPolicy.Name}}' "$name")
  [[ "$state" == "exited|false|no" ]] || { echo "jvm-stop-state-invalid:$name" >&2; exit 1; }
  sudo -n docker logs "$name" 2>&1 | grep -Fq '[odisseu-bridge] stopped' \
    || { echo "jvm-shutdown-hook-unconfirmed:$name" >&2; exit 1; }
}

start_jvm "$JVM" "${BASE}datomic" "${BASE}journal" "${BASE}maint" "${BASE}data"
wait_healthy "$JVM"
sudo -n docker exec "$JVM" sh -c \
  "curl -fsS -m 30 -X POST -H 'X-A6-Maintenance-Token: $MAINT_TOKEN' 'http://127.0.0.1:18090/ops/maintenance/enter?timeoutMs=30000' | grep -q quiescent"
stop_cleanly "$JVM"

sudo -n python3 /tmp/a6_cutover_backup.py backup-disposable \
  "$ROOT/backups/backup" --volume-prefix "$BASE" --helper-image "$IMAGE" --reserve-bytes 0
sudo -n python3 /tmp/a6_cutover_backup.py verify "$ROOT/backups/backup"
sudo -n python3 /tmp/a6_cutover_backup.py restore-isolated \
  "$ROOT/backups/backup" --volume-prefix "$RESTORE" --helper-image "$IMAGE"

start_jvm "$JVM_RESTORED" "$RESTORE-datomic" "$RESTORE-journal" "$RESTORE-maint" "$RESTORE-data"
wait_healthy "$JVM_RESTORED"
sudo -n docker exec "$JVM_RESTORED" sh -c \
  "curl -fsS -m 5 -H 'X-A6-Maintenance-Token: $MAINT_TOKEN' http://127.0.0.1:18090/ops/maintenance/status" \
  | python3 -c 'import json,sys; d=json.load(sys.stdin).get("data",{}); p=d.get("phase"); n=d.get("active-requests",d.get("activeRequests")); print("RESTORED_JVM_HEALTH_PASS phase=%s active=%s"%(p,n)); sys.exit(0 if p=="quiescent" and n==0 else 1)'
stop_cleanly "$JVM_RESTORED"

sudo -n docker run --rm --network none --read-only --tmpfs /tmp:rw,nosuid,size=1g \
  --mount "type=volume,src=$RESTORE-datomic,dst=/data/datomic-local" \
  --mount "type=bind,src=/tmp/a6_restore_aggregate.clj,dst=/app/apps/odisseu/src/odisseu/smoke/a6_restore_aggregate.clj,readonly" \
  -e ODISSEU_BRIDGE_SYSTEM="odisseu-a6-rehearsal-${PREFIX}" \
  -e ODISSEU_BRIDGE_STORAGE=/data/datomic-local \
  -e ODISSEU_BRIDGE_DB=core -e ODISSEU_PLATFORM_DB=platform \
  --entrypoint /bin/sh "$IMAGE" -c \
  'mkdir -p /tmp/.clojure && HOME=/tmp CLJ_CONFIG=/tmp/.clojure clojure -M -m odisseu.smoke.a6-restore-aggregate'

echo "A6_DISPOSABLE_PHYSICAL_REHEARSAL_PASS backup=$ROOT/backups/backup restore-prefix=$RESTORE"
