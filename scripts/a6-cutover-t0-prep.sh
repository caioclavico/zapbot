#!/usr/bin/env bash
# Fase 7.2 — cold T0 + restore-check isolado para o corte A5.6→A6.
# NÃO aplica migrations, NÃO sobe A6, NÃO altera grupos.
#
# No Core, somente com autorização explícita:
#   A6_CUTOVER_T0=1 ./scripts/a6-cutover-t0-prep.sh
set -euo pipefail

if [[ "${A6_CUTOVER_T0:-0}" != "1" ]]; then
  echo "Defina A6_CUTOVER_T0=1 para executar (requer GO escrito)." >&2
  exit 2
fi

STAMP="${A6_CUTOVER_STAMP:-$(date -u +%Y%m%dT%H%M%SZ)}"
ROOT="${ODISSEU_CORE_DIR:-/home/caiohclavico/odisseu-core}"
REC="${ODISSEU_RECOVERY_DIR:-/home/caiohclavico/odisseu-recovery}"
BK="${REC}/a6-cutover-t0-cold-${STAMP}"
RC="${REC}/a6-cutover-t0-restore-check-${STAMP}"
COMPOSE_ENV="${ROOT}/.env.odisseu-core"
COMPOSE_BASE="${ROOT}/docker-compose.odisseu-core.yml"
COMPOSE_REL="${ROOT}/docker-compose.release-battle-idle.yml"
VOL_DATOMIC="odisseu-core-a56-datomic"
VOL_JOURNAL="odisseu-core-a56-journal"
VOL_MAINT="odisseu-core-a56-maint"

cd "$ROOT"
mkdir -p "$BK/datomic" "$BK/journal" "$BK/maint" "$BK/meta"

echo "==> [T0] stop JVM writer (Node pode permanecer up se política permitir quiet)"
docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" stop odisseu-jvm

cleanup_start_jvm() {
  echo "==> [T0] start JVM (cleanup trap)"
  docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" start odisseu-jvm || true
}
trap cleanup_start_jvm EXIT

echo "==> [T0] cold copy → $BK"
docker run --rm -v "${VOL_DATOMIC}:/data:ro" -v "${BK}/datomic:/out" alpine sh -c 'cp -a /data/. /out/'
docker run --rm -v "${VOL_JOURNAL}:/data:ro" -v "${BK}/journal:/out" alpine sh -c 'cp -a /data/. /out/ || true'
docker run --rm -v "${VOL_MAINT}:/data:ro" -v "${BK}/maint:/out" alpine sh -c 'cp -a /data/. /out/ || true'

echo "==> [T0] start JVM again"
docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_BASE" -f "$COMPOSE_REL" start odisseu-jvm
trap - EXIT

healthy=0
for i in $(seq 1 40); do
  if curl -fsS -m 2 http://127.0.0.1:18090/health >/dev/null 2>&1; then
    echo "JVM healthy (attempt $i)"
    healthy=1
    break
  fi
  sleep 2
done
[[ "$healthy" == "1" ]] || { echo "FAIL JVM health após T0" >&2; exit 5; }

(
  cd "$BK"
  find . -type f | wc -l | tee "${BK}.filecount"
  find . -type f | sort | xargs -r sha256sum > "${BK}.files.sha256"
  sha256sum "${BK}.files.sha256" | tee "${BK}.manifest-of-manifest.sha256"
)

echo "==> [T0] restore-check isolado → $RC"
rm -rf "$RC"
mkdir -p "$RC"
cp -a "$BK/." "$RC/"
SRC_N=$(find "$BK" -type f | wc -l)
DST_N=$(find "$RC" -type f | wc -l)
echo "restore src=$SRC_N dst=$DST_N"
[[ "$SRC_N" == "$DST_N" && "$SRC_N" -ge 1 ]] || { echo "FAIL restore counts" >&2; exit 1; }
test -d "$RC/datomic/odisseu-m90-core/core"

cat > "${BK}/meta/t0-pointer.txt" <<EOF
stamp=${STAMP}
cold=${BK}
restore_check=${RC}
filecount=${SRC_N}
manifest=$(cut -d' ' -f1 "${BK}.manifest-of-manifest.sha256")
note=NO_A6_WRITE_YET
EOF

echo "OK T0_COLD=$BK"
echo "OK T0_RESTORE=$RC"
echo "NEXT: gerar manifesto Datomic na cópia restore (host com clojure):"
echo "  ODISSEU_PROBE_STORAGE=$RC/datomic ODISSEU_FASE7_OUT=$REC/a6-cutover-t0-manifest-${STAMP}.edn \\"
echo "  ODISSEU_FASE7_LABEL=cutover-t0 clojure -M -e '(load-file \"scripts/a6_fase7_integrity_manifest.clj\")'"
echo "Só depois disso: escrever no volume destino A6 (fora deste script)."
