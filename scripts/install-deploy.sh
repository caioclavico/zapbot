#!/usr/bin/env bash
# Manual bootstrap only: never invoked by GitHub Actions or deploy-vm.sh.
set -euo pipefail
[[ $# == 4 && $4 == --confirm-reviewed-persistence-v1 && $EUID == 0 ]] || {
  echo 'Usage (root): install-deploy.sh odisseu|pokemon GHCR_OWNER DEPLOY_KEY.pub --confirm-reviewed-persistence-v1' >&2
  exit 2
}
service=$1
registry_owner=$2
public_key=$3
[[ "$registry_owner" =~ ^[a-z0-9][a-z0-9-]*$ && "$public_key" == *.pub && -f "$public_key" ]] || exit 2
case "$service" in
  odisseu) deploy_user=zapbot-deploy ;;
  pokemon) deploy_user=pokemon-deploy ;;
  *) exit 2 ;;
esac
# A private key is never a valid input to this installer.
[[ $(wc -l < "$public_key") -eq 1 ]] || exit 2
grep -Eq '^ssh-ed25519 [A-Za-z0-9+/=]+( .*)?$' "$public_key" || exit 2
ssh-keygen -lf "$public_key" >/dev/null
source_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
command -v python3 >/dev/null
command -v visudo >/dev/null
install -d -o root -g root -m 755 /usr/local/lib/zapbot-deploy
for file in deploy-vm.sh deploy-service.py deploy-ssh-command.py; do
  install -o root -g root -m 755 "$source_dir/$file" "/usr/local/lib/zapbot-deploy/$file"
done
install -o root -g root -m 755 "$source_dir/deploy-entry.py" "/usr/local/sbin/zapbot-deploy-$service"
install -d -o root -g root -m 700 "/etc/zapbot-deploy/$service" "/var/lib/zapbot-deploy/$service"
install -d -o root -g root -m 700 "/etc/zapbot-deploy/$service/docker"
umask 077
printf '{"ghcr_owner":"%s"}\n' "$registry_owner" > "/etc/zapbot-deploy/$service/config.json"
printf '1\n' > "/etc/zapbot-deploy/$service/persistence-version"
login_home="/var/lib/zapbot-deploy-login/$service"
if ! id "$deploy_user" >/dev/null 2>&1; then
  install -d -o root -g root -m 755 /var/lib/zapbot-deploy-login
  useradd --system --create-home --home-dir "$login_home" --shell /bin/sh "$deploy_user"
fi
account=$(getent passwd "$deploy_user")
deploy_uid=$(printf '%s\n' "$account" | cut -d: -f3)
account_home=$(printf '%s\n' "$account" | cut -d: -f6)
case "$account_home" in
  "$login_home"|"/home/$deploy_user") login_home=$account_home ;;
  *) echo 'Existing deployment account has an unexpected home; inspect manually.' >&2
     exit 1 ;;
esac
[[ -d "$login_home" && ! -L "$login_home" ]] || {
  echo 'Deployment account home must exist and must not be a symlink.' >&2
  exit 1
}
home_owner=$(stat -c '%u' "$login_home")
home_mode=$(stat -c '%a' "$login_home")
if [[ "$home_owner" != 0 && "$home_owner" != "$deploy_uid" ]] || (( (8#$home_mode & 0022) != 0 )); then
  echo 'Deployment account home has unsafe ownership or permissions; inspect manually.' >&2
  exit 1
fi
if id -nG "$deploy_user" | tr ' ' '\n' | grep -Fxq docker; then
  echo 'Deployment account must not belong to the docker group.' >&2
  exit 1
fi
# No password login; a non-locked, unusable hash permits public-key-only SSH.
usermod --password '*' "$deploy_user"
[[ $(id -u "$deploy_user") == "$deploy_uid" && $(getent passwd "$deploy_user" | cut -d: -f6) == "$login_home" ]] || {
  echo 'Deployment account UID or home changed unexpectedly.' >&2
  exit 1
}
[[ ! -L "$login_home/.ssh" ]] || {
  echo 'Deployment account .ssh directory must not be a symlink.' >&2
  exit 1
}
install -d -o root -g root -m 755 "$login_home/.ssh"
printf 'restrict,command="/usr/bin/python3 /usr/local/lib/zapbot-deploy/deploy-ssh-command.py %s" %s\n' \
  "$service" "$(cat "$public_key")" > "$login_home/.ssh/authorized_keys"
chown root:root "$login_home/.ssh/authorized_keys"
# sshd opens this file as the login user. It contains only a public key; root
# ownership and read-only permissions keep the forced-command rule immutable.
chmod 644 "$login_home/.ssh/authorized_keys"
sudo_file=$(mktemp)
trap 'rm -f "$sudo_file"' EXIT
printf '%s ALL=(root) NOPASSWD: /usr/local/sbin/zapbot-deploy-%s\n' "$deploy_user" "$service" > "$sudo_file"
visudo -cf "$sudo_file" >/dev/null
install -o root -g root -m 440 "$sudo_file" "/etc/sudoers.d/zapbot-deploy-$service"
echo "Installed restricted $service deployment account: $deploy_user. No container was restarted."
