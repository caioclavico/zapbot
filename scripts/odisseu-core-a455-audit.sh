#!/usr/bin/env bash
# A4.5.5 — auditoria de uma cópia de backup já criada.
# Não conecta à VM, não para serviços e nunca usa volumes odisseu-core-*.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${A455_IMAGE:-odisseu-contract-tests}"
BACKUP="${1:?Uso: $0 <backup-dir> [system] [db]}"
SYSTEM="${2:-odisseu-m90-core}"
DB="${3:-core}"
VOLUME="odisseu-a455-audit-${RANDOM}-${RANDOM}"
VOLUME_CREATED=0
SNAP="/app/.a455-before.edn"
PHASE="startup"

die() { echo "A455 ERRO: $*" >&2; exit 3; }
cleanup() {
  status=$?
  echo "A455 phase=${PHASE} volume=${VOLUME} exit=${status}" >&2
  if [[ "$VOLUME_CREATED" == "1" ]]; then
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
  fi
  rm -f "$ROOT/.a455-before.edn"
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || die "Docker CLI ausente"
docker info >/dev/null 2>&1 || die "Docker daemon indisponível"
docker image inspect "$IMAGE" >/dev/null 2>&1 || die "imagem ausente: $IMAGE"

PHASE="preflight-clojure"
docker run --rm --network none -v "$ROOT:/app" "$IMAGE" \
  clojure -M -e \
  '(load-file "/app/scripts/odisseu-core-a452-fixture.clj")
    (load-file "/app/scripts/odisseu-core-a452-audit.clj")
    (println "A455_AUDITOR_COMPILED")'

[[ -d "$BACKUP" && ! -L "$BACKUP" ]] || die "backup deve ser diretório real"
# macOS não suporta `realpath -e`; cd + pwd -P resolve apenas diretórios existentes.
BACKUP="$(cd "$BACKUP" && pwd -P)" || die "não foi possível resolver o backup"
[[ -d "$BACKUP/datomic" ]] || die "backup sem datomic"
[[ -f "$BACKUP/meta/SHA256SUMS" ]] || die "manifesto SHA-256 ausente"
verify_manifest() {
  local manifest="$BACKUP/meta/SHA256SUMS" hash file actual
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$BACKUP" && sha256sum -c meta/SHA256SUMS)
    return
  fi
  command -v shasum >/dev/null 2>&1 || die "sha256sum/shasum ausentes"
  while read -r hash file; do
    [[ -n "$hash" && -n "$file" ]] || continue
    actual="$(shasum -a 256 "$BACKUP/$file" | awk '{print $1}')"
    [[ "$actual" == "$hash" ]] || return 1
  done < "$manifest"
}
verify_manifest || die "manifesto SHA-256 inválido"

for part in datomic journal maint; do
  [[ -d "$BACKUP/$part" ]] || die "backup incompleto: $part"
done

case "$VOLUME" in odisseu-a455-audit-*) ;; *) die "volume inválido";; esac
docker volume create "$VOLUME" >/dev/null
VOLUME_CREATED=1
docker volume inspect "$VOLUME" >/dev/null
if docker volume inspect "$VOLUME" | grep -Eqi 'odisseu-core-'; then
  die "volume de auditoria associado a volume oficial"
fi

PHASE="restore-isolated"
docker run --rm --network none \
  -v "$VOLUME:/to" -v "$BACKUP/datomic:/from:ro" alpine:3.20 \
  sh -c 'cp -a /from/. /to/'

PHASE="snapshot"
docker run --rm --network none \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a455" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh snapshot \
    /data/audit-a455 "$SYSTEM" "$DB" "$SNAP"

PHASE="migration-020"
docker run --rm --network none -e ODISSEU_A452_VOLUME_VALIDATED=1 \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a455" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh install \
    /data/audit-a455 "$SYSTEM" "$DB" "$SNAP"

PHASE="idempotency"
docker run --rm --network none -e ODISSEU_A452_VOLUME_VALIDATED=1 \
  -v "$ROOT:/app" -v "$VOLUME:/data/audit-a455" "$IMAGE" \
  bash /app/scripts/odisseu-core-a452-audit.sh install \
    /data/audit-a455 "$SYSTEM" "$DB" "$SNAP"

echo "A455_AUDIT_OK"
