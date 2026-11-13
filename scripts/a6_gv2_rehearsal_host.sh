#!/usr/bin/env bash
set -euo pipefail
EVID=$(sudo cat /tmp/a6-gv2-evid.path)
STAMP=$(sudo cat /tmp/a6-gv2-stamp.path)
BUILD=/tmp/a6-gv2-build-$STAMP/zapbot
PREFIX=$(sudo cat "$EVID/restore-prefix.txt")
IMG=odisseu-jvm:a6-pr80-20261112T225903Z-8b05886f6964-amd64
COLD=/home/caiohclavico/odisseu-recovery/a6-cold-live-private/cold-pr80-a6-20261112T231100Z

echo "live mounts:"
sudo docker inspect odisseu-a6-jvm --format '{{range .Mounts}}{{.Name}} {{.Destination}}{{"\n"}}{{end}}' | grep cutover || true
curl -sS -m 3 http://127.0.0.1:3003/health; echo

# Economy AFTER conversion (converted restore; may include gameplay seed pokemon).
# Datomic Local exige FS gravável para .lock mesmo em consulta — volumes são
# prefixo restore-*, nunca cutover live.
sudo docker run --rm --network none \
  -v "${PREFIX}-datomic:/data/datomic-local" \
  -v /tmp/a6_gv2_economy_ro.clj:/work/econ.clj:ro \
  -v "$BUILD/shared:/app/shared:ro" \
  -w /app --entrypoint clojure "$IMG" \
  -M -e '(load-file "/work/econ.clj")' \
  | tee /tmp/a6-gv2-econ-after.out | tail -30
sudo cp /tmp/a6-gv2-econ-after.out "$EVID/05-economy-after.txt"

# Fresh baseline restore for economy BEFORE (consultas only)
PREFIX2="odisseu-a6-restore-gv2base-$(date -u +%Y%m%dT%H%M)"
echo "$PREFIX2" | sudo tee "$EVID/13-restore-baseline2-prefix.txt"
sudo python3 /tmp/a6_cutover_backup.py restore-isolated --volume-prefix "$PREFIX2" "$COLD" \
  | sudo tee "$EVID/13-restore-baseline2.json"
sudo docker run --rm --network none -v "${PREFIX2}-datomic:/vol" alpine:3.20 \
  sh -lc 'printf "%s\n" "{:phase :open :note \"gv2-baseline-ro\"}" > /vol/.a6-maintenance/bridge-maintenance.edn'

sudo docker run --rm --network none \
  -v "${PREFIX2}-datomic:/data/datomic-local" \
  -v /tmp/a6_gv2_economy_ro.clj:/work/econ.clj:ro \
  -v "$BUILD/shared:/app/shared:ro" \
  -w /app --entrypoint clojure "$IMG" \
  -M -e '(load-file "/work/econ.clj")' \
  | tee /tmp/a6-gv2-econ-before.out | tail -30
sudo cp /tmp/a6-gv2-econ-before.out "$EVID/05-economy-before.txt"

# Inventory-only on baseline2 (no convert write) for live-cold RO snapshot
sudo mkdir -p "$EVID/live-ro"
sudo docker run --rm --network none \
  -v "${PREFIX2}-datomic:/data/datomic-local" \
  -v "$BUILD/modules:/app/modules:ro" \
  -v "$BUILD/apps:/app/apps:ro" \
  -v "$BUILD/shared:/app/shared:ro" \
  -v "$BUILD/scripts:/work/scripts:ro" \
  -v "$EVID/live-ro:/evid" \
  -e ODISSEU_BRIDGE_STORAGE=/data/datomic-local \
  -e ODISSEU_BRIDGE_SYSTEM=odisseu-m90-core \
  -e ODISSEU_BRIDGE_DB=core \
  -e A6_GV2_EVID=/evid \
  -e A6_GV2_MODE=inventory \
  -w /app --entrypoint clojure "$IMG" \
  -M -e '(load-file "/work/scripts/a6_growth_v2_rehearsal.clj")' \
  | sudo tee "$EVID/21-baseline-inventory.log" | tail -40

echo DONE_HOST
