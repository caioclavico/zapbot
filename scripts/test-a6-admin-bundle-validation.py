#!/usr/bin/env python3
"""Negative coverage for the pre-extraction A6 administrative bundle gate."""
import io
import json
import os
import pathlib
import shutil
import subprocess
import tarfile
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parent
VALIDATOR = ROOT / "a6_validate_admin_bundle.py"
EXPECTED = (
    "a6-root-backup", "a6_root_backup.py", "a6_production_backup.py",
    "a6_production_contract.py", "a6_production_deploy.py",
    "a6_mount_policy.py", "a6_writer_policy.py", "chromium-seccomp.json",
)


def add_member(archive, name, member_type=tarfile.REGTYPE, size=1, linkname=""):
    entry = tarfile.TarInfo(name)
    entry.type = member_type
    entry.size = size
    entry.linkname = linkname
    archive.addfile(entry, io.BytesIO(b"x" * size) if member_type in (tarfile.REGTYPE, tarfile.AREGTYPE) else None)


def make_archive(path, mutate=None, overrides=None, archive_format=tarfile.USTAR_FORMAT):
    overrides = overrides or {}
    with tarfile.open(path, "w", format=archive_format) as archive:
        for name in EXPECTED:
            add_member(archive, name, *overrides.get(name, ()))
        if mutate:
            mutate(archive)


class AdministrativeBundleValidationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.stage_parent = self.root / "stages"
        self.stage_parent.mkdir()

    def tearDown(self):
        self.temp.cleanup()

    def validate(self, archive):
        digest = subprocess.check_output(["sha256sum", str(archive)], text=True).split()[0]
        return subprocess.run(
            ["python3", str(VALIDATOR), "--bundle", str(archive), "--sha256", digest,
             "--stage-parent", str(self.stage_parent)],
            text=True, capture_output=True,
        )

    def assert_rejected_without_staging(self, archive):
        before = list(self.stage_parent.iterdir())
        result = self.validate(archive)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(before, list(self.stage_parent.iterdir()))
        self.assertFalse((self.root / "escape").exists())

    def test_valid_archive_is_snapshotted_only_after_validation(self):
        archive = self.root / "valid.tar"
        make_archive(archive)
        result = self.validate(archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        stage = pathlib.Path(json.loads(result.stdout)["stage"])
        self.assertEqual({item.name for item in stage.iterdir()}, {"bundle.tar"})
        self.assertEqual(oct((stage / "bundle.tar").stat().st_mode & 0o777), "0o600")
        shutil.rmtree(stage)

    def test_rejects_unknown_missing_duplicate_and_macos_entries(self):
        cases = {
            "extra": lambda a: add_member(a, "unexpected"),
            "duplicate": lambda a: add_member(a, EXPECTED[0]),
            "appledouble": lambda a: add_member(a, "._a6-root-backup"),
            "macosx": lambda a: add_member(a, "__MACOSX"),
            "directory": lambda a: add_member(a, "unexpected-directory", tarfile.DIRTYPE, 0),
        }
        for label, mutation in cases.items():
            with self.subTest(label=label):
                archive = self.root / f"{label}.tar"
                make_archive(archive, mutation)
                self.assert_rejected_without_staging(archive)
        archive = self.root / "missing.tar"
        with tarfile.open(archive, "w", format=tarfile.USTAR_FORMAT) as out:
            for name in EXPECTED[:-1]:
                add_member(out, name)
        self.assert_rejected_without_staging(archive)

    def test_rejects_unsafe_paths_and_special_members(self):
        cases = {
            "traversal": lambda a: add_member(a, "../escape"),
            "absolute": lambda a: add_member(a, "/escape"),
        }
        for label, mutation in cases.items():
            with self.subTest(label=label):
                archive = self.root / f"{label}.tar"
                make_archive(archive, mutation)
                self.assert_rejected_without_staging(archive)
        replacements = {
            "symlink": (tarfile.SYMTYPE, 0, "/escape"),
            "hardlink": (tarfile.LNKTYPE, 0, EXPECTED[1]),
            "fifo": (tarfile.FIFOTYPE, 0, ""),
            "device": (tarfile.CHRTYPE, 0, ""),
        }
        for label, replacement in replacements.items():
            with self.subTest(label=label):
                archive = self.root / f"{label}.tar"
                make_archive(archive, overrides={EXPECTED[0]: replacement})
                self.assert_rejected_without_staging(archive)
        archive = self.root / "pax.tar"
        with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT, pax_headers={"comment": "forbidden"}) as out:
            for name in EXPECTED:
                add_member(out, name)
        self.assert_rejected_without_staging(archive)

    def test_rejects_truncation_corruption_and_oversized_member(self):
        archive = self.root / "truncated.tar"
        make_archive(archive)
        archive.write_bytes(archive.read_bytes()[:700])
        self.assert_rejected_without_staging(archive)
        archive = self.root / "corrupt.tar"
        make_archive(archive)
        payload = bytearray(archive.read_bytes())
        payload[0] ^= 1
        archive.write_bytes(payload)
        self.assert_rejected_without_staging(archive)
        archive = self.root / "large.tar"
        make_archive(archive, overrides={EXPECTED[0]: (tarfile.REGTYPE, 16 * 1024 * 1024 + 1, "")})
        self.assert_rejected_without_staging(archive)


if __name__ == "__main__":
    unittest.main()
