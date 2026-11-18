#!/usr/bin/env bash
# Entrada oficial da entrega A6. Não contacta produção; o plano roda sobre fixture isolada.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$root/scripts/a6_delivery.py" "$@"
