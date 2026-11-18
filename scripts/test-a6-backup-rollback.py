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

from a6_consistent_backup import BackupError, consistent_copy, restore_copy


ROOT = Path(__file__).resolve().parents[1]
UPDATE = ROOT / "scripts" / "a6-container-update.sh"
PROVE = ROOT / "scripts" / "a6-storage-namespace-prove.sh"
ENTRY = ROOT / "docker" / "jvm-storage-entrypoint.sh"


def evidence():
    return {
        "nodePhase": "draining",
        "jvmPhase": "quiescent",
        "activeNodeOps": 0,
        "activeJvmWrites": 0,
        "liveExternalWriters": [],
        "authorizedWriter": "odisseu-a6-jvm",
        "writableMounts": ["odisseu-a6-jvm"],
        "capSysAdmin": False,
        "maintenanceToolsRunning": [],
        "laterWriterResult": "no_mount",
        "mountPolicyHeld": True,
        "mountsObserved": True,
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
            restored = restore_copy(report["manifest"], Path(tmp) / "copy", Path(tmp) / "restored")
            self.assertEqual((Path(restored["restored"]) / "save.edn").read_text(encoding="utf-8"), "{:player \"p\"}")
            self.assertTrue((source / "save.edn").exists())

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
                "observedContainers": [{
                    "Name": "odisseu-a6-jvm",
                    "Mounts": [{"Name": "odisseu-a6-cutover-datomic", "Destination": "/data/datomic-local", "RW": True}],
                    "HostConfig": {"Privileged": False, "CapAdd": []},
                }],
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

    def test_remount_proof_is_withdrawn(self):
        done = subprocess.run(["bash", str(PROVE)], check=False, capture_output=True, text=True)
        self.assertEqual(done.returncode, 2)
        self.assertIn("retirada", done.stderr)

    def test_exclusive_maintenance_refuses_the_live_volume(self):
        env = os.environ.copy()
        env["A6_ISOLATED"] = "1"
        done = subprocess.run(
            ["bash", str(ROOT / "scripts" / "a6-exclusive-maintenance.sh"), "odisseu-a6-cutover-datomic"],
            check=False, capture_output=True, text=True, env=env,
        )
        self.assertEqual(done.returncode, 2)
        self.assertIn("volume live", done.stderr)

    def test_second_writable_mount_is_refused_before_docker(self):
        with tempfile.TemporaryDirectory() as tmp:
            plan = {
                "isolated": True,
                "container": "odisseu-a6-jvm",
                "image": "odisseu-jvm:new",
                "network": "lab",
                "preserve": {
                    "datomic": "a6-lab-datomic",
                    "journal": "a6-lab-journal",
                    "maint": "a6-lab-maint",
                    "session": "a6-lab-session",
                },
                "evidence": evidence(),
                "observedContainers": [
                    {"Name": "odisseu-a6-jvm", "Mounts": [{"Name": "a6-lab-datomic", "RW": True}],
                     "HostConfig": {"Privileged": False, "CapAdd": []}},
                    {"Name": "odisseu-a6-extra", "Mounts": [{"Name": "a6-lab-datomic", "RW": True}],
                     "HostConfig": {"Privileged": False}},
                ],
            }
            path = Path(tmp) / "plan.json"
            path.write_text(json.dumps(plan), encoding="utf-8")
            env = os.environ.copy()
            env["A6_ISOLATED"] = "1"
            done = subprocess.run(["bash", str(UPDATE), str(path)], check=False, capture_output=True, text=True, env=env)
            self.assertEqual(done.returncode, 2)
            self.assertIn("writable_mount_not_exclusive", done.stderr)

    def test_entrypoint_requires_the_mount_before_java_and_refuses_sys_admin(self):
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        volume = "a6lab-entrypoint"
        subprocess.run(["docker", "volume", "rm", volume], check=False, capture_output=True, text=True)
        subprocess.run(["docker", "volume", "create", volume], check=True, capture_output=True, text=True)
        try:
            missing = subprocess.run(
                ["docker", "run", "--rm", "--cap-drop", "SYS_ADMIN",
                 "-v", f"{ENTRY}:/usr/local/bin/jvm-storage-entrypoint.sh:ro",
                 "ubuntu:24.04", "/usr/local/bin/jvm-storage-entrypoint.sh", "echo", "started"],
                check=False, capture_output=True, text=True,
            )
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn("não está montado", missing.stderr)
            started = subprocess.run(
                ["docker", "run", "--rm", "--cap-drop", "SYS_ADMIN",
                 "-v", f"{ENTRY}:/usr/local/bin/jvm-storage-entrypoint.sh:ro",
                 "-v", f"{volume}:/data/datomic-local",
                 "ubuntu:24.04", "/usr/local/bin/jvm-storage-entrypoint.sh", "echo", "started"],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(started.returncode, 0, started.stderr)
            self.assertEqual(started.stdout.strip(), "started")
            flagged = subprocess.run(
                ["docker", "run", "--rm", "--cap-drop", "SYS_ADMIN",
                 "-e", "ODISSEU_STORAGE_NAMESPACE=1",
                 "-v", f"{ENTRY}:/usr/local/bin/jvm-storage-entrypoint.sh:ro",
                 "-v", f"{volume}:/data/datomic-local",
                 "ubuntu:24.04", "/usr/local/bin/jvm-storage-entrypoint.sh", "echo", "started"],
                check=False, capture_output=True, text=True,
            )
            self.assertNotEqual(flagged.returncode, 0)
            self.assertIn("não isola", flagged.stderr)
            privileged = subprocess.run(
                ["docker", "run", "--rm", "--privileged",
                 "-v", f"{ENTRY}:/usr/local/bin/jvm-storage-entrypoint.sh:ro",
                 "-v", f"{volume}:/data/datomic-local",
                 "ubuntu:24.04", "/usr/local/bin/jvm-storage-entrypoint.sh", "echo", "started"],
                check=False, capture_output=True, text=True,
            )
            self.assertNotEqual(privileged.returncode, 0)
            self.assertIn("CAP_SYS_ADMIN", privileged.stderr)
        finally:
            subprocess.run(["docker", "volume", "rm", volume], check=False, capture_output=True, text=True)

    def test_later_process_without_the_volume_cannot_write(self):
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        name = "a6labproof"
        jvm = "a6lab-jvm"
        extra = "a6lab-extra"
        subprocess.run(["docker", "rm", "-f", jvm, extra], check=False, capture_output=True, text=True)
        subprocess.run(["docker", "volume", "rm", name], check=False, capture_output=True, text=True)
        try:
            created = subprocess.run(["docker", "volume", "create", name], check=False, capture_output=True, text=True)
            self.assertEqual(created.returncode, 0, created.stderr)
            started = subprocess.run(
                ["docker", "run", "-d", "--name", jvm, "--cap-drop", "SYS_ADMIN",
                 "-v", f"{name}:/data/datomic-local", "alpine", "sleep", "30"],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(started.returncode, 0, started.stderr)
            outsider = subprocess.run(
                ["docker", "run", "--rm", "--cap-drop", "SYS_ADMIN", "alpine",
                 "sh", "-c", "echo later >> /data/datomic-local/save"],
                check=False, capture_output=True, text=True,
            )
            self.assertNotEqual(outsider.returncode, 0)
            self.assertNotIn(name, outsider.stdout)
            second = subprocess.run(
                ["docker", "run", "-d", "--name", extra, "--cap-drop", "SYS_ADMIN",
                 "-v", f"{name}:/data/datomic-local", "alpine", "sleep", "20"],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(second.returncode, 0, second.stderr)
            inspect = subprocess.run(
                ["docker", "inspect", jvm, extra], check=False, capture_output=True, text=True,
            )
            observed = json.loads(inspect.stdout)
            from a6_mount_policy import assess
            result = assess(observed, name, jvm)
            self.assertFalse(result["exclusive"])
            self.assertEqual(result["reason"], "writable_mount_not_exclusive")
            subprocess.run(["docker", "rm", "-f", extra], check=False, capture_output=True, text=True)
            inspect_one = subprocess.run(["docker", "inspect", jvm], check=False, capture_output=True, text=True)
            alone = assess(json.loads(inspect_one.stdout), name, jvm)
            self.assertTrue(alone["exclusive"], alone)
        finally:
            subprocess.run(["docker", "rm", "-f", jvm, extra], check=False, capture_output=True, text=True)
            subprocess.run(["docker", "volume", "rm", name], check=False, capture_output=True, text=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
