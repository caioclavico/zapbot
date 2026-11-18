#!/usr/bin/env bash
# Auditoria rápida do staging M6.2 (rede, volumes, guards, recursos).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
REPORT_DIR="${ODISSEU_M62_REPORT_DIR:-${HOME}/odisseu-recovery/m6-2-private}"
mkdir -p "${REPORT_DIR}"
OUT="${REPORT_DIR}/audit-$(date -u +%Y%m%dT%H%M%SZ).txt"

{
  echo "# M6.2 staging audit $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## Compose ports (deve ser 127.0.0.1:18090)"
  grep -n 'ports\|mem_limit\|healthcheck\|POKEMON_SERVICE\|ODISSEU_BRIDGE' docker-compose.staging.yml || true
  echo
  echo "## Config guards (produção força legacy)"
  grep -n 'jvm-adapter-envs\|pokemon-adapter\|APP_ENV' src/zapbot/config.cljs || true
  echo
  echo "## Host resources"
  sysctl -n hw.memsize hw.ncpu 2>/dev/null || true
  df -h . | tail -1
  echo
  echo "## Datomic refs M5 (SHA se existir)"
  for d in .datomic-local/m53-real-offline .datomic-local/m55-real-offline; do
    if [[ -d "$d" ]]; then
      echo -n "$d: "
      (cd "$d" && find . -type f | sort | shasum | shasum | awk '{print $1}')
    else
      echo "$d: (ausente neste host — ok)"
    fi
  done
  echo
  echo "## Bridge / Docker"
  curl -fsS -m 2 http://127.0.0.1:18090/health 2>/dev/null || echo "bridge down (ok se parado)"
  if command -v docker >/dev/null 2>&1; then
    docker compose -f docker-compose.staging.yml ps 2>/dev/null || true
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' 2>/dev/null \
      | grep -E 'odisseu-staging|NAME' || echo "(sem containers staging)"
  fi
  echo
  echo "## Writer lock file (se storage host ativo)"
  ls -la .datomic-local/m61-staging/.odisseu-writer.lock 2>/dev/null || echo "(sem lock host)"
} | tee "${OUT}"

echo "Relatório: ${OUT}"
