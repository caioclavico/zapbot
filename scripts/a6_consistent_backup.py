"""Backup consistente só em diretório isolado. Não toca volume de produção."""

from __future__ import annotations

import hashlib
import shutil
from pathlib import Path

from a6_writer_policy import prove_quiescence


FORBIDDEN = ("odisseu-a6-cutover", "/var/lib/docker/volumes")


class BackupError(Exception):
    pass


def _forbid(path):
    text = str(path)
    if any(marker in text for marker in FORBIDDEN):
        raise BackupError("production_path_refused")


def consistent_copy(source, destination, evidence):
    source = Path(source)
    destination = Path(destination)
    _forbid(source)
    _forbid(destination)
    proof = prove_quiescence(evidence)
    if not proof["quiescenceProven"]:
        raise BackupError(proof["reason"] or "quiescence_not_proven")
    if not source.is_dir():
        raise BackupError("source_missing")
    if destination.exists():
        raise BackupError("destination_exists")
    shutil.copytree(source, destination)
    inventory = _inventory(destination)
    manifest = destination.parent / "backup-manifest.txt"
    _write_manifest(manifest, inventory)
    _verify(source, destination, inventory)
    if not source.exists():
        raise BackupError("source_removed")
    return {"files": len(inventory), "manifest": str(manifest), "quiescenceProven": True}


def restore_copy(manifest, backup, restore_dir):
    """Restaura só em diretório novo. Não apaga a origem nem o backup."""
    backup = Path(backup)
    restore_dir = Path(restore_dir)
    manifest = Path(manifest)
    _forbid(backup)
    _forbid(restore_dir)
    if restore_dir.exists():
        raise BackupError("restore_destination_exists")
    if not backup.is_dir() or not manifest.is_file():
        raise BackupError("backup_missing")
    shutil.copytree(backup, restore_dir)
    inventory = []
    for line in manifest.read_text(encoding="utf-8").splitlines():
        digest, relative = line.split("  ", 1)
        inventory.append({"path": relative, "sha256": digest})
    _verify(backup, restore_dir, inventory)
    if not backup.exists():
        raise BackupError("backup_removed")
    return {"files": len(inventory), "restored": str(restore_dir)}


def _inventory(root):
    inventory = []
    for path in sorted(path for path in root.rglob("*") if path.is_file()):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        inventory.append({"path": str(path.relative_to(root)), "sha256": digest})
    return inventory


def _write_manifest(manifest, inventory):
    lines = [f"{item['sha256']}  {item['path']}" for item in inventory]
    manifest.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def _verify(source, destination, inventory):
    for item in inventory:
        copied = destination / item["path"]
        if hashlib.sha256(copied.read_bytes()).hexdigest() != item["sha256"]:
            raise BackupError("backup_hash_mismatch")
        if (source / item["path"]).read_bytes() != copied.read_bytes():
            raise BackupError("backup_diverged_from_source")
