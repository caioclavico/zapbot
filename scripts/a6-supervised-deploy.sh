#!/bin/bash
# Janela supervisionada do stack A6. Não responde a push nem ao CI.
# Sem a confirmação explícita, não chama Docker e não copia volume.
# Mesmo com a janela autorizada, este script não chama Docker nesta fase.
# O ciclo real fica em a6_supervised_deploy.py e só corre com um runner injetado.
set -euo pipefail

for argument in "$@"; do
  case "$argument" in
    *'compose down'*|*'--remove-orphans'*|*'volume rm'*|*'volume prune'*)
      echo "comando destrutivo recusado" >&2
      exit 2
      ;;
  esac
done

if [[ "${GITHUB_ACTIONS:-}" == "true" || "${GITHUB_EVENT_NAME:-}" == "push" ]]; then
  echo "push_not_authorized" >&2
  exit 2
fi

if [[ "${1:-}" != "--window" || "${A6_SUPERVISED_CONFIRM:-}" != "executar-janela-a6" ]]; then
  echo "window_not_authorized" >&2
  exit 2
fi

if [[ "${ODISSEU_PRODUCTION_DEPLOY_HOLD:-true}" != "false" ]]; then
  echo "hold_active" >&2
  exit 2
fi

if [[ "${A6_SUPERVISED_RUNNER:-}" != "vm" ]]; then
  echo "apply_requires_live_recheck" >&2
  exit 2
fi

if [[ "${A6_LIVE_BACKUP:-}" != "autorizar-backup-producao-a6" ]]; then
  echo "backup_not_authorized" >&2
  exit 2
fi

echo "apply_requires_live_recheck" >&2
exit 2
