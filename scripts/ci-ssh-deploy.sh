#!/usr/bin/env bash
# Runner-only client. The VM accepts a forced command, never an arbitrary shell.
set -euo pipefail
umask 077
SSH_PORT=${SSH_PORT:-22}

[[ "${GITHUB_REF:-}" == refs/heads/master ]] || { echo 'Deploy is restricted to master.' >&2; exit 2; }
[[ "${GITHUB_SHA:-}" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid commit SHA.' >&2; exit 2; }
[[ "${DEPLOY_OPERATION:-}" == deploy || "${DEPLOY_OPERATION:-}" == rollback || "${DEPLOY_OPERATION:-}" == check ]] || exit 2
if [[ "$DEPLOY_OPERATION" == check && "${DEPLOY_SERVICE:-}" != odisseu ]]; then
  echo 'SSH check is available only for Odisseu.' >&2
  exit 2
fi
[[ "${SSH_USER:-}" =~ ^[a-z_][a-z0-9_-]*$ ]] || { echo 'Invalid SSH user.' >&2; exit 2; }
[[ "${SSH_HOST:-}" =~ ^[a-zA-Z0-9][a-zA-Z0-9.:-]*$ ]] || { echo 'Invalid SSH host.' >&2; exit 2; }
[[ "$SSH_PORT" =~ ^[1-9][0-9]{0,4}$ ]] && (( SSH_PORT <= 65535 )) || { echo 'Invalid SSH port.' >&2; exit 2; }
[[ -n "${SSH_KEY:-}" && -n "${SSH_KNOWN_HOSTS:-}" ]] || { echo 'Configure the service SSH key and verified known_hosts in production.' >&2; exit 2; }
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  printf 'performed=false\ndeployed=false\n' >> "$GITHUB_OUTPUT"
fi

# Recheck after the concurrency queue and Environment approval. An old run must
# never silently replace a newer commit from master.
if [[ "${GITHUB_EVENT_NAME:-}" == push ]]; then
  node <<'NODE'
const fs = require('node:fs');
(async () => {
  const url = `${process.env.GITHUB_API_URL || 'https://api.github.com'}/repos/${process.env.GITHUB_REPOSITORY}/git/ref/heads/master`;
  const response = await fetch(url, {
    headers: {Authorization: `Bearer ${process.env.GITHUB_TOKEN}`, Accept: 'application/vnd.github+json'},
    signal: AbortSignal.timeout(20000),
  });
  if (!response.ok) throw Error(`Could not verify master HEAD (HTTP ${response.status}).`);
  const data = await response.json();
  if (!/^[a-f0-9]{40}$/.test(data.object?.sha || '')) throw Error('Invalid master HEAD response.');
  if (data.object.sha !== process.env.GITHUB_SHA) {
    fs.writeFileSync(`${process.env.RUNNER_TEMP}/zapbot-stale-${process.env.GITHUB_SHA}`, 'stale');
    console.log('Automatic deployment skipped: master has a newer commit.');
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
NODE
  stale_marker="$RUNNER_TEMP/zapbot-stale-$GITHUB_SHA"
  if [[ -f "$stale_marker" ]]; then rm -f "$stale_marker"; exit 0; fi
fi

ssh_dir=$(mktemp -d "${RUNNER_TEMP:-/tmp}/zapbot-deploy.XXXXXX")
trap 'rm -rf -- "$ssh_dir"' EXIT
printf '%s\n' "$SSH_KEY" > "$ssh_dir/key"
printf '%s\n' "$SSH_KNOWN_HOSTS" > "$ssh_dir/known_hosts"
unset SSH_KEY SSH_KNOWN_HOSTS
chmod 600 "$ssh_dir/key" "$ssh_dir/known_hosts"
ssh-keygen -y -P '' -f "$ssh_dir/key" >/dev/null
ssh-keygen -l -f "$ssh_dir/known_hosts" >/dev/null
case "$DEPLOY_OPERATION" in
  deploy) remote_command="deploy $GITHUB_SHA" ;;
  rollback) remote_command=rollback ;;
  check) remote_command=check ;;
esac
echo "Requesting $DEPLOY_OPERATION for ${DEPLOY_SERVICE:?} at commit $GITHUB_SHA."
ssh_timeout=45m
if [[ "$DEPLOY_OPERATION" == check ]]; then ssh_timeout=45s; fi
timeout "$ssh_timeout" ssh -T -F /dev/null -i "$ssh_dir/key" -p "$SSH_PORT" \
  -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=yes \
  -o "UserKnownHostsFile=$ssh_dir/known_hosts" -o GlobalKnownHostsFile=/dev/null \
  -o UpdateHostKeys=no -o ClearAllForwardings=yes -o RequestTTY=no \
  -o ConnectTimeout=20 -o ConnectionAttempts=1 \
  -o ServerAliveInterval=15 -o ServerAliveCountMax=8 \
  "$SSH_USER@$SSH_HOST" "$remote_command"
if [[ -n "${GITHUB_OUTPUT:-}" && "$DEPLOY_OPERATION" != check ]]; then
  printf 'performed=true\n' >> "$GITHUB_OUTPUT"
  if [[ "$DEPLOY_OPERATION" == deploy ]]; then printf 'deployed=true\n' >> "$GITHUB_OUTPUT"; fi
fi
