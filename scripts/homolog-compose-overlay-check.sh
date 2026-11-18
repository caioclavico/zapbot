#!/usr/bin/env bash
# Validação isolada do overlay (sem produção, sem secrets).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

NODE_TAG='odisseu-node:a6-abc-p0-20261108T061445Z-c5ab41dfcbf2-amd64'
NODE_ID='sha256:289a0407ad5db946f0433b2d721b29d7f338879483f3e5899b834342dad94ade'
BREAKGLASS_TAG='odisseu-node:a6-drain-breakglass-20261106-1d00dd01'

mkdir -p "$TMP/docker"
cp "$ROOT/docker/chromium-seccomp.json" "$TMP/docker/chromium-seccomp.json"
cp "$ROOT/docker-compose.a6-ai-drain-sandbox.overlay.yml" "$TMP/"

# Base mínimo espelhando o serviço live (sem tokens).
# Flags no base devem sobreviver ao overlay (overlay NÃO as redefine).
cat > "$TMP/docker-compose.base.yml" <<YAML
services:
  odisseu-a6-jvm:
    image: alpine:3.20
    command: ["sleep", "3600"]
  odisseu-a6-node:
    image: ${BREAKGLASS_TAG}
    container_name: odisseu-a6-node
    depends_on:
      - odisseu-a6-jvm
    environment:
      POKEMON_GYM_CHALLENGES_ENABLED: "false"
      POKEMON_RAID_AUTO_TICK: "true"
      POKEMON_THEMATIC_EVENTS_ENABLED: "false"
      WWEBJS_DATA_PATH: /app/.wwebjs_auth_a6_cutover
    volumes:
      - odisseu-a6-cutover-data:/app/data
      - ./session-fixture:/app/.wwebjs_auth_a6_cutover
    ports:
      - "127.0.0.1:3003:3001"
    mem_limit: 2560m
    restart: "no"
volumes:
  odisseu-a6-cutover-data:
YAML

mkdir -p "$TMP/session-fixture"
cd "$TMP"
CFG="$(docker compose -f docker-compose.base.yml -f docker-compose.a6-ai-drain-sandbox.overlay.yml config)"
printf '%s\n' "$CFG" > compose.resolved.yml

OV_FILE="$ROOT/docker-compose.a6-ai-drain-sandbox.overlay.yml"
export OV_FILE NODE_TAG NODE_ID
python3 - <<'PY'
from pathlib import Path
import os, sys
text = Path('compose.resolved.yml').read_text()
ov = Path(os.environ['OV_FILE']).read_text()
node_tag = os.environ['NODE_TAG']
node_id = os.environ['NODE_ID']
legacy_tag = 'a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64'
checks = {
  'image_tag': node_tag in text,
  'overlay_declares_tag': node_tag in ov,
  'overlay_declares_image_id': node_id in ov,
  'no_legacy_fefab4c_tag': legacy_tag not in text and legacy_tag not in ov,
  'no_fake_registry_digest_pin': 'image: odisseu-node@sha256:' not in ov,
  'user_1000': 'user: "1000:1000"' in text or 'user: 1000:1000' in text,
  'cap_drop': 'cap_drop:' in text and 'ALL' in text,
  'nnp': 'no-new-privileges' in text,
  'seccomp': 'chromium-seccomp.json' in text,
  'stop_grace': 'stop_grace_period: 60s' in text or 'stop_grace_period: 1m' in text,
  'gym_flag_preserved': (
      'POKEMON_GYM_CHALLENGES_ENABLED: "false"' in text
      or 'POKEMON_GYM_CHALLENGES_ENABLED: false' in text),
  'raid_flag_preserved': (
      'POKEMON_RAID_AUTO_TICK: "true"' in text
      or 'POKEMON_RAID_AUTO_TICK: true' in text),
  'thematic_stays_false': (
      'POKEMON_THEMATIC_EVENTS_ENABLED: "false"' in text
      or 'POKEMON_THEMATIC_EVENTS_ENABLED: false' in text),
  'overlay_no_flag_overrides': all(k not in ov for k in [
      'POKEMON_GYM_CHALLENGES_ENABLED',
      'POKEMON_RAID_AUTO_TICK',
      'POKEMON_THEMATIC_EVENTS_ENABLED',
  ]),
  'volume_data': 'odisseu-a6-cutover-data' in text,
  'volume_session': '.wwebjs_auth_a6_cutover' in text,
  'breakglass_mentioned_for_rollback': '1d00dd01' in ov,
  'live_node_rollback_pin': '05b36c5a' in ov,
  'live_jvm_rollback_pin': 'ad0b7c1b' in ov,
  'health_bind_host': 'HEALTH_BIND_HOST: "0.0.0.0"' in text or 'HEALTH_BIND_HOST: 0.0.0.0' in text,
  'jvm_candidate_documented': '7bc8f341' in ov,
}
failed = [k for k, v in checks.items() if not v]
print({k: ('PASS' if v else 'FAIL') for k, v in checks.items()})
if failed:
    print('FAIL', failed)
    sys.exit(1)
print('COMPOSE_OVERLAY_ISOLATED_CHECK PASS')
print(f'EXPECTED_NODE_IMAGE_ID={node_id}')
PY
