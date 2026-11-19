#!/usr/bin/env bash
# Instala só o helper administrativo. Não fala com GitHub, Docker run ou maintenance APIs.
set -euo pipefail

bundle="${A6_ADMIN_BUNDLE:-}"
expected="${A6_ADMIN_BUNDLE_SHA256:-}"
seccomp_sha="${A6_NODE_SECCOMP_SHA256:-}"
target=/usr/local/lib/odisseu-a6
entry=/usr/local/libexec/odisseu-a6/a6-root-backup
sudoers=/etc/sudoers.d/odisseu-a6-backup
profile=/etc/odisseu-a6/chromium-seccomp.json

if [[ "$(id -u)" != 0 || -z "$bundle" || -z "$expected" || -z "$seccomp_sha" ]]; then
  echo 'root, bundle, SHA-256 do bundle e SHA-256 do seccomp são obrigatórios' >&2; exit 2
fi
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]] || [[ ! -f "$bundle" ]] || [[ -L "$bundle" ]]; then
  echo 'bundle ou SHA-256 inválido' >&2; exit 2
fi
actual="$(sha256sum "$bundle" | awk '{print $1}')"
if [[ "$actual" != "$expected" ]]; then echo 'bundle SHA-256 divergente' >&2; exit 2; fi
stage="$(mktemp -d /root/odisseu-a6-admin.XXXXXX)"
cleanup() { rm -rf "$stage"; }
trap cleanup EXIT
tar --extract --file "$bundle" --directory "$stage" --no-same-owner --no-same-permissions
for item in a6-root-backup a6_root_backup.py a6_production_backup.py a6_production_contract.py a6_production_deploy.py a6_mount_policy.py a6_writer_policy.py chromium-seccomp.json; do
  [[ -f "$stage/$item" && ! -L "$stage/$item" ]] || { echo "artefato ausente: $item" >&2; exit 2; }
done
[[ "$seccomp_sha" =~ ^[0-9a-f]{64}$ ]] || { echo 'SHA-256 do seccomp inválido' >&2; exit 2; }
[[ "$(sha256sum "$stage/chromium-seccomp.json" | awk '{print $1}')" == "$seccomp_sha" ]] || { echo 'seccomp SHA-256 divergente' >&2; exit 2; }
missing=()
for command in python3 sudo gh; do command -v "$command" >/dev/null 2>&1 || missing+=("$command"); done
if (( ${#missing[@]} )); then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "${missing[@]}" ca-certificates
fi
install -d -o root -g root -m 0755 "$target" "$(dirname "$entry")"
install -d -o root -g root -m 0755 "$(dirname "$profile")"
install -o root -g root -m 0644 "$stage"/*.py "$target/"
install -o root -g root -m 0755 "$stage/a6-root-backup" "$entry"
install -o root -g root -m 0644 "$stage/chromium-seccomp.json" "$profile"
install -d -o root -g root -m 0700 /var/backups/odisseu-a6
install -o root -g root -m 0440 /dev/stdin "$sudoers" <<'EOF'
Cmnd_Alias ODISSEU_A6_BACKUP = /usr/local/libexec/odisseu-a6/a6-root-backup *
caioclavico ALL=(root) NOPASSWD: ODISSEU_A6_BACKUP
EOF
visudo -cf "$sudoers"
python3 -m py_compile "$target"/*.py
echo '{"provisioned":true,"maintenanceStarted":false,"holdChanged":false,"imagesTransferred":false}'
