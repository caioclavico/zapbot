#!/bin/bash
# Entrada manual da janela A6. Não é chamada por CI nem por push.
# O script scripts/a6-supervised-deploy.sh continua sem Docker.
#
# Comando da janela real, num terminal interativo, com o hold ainda true:
#   A6_EXECUTE=1 \
#   A6_SUPERVISED_WINDOW=janela-supervisionada-a6 \
#   A6_SUPERVISED_CONFIRM=executar-janela-a6 \
#   PRODUCTION_AUTHORIZATION=autorizar-producao-a6 \
#   ODISSEU_A6_DELIVERY_VALIDATED=true \
#   A6_LIVE_BACKUP=autorizar-backup-producao-a6 \
#   A6_RESUME_EXPLICIT=confirmar-resume-a6 \
#   A6_WINDOW_ID=janela-aaaammdd-hhmm \
#   A6_HOLD_STORE=github \
#   A6_IMAGE_SHA=<sha do código das imagens> \
#   A6_JVM_DIGEST=sha256:<digest amd64 da JVM> \
#   A6_NODE_DIGEST=sha256:<digest amd64 do Node> \
#   A6_BACKUP_DESTINATION=<diretório fora dos volumes> \
#   bash scripts/a6-operational-window.sh --window
# O terminal pede confirmo-alterar-vm-a6 antes da primeira alteração.
# Interromper o processo restaura o hold para true se ele tinha sido
# liberado e o resume ainda não tinha ocorrido. Um checkpoint em
# a6-window-checkpoint.json, ao lado do backup, guarda a janela, a VM
# e o último passo. Retomar é repetir o mesmo A6_WINDOW_ID. Outro id
# com janela incompleta é recusado. Não restaurar o Datomic à mão.
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

if [[ "${A6_EXECUTE:-}" != "1" || "${A6_SUPERVISED_WINDOW:-}" != "janela-supervisionada-a6" ]]; then
  echo "window_not_authorized" >&2
  exit 2
fi

if [[ "${PRODUCTION_AUTHORIZATION:-}" != "autorizar-producao-a6" || "${ODISSEU_A6_DELIVERY_VALIDATED:-}" != "true" ]]; then
  echo "window_not_authorized" >&2
  exit 2
fi

if [[ "${A6_LIVE_BACKUP:-}" != "autorizar-backup-producao-a6" || "${A6_RESUME_EXPLICIT:-}" != "confirmar-resume-a6" ]]; then
  echo "window_not_authorized" >&2
  exit 2
fi

if [[ -z "${A6_WINDOW_ID:-}" || -z "${A6_IMAGE_SHA:-}" || -z "${A6_JVM_DIGEST:-}" || -z "${A6_NODE_DIGEST:-}" || -z "${A6_BACKUP_DESTINATION:-}" ]]; then
  echo "window_not_authorized" >&2
  exit 2
fi

if [[ ! -t 0 ]]; then
  echo "interactive_confirmation_required" >&2
  exit 2
fi

if [[ "${A6_HOLD_STORE:-}" != "github" ]]; then
  echo "hold_store_required" >&2
  exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$root/scripts/a6_operational_window.py"
