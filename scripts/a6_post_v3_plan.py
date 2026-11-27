"""Provisiona um plano POST_V3_UPGRADE validado e root-owned."""
from __future__ import annotations

import json
import hashlib
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

import a6_post_v3_backup as post
from a6_production_contract import DATOMIC_SOURCE
from a6_production_deploy import DeployError


INPUT_ROOT = Path("/root/odisseu-a6-plans")
PLAN_ROOT = post.PLAN_ROOT
PLAN_KEYS = {
    "protocol", "windowId", "imageSha", "jvmDigest", "nodeDigest",
    "rollbackSha", "rollbackJvmDigest", "rollbackNodeDigest",
    "auditCodeSha256", "system", "database",
}


def _private(path, mode=None):
    path = Path(path)
    if path.resolve() != path:
        raise DeployError("post_v3_plan_path_invalid")
    try:
        for item in (path, *path.parents):
            info = item.lstat()
            if (info.st_uid != 0 or info.st_mode & 0o022 or stat.S_ISLNK(info.st_mode)
                    or (item == path and info.st_nlink != 1)):
                raise DeployError("post_v3_plan_permissions_invalid")
        if mode is not None and stat.S_IMODE(path.stat().st_mode) != mode:
            raise DeployError("post_v3_plan_permissions_invalid")
    except OSError as exc:
        raise DeployError("post_v3_plan_permissions_invalid") from exc


def _unique_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate_key")
        value[key] = item
    return value


def _read_source(window, source_root=INPUT_ROOT):
    if not isinstance(window, str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", window):
        raise DeployError("window_id_invalid")
    directory = Path(source_root)
    _private(directory, 0o700)
    source = directory / (window + ".json")
    _private(source, 0o600)
    try:
        if not stat.S_ISREG(source.stat().st_mode) or source.stat().st_size > 32768:
            raise ValueError()
        value = json.loads(source.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        raise DeployError("post_v3_plan_invalid") from exc
    if not isinstance(value, dict) or set(value) != PLAN_KEYS:
        raise DeployError("post_v3_plan_invalid")
    if (value.get("protocol") != "POST_V3_UPGRADE" or value.get("windowId") != window
            or not re.fullmatch(r"[0-9a-f]{40}", str(value.get("imageSha") or ""))
            or not re.fullmatch(r"[0-9a-f]{40}", str(value.get("rollbackSha") or ""))
            or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(value.get(key) or ""))
                   for key in ("jvmDigest", "nodeDigest", "rollbackJvmDigest", "rollbackNodeDigest"))
            or not re.fullmatch(r"[0-9a-f]{64}", str(value.get("auditCodeSha256") or ""))
            or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", str(value.get(key) or ""))
                   for key in ("system", "database"))):
        raise DeployError("post_v3_plan_invalid")
    return value


def _verify_audit_code(value):
    hashes = {}
    for name in post.CODE_FILES:
        path = post.CODE_ROOT / name
        _private(path)
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if post.digest(hashes) != value["auditCodeSha256"]:
        raise DeployError("post_v3_audit_code_mismatch")


def _source_containers(value, runner=subprocess.run):
    result = runner([
        "docker", "inspect", "--format",
        "{{.Image}}\t{{.State.Running}}\t{{.State.Health.Status}}",
        "odisseu-a6-jvm", "odisseu-a6-node",
    ], check=False, capture_output=True, text=True)
    rows = (result.stdout or "").splitlines()
    expected = [value["rollbackJvmDigest"], value["rollbackNodeDigest"]]
    if result.returncode or len(rows) != 2:
        raise DeployError("post_v3_source_image_mismatch")
    for row, digest_id in zip(rows, expected):
        fields = row.split("\t")
        if fields != [digest_id, "true", "healthy"]:
            raise DeployError("post_v3_source_image_mismatch")
    return {"jvmDigest": expected[0], "nodeDigest": expected[1]}


def _capacity():
    import a6_root_backup as admin
    from a6_capacity import budget
    source = Path(DATOMIC_SOURCE)
    if not source.is_dir() or source.is_symlink():
        raise DeployError("source_not_live_datomic")
    admin._no_symlinks(source)
    size = admin._dir_bytes(source)
    return budget(shutil.disk_usage(admin.BACKUP_ROOT).free, size)


def install(window, source_root=INPUT_ROOT, plan_root=PLAN_ROOT, runner=subprocess.run):
    if os.geteuid() != 0:
        raise DeployError("backup_root_required")
    value = _read_source(window, source_root)
    _verify_audit_code(value)
    post.verify_images(value)
    sources = _source_containers(value, runner)
    capacity = _capacity()
    target_root = Path(plan_root)
    _private(target_root, 0o700)
    target = target_root / (window + ".json")
    if target.exists() or target.is_symlink():
        _private(target, 0o600)
        if json.loads(target.read_text(encoding="utf-8")) != value:
            raise DeployError("post_v3_plan_conflict")
        installed = post.plan(window)
        return {
            "installed": True,
            "reused": True,
            "windowId": window,
            "planSha256": post.digest(installed),
            "sourceImages": sources,
            "capacity": capacity,
        }
    descriptor, temporary = tempfile.mkstemp(
        prefix="." + target.name + ".", dir=str(target_root))
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(post.canonical(value) + b"\n")
            stream.flush()
            os.fchmod(stream.fileno(), 0o600)
            os.fsync(stream.fileno())
        os.link(temporary, target)
        parent = os.open(target_root, os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    except FileExistsError as exc:
        raise DeployError("post_v3_plan_conflict") from exc
    except OSError as exc:
        raise DeployError("post_v3_plan_write_failed") from exc
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    installed = post.plan(window)
    return {
        "installed": True,
        "reused": False,
        "windowId": window,
        "planSha256": post.digest(installed),
        "sourceImages": sources,
        "capacity": capacity,
    }


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--window-id", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(install(args.window_id), sort_keys=True))
    except (DeployError, post.ProductionBackupError, OSError, ValueError) as exc:
        raise SystemExit("post_v3_plan_blocked:" + (str(exc) or type(exc).__name__))
