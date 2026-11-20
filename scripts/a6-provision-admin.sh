#!/usr/bin/env bash
# Instala só o helper administrativo. Não fala com GitHub, Docker run ou maintenance APIs.
set -euo pipefail
export PATH=/usr/sbin:/usr/bin:/sbin:/bin
umask 077

bundle="${A6_ADMIN_BUNDLE:-}"
expected="${A6_ADMIN_BUNDLE_SHA256:-}"
seccomp_sha="${A6_NODE_SECCOMP_SHA256:-}"
provisioner_sha="${A6_PROVISIONER_SHA256:-}"
validator_sha="${A6_ADMIN_VALIDATOR_SHA256:-}"
target=/usr/local/lib/odisseu-a6
entry=/usr/local/libexec/odisseu-a6/a6-root-backup
sudoers=/etc/sudoers.d/odisseu-a6-backup
profile=/etc/odisseu-a6/chromium-seccomp.json

if [[ "$(id -u)" != 0 || -z "$bundle" || -z "$expected" || -z "$seccomp_sha" || -z "$provisioner_sha" || -z "$validator_sha" ]]; then
  echo 'root e SHA-256 do bundle, seccomp, provisionador e validador são obrigatórios' >&2; exit 2
fi
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ || ! "$provisioner_sha" =~ ^[0-9a-f]{64}$ || ! "$validator_sha" =~ ^[0-9a-f]{64}$ ]] || [[ ! -f "$bundle" ]] || [[ -L "$bundle" ]]; then
  echo 'bundle ou SHA-256 inválido' >&2; exit 2
fi
[[ "$seccomp_sha" =~ ^[0-9a-f]{64}$ ]] || { echo 'SHA-256 do seccomp inválido' >&2; exit 2; }
script_path="${BASH_SOURCE[0]}"
script_dir="$(dirname "$script_path")"
validator="$script_dir/a6_validate_admin_bundle.py"
# Require a canonical path beneath /root and protect every ancestor.
[[ "$script_path" == /root/* && "$script_path" == "$(readlink -f -- "$script_path")" ]] || {
  echo 'execute a cópia revisada por caminho absoluto sob /root' >&2; exit 2
}
directory="$script_dir"
while [[ "$directory" != / ]]; do
  [[ "$(stat -c '%u' -- "$directory")" == 0 ]] && (( (8#$(stat -c '%a' -- "$directory") & 8#022) == 0 )) || {
    echo 'diretório do instalador gravável pelo operador' >&2; exit 2
  }
  directory="$(dirname "$directory")"
done
for trusted in "$script_path" "$validator"; do
  [[ -f "$trusted" && ! -L "$trusted" ]] || { echo 'instalador administrativo inválido' >&2; exit 2; }
  [[ "$(stat -c '%u' "$trusted")" == 0 ]] || { echo 'instalador deve ser root-owned' >&2; exit 2; }
  (( (8#$(stat -c '%a' "$trusted") & 8#022) == 0 )) || { echo 'instalador gravável pelo operador' >&2; exit 2; }
done
[[ "$(sha256sum "$script_path" | awk '{print $1}')" == "$provisioner_sha" ]] || { echo 'provisionador SHA-256 divergente' >&2; exit 2; }
[[ "$(sha256sum "$validator" | awk '{print $1}')" == "$validator_sha" ]] || { echo 'validador SHA-256 divergente' >&2; exit 2; }
stage_json="$(/usr/bin/python3 -I "$validator" --bundle "$bundle" --sha256 "$expected")"
stage="$(/usr/bin/python3 -I -c 'import json, sys; print(json.load(sys.stdin)["stage"])' <<<"$stage_json")"
cleanup() { rm -rf "$stage"; }
trap cleanup EXIT
for item in a6-root-backup a6_root_backup.py a6_production_backup.py a6_production_contract.py a6_production_deploy.py a6_mount_policy.py a6_writer_policy.py chromium-seccomp.json; do
  [[ -f "$stage/$item" && ! -L "$stage/$item" && "$(stat -c '%u:%a:%h' -- "$stage/$item")" == 0:600:1 ]] || { echo "artefato extraído inválido: $item" >&2; exit 2; }
done
[[ "$(sha256sum "$stage/chromium-seccomp.json" | awk '{print $1}')" == "$seccomp_sha" ]] || { echo 'seccomp SHA-256 divergente' >&2; exit 2; }
missing=()
for command in sudo gh; do command -v "$command" >/dev/null 2>&1 || missing+=("$command"); done
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
/usr/bin/python3 -I -m py_compile "$target"/*.py
echo '{"provisioned":true,"maintenanceStarted":false,"holdChanged":false,"imagesTransferred":false}'
