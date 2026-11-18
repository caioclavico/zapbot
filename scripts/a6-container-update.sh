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
plan = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
if plan.get("isolated") is not True:
    raise SystemExit("plano não isolado")
proof = prove_quiescence(plan.get("evidence") or {})
if not proof["quiescenceProven"]:
    raise SystemExit(proof["reason"] or "quiescence_not_proven")
for key in ("datomic", "journal", "maint", "session"):
    if not plan.get("preserve", {}).get(key):
        raise SystemExit("volume ausente")
text = json.dumps(plan)
if "volume rm" in text or "compose down" in text or "--remove-orphans" in text:
    raise SystemExit("plano pede destruição de volume")
PY

name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["container"])' "$plan")"
image="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image"])' "$plan")"
network="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["network"])' "$plan")"
previous="$(docker inspect --format '{{.Config.Image}}' "$name")"
docker rename "$name" "${name}-previous"
docker run -d --name "$name" --network "$network" "$image" >/dev/null
printf 'previous=%s\nimage=%s\n' "$previous" "$image"
