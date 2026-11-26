"""Backup de produção, separado do helper de laboratório.

consistent_copy continua recusando odisseu-a6-cutover. A cópia do volume
live só acontece com coleta de um runner, VM esperada, drain concluído,
JVM parada e autorização explícita. O padrão é não copiar.
"""

from __future__ import annotations

import hashlib
import re
import shutil
import stat
from pathlib import Path

from a6_mount_policy import LIVE_VOLUME, assess, assess_observed
from a6_production_contract import DATOMIC_SOURCE, VM, vm_matches
from a6_writer_policy import INVENTORY


class ProductionBackupError(Exception):
    pass


MARGIN = 64 * 1024 * 1024


def _live_marker(path):
    text = str(path)
    return "odisseu-a6-cutover" in text or "/var/lib/docker/volumes" in text


BACKUP_AUTHORIZATION = "autorizar-backup-producao-a6"


def _quiescence_reason(collection, vm):
    if isinstance(collection, dict) or collection is None:
        return "declared_evidence"
    if getattr(collection, "source", "") != "docker-inspect-subprocess":
        return "declared_evidence"
    if not vm_matches(vm or {}):
        return "vm_mismatch"
    if collection.volume != LIVE_VOLUME or collection.authorized != "odisseu-a6-jvm":
        return "writer_not_identified"
    if collection.active_jvm_writes != 0 or collection.active_node_ops != 0:
        return "writes_in_flight"
    if collection.node_phase not in ("draining", "quiescent", "frozen"):
        return "node_not_draining"
    if collection.jvm_phase not in ("quiescent", "frozen"):
        return "jvm_not_quiescent"
    for row in INVENTORY:
        if row["transact"] and row["path"] in collection.processes:
            return "external_datomic_client"
    exclusive = assess_observed(collection.documents, collection.volume, collection.authorized)
    if not exclusive["exclusive"]:
        return exclusive["reason"] or "writable_mount_not_exclusive"
    if assess(collection.documents, LIVE_VOLUME, collection.authorized)["reason"] != "live_volume_refused":
        return "lab_gate_removed"
    return None


def admit(collection, vm, source, destination, free_bytes, source_bytes):
    """Devolve a admissão do volume live. Não copia e não apaga a origem."""
    reason = _quiescence_reason(collection, vm)
    if reason == "writer_not_identified" and getattr(collection, "volume", None) not in (None, LIVE_VOLUME):
        reason = "source_not_live_datomic"
    if reason:
        return _deny(reason)
    if str(source) != DATOMIC_SOURCE:
        return _deny("source_not_live_datomic")
    if _live_marker(destination) or Path(destination) == Path(source):
        return _deny("destination_not_separate")
    try:
        if Path(destination).resolve().is_relative_to(Path(source).resolve()):
            return _deny("destination_not_separate")
    except OSError:
        return _deny("destination_not_separate")
    if free_bytes < source_bytes * 2 + MARGIN:
        return _deny("insufficient_space")
    return {
        "admitted": True,
        "reason": None,
        "execute": False,
        "source": DATOMIC_SOURCE,
        "destination": str(destination),
        "vm": {key: VM[key] for key in ("instance", "zone")},
    }


def copy_authorized_isolated(source, destination, collection, vm, authorization, free_bytes, source_bytes):
    """Mesmos gates do backup de produção, copiando só um diretório isolado."""
    if authorization != BACKUP_AUTHORIZATION:
        raise ProductionBackupError("backup_not_authorized")
    if _live_marker(source) or _live_marker(destination):
        raise ProductionBackupError("live_path_refused")
    reason = _quiescence_reason(collection, vm)
    if reason:
        raise ProductionBackupError(reason)
    if free_bytes < source_bytes * 2 + MARGIN:
        raise ProductionBackupError("insufficient_space")
    report = rehearse_isolated_copy(source, destination)
    report["authorization"] = "accepted"
    report["quiescence"] = "observed"
    return report


def _active_writer(documents, volume):
    """O volume só pode ser copiado quando nenhum holder está em execução."""
    found = False
    for container in documents or []:
        holds = False
        for mount in container.get("Mounts") or []:
            haystack = " ".join(str(mount.get(key) or "") for key in ("Name", "Source", "Destination"))
            writable = mount.get("RW", mount.get("rw", True))
            if volume and volume in haystack and writable is not False:
                holds = True
        if not holds:
            continue
        found = True
        state = container.get("State")
        if not isinstance(state, dict) or "Running" not in state:
            return "writer_state_unknown"
        if state.get("Running") is True or state.get("Status") == "running":
            return "datomic_writable"
    if not found:
        return "writer_not_identified"
    return None


