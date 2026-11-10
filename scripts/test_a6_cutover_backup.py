import importlib.util
import json
import os
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "a6_cutover_backup", Path(__file__).with_name("a6_cutover_backup.py"))
backup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(backup)


class ColdBackupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.parent = self.root / "protected"
        self.parent.mkdir(mode=0o700)
        os.chmod(self.parent, 0o700)
        self.roots = {}
        for name in backup.VOLUMES:
            directory = self.root / ("source-" + name.rsplit("-", 1)[-1])
            directory.mkdir()
            (directory / "data.edn").write_text("{:fixture true}\n")
            (directory / "empty").mkdir()
            self.roots[name] = directory
        self.destination = self.parent / "backup-001"

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, volume, output):
        with tarfile.open(output, "w:") as tar:
            tar.add(self.roots[volume], arcname=".", recursive=True)

    def test_backup_manifest_hashes_and_metadata(self):
        result = backup.publish_backup(self.roots, self.destination,
                                       {"host": "fixture", "architecture": "linux/amd64",
                                        "images": {"node": "sha256:node", "jvm": "sha256:jvm"}},
                                       self.archive, reserve_bytes=0)
        self.assertTrue(result["ok"])
        manifest = json.loads((self.destination / "manifest.json").read_text())
        self.assertEqual(set(backup.ROLES), set(manifest["volumes"]))
        self.assertEqual("sha256:node", manifest["images"]["node"])
        for item in manifest["volumes"].values():
            self.assertEqual(64, len(item["archive_sha256"]))
            self.assertEqual(1, item["inventory"]["file_count"])
        self.assertEqual(0o700, stat.S_IMODE(self.destination.stat().st_mode))

    def test_existing_destination_is_never_replaced(self):
        self.destination.mkdir()
        sentinel = self.destination / "keep"
        sentinel.write_text("untouched")
        with self.assertRaisesRegex(backup.BackupError, "destination_exists"):
            backup.publish_backup(self.roots, self.destination, {}, self.archive, reserve_bytes=0)
        self.assertEqual("untouched", sentinel.read_text())

    def test_interrupted_copy_is_not_published_and_stage_is_retained(self):
        def broken(_volume, output):
            output.write_bytes(b"partial archive")
            raise OSError("injected-copy-interruption")
        with self.assertRaisesRegex(OSError, "injected-copy"):
            backup.publish_backup(self.roots, self.destination, {}, broken, reserve_bytes=0)
        self.assertFalse(self.destination.exists())
        stages = list(self.parent.glob(".backup-001.staging.*"))
        self.assertEqual(1, len(stages))
        self.assertTrue((stages[0] / "INCOMPLETE.json").exists())
        self.assertTrue((stages[0] / "FAILURE.json").exists())

    def test_source_change_during_copy_blocks_publish(self):
        changed = False
        def racing(volume, output):
            nonlocal changed
            self.archive(volume, output)
            if not changed:
                changed = True
                (self.roots[volume] / "data.edn").write_text("changed\n")
        with self.assertRaisesRegex(backup.BackupError, "volume_changed"):
            backup.publish_backup(self.roots, self.destination, {}, racing, reserve_bytes=0)
        self.assertFalse(self.destination.exists())

    def test_checksum_corruption_is_detected(self):
        backup.publish_backup(self.roots, self.destination, {}, self.archive, reserve_bytes=0)
        archive = next(self.destination.glob("*.tar"))
        archive.write_bytes(archive.read_bytes() + b"corrupt")
        with self.assertRaisesRegex(backup.BackupError, "checksum_mismatch"):
            backup.verify_backup(self.destination)

    def test_symlink_metadata_is_archived_without_following(self):
        root = self.roots[backup.VOLUMES[0]]
        (root / "subdir").mkdir()
        (root / "subdir" / "link").symlink_to("../data.edn")
        inventory = backup.tree_inventory(root)
        self.assertEqual(1, inventory["file_count"])
        destination = self.parent / "backup-link"
        backup.publish_backup(self.roots, destination, {}, self.archive, reserve_bytes=0)
        with tarfile.open(destination / f"{backup.VOLUMES[0]}.tar", "r:") as tar:
            link = next(member for member in tar if member.name.endswith("/subdir/link"))
        self.assertTrue(link.issym())

    def test_unsafe_archive_member_is_rejected(self):
        bad = self.parent / "bad.tar"
        with tarfile.open(bad, "w:") as tar:
            info = tarfile.TarInfo("../../escape")
            info.size = 1
            import io
            tar.addfile(info, io.BytesIO(b"x"))
        with self.assertRaisesRegex(backup.BackupError, "archive_path_escape"):
            backup._verify_tar(bad)

    def test_parent_must_be_private(self):
        os.chmod(self.parent, 0o755)
        with self.assertRaisesRegex(backup.BackupError, "mode_0700"):
            backup.publish_backup(self.roots, self.destination, {}, self.archive, reserve_bytes=0)

    def test_operational_context_requires_all_gates_and_no_secret_fields(self):
        context = {
            "exclusive_operator_confirmed": True,
            "github": {"hold": True, "ci_workflow": "disabled_manually", "active_runs": 0, "queued_runs": 0},
            "preflight": {"node_health": "healthy", "whatsapp": "READY", "jvm_health": "healthy",
                          "node_maintenance_phase": "open", "node_active_operations": 0,
                          "jvm_maintenance_phase": "open", "jvm_active_requests": 0},
            "drain": {"node_phase": "quiescent", "node_active_operations": 0, "node_durable": True,
                      "jvm_phase": "quiescent", "jvm_active_requests": 0},
            "flags": {"raidAutoTickEnabled": True},
        }
        self.assertEqual(context, backup.validate_context(context))
        bad = {**context, "github": {**context["github"], "hold": False}}
        with self.assertRaisesRegex(backup.BackupError, "gates_not_proven"):
            backup.validate_context(bad)
        secret = {**context, "token": "not-for-output"}
        with self.assertRaisesRegex(backup.BackupError, "must_not_contain_secrets"):
            backup.validate_context(secret)


if __name__ == "__main__":
    unittest.main()
