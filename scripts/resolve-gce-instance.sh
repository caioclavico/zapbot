#!/usr/bin/env bash
# Resolve a GCE instance by project, zone and name. No fixed address is accepted.
set -euo pipefail

project="${ODISSEU_GCP_PROJECT:-}"
zone="${ODISSEU_GCP_ZONE:-}"
name="${ODISSEU_GCP_INSTANCE:-}"

if [[ -z "$project" || -z "$zone" || -z "$name" ]]; then
  echo 'Projeto, zona e nome da VM são obrigatórios. Nenhum IP fixo é usado.' >&2
  exit 2
fi
if [[ "$project" =~ ^[0-9]+(\.[0-9]+){3}$ || "$zone" =~ ^[0-9]+(\.[0-9]+){3}$ || "$name" =~ ^[0-9]+(\.[0-9]+){3}$ ]]; then
  echo 'Projeto, zona e nome não podem ser um endereço IP.' >&2
  exit 2
fi
if ! command -v gcloud >/dev/null 2>&1; then
  echo 'gcloud ausente. A VM não foi resolvida e nenhuma conexão foi aberta.' >&2
  exit 2
fi

host="$(gcloud compute instances describe "$name" \
  --project "$project" \
  --zone "$zone" \
  --format='value(networkInterfaces[0].accessConfigs[0].natIP)')"
if [[ ! "$host" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]]; then
  echo 'A descrição da instância não trouxe um NAT. Nenhuma conexão foi aberta.' >&2
  exit 2
fi
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'host=%s\n' "$host" >> "$GITHUB_OUTPUT"
else
  printf 'host=%s\n' "$host"
fi
