#!/usr/bin/env python3
"""Backup, rollback de container e prova de namespace. Não usa a VM."""

import json
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from a6_consistent_backup import BackupError, consistent_copy


ROOT = Path(__file__).resolve().parents[1]
UPDATE = ROOT / "scripts" / "a6-container-update.sh"
PROVE = ROOT / "scripts" / "a6-storage-namespace-prove.sh"
ENTRY = ROOT / "docker" / "jvm-storage-entrypoint.sh"


def evidence():
    return {
        "activeNodeOps": 0,
        "activeJvmWrites": 0,
        "liveExternalWriters": [],
        "storageGate": {
            "othersSeeReadOnly": True,
            "capSysAdmin": False,
            "bridgeMountPrivate": True,
            "laterProcessWrite": "erofs",
            "ownerCanRearmWrite": False,
        },
    }


class BackupRollbackTests(unittest.TestCase):
    def test_backup_waits_for_quiescence_and_keeps_the_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "datomic"
            source.mkdir()
            (source / "save.edn").write_text("{:player \"p\"}", encoding="utf-8")
            with self.assertRaises(BackupError):
                consistent_copy(source, Path(tmp) / "copy", {"storageGate": {"cooperativeLockOnly": True}})
            self.assertTrue((source / "save.edn").exists())
            report = consistent_copy(source, Path(tmp) / "copy", evidence())
            self.assertEqual(report["files"], 1)
            self.assertTrue(report["quiescenceProven"])
            self.assertEqual((source / "save.edn").read_text(encoding="utf-8"), "{:player \"p\"}")
            manifest = Path(report["manifest"]).read_text(encoding="utf-8")
            self.assertIn("save.edn", manifest)

    def test_backup_refuses_the_live_volume(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(BackupError):
                consistent_copy(
                    Path("/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data"),
                    Path(tmp) / "copy",
                    evidence(),
                )

    def test_container_update_is_isolated_and_keeps_mount_names(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            binary = root / "bin"
            binary.mkdir()
            log = root / "docker.log"
            fake = binary / "docker"
            fake.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$*\" >> \"$DOCKER_LOG\"\n"
                "if [ \"$1\" = inspect ]; then echo 'odisseu-jvm:old'; exit 0; fi\n"
                "exit 0\n",
                encoding="utf-8",
            )
            fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
            plan = {
                "isolated": True,
                "container": "odisseu-a6-jvm",
                "image": "odisseu-jvm:a6-b02576b0caad",
                "network": "odisseu-a6",
                "preserve": {
                    "datomic": "odisseu-a6-cutover-datomic",
                    "journal": "odisseu-a6-cutover-journal",
                    "maint": "odisseu-a6-cutover-maint",
                    "session": "a6-cutover-session-from-core",
                },
                "evidence": evidence(),
            }
            path = root / "plan.json"
            path.write_text(json.dumps(plan), encoding="utf-8")
            env = os.environ.copy()
            env.update({"A6_ISOLATED": "1", "PATH": f"{binary}:{env.get('PATH', '')}", "DOCKER_LOG": str(log)})
            done = subprocess.run(["bash", str(UPDATE), str(path)], check=False, capture_output=True, text=True, env=env)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertIn("previous=odisseu-jvm:old", done.stdout)
            recorded = log.read_text(encoding="utf-8")
            self.assertNotIn("volume rm", recorded)
            self.assertNotIn("compose down", recorded)
            refused = subprocess.run(["bash", str(UPDATE), str(path)], check=False, capture_output=True, text=True)
            self.assertEqual(refused.returncode, 2)

    def test_prove_script_refuses_a_production_path(self):
        done = subprocess.run(
            ["bash", str(PROVE), "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data"],
            check=False, capture_output=True, text=True,
        )
        self.assertEqual(done.returncode, 2)

    def test_entrypoint_without_the_namespace_flag_forwards_the_command(self):
        done = subprocess.run(
            ["bash", str(ENTRY), "echo", "passthrough"],
            check=False, capture_output=True, text=True,
            env={"PATH": os.environ.get("PATH", "")},
        )
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual(done.stdout.strip(), "passthrough")

    def test_later_process_cannot_write_or_remount_in_a_private_namespace(self):
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        done = subprocess.run(
            ["docker", "run", "--rm", "--privileged",
             "-v", f"{PROVE}:/prove.sh:ro",
             "ubuntu:24.04", "bash", "-lc",
             "apt-get update -qq && apt-get install -y -qq util-linux libcap2-bin >/dev/null && bash /prove.sh"],
            check=False, capture_output=True, text=True,
        )
        self.assertEqual(done.returncode, 0, done.stderr[-2000:])
        self.assertIn("laterProcessWrite=erofs", done.stdout)
        self.assertIn("ownerCanRearmWrite=false", done.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
