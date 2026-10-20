#!/usr/bin/env bash
# Sobe odisseu-core (Compose). Não toca odisseu-zapbot / odisseu-pokemon.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=odisseu-core-lib.sh
source "${ROOT}/scripts/odisseu-core-lib.sh"

odisseu_core_require_env "$ROOT"
odisseu_core_assert_not_prod_paths "${WWEBJS_DATA_PATH:-.wwebjs_auth_core}"

mkdir -p .wwebjs_auth_core
export APP_ENV=staging
export POKEMON_ADAPTER=jvm
export PLATFORM_ADAPTER=datomic
export WWEBJS_CLIENT_ID="${WWEBJS_CLIENT_ID:-core}"

echo "==> odisseu-core up (jvm + node)"
echo "    compose=${ODISSEU_CORE_COMPOSE}"
echo "    system=odisseu-m90-core storage=volume:odisseu-core-datomic"
echo "    session_host=.wwebjs_auth_core clientId=${WWEBJS_CLIENT_ID}"
echo "    bridge=127.0.0.1:18090 health_node=127.0.0.1:3001"
echo "    cutover-ready=false (M9.0 — preparação apenas)"

odisseu_core_compose "$ROOT" up -d --build "$@"
odisseu_core_compose "$ROOT" ps
echo "==> Health JVM (loopback):"
curl -fsS -m 5 -H "Authorization: Bearer ${ODISSEU_BRIDGE_TOKEN}" \
  http://127.0.0.1:18090/health | head -c 200 || echo "(aguardando health)"
echo
echo "==> Health Node (pode ser degraded sem WhatsApp READY):"
curl -sS -m 5 http://127.0.0.1:3001/health || echo "(node ainda subindo / sem READY)"
echo
echo "Pronto. Auth WhatsApp: STAGING_WHATSAPP_CONFIRM=1 STAGING_ALLOW_QR=1 (conta TESTE)"
echo "  ou bootstrap: STAGING_BOOTSTRAP_GROUP=1 — ver docs/m9-0-odisseu-core-deploy.md"