def execute_copy(collection, vm, destination, hold, execute, authorization, free_bytes, source_bytes, isolated_root=None):
    """Copia o volume live depois da quiescência e com a JVM parada.

    isolated_root exercita o mesmo caminho em diretório temporário. Sem ele,
    a origem é o volume live e esta revisão não é chamada contra a VM.
    """
    if execute is not True:
        raise ProductionBackupError("dry_run")
    if hold != "false":
        raise ProductionBackupError("hold_active")
    if authorization != BACKUP_AUTHORIZATION:
        raise ProductionBackupError("backup_not_authorized")
    reason = _quiescence_reason(collection, vm)
    if reason:
        raise ProductionBackupError(reason)
    active = _active_writer(getattr(collection, "documents", ()), LIVE_VOLUME)
    if active:
        raise ProductionBackupError(active)
    if _live_marker(destination) or Path(destination) == Path(DATOMIC_SOURCE):
        raise ProductionBackupError("destination_not_separate")
    if isolated_root is None:
        source = Path(DATOMIC_SOURCE)
    else:
        source = Path(isolated_root)
        if _live_marker(source):
            raise ProductionBackupError("live_path_refused")
    try:
        if Path(destination).resolve().is_relative_to(source.resolve()):
            raise ProductionBackupError("destination_not_separate")
        readable = source.is_dir()
    except ProductionBackupError:
        raise
    except OSError as exc:
        raise ProductionBackupError("source_unreadable") from exc
    if not readable:
        raise ProductionBackupError("source_missing")
    try:
        measured = _dir_bytes(source)
    except OSError as exc:
        raise ProductionBackupError("source_unreadable") from exc
    if source_bytes < measured:
        raise ProductionBackupError("source_bytes_mismatch")
    if free_bytes < max(source_bytes, measured) * 2 + MARGIN:
        raise ProductionBackupError("insufficient_space")
    report = _copy_and_verify(source, destination)
    report["authorization"] = "accepted"
    report["quiescence"] = "observed"
    report["writersStopped"] = True
    report["source"] = DATOMIC_SOURCE if isolated_root is None else "isolated"
    return report


def rehearse_isolated_copy(source, destination):
    """Copia só diretório temporário. Recusa o volume live."""
    source = Path(source)
    destination = Path(destination)
    if _live_marker(source) or _live_marker(destination):
        raise ProductionBackupError("live_path_refused")
    return _copy_and_verify(source, destination)


def _copy_and_verify(source, destination):
    source = Path(source)
    destination = Path(destination)
    if destination.exists():
        raise ProductionBackupError("destination_exists")
    if not source.is_dir():
        raise ProductionBackupError("source_missing")
    if _live_marker(destination):
        raise ProductionBackupError("destination_not_separate")
    shutil.copytree(source, destination)
    manifest = _manifest(destination)
    _verify(source, destination, manifest)
    if not source.exists():
        raise ProductionBackupError("source_removed")
    manifest_path = destination.parent / "backup-manifest.txt"
    lines = [f"{item['sha256']}  {item['path']}" for item in manifest]
    manifest_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return {
        "files": len(manifest),
        "manifest": str(manifest_path),
        "sourcePreserved": True,
        "manifestVerified": True,
    }


def restore_isolated(manifest_path, backup, restore_dir):
    backup = Path(backup)
    restore_dir = Path(restore_dir)
    if _live_marker(backup) or _live_marker(restore_dir):
        raise ProductionBackupError("live_path_refused")
    if restore_dir.exists():
        raise ProductionBackupError("restore_destination_exists")
    manifest = read_manifest(manifest_path)
    shutil.copytree(backup, restore_dir)
    try:
        _verify(backup, restore_dir, manifest)
    except ProductionBackupError:
        shutil.rmtree(restore_dir)
        raise
    if not backup.exists():
        raise ProductionBackupError("backup_removed")
    return {"files": len(manifest), "restored": str(restore_dir)}


def _deny(reason):
    return {"admitted": False, "reason": reason, "execute": False}


def _dir_bytes(root):
    return sum(path.stat().st_size for path in root.rglob("*") if path.is_file())


def _manifest(root):
    items = []
    for path in _files(root):
        items.append({
            "path": str(path.relative_to(root)),
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    return items


def _files(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ProductionBackupError("backup_invalid")
    files = []
    for path in root.rglob("*"):
        mode = path.lstat().st_mode
        if stat.S_ISREG(mode):
            files.append(path)
        elif not stat.S_ISDIR(mode):
            raise ProductionBackupError("backup_invalid")
    return sorted(files)


def read_manifest(path):
    try:
        rows = []
        seen = set()
        for line in Path(path).read_text(encoding="utf-8").splitlines():
            digest, relative = line.split("  ", 1)
            parts = Path(relative).parts
            if (not re.fullmatch(r"[0-9a-f]{64}", digest) or not relative
                    or Path(relative).is_absolute() or ".." in parts
                    or str(Path(relative)) != relative or relative in seen):
                raise ValueError()
            seen.add(relative)
            rows.append({"path": relative, "sha256": digest})
        if not rows:
            raise ValueError()
        return rows
    except (OSError, UnicodeError, ValueError) as exc:
        raise ProductionBackupError("backup_manifest_invalid") from exc


def _verify(left, right, manifest):
    paths = [item["path"] for item in manifest]
    if not paths or len(paths) != len(set(paths)):
        raise ProductionBackupError("backup_invalid")
    for root in (Path(left), Path(right)):
        if {str(path.relative_to(root)) for path in _files(root)} != set(paths):
            raise ProductionBackupError("backup_invalid")
    for item in manifest:
        copied = right / item["path"]
        original = left / item["path"]
        if hashlib.sha256(copied.read_bytes()).hexdigest() != item["sha256"]:
            raise ProductionBackupError("backup_invalid")
        if hashlib.sha256(original.read_bytes()).hexdigest() != item["sha256"]:
            raise ProductionBackupError("backup_invalid")
