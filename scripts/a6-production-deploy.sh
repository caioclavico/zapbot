#!/bin/bash
# Dry-run do stack real A6. Não remove volume e não chama compose.
set -euo pipefail

for argument in "$@"; do
  case "$argument" in
    *'compose down'*|*'--remove-orphans'*|*'volume rm'*|*'volume prune'*)
      echo "comando destrutivo recusado" >&2
      exit 2
      ;;
  esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="dry-run"
sha="${A6_SHA:-0507831d1b6ec8f35aac1794256e94de2b7a0e20}"
if [[ "${1:-}" == "--execute" ]]; then
  mode="execute"
fi

python3 - "$root" "$mode" "$sha" <<'PY'
import json, sys
sys.path.insert(0, sys.argv[1] + "/scripts")
from a6_production_deploy import DeployError, current_dry_run
report = current_dry_run(sys.argv[3])
report["mode"] = sys.argv[2]
if sys.argv[2] == "execute":
    report["executeAllowed"] = False
    report["blocked"] = sorted(set(report["blocked"] + ["apply_requires_live_recheck"]))
text = json.dumps(report, indent=2, sort_keys=True)
if "TOKEN=" in text or "KEY=" in text:
    raise DeployError("secret_in_output")
sys.stdout.write(text + "\n")
PY
