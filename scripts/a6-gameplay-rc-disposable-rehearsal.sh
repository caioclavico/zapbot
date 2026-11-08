#!/usr/bin/env bash
# Ensaio descartável de compose/overlay + Image ID + JVM/Node health + rollback simulado.
# NÃO toca produção, Datomic live, sessão WhatsApp real nem deploy hold.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

NODE_TAG='odisseu-node:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64'
JVM_TAG='odisseu-jvm:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64'
NODE_ID='sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade'
JVM_ID='sha256:7bc8f341f4ee1d273931b283e0c0c3374a5aba6fcb9574fc4920767de816971c'
BREAKGLASS_ID='sha256:1d00dd01cdcabeb6a714d3df3e6715ebac4502bc104cfd5d61a64098de35a390'
# Rollback operacional live (produção atual), usado só na simulação de pin:
OPERATIONAL_NODE_ID='sha256:05b36c5aebeff40875c2a3b4545e368aa2dfffe7d93a3fc7a02c05b0d3b3a18c'
OPERATIONAL_JVM_ID='sha256:ad0b7c1b265cb52eee221ea6458ea6bdaad7c5c230cd8ec6dfe7e2474b2acd5c'
PROJ="a6-rc-rehearsal-$$"
TOKEN="$(openssl rand -hex 24)"
MAINT="$(openssl rand -hex 16)"
OUT="${A6_REHEARSAL_OUT:-/tmp/a6-rc-rehearsal-$$.log}"
exec > >(tee "$OUT") 2>&1

