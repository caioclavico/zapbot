#!/bin/bash
# Atualiza um container só em ensaio isolado. Não remove volume e não chama compose.
set -euo pipefail

if [[ "${A6_ISOLATED:-}" != "1" ]]; then
  echo "atualização de container recusada fora de ensaio isolado" >&2
  exit 2
fi

plan="${1:-}"
if [[ -z "$plan" || ! -f "$plan" ]]; then
  echo "plano ausente" >&2
  exit 2
fi

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 - "$plan" "$root" <<'PY'
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(sys.argv[2]) / "scripts"))
from a6_writer_policy import prove_quiescence
from a6_mount_policy import assess
plan = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if plan.get("isolated") is not True:
    print("plano não isolado", file=sys.stderr)
    raise SystemExit(2)
proof = prove_quiescence(plan.get("evidence") or {})
if not proof["quiescenceProven"]:
    print(proof["reason"] or "quiescence_not_proven", file=sys.stderr)
    raise SystemExit(2)
observed = plan.get("observedContainers")
if not isinstance(observed, list):
    print("mounts_not_observed", file=sys.stderr)
    raise SystemExit(2)
mounts = assess(observed, plan["preserve"]["datomic"], plan["container"])
if not mounts["exclusive"]:
    print(mounts["reason"] or "writable_mount_not_exclusive", file=sys.stderr)
    raise SystemExit(2)
for key in ("datomic", "journal", "maint", "session"):
    if not plan.get("preserve", {}).get(key):
        print("volume ausente", file=sys.stderr)
        raise SystemExit(2)
text = json.dumps(plan)
if "volume rm" in text or "compose down" in text or "--remove-orphans" in text:
    print("plano pede destruição de volume", file=sys.stderr)
    raise SystemExit(2)
PY

name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["container"])' "$plan")"
image="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image"])' "$plan")"
network="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["network"])' "$plan")"
previous="$(docker inspect --format '{{.Config.Image}}' "$name")"
docker rename "$name" "${name}-previous"
docker run -d --name "$name" --network "$network" "$image" >/dev/null
printf 'previous=%s\nimage=%s\n' "$previous" "$image"
