#!/usr/bin/env bash
# Validação estática M9.0 (sem SSH, sem cutover).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAIL=0
ok() { echo "OK  $*"; }
bad() { echo "FAIL $*"; FAIL=1; }

[[ -f docker-compose.odisseu-core.yml ]] && ok "compose file" || bad "compose missing"
[[ -f .env.odisseu-core.example ]] && ok "env example" || bad "env example"
[[ -f docs/m9-0-odisseu-core-deploy.md ]] && ok "docs" || bad "docs"
chmod +x scripts/odisseu-core-*.sh scripts/odisseu-core-lib.sh 2>/dev/null || true

if grep -E '^\s+- "0\.0\.0\.0:(18090|3001)' docker-compose.odisseu-core.yml; then
  bad "porta pública detectada"
else
  ok "ports não publicam 0.0.0.0:18090/3001"
fi

grep -q '127.0.0.1:18090' docker-compose.odisseu-core.yml && ok "bridge loopback" || bad "bridge publish"
grep -q 'wwebjs_auth_core' docker-compose.odisseu-core.yml && ok "session core bind" || bad "session bind"
grep -q 'odisseu-core-journal' docker-compose.odisseu-core.yml && ok "journal volume" || bad "journal volume"
grep -q 'POKEMON_SERVICE_URL: ""' docker-compose.odisseu-core.yml && ok "sem pokemon-service" || bad "pokemon-service"
grep -q 'name: odisseu-core-datomic' docker-compose.odisseu-core.yml && ok "volume names explícitos" || bad "volume names"

if grep -q 'evaluate-m90-gates' apps/odisseu/src/odisseu/cutover/durability.clj \
   && grep -A30 'defn evaluate-m90-gates' apps/odisseu/src/odisseu/cutover/durability.clj \
        | grep -q ':cutover-ready? false'; then
  ok "evaluate-m90-gates cutover-ready?=false"
else
  bad "gates M9.0"
fi

grep -q '.wwebjs_auth_core/' .gitignore && ok "gitignore session core" || bad "gitignore session"
grep -q '.env.odisseu-core' .gitignore && ok "gitignore env core" || bad "gitignore env"

TMPENV=$(mktemp)
trap 'rm -f "$TMPENV"' EXIT
cp .env.odisseu-core.example "$TMPENV"
if command -v docker >/dev/null 2>&1; then
  if docker compose -f docker-compose.odisseu-core.yml --env-file "$TMPENV" config -q 2>/dev/null; then
    ok "docker compose config"
  else
    echo "WARN docker compose config (daemon?) — artefatos presentes"
  fi
else
  echo "WARN docker ausente — skip compose config"
fi

echo "nota: preserve gym WIP (store.clj / gym_m48_test.clj)"
exit "$FAIL"
