#!/usr/bin/env bash
set -euo pipefail
EVID=${EVID:-/home/caiohclavico/odisseu-recovery/a6-p0-hp-ui-cutover-20261108T074953Z}
RC_NODE=${RC_NODE:-sha256:bec910bf80b8fe5e3fdbfa385b81b4caf9dff3f1f6c1ccd22c2933e0c0e65305}
RC_JVM=${RC_JVM:-sha256:9e87bb6f0d48e964ad4f8eaa001913f7811da05c0365bb65bd9a309858050d60}
READY_AT=$(cut -d= -f2 "$EVID/logs/ready-at.txt")
LOG="$EVID/logs/stability-20m.log"
: > "$LOG"
echo "stability_start=$(date -u +%Y-%m-%dT%H:%M:%SZ) ready_at=$READY_AT" | tee -a "$LOG"
ok=0
for i in $(seq 1 40); do
  TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  curl -fsS -m 3 http://127.0.0.1:3003/health > /tmp/a6-stab-node.json || echo '{}' > /tmp/a6-stab-node.json
  curl -fsS -m 3 http://127.0.0.1:18092/health > /tmp/a6-stab-jvm.json || echo '{}' > /tmp/a6-stab-jvm.json
  NI=$(docker inspect odisseu-a6-node --format '{{.Image}}' 2>/dev/null || echo missing)
  JI=$(docker inspect odisseu-a6-jvm --format '{{.Image}}' 2>/dev/null || echo missing)
  NR=$(docker inspect odisseu-a6-node --format '{{.RestartCount}}' 2>/dev/null || echo x)
  JR=$(docker inspect odisseu-a6-jvm --format '{{.RestartCount}}' 2>/dev/null || echo x)
  NH=$(docker inspect odisseu-a6-node --format '{{.State.Health.Status}}' 2>/dev/null || echo x)
  JHST=$(docker inspect odisseu-a6-jvm --format '{{.State.Health.Status}}' 2>/dev/null || echo x)
  eval "$(python3 - <<'PY'
import json
n=json.load(open("/tmp/a6-stab-node.json"))
j=json.load(open("/tmp/a6-stab-jvm.json"))
print("WA=%s" % json.dumps(n.get("whatsapp") or ""))
print("ST=%s" % json.dumps(n.get("status") or ""))
print("JP=%s" % json.dumps(((j.get("data") or {}).get("maintenance") or {}).get("phase") or ""))
PY
)"
  line="t=$i ts=$TS status=$ST wa=$WA node_health=$NH jvm_health=$JHST maint=$JP restarts=$NR/$JR"
  echo "$line" | tee -a "$LOG"
  if [[ "$WA" != "READY" || "$ST" != "ok" || "$NH" != "healthy" || "$JHST" != "healthy" || "$NI" != "$RC_NODE" || "$JI" != "$RC_JVM" || "$NR" != "0" || "$JR" != "0" || "$JP" != "open" ]]; then
    echo "STABILITY_FAIL $line" | tee -a "$LOG"
    cp /tmp/a6-stab-node.json "$EVID/logs/stability-fail-node-health.json"
    cp /tmp/a6-stab-jvm.json "$EVID/logs/stability-fail-jvm-health.json"
    docker logs --since 10m odisseu-a6-node 2>&1 | grep -E "RECOVERING|QR|ERROR|READY|hasSynced" | sed -E "s/[A-Za-z0-9+\/=_]{40,}/***REDACTED***/g" | tail -40 > "$EVID/logs/stability-fail-logs.txt" || true
    exit 2
  fi
  ok=$i
  sleep 30
done
date -u +%Y-%m-%dT%H:%M:%SZ | tee "$EVID/logs/stability-ok-at.txt"
curl -fsS http://127.0.0.1:3003/health | tee "$EVID/logs/health-after-20m.json"
echo
echo "STABILITY_20M_PASS samples=$ok" | tee -a "$LOG"
docker logs --since "$READY_AT" odisseu-a6-node 2>&1 | grep -E "RECOVERING|QR code|qr-code|STATUS: QR" | sed -E "s/[A-Za-z0-9+\/=_]{40,}/***REDACTED***/g" | tee "$EVID/logs/stability-log-scan.txt" || true
# Boot RECOVERING before READY_AT is expected; scan only flags post-ready issues if file non-empty after READY.
if [[ -s "$EVID/logs/stability-log-scan.txt" ]]; then
  echo "WARN_SCAN_NONEMPTY (inspect; boot RECOVERING before READY may appear if --since includes boot)" | tee -a "$LOG"
fi
