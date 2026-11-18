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
    inventory = []
    for path in sorted(path for path in destination.rglob("*") if path.is_file()):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        inventory.append({"path": str(path.relative_to(destination)), "sha256": digest})
    manifest = destination.parent / "backup-manifest.txt"
    lines = [f"{item['sha256']}  {item['path']}" for item in inventory]
    manifest.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    for item in inventory:
        copied = destination / item["path"]
        if hashlib.sha256(copied.read_bytes()).hexdigest() != item["sha256"]:
            raise BackupError("backup_hash_mismatch")
        if (source / item["path"]).read_bytes() != copied.read_bytes():
            raise BackupError("backup_diverged_from_source")
    if not source.exists():
        raise BackupError("source_removed")
    return {"files": len(inventory), "manifest": str(manifest), "quiescenceProven": True}
