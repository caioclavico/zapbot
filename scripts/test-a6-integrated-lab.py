from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import a6_integrated_lab as lab
import a6_maintenance_ops as ops


class IsolationTests(unittest.TestCase):
    def test_mounts_are_confined_to_disposable_root_and_allowed_destinations(self):
        with tempfile.TemporaryDirectory(prefix="a6-integrated-", dir="/tmp") as raw:
            root = Path(raw).resolve()
            with patch.object(lab, "STAGING", root.parent):
                self.assertIn(str(root / "datomic"), lab.lab_mount(
                    root, root / "datomic", "/data/datomic-local"))
                for source, target in (
                    (Path("/var/run/docker.sock"), "/data/datomic-local"),
                    (Path("/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data"),
                     "/data/datomic-local"),
                    (root / "node", "/app/.wwebjs_auth_a6_cutover"),
                    (root / "docker.sock", "/app/data"),
                    (root / "wwebjs-session", "/app/data"),
                ):
                    with self.subTest(source=source, target=target), self.assertRaises(ValueError):
                        lab.lab_mount(root, source, target)
                (root / "link").symlink_to("/var/run/docker.sock")
                with self.assertRaises(ValueError):
                    lab.lab_mount(root, root / "link", "/app/data")

    def test_adapter_refuses_production_network_and_unowned_containers(self):
        with tempfile.TemporaryDirectory(prefix="a6-integrated-", dir="/tmp") as raw:
            runner = lab.Lab(Path(raw).resolve())
            with patch.object(lab, "docker") as docker:
                for argv in (
                    ["docker", "network", "connect", "odisseu-a6-cutover_default", ops.NODE],
                    ["docker", "exec", ops.JVM, "true"],
                    ["docker", "exec", "odisseu-a6-node-previous", "true"],
                ):
                    with self.assertRaises(ValueError):
                        runner.exec(argv)
                with self.assertRaisesRegex(ValueError, "unowned_lab_network"):
                    runner.replace(ops.NODE, "sha256:" + "a" * 64, False)
                docker.assert_not_called()

    def test_lab_only_accepts_local_copy(self):
        for source in ("/var/backups/odisseu-a6/test/restore-isolated", "/var/run/docker.sock"):
            with self.assertRaises(ValueError):
                lab.restore_source(source)


class ClosedOnFailureTests(unittest.TestCase):
    def test_failed_candidate_gates_never_resume(self):
        for reason in ("v3_node_not_quiescent", "v3_xp_reconcile_failed",
                       "v3_charmander_receipt_invalid", "v3_post_audit_failed"):
            with (
                self.subTest(reason=reason),
                patch.object(lab.release, "prepare", return_value={"status": "BLOCKED"}),
                patch.object(lab.release, "validate_pre_audit"),
                patch.object(lab.release, "reconcile", side_effect=lab.DeployError("wrong_fingerprint")),
                patch.object(lab.release, "audit", return_value={"status": "BLOCKED"}),
                patch.object(lab.release, "commit_once", side_effect=lab.DeployError(reason)),
                patch.object(lab.ops, "resume_jvm") as jvm,
                patch.object(lab.ops, "resume_node") as node,
            ):
                runner = object.__new__(lab.Lab)
                with self.assertRaisesRegex(lab.DeployError, reason):
                    runner.rehearse_release()
                jvm.assert_not_called()
                node.assert_not_called()


if __name__ == "__main__":
    unittest.main()
