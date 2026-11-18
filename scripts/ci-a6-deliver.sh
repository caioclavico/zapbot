#!/usr/bin/env bash
# Runner da entrega A6. Esta revisão não abre SSH nem altera container.
set -euo pipefail

operation="${DEPLOY_OPERATION:-}"
service="${A6_SERVICE:-}"
authorization="${PRODUCTION_AUTHORIZATION:-}"

if [[ "$operation" != "deploy" && "$operation" != "rollback" && "$operation" != "check" ]]; then
  echo 'Operação A6 desconhecida.' >&2
  exit 2
fi
if [[ "$service" != "node" && "$service" != "jvm" ]]; then
  echo 'O serviço A6 precisa ser node ou jvm, nunca os dois no mesmo passo.' >&2
  exit 2
fi
if [[ "$authorization" != "autorizar-producao-a6" ]]; then
  echo 'Autorização explícita de produção ausente.' >&2
  exit 2
fi
if [[ "$operation" != "check" && "${ODISSEU_PRODUCTION_DEPLOY_HOLD:-}" != "false" ]]; then
  echo 'Hold de produção ativo. Deploy e rollback continuam bloqueados.' >&2
  exit 2
fi
if [[ "${ODISSEU_A6_DELIVERY_VALIDATED:-}" != "true" ]]; then
  echo 'A infraestrutura de entrega A6 ainda não foi validada.' >&2
  exit 2
fi
if [[ -z "${SSH_HOST:-}" ]]; then
  echo 'A VM não foi resolvida por projeto, zona e nome.' >&2
  exit 2
fi

echo "Entrega ${service} (${operation}) recusada nesta revisão: apply na VM continua desligado." >&2
exit 2
