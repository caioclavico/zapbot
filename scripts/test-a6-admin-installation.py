"""Instalação root em prefixo descartável; não roda em host sem opt-in CI."""
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest

from a6_build_admin_bundle import build
from a6_install_admin import install, InstallationError
from a6_validate_admin_bundle import validate


@unittest.skipUnless(os.geteuid() == 0 and Path("/work/scripts").is_dir(),
                     "container administrativo descartável")
class InstallationTests(unittest.TestCase):
    def test_published_generation_is_traversable_with_restrictive_umask(self):
        temp_root = str(Path.home().resolve())
        with tempfile.TemporaryDirectory(dir=temp_root) as temporary:
            root = Path(temporary)
            stage = root / "stage"
            stage.mkdir(mode=0o700)
            kit = build(root, "a" * 40)
            for name, data in validate((root / "admin.tar").read_bytes(), kit["bundleSha256"]).items():
                (stage / name).write_bytes(data)
                (stage / name).chmod(0o600)
            previous_umask = os.umask(0o077)
            try:
                result = install(stage, kit["bundleSha256"], root)
            finally:
                os.umask(previous_umask)

            target = root / "usr/local/lib/odisseu-a6"
            releases = target / "releases"
            generation = Path(result["generation"])
            self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o755)
            self.assertEqual(stat.S_IMODE(releases.stat().st_mode), 0o755)
            self.assertEqual(stat.S_IMODE(generation.stat().st_mode), 0o755)
            self.assertTrue((target / "current" / "a6_operational_window.py").is_file())

    def test_interruptions_resume_one_verified_generation(self):
        for point in ("release_ready", "entry_ready", "policy_ready", "published"):
            with self.subTest(point=point), tempfile.TemporaryDirectory(dir="/root") as temporary:
                root = Path(temporary)
                stage = root / "stage"
                stage.mkdir(mode=0o700)
                kit = build(root, "a" * 40)
                members = validate((root / "admin.tar").read_bytes(), kit["bundleSha256"])
                for name, data in members.items():
                    (stage / name).write_bytes(data)
                    (stage / name).chmod(0o600)
                def interrupt(actual):
                    if actual == point:
                        raise InterruptedError(point)
                with self.assertRaises(InterruptedError):
                    install(stage, kit["bundleSha256"], root, interrupt)
                result = install(stage, kit["bundleSha256"], root)
                self.assertTrue(result["resumed"])
                current = root / "usr/local/lib/odisseu-a6/current"
                self.assertEqual(current.resolve(), Path(result["generation"]))
                manifest = json.loads((current / "bundle-manifest.json").read_text())
                for name, digest in manifest["files"].items():
                    self.assertEqual(hashlib.sha256((current / name).read_bytes()).hexdigest(), digest)
                again = install(stage, kit["bundleSha256"], root)
                self.assertEqual(result["generation"], again["generation"])
                self.assertFalse(again["resumed"])

    def test_corruption_and_other_pending_release_fail_closed(self):
        with tempfile.TemporaryDirectory(dir="/root") as temporary:
            root = Path(temporary)
            kit = build(root, "a" * 40)
            stage = root / "stage"
            stage.mkdir(mode=0o700)
            for name, data in validate((root / "admin.tar").read_bytes(), kit["bundleSha256"]).items():
                (stage / name).write_bytes(data)
                (stage / name).chmod(0o600)
            def interrupt(_):
                raise InterruptedError()
            with self.assertRaises(InterruptedError):
                install(stage, kit["bundleSha256"], root, interrupt)
            with self.assertRaisesRegex(InstallationError, "pending_other"):
                install(stage, "b" * 64, root)
            (stage / "a6_root_backup.py").write_text("corrupted")
            with self.assertRaisesRegex(InstallationError, "component_mismatch"):
                install(stage, kit["bundleSha256"], root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
