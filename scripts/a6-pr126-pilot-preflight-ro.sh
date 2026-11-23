#!/usr/bin/env bash
# Sonda somente leitura do preflight do piloto PR #126.
# Não altera hold, containers, volumes, Datomic, sessão WhatsApp nem workflows.
set -euo pipefail

TARGET_SHA="${A6_TARGET_SHA:-741034c517a5f583287826324d6a069ee67f3b7c}"
PROD_SHA="${A6_PROD_SHA:-c4deb6a878fdc914a02532af54c169cb1c738dda}"
PROJECT="${A6_GCP_PROJECT:-project-e8cd7809-1145-4be9-b8d}"
ZONE="${A6_GCP_ZONE:-us-central1-a}"
INSTANCE="${A6_GCP_INSTANCE:-odisseu-core}"
REPO="${A6_GH_REPO:-caioclavico/zapbot}"

for argument in "$@"; do
  case "$argument" in
    *'compose down'*|*'--remove-orphans'*|*'volume rm'*|*'volume prune'*|*executar-janela*|*autorizar-producao*)
      echo "comando mutável/destrutivo recusado neste preflight RO" >&2
      exit 2
      ;;
  esac
done

echo "=== GitHub hold / workflow (RO) ==="
hold="$(gh variable get ODISSEU_PRODUCTION_DEPLOY_HOLD --repo "$REPO")"
workflow_state="$(gh api "repos/$REPO/actions/workflows/deploy.yml" --jq .state)"
echo "ODISSEU_PRODUCTION_DEPLOY_HOLD=$hold"
echo "deploy.yml.state=$workflow_state"
echo "target_sha=$TARGET_SHA"
echo "expected_prod_sha=$PROD_SHA"

if [[ "$hold" != "true" ]]; then
  echo "ALERT: hold não está true (preflight RO não altera)" >&2
fi
if [[ "$workflow_state" != "disabled_manually" ]]; then
  echo "ALERT: deploy.yml não está disabled_manually" >&2
fi

echo
echo "=== VM probe (RO) ==="
gcloud compute ssh "$INSTANCE" \
  --project="$PROJECT" \
  --zone="$ZONE" \
  --command="$(cat <<'REMOTE'
set -euo pipefail
echo "hostname=$(hostname)"
echo "utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "--- health ---"
curl -fsS -m 5 http://127.0.0.1:3003/health || echo 'node_health_unavailable'
echo
curl -fsS -m 5 http://127.0.0.1:18092/health || echo 'jvm_health_unavailable'
echo
echo "--- containers ---"
sudo -n docker inspect odisseu-a6-node odisseu-a6-jvm \
  --format '{{.Name}} status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{end}} image={{.Config.Image}}' 2>/dev/null || true
echo "--- images matching target/prod ---"
sudo -n docker images --format '{{.Repository}}:{{.Tag}}' | grep -E '741034c|c4deb6a878fd' || true
echo "--- maintenance markers (head) ---"
sudo -n docker exec odisseu-a6-node sh -c 'head -c 200 /app/data/a6-maintenance.json; echo' 2>/dev/null || true
sudo -n docker exec odisseu-a6-jvm sh -c 'head -c 200 /data/datomic-local/.a6-maintenance/bridge-maintenance.edn; echo' 2>/dev/null || true
echo "--- env flags (names only / non-secret) ---"
sudo -n docker exec odisseu-a6-jvm sh -c 'printf "ADVENTURE_AUTO_HATCH="; printenv ADVENTURE_AUTO_HATCH || true; echo; printf "RAID_AUTO_TICK="; printenv ODISSEU_BRIDGE_RAID_AUTO_TICK || true; echo' 2>/dev/null || true
echo "--- checkpoint ---"
python3 - <<'PY'
import json
from pathlib import Path
p = Path.home() / ".odisseu-a6-window" / "a6-window-checkpoint.json"
print("checkpoint_exists", p.exists())
if p.exists():
    d = json.loads(p.read_text())
    for k in ("deployComplete", "windowId", "holdRestored", "lastCompleted", "blocked"):
        if k in d:
            print(k, d.get(k))
PY
echo "RO_PROBE_DONE"
REMOTE
)"

echo
echo "=== Decisão local (esta sonda não autoriza deploy) ==="
echo "PRODUCTION_PILOT_PLANNED"
echo "DEPLOY_NO_GO"
echo "Ver docs/a6-pr126-production-pilot-preflight.md"
