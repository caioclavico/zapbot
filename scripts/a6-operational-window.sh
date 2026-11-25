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
#   A6_BACKUP_DESTINATION=/var/backups/odisseu-a6/<window-id>/data \
#   A6_NODE_SECCOMP_PATH=/etc/odisseu-a6/chromium-seccomp.json \
#   A6_NODE_SECCOMP_SHA256=4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10 \
#   A6_STATE_DIR=<diretório do diário, padrão ~/.odisseu-a6-window> \
#   bash scripts/a6-operational-window.sh --window
# O terminal pede confirmo-alterar-vm-a6 antes da primeira alteração.
# O diário a6-window-checkpoint.json fica em A6_STATE_DIR. Ele grava a
# intenção antes de cada operação irreversível e a conclusão só depois
# de observá-la, com escrita atômica e fsync. Falha ao gravar bloqueia.
# Retomar é repetir o mesmo A6_WINDOW_ID; operação com resultado
# desconhecido é conferida no docker inspect e nunca repetida às cegas.
# Outro id com janela incompleta é recusado, e a6-window.lock impede
# duas janelas ao mesmo tempo.
# Se o processo morrer, até com SIGKILL, rodar antes de qualquer coisa:
#   A6_HOLD_STORE=github bash scripts/a6-hold-recovery.sh
# Ele volta o hold para true mesmo sem diário, grava uma auditoria e
# não toca em containers. Não restaurar o Datomic à mão.
# Ambiente é materializado em tmpfs na VM; seccomp customizado exige
# A6_NODE_SECCOMP_PATH e A6_NODE_SECCOMP_SHA256 verificados antes do hold.
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

expected_backup="/var/backups/odisseu-a6/${A6_WINDOW_ID}/data"
if [[ "${A6_BACKUP_DESTINATION}" != "$expected_backup" || \
      "${A6_NODE_SECCOMP_PATH:-}" != "/etc/odisseu-a6/chromium-seccomp.json" || \
      "${A6_NODE_SECCOMP_SHA256:-}" != "4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10" ]]; then
  echo "operational_profile_required" >&2
  exit 2
fi

if [[ ! -t 0 ]]; then
  echo "interactive_confirmation_required" >&2
  exit 2
fi

# PR #136: no fallback to old unguarded runner; enforce explicit combined release.
if [[ "${A6_V3_SINGLE_CUTOVER:-}" != "a6-v3-raid-auto-136" || -z "${A6_V3_REHEARSAL_REPORT:-}" ]]; then
  echo "v3_single_cutover_protocol_required" >&2
  exit 2
fi

if [[ "${A6_HOLD_STORE:-}" != "github" ]]; then
  echo "hold_store_required" >&2
  exit 2
fi

root=$(cd "$(dirname "$0")/.." && pwd)
exec python3 "$root/scripts/a6_operational_window.py"