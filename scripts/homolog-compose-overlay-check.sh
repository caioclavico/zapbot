#!/usr/bin/env bash
# Validação isolada do overlay (sem produção, sem secrets).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/docker"
cp "$ROOT/docker/chromium-seccomp.json" "$TMP/docker/chromium-seccomp.json"
cp "$ROOT/docker-compose.a6-ai-drain-sandbox.overlay.yml" "$TMP/"

# Base mínimo espelhando o serviço live (sem tokens).
cat > "$TMP/docker-compose.base.yml" <<'YAML'
services:
  odisseu-a6-jvm:
    image: alpine:3.20
    command: ["sleep", "3600"]
  odisseu-a6-node:
    image: odisseu-node:a6-drain-breakglass-20261106-1d00dd01
    container_name: odisseu-a6-node
    depends_on:
      - odisseu-a6-jvm
    environment:
      POKEMON_GYM_CHALLENGES_ENABLED: "false"
      POKEMON_RAID_AUTO_TICK: "true"
      POKEMON_THEMATIC_EVENTS_ENABLED: "true"
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

python3 - <<'PY'
from pathlib import Path
import sys
text = Path('compose.resolved.yml').read_text()
checks = {
  'image_tag': 'a6-ai-drain-sandbox-20261107T204141Z-fefab4c-amd64' in text,
  'user_1000': 'user: "1000:1000"' in text or 'user: 1000:1000' in text,
  'cap_drop': 'cap_drop:' in text and 'ALL' in text,
  'nnp': 'no-new-privileges' in text,
  'seccomp': 'chromium-seccomp.json' in text,
  'stop_grace': 'stop_grace_period: 60s' in text or 'stop_grace_period: 1m' in text,
  'gym_flag_preserved': 'POKEMON_GYM_CHALLENGES_ENABLED: "false"' in text or 'POKEMON_GYM_CHALLENGES_ENABLED: false' in text,
  'raid_flag_preserved': 'POKEMON_RAID_AUTO_TICK: "true"' in text or 'POKEMON_RAID_AUTO_TICK: true' in text,
  'volume_data': 'odisseu-a6-cutover-data' in text,
  'volume_session': '.wwebjs_auth_a6_cutover' in text,
}
failed = [k for k,v in checks.items() if not v]
print({k: ('PASS' if v else 'FAIL') for k,v in checks.items()})
if failed:
    print('FAIL', failed)
    sys.exit(1)
print('COMPOSE_OVERLAY_ISOLATED_CHECK PASS')
PY
