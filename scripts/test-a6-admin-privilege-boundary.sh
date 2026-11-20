#!/usr/bin/env bash
# Executado somente no container AMD64 descartável pelo harness de operações.
set -euo pipefail

# The Python base image installs its interpreter in /usr/local/bin.
if [[ ! -x /usr/bin/python3 ]]; then
  ln -s /usr/local/bin/python3 /usr/bin/python3
fi

test_root=/root/a6-admin-fixture
mkdir -p "$test_root"
installer="$test_root/installer"
install -d -o root -g root -m 0700 "$installer"
install -o root -g root -m 0700 /work/scripts/a6-provision-admin.sh "$installer/a6-provision-admin.sh"
install -o root -g root -m 0600 /work/scripts/a6_validate_admin_bundle.py "$installer/a6_validate_admin_bundle.py"
tar --format=ustar -C /work/scripts -cf "$test_root/admin.tar" \
  a6-root-backup a6-docker-mediator a6_docker_mediator.py a6_root_backup.py a6_production_backup.py a6_production_contract.py \
  a6_production_deploy.py a6_mount_policy.py a6_writer_policy.py
tar --format=ustar -C /work/docker -rf "$test_root/admin.tar" chromium-seccomp.json
bundle_sha="$(sha256sum "$test_root/admin.tar" | awk '{print $1}')"
A6_ADMIN_BUNDLE="$test_root/admin.tar" A6_ADMIN_BUNDLE_SHA256="$bundle_sha" \
  A6_NODE_SECCOMP_SHA256="$(sha256sum /work/docker/chromium-seccomp.json | awk '{print $1}')" \
  A6_PROVISIONER_SHA256="$(sha256sum "$installer/a6-provision-admin.sh" | awk '{print $1}')" \
  A6_ADMIN_VALIDATOR_SHA256="$(sha256sum "$installer/a6_validate_admin_bundle.py" | awk '{print $1}')" \
  bash "$installer/a6-provision-admin.sh"

useradd --create-home --shell /bin/sh odisseu-ops
su -s /bin/sh odisseu-ops -c 'sudo -n /usr/local/libexec/odisseu-a6/a6-root-backup --preflight fixture' \
  | grep -qx '{"ready": true, "window": "fixture", "operation": "preflight"}'
su -s /bin/sh odisseu-ops -c 'PYTHONPATH=/work/scripts python3 -c "from a6_admin_backup_client import preflight; assert preflight(\"/var/backups/odisseu-a6/fixture/data\")[\"ready\"]"'
install -d -o root -g root -m 0700 /var/backups/odisseu-a6/fixture/data /var/backups/odisseu-a6/fixture/restore-isolated
printf synthetic > /var/backups/odisseu-a6/fixture/data/save.edn
cp /var/backups/odisseu-a6/fixture/data/save.edn /var/backups/odisseu-a6/fixture/restore-isolated/save.edn
payload_sha="$(sha256sum /var/backups/odisseu-a6/fixture/data/save.edn | awk '{print $1}')"
printf '%s  save.edn\n' "$payload_sha" > /var/backups/odisseu-a6/fixture/backup-manifest.txt
manifest_sha="$(sha256sum /var/backups/odisseu-a6/fixture/backup-manifest.txt | awk '{print $1}')"
printf '{"files":1,"manifestSha256":"%s","restoreIsolated":true,"sourcePreserved":true,"volume":"odisseu-a6-cutover-datomic","window":"fixture"}\n' "$manifest_sha" \
  > /var/backups/odisseu-a6/fixture/backup-attestation.json
chmod 0600 /var/backups/odisseu-a6/fixture/backup-attestation.json
su -s /bin/sh odisseu-ops -c 'PYTHONPATH=/work/scripts python3 -c "from a6_admin_backup_client import status; assert status(\"/var/backups/odisseu-a6/fixture/data\")[\"files\"] == 1"'
test "$(stat -c '%U:%G %a' /usr/local/libexec/odisseu-a6/a6-root-backup)" = 'root:root 755'
test "$(stat -c '%U:%G %a' /usr/local/lib/odisseu-a6/a6_root_backup.py)" = 'root:root 644'
test "$(stat -c '%U:%G %a' /var/backups/odisseu-a6)" = 'root:root 700'
test "$(stat -c '%U:%G %a' /etc/odisseu-a6/chromium-seccomp.json)" = 'root:root 644'
install -d -o root -g root -m 0700 /var/backups/odisseu-a6/probe
printf synthetic > /var/backups/odisseu-a6/probe/manifest
chmod 0600 /var/backups/odisseu-a6/probe/manifest
if su -s /bin/sh odisseu-ops -c 'cat /var/backups/odisseu-a6/probe/manifest' 2>/dev/null; then
  echo 'operador leu backup protegido' >&2; exit 1
fi
if su -s /bin/sh odisseu-ops -c 'sudo -n /work/scripts/a6-root-backup --preflight fixture' 2>/dev/null; then
  echo 'checkout gravável foi autorizado como root' >&2; exit 1
fi
if su -s /bin/sh odisseu-ops -c 'sudo -n /bin/sh -c id' 2>/dev/null; then
  echo 'operador recebeu shell root' >&2; exit 1
fi
if printf '%s' '{"op":"shell","service":"node"}' | su -s /bin/sh odisseu-ops -c 'sudo -n /usr/local/libexec/odisseu-a6/a6-docker-mediator' 2>/dev/null; then
  echo 'mediador aceitou shell arbitrário' >&2; exit 1
fi
