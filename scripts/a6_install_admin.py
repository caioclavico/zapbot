"""Publicação root de gerações imutáveis; retomada da mesma instalação."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import py_compile
import re
import shutil
import stat
import subprocess
import tempfile


class InstallationError(Exception):
    pass


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def private(path):
    if path.resolve() != path:
        raise InstallationError("admin_path_invalid")
    for item in (path, *path.parents):
        s = item.lstat()
        if s.st_uid != 0 or s.st_mode & 0o022 or stat.S_ISLNK(s.st_mode):
            raise InstallationError("admin_permissions_invalid")


def atomic(path, data, mode):
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name, dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fchmod(stream.fileno(), mode)
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        parent = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def install(stage, bundle_sha, root=Path("/"), checkpoint=lambda _: None):
    if os.geteuid() != 0:
        raise InstallationError("admin_root_required")
    if not re.fullmatch(r"[0-9a-f]{64}", bundle_sha):
        raise InstallationError("admin_bundle_sha_invalid")
    stage = Path(stage)
    private(stage)
    private(stage / "bundle-manifest.json")
    manifest = json.loads((stage / "bundle-manifest.json").read_text())
    for name, expected in manifest["files"].items():
        private(stage / name)
        if sha(stage / name) != expected:
            raise InstallationError("admin_component_mismatch")
    target = root / "usr/local/lib/odisseu-a6"
    target.mkdir(parents=True, exist_ok=True, mode=0o755)
    private(target)
    releases = target / "releases"
    releases.mkdir(exist_ok=True, mode=0o755)
    private(releases)
    current = target / "current"
    release = releases / bundle_sha
    lock = target / ".install.lock"
    if lock.exists() or lock.is_symlink():
        private(lock)
    with lock.open("a") as guard:
        os.chmod(lock, 0o600)
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        journal = target / ".install-journal.json"
        if journal.exists() or journal.is_symlink():
            private(journal)
        prior = json.loads(journal.read_text()) if journal.exists() else None
        previous = os.readlink(current) if current.is_symlink() else None
        if current.exists() and not current.is_symlink():
            raise InstallationError("admin_current_invalid")
        if prior and (prior["phase"] != "complete" or prior["bundleSha256"] == bundle_sha):
            if prior["bundleSha256"] != bundle_sha:
                raise InstallationError("admin_install_pending_other_release")
            previous = prior["previous"]
        new_link = "releases/" + bundle_sha
        if current.is_symlink() and os.readlink(current) not in (previous, new_link):
            raise InstallationError("admin_install_pointer_changed")
        state = {"bundleSha256": bundle_sha, "previous": previous, "phase": "prepared"}
        atomic(journal, json.dumps(state, sort_keys=True).encode(), 0o600)
        if not release.exists():
            with tempfile.TemporaryDirectory(prefix=".release-", dir=releases) as temporary:
                pending = Path(temporary) / "generation"
                pending.mkdir(mode=0o755)
                for item in stage.iterdir():
                    atomic(pending / item.name, item.read_bytes(), 0o644)
                for path in pending.glob("*.py"):
                    py_compile.compile(str(path), doraise=True)
                os.rename(pending, release)
                parent = os.open(releases, os.O_RDONLY)
                try:
                    os.fsync(parent)
                finally:
                    os.close(parent)
        private(release)
        for name, expected in manifest["files"].items():
            if sha(release / name) != expected:
                raise InstallationError("admin_release_mismatch")
        checkpoint("release_ready")
        for relative in ("var/backups/odisseu-a6", "etc/odisseu-a6/post-v3-windows"):
            path = root / relative
            path.mkdir(parents=True, exist_ok=True, mode=0o700)
            private(path)
            if stat.S_IMODE(path.stat().st_mode) != 0o700:
                raise InstallationError("admin_private_directory_invalid")
        profile = root / "etc/odisseu-a6/chromium-seccomp.json"
        if profile.exists() or profile.is_symlink():
            private(profile)
        if profile.exists() and sha(profile) != sha(stage / "chromium-seccomp.json"):
            raise InstallationError("admin_seccomp_change_requires_authorization")
        atomic(profile, (stage / "chromium-seccomp.json").read_bytes(), 0o644)
        entry = root / "usr/local/libexec/odisseu-a6/a6-root-backup"
        entry.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        private(entry.parent)
        atomic(entry, (stage / "a6-root-backup").read_bytes(), 0o755)
        checkpoint("entry_ready")
        rules = root / "etc/sudoers.d"
        rules.mkdir(parents=True, exist_ok=True, mode=0o750)
        private(rules)
        verbs = ("preflight", "status", "ensure", "backup", "post-v3-preflight",
                 "post-v3-backup", "post-v3-audit", "post-v3-status", "post-v3-live-gate", "post-v3-gate-status")
        data = ("Cmnd_Alias ODISSEU_A6_BACKUP = " +
                ", ".join("/usr/local/libexec/odisseu-a6/a6-root-backup --" + v + " *" for v in verbs) +
                "\ncaioclavico ALL=(root) NOPASSWD: ODISSEU_A6_BACKUP\n").encode()
        candidate = rules / ".odisseu-a6-backup.pending"
        atomic(candidate, data, 0o440)
        checked = subprocess.run(["visudo", "-cf", str(candidate)], capture_output=True)
        if checked.returncode:
            raise InstallationError("admin_sudoers_invalid")
        os.replace(candidate, rules / "odisseu-a6-backup")
        checkpoint("policy_ready")
        temporary_link = target / ".current.pending"
        if temporary_link.is_symlink():
            temporary_link.unlink()
        if temporary_link.exists():
            raise InstallationError("admin_pointer_temp_invalid")
        os.symlink(new_link, temporary_link)
        os.replace(temporary_link, current)
        checkpoint("published")
        state["phase"] = "complete"
        atomic(journal, json.dumps(state, sort_keys=True).encode(), 0o600)
        return {"installed": True, "bundleSha256": bundle_sha, "revision": manifest["revision"],
                "generation": str(release), "resumed": bool(prior and prior["phase"] != "complete"),
                "maintenanceStarted": False, "holdChanged": False}


if __name__ == "__main__":
    import argparse
    p = argparse.ArgumentParser()
    p.add_argument("--stage", required=True)
    p.add_argument("--bundle-sha256", required=True)
    a = p.parse_args()
    try:
        print(json.dumps(install(Path(a.stage), a.bundle_sha256), sort_keys=True))
    except (InstallationError, OSError, ValueError, py_compile.PyCompileError) as exc:
        reason = str(exc) if isinstance(exc, InstallationError) else type(exc).__name__
        raise SystemExit("admin_install_blocked:" + reason)
