#!/usr/bin/env bash
# Mede RSS/CPU do ambiente de staging (host). Não assume smoke JVM isolado = monólito.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT_DIR="${ODISSEU_M61_REPORT_DIR:-${HOME}/odisseu-recovery/m6-1-private}"
mkdir -p "${REPORT_DIR}"
OUT="${REPORT_DIR}/resources-$(date -u +%Y%m%dT%H%M%SZ).txt"

{
  echo "# M6.1 resource snapshot $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## Host"
  if command -v sysctl >/dev/null 2>&1; then
    echo "memsize_bytes=$(sysctl -n hw.memsize 2>/dev/null || true)"
    echo "ncpu=$(sysctl -n hw.ncpu 2>/dev/null || true)"
  fi
  if command -v vm_stat >/dev/null 2>&1; then
    echo "## vm_stat"
    vm_stat
  fi
  if command -v free >/dev/null 2>&1; then
    free -m
  fi
  echo
  echo "## Processos relevantes (RSS KB)"
  ps -axo pid,rss,pcpu,comm,args 2>/dev/null | grep -E 'clojure|java|node|Chromium|chrome|odisseu|zapbot' | grep -v grep || true
  echo
  echo "## Docker (se houver)"
  if command -v docker >/dev/null 2>&1; then
    docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' 2>/dev/null | grep -E 'odisseu-staging|NAME' || echo "(sem containers staging)"
  fi
  echo
  echo "## Bridge health"
  curl -fsS -m 3 http://127.0.0.1:18090/health 2>/dev/null || echo "bridge down"
  echo
  echo "## Limitações"
  echo "- Sem sessão WhatsApp autenticada, Chromium pode estar só em QR/STARTING — RSS Node+Chromium não representa produção."
  echo "- Este snapshot é do host local de staging, não da VM de produção."
} | tee "${OUT}"

echo "Relatório: ${OUT}"