cleanup() {
  docker rm -f "${PROJ}-jvm" "${PROJ}-node-probe" 2>/dev/null || true
  docker volume rm "${PROJ}-datomic" "${PROJ}-data" "${PROJ}-sess" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> verify images"
bash scripts/verify-a6-gameplay-rc-images.sh

echo "==> overlay check"
bash scripts/homolog-compose-overlay-check.sh

echo "==> ACL mask rehearsal (Linux disposable)"
docker volume create "${PROJ}-sess" >/dev/null
docker volume create "${PROJ}-data" >/dev/null
docker run --rm --platform linux/amd64 \
  -v "${PROJ}-sess:/sess" -v "${PROJ}-data:/data" \
  debian:bookworm-slim bash -lc '
set -e
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq && apt-get install -y -qq acl passwd >/dev/null
groupadd -g 1000 node 2>/dev/null || true
id -u 1000 >/dev/null 2>&1 || useradd -u 1000 -g 1000 -M -s /bin/bash node
mkdir -p /sess/session-a6/Default /data
chown 1001:1002 /sess && chmod 700 /sess
chown -R 0:0 /sess/session-a6
chmod 700 /sess/session-a6 /sess/session-a6/Default
printf prefs > /sess/session-a6/Default/Preferences && chmod 600 /sess/session-a6/Default/Preferences
chown 0:0 /data && chmod 755 /data
printf "{\"phase\":\"open\"}\n" > /data/a6-maintenance.json && chmod 600 /data/a6-maintenance.json
as1000(){ setpriv --reuid=1000 --regid=1000 --clear-groups -- "$@"; }
set +e
as1000 cat /sess/session-a6/Default/Preferences >/dev/null; E1=$?
as1000 cat /data/a6-maintenance.json >/dev/null; E2=$?
set -e
[ "$E1" -ne 0 ] && [ "$E2" -ne 0 ] && echo SESSION_ACCESS_DENIED_SIM=PASS
# ACL inválida simulada (máscara vazia sem user:1000) — ainda nega
setfacl -m m::--- /sess || true
set +e
as1000 cat /sess/session-a6/Default/Preferences >/dev/null; Ebad=$?
set -e
[ "$Ebad" -ne 0 ] && echo ACL_INVALID_STILL_DENIED=PASS
setfacl -b /sess || true
setfacl -m u:1000:rwx /sess
setfacl -R -m u:1000:rwX /sess
setfacl -d -m u:1000:rwx /sess
setfacl -m u:1000:rwx /data
setfacl -m u:1000:rw /data/a6-maintenance.json
getfacl -p /sess | grep -q "mask::" && echo ACL_MASK_EFFECTIVE=PASS
as1000 cat /sess/session-a6/Default/Preferences >/dev/null
as1000 cat /data/a6-maintenance.json >/dev/null
echo sentinel > /data/persist-marker
# pós-stop revert
setfacl -R -x u:1000 /sess; setfacl -k /sess || true
setfacl -x u:1000 /data; setfacl -x u:1000 /data/a6-maintenance.json || true
set +e
as1000 cat /sess/session-a6/Default/Preferences >/dev/null; Er=$?
set -e
[ "$Er" -ne 0 ] && echo POST_STOP_ACL_REVERT=PASS
test -f /data/persist-marker && echo PERSIST_MARKER_KEPT=PASS
'

echo "==> JVM disposable health + bridge"
docker volume create "${PROJ}-datomic" >/dev/null
docker run -d --name "${PROJ}-jvm" --platform linux/amd64 \
  -e APP_ENV=staging \
  -e POKEMON_THEMATIC_EVENTS_ENABLED=false \
  -e ODISSEU_BRIDGE_HOST=0.0.0.0 \
  -e ODISSEU_BRIDGE_PORT=18090 \
  -e ODISSEU_BRIDGE_ALLOW_PRIVATE_BIND=true \
  -e ODISSEU_BRIDGE_TOKEN="$TOKEN" \
  -e ODISSEU_BRIDGE_MAINTENANCE_TOKEN="$MAINT" \
  -e ODISSEU_BRIDGE_SYSTEM=odisseu-a6-rc-rehearsal \
  -e ODISSEU_BRIDGE_STORAGE=/data/datomic-local \
  -e ODISSEU_BRIDGE_DB=core-a6-rc-rehearsal \
  -e ODISSEU_PLATFORM_DB=platform-a6-rc-rehearsal \
  -e ODISSEU_PLATFORM_ENABLED=true \
  -e ODISSEU_BRIDGE_SEED=false \
  -e ODISSEU_BRIDGE_RESET=false \
  -e ODISSEU_BRIDGE_RAID_AUTO_TICK=false \
  -e JAVA_TOOL_OPTIONS='-Xms64m -Xmx320m' \
  -v "${PROJ}-datomic:/data/datomic-local" \
  -p 127.0.0.1:18093:18090 \
  "$JVM_TAG" >/dev/null

for i in $(seq 1 40); do
  if curl -fsS -m 3 http://127.0.0.1:18093/health >/tmp/${PROJ}-health.json 2>/dev/null; then
    echo JVM_HEALTH=PASS
    break
  fi
  if [ "$(docker inspect -f '{{.State.Status}}' "${PROJ}-jvm")" = exited ]; then
    docker logs "${PROJ}-jvm" | tail -40
    exit 1
  fi
  sleep 3
done
test -f /tmp/${PROJ}-health.json
python3 - <<PY
import json
h=json.load(open('/tmp/${PROJ}-health.json'))
assert h.get('ok') is True
print('JVM_BRIDGE_OK', h.get('data',{}).get('status'), 'thematic_env_checked_separately')
PY
docker exec "${PROJ}-jvm" printenv POKEMON_THEMATIC_EVENTS_ENABLED | grep -qx false
echo THEMATIC_FALSE=PASS

echo "==> Node probe without WhatsApp session (expect no READY / no prod session)"
# Simula inicialização Node sem sessão → não deve montar produção; apenas uid/seccomp/image
docker run -d --name "${PROJ}-node-probe" --platform linux/amd64 \
  --user 1000:1000 --cap-drop ALL --security-opt no-new-privileges:true \
  --security-opt seccomp=docker/chromium-seccomp.json \
  -e WHATSAPP_TRANSPORT=disabled \
  -e POKEMON_THEMATIC_EVENTS_ENABLED=false \
  -e PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium \
  "$NODE_TAG" node -e 'console.log(JSON.stringify({uid:process.getuid(),node:process.version})); setTimeout(()=>process.exit(0),500)' \
  >/dev/null
docker wait "${PROJ}-node-probe" >/dev/null
docker logs "${PROJ}-node-probe" | tee /tmp/${PROJ}-node-probe.log
grep -q '"uid":1000' /tmp/${PROJ}-node-probe.log && echo NODE_UID_PROBE=PASS
# Falha controlada: Image ID errado não deve passar verify
if docker image inspect "$NODE_TAG" --format '{{.Id}}' | grep -qx "$NODE_ID"; then
  echo NODE_IMAGE_ID_MATCH=PASS
fi
echo "INFO breakglass_rollback_target=$BREAKGLASS_ID (não aplicado)"

echo "==> rollback simulation (compose pin swap only in fixture file)"
TMP=$(mktemp -d)
cp docker-compose.a6-ai-drain-sandbox.overlay.yml "$TMP/overlay-rc.yml"
# Simulate rollback overlay pointing to operacional live tag (não aplicado)
sed "s|image: odisseu-node:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64|image: odisseu-node:a6-gameplay-chromium156-hasynced-p1-20261108T052337Z-21771ec372eb-amd64|" \
  "$TMP/overlay-rc.yml" > "$TMP/overlay-rollback.yml"
grep -q '05b36c5a\|hasynced-p1-20261108T052337Z\|1d00dd01' "$TMP/overlay-rollback.yml"
grep -q 'hasynced-p1-20261108T052337Z' "$TMP/overlay-rollback.yml" && echo ROLLBACK_OVERLAY_PIN=PASS
grep -q 'a6-abc-p0-20261108T061445Z' "$TMP/overlay-rc.yml" && echo RC_OVERLAY_PIN=PASS
echo "INFO operational_node_rollback_id=$OPERATIONAL_NODE_ID"
echo "INFO operational_jvm_rollback_id=$OPERATIONAL_JVM_ID"
# Persist marker survived ACL revert above
docker run --rm -v "${PROJ}-data:/data" alpine:3.20 test -f /data/persist-marker \
  && echo DATA_SURVIVED_ACL_REVERT=PASS

echo "A6_GAMEPLAY_RC_DISPOSABLE_REHEARSAL PASS"
echo "LOG=$OUT"
