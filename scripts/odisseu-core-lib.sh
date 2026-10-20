#!/usr/bin/env bash
# Helpers compartilhados M9.0 — odisseu-core. Sourced por outros scripts.

ODISSEU_CORE_COMPOSE="${ODISSEU_CORE_COMPOSE:-docker-compose.odisseu-core.yml}"
ODISSEU_CORE_ENV="${ODISSEU_CORE_ENV:-.env.odisseu-core}"

odisseu_core_assert_not_prod_paths() {
  local p="${1:-}"
  # Bloqueia sessão de produção (.wwebjs_auth sem sufixo _core/_staging).
  if [[ "$p" == ".wwebjs_auth" || "$p" == */.wwebjs_auth || "$p" == */.wwebjs_auth/ ]]; then
    echo "Path de sessão de produção recusado: $p" >&2
    return 3
  fi
  if echo "$p" | grep -Eqi 'odisseu-zapbot|odisseu-live|m53-real|m55-real|prod-cassandra'; then
    echo "Path proibido (produção/legado): $p" >&2
    return 3
  fi
  return 0
}

odisseu_core_require_env() {
  local root="${1:?}"
  local env_file="${root}/${ODISSEU_CORE_ENV}"
  if [[ ! -f "${env_file}" ]]; then
    echo "Crie ${ODISSEU_CORE_ENV} a partir de .env.odisseu-core.example" >&2
    exit 2
  fi
  # shellcheck disable=SC1090
  set -a; source "${env_file}"; set +a
  if [[ -z "${ODISSEU_BRIDGE_TOKEN:-}" || "${ODISSEU_BRIDGE_TOKEN}" == change-me* ]]; then
    echo "ODISSEU_BRIDGE_TOKEN deve ser um segredo real (não o placeholder)." >&2
    exit 2
  fi
}

odisseu_core_compose() {
  local root="${1:?}"
  shift
  docker compose -f "${root}/${ODISSEU_CORE_COMPOSE}" --env-file "${root}/${ODISSEU_CORE_ENV}" "$@"
}
