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
grep -q 'profiles: \["with-node"\]' docker-compose.odisseu-core.yml && ok "node gated by profile" || bad "node profile"
grep -q 'curl' Dockerfile.jvm && ok "curl in Dockerfile.jvm" || bad "curl missing"
grep -q 'ODISSEU_BRIDGE_SEED=false' Dockerfile.jvm && ok "seed default false" || bad "seed default"
# /health responde sem require-auth no mesmo branch (contrato healthcheck)
if awk '/\(= path "\/health"\)/,/\(or \(str\/starts-with\? path "\/admin"\)/' \
     modules/pokemon/infrastructure/pokemon/infrastructure/http/bridge.clj \
     | grep -q 'require-auth!'; then
  bad "health branch calls require-auth"
else
  ok "GET /health sem Authorization"
fi


if grep -q 'evaluate-m90-gates' apps/odisseu/src/odisseu/cutover/durability.clj \
   && grep -A30 'defn evaluate-m90-gates' apps/odisseu/src/odisseu/cutover/durability.clj \
        | grep -q ':cutover-ready? false'; then
  ok "evaluate-m90-gates cutover-ready?=false"
else
  bad "gates M9.0"
fi

grep -q '.wwebjs_auth_core/' .gitignore && ok "gitignore session core" || bad "gitignore session"
grep -q '.env.odisseu-core' .gitignore && ok "gitignore env core" || bad "gitignore env"

if grep -q 'WWEBJS_DATA_PATH: /app/.wwebjs_auth_core' docker-compose.odisseu-core.yml \
   && grep -q './.wwebjs_auth_core:/app/.wwebjs_auth_core' docker-compose.odisseu-core.yml \
   && ! grep -E 'WWEBJS_DATA_PATH: /app/\.wwebjs_auth$' docker-compose.odisseu-core.yml; then
  ok "compose session path _core (M9.4)"
else
  bad "compose deve usar /app/.wwebjs_auth_core (não /.wwebjs_auth)"
fi

if grep -q 'bootstrap-tentativas' src/zapbot/core.cljs \
   && grep -q 'bootstrap-group-discovery' src/zapbot/core.cljs \
   && grep -q 'WAWebCollections' scripts/lib/bootstrap-group-discovery.cjs; then
  ok "bootstrap descoberta leve WAWebCollections (M9.5)"
else
  bad "descoberta de grupos M9.5 incompleta"
fi

if grep -q '/bootstrap/discover-groups' src/zapbot/whatsapp_saude.cljs; then
  ok "endpoint re-descoberta bootstrap (M9.5)"
else
  bad "faltando /bootstrap/discover-groups"
fi

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
