#!/bin/bash
# Recuperação administrativa do hold de produção do A6.
# Consulta ODISSEU_PRODUCTION_DEPLOY_HOLD no GitHub e volta para true se
# a janela foi interrompida, mesmo sem diário ou com diário incompleto.
# Não retoma containers, não faz rollback e não toca no Datomic. Grava
# a6-hold-recovery-*.json em A6_STATE_DIR para auditoria. Recusa rodar
# enquanto outra janela segura a6-window.lock.
#   A6_HOLD_STORE=github A6_STATE_DIR=<diretório do diário> \
#   bash scripts/a6-hold-recovery.sh
set -euo pipefail

if [[ "${GITHUB_ACTIONS:-}" == "true" || "${GITHUB_EVENT_NAME:-}" == "push" ]]; then
  echo "push_not_authorized" >&2
  exit 2
fi

if [[ "${A6_HOLD_STORE:-}" != "github" ]]; then
  echo "hold_store_required" >&2
  exit 2
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
if [[ -f "$script_dir/a6_operational_window.py" ]]; then
  runner="$script_dir/a6_operational_window.py"
else
  runner="$(cd "$script_dir/.." && pwd)/scripts/a6_operational_window.py"
fi
exec python3 "$runner" --recover-hold
