#!/usr/bin/env bash
# Installed manually in a root-owned directory; never fetched from a release.
set -euo pipefail
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec /usr/bin/python3 -u "$script_dir/deploy-service.py" "$@"
