"""Regressions for secure installation of the root-owned POST_V3_UPGRADE plan."""
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import a6_post_v3_plan as provisioner
from a6_production_deploy import DeployError


WINDOW = "oauth-window-137"
PLAN = {
    "protocol": "POST_V3_UPGRADE",
    "windowId": WINDOW,
    "imageSha": "a" * 40,
    "jvmDigest": "sha256:" + "1" * 64,
    "nodeDigest": "sha256:" + "2" * 64,
    "rollbackSha": "b" * 40,
    "rollbackJvmDigest": "sha256:" + "3" * 64,
    "rollbackNodeDigest": "sha256:" + "4" * 64,
    "auditCodeSha256": "5" * 64,
    "system": "datomic-system",
    "database": "pokemon",
}


def docker_inspect(stdout=None, returncode=0):
    if stdout is None:
        stdout = "\n".join(
            f"{PLAN[key]}\ttrue\thealthy"
            for key in ("rollbackJvmDigest", "rollbackNodeDigest")
        )
    return subprocess.CompletedProcess([], returncode, stdout, "")


class PostV3PlanProvisionerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.source = self.root / "incoming"
        self.target = self.root / "installed"
        self.source.mkdir(mode=0o700)
        self.target.mkdir(mode=0o700)
        self.source_file = self.source / (WINDOW + ".json")
        self.write_source(PLAN)

    def tearDown(self):
        self.temporary.cleanup()

    def write_source(self, value):
        self.source_file.write_text(json.dumps(value), encoding="utf-8")
        self.source_file.chmod(0o600)

    def install(self, runner=None):
        with (
            mock.patch.object(provisioner.os, "geteuid", return_value=0),
            mock.patch.object(provisioner, "_private"),
            mock.patch.object(provisioner, "_verify_audit_code"),
            mock.patch.object(provisioner.post, "verify_images"),
            mock.patch.object(provisioner, "_capacity", return_value={"sufficient": True}),
            mock.patch.object(provisioner.post, "plan", return_value=PLAN),
        ):
            return provisioner.install(
                WINDOW, self.source, self.target, runner or (lambda *a, **k: docker_inspect())
            )

    def test_installs_canonical_plan_durably_and_reuses_identical_plan(self):
        result = self.install()
        installed = self.target / (WINDOW + ".json")
        self.assertTrue(result["installed"])
        self.assertFalse(result["reused"])
        self.assertEqual(installed.stat().st_mode & 0o777, 0o600)
        self.assertEqual(json.loads(installed.read_text()), PLAN)
        self.assertEqual(installed.read_bytes(), provisioner.post.canonical(PLAN) + b"\n")

        reused = self.install()
        self.assertTrue(reused["reused"])
        self.assertEqual(reused["planSha256"], result["planSha256"])

    def test_rejects_plan_conflict_and_unexpected_credential_fields(self):
        installed = self.target / (WINDOW + ".json")
        installed.write_text(json.dumps({**PLAN, "imageSha": "c" * 40}))
        installed.chmod(0o600)
        with self.assertRaisesRegex(DeployError, "post_v3_plan_conflict"):
            self.install()

        installed.unlink()
        self.write_source({**PLAN, "installationToken": "must-not-be-persisted"})
        with self.assertRaisesRegex(DeployError, "post_v3_plan_invalid"):
            self.install()
        self.assertFalse(installed.exists())

    def test_blocks_when_running_rollback_images_do_not_match_plan(self):
        mismatch = docker_inspect(
            f"{PLAN['rollbackJvmDigest']}\ttrue\thealthy\n"
            f"{PLAN['nodeDigest']}\ttrue\thealthy"
        )
        with self.assertRaisesRegex(DeployError, "post_v3_source_image_mismatch"):
            self.install(runner=lambda *a, **k: mismatch)
        self.assertFalse((self.target / (WINDOW + ".json")).exists())

    def test_blocks_failed_container_inspection(self):
        failed = docker_inspect(returncode=1)
        with self.assertRaisesRegex(DeployError, "post_v3_source_image_mismatch"):
            self.install(runner=lambda *a, **k: failed)
        self.assertFalse((self.target / (WINDOW + ".json")).exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
