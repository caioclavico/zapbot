#!/usr/bin/env bash
# Executado somente no container AMD64 descartável pelo harness de operações.
set -euo pipefail

test_root=/root/a6-admin-fixture
mkdir -p "$test_root"
tar -C /work/scripts -cf "$test_root/admin.tar" \
  a6-root-backup a6_root_backup.py a6_production_backup.py a6_production_contract.py \
  a6_production_deploy.py a6_mount_policy.py a6_writer_policy.py
tar -C /work/docker -rf "$test_root/admin.tar" chromium-seccomp.json
bundle_sha="$(sha256sum "$test_root/admin.tar" | awk '{print $1}')"
A6_ADMIN_BUNDLE="$test_root/admin.tar" A6_ADMIN_BUNDLE_SHA256="$bundle_sha" \
  A6_NODE_SECCOMP_SHA256="$(sha256sum /work/docker/chromium-seccomp.json | awk '{print $1}')" \
  bash /work/scripts/a6-provision-admin.sh

useradd --create-home --shell /bin/sh caioclavico
su -s /bin/sh caioclavico -c 'sudo -n /usr/local/libexec/odisseu-a6/a6-root-backup --preflight fixture' \
  | grep -qx '{"ready": true, "window": "fixture", "operation": "preflight"}'
su -s /bin/sh caioclavico -c 'PYTHONPATH=/work/scripts python3 -c "from a6_admin_backup_client import preflight; assert preflight(\"/var/backups/odisseu-a6/fixture/data\")[\"ready\"]"'
test "$(stat -c '%U:%G %a' /usr/local/libexec/odisseu-a6/a6-root-backup)" = 'root:root 755'
test "$(stat -c '%U:%G %a' /usr/local/lib/odisseu-a6/a6_root_backup.py)" = 'root:root 644'
test "$(stat -c '%U:%G %a' /var/backups/odisseu-a6)" = 'root:root 700'
test "$(stat -c '%U:%G %a' /etc/odisseu-a6/chromium-seccomp.json)" = 'root:root 644'
install -d -o root -g root -m 0700 /var/backups/odisseu-a6/probe
printf synthetic > /var/backups/odisseu-a6/probe/manifest
chmod 0600 /var/backups/odisseu-a6/probe/manifest
if su -s /bin/sh caioclavico -c 'cat /var/backups/odisseu-a6/probe/manifest' 2>/dev/null; then
  echo 'operador leu backup protegido' >&2; exit 1
fi
if su -s /bin/sh caioclavico -c 'sudo -n /work/scripts/a6-root-backup --preflight fixture' 2>/dev/null; then
  echo 'checkout gravável foi autorizado como root' >&2; exit 1
fi
