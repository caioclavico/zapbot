#!/usr/bin/env python3
"""Fail-closed cold backup and isolated restore helpers for the A6 Docker volumes.

This tool never stops services, resumes maintenance, deletes a backup, or restores
over an existing volume. The operator must establish quiescence and stop the
writers first. See docs/a6-cutover-cold-backup-and-drain.md.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import stat
import subprocess
import sys
import uuid

VOLUMES = (
    "odisseu-a6-cutover-datomic",
    "odisseu-a6-cutover-journal",
    "odisseu-a6-cutover-maint",
    # The Node maintenance marker and durable app idempotency/history live here.
    "odisseu-a6-cutover-data",
)
ROLES = ("datomic", "journal", "maint", "data")
CONTAINERS = ("odisseu-a6-node", "odisseu-a6-jvm")
DEFAULT_HELPER = "sha256:c60eeb31f0e66948f92e8ff5e03b04c055c381b3cf10c59ed5698f175ef59c20"
RESERVE_BYTES = 5 * 1024**3
LABELS = (
    "org.opencontainers.image.revision",
    "org.opencontainers.image.version",
    "org.opencontainers.image.created",
    "org.opencontainers.image.source",
    "org.opencontainers.image.title",
    "org.opencontainers.image.base.digest",
    "org.opencontainers.image.tree",
)


class BackupError(RuntimeError):
    pass


def _run(args: list[str], *, capture: bool = True) -> str:
    p = subprocess.run(args, check=False, text=True, stdout=subprocess.PIPE if capture else None,
                       stderr=subprocess.PIPE if capture else None)
    if p.returncode:
        detail = (p.stderr or "").strip() if capture else ""
        raise BackupError(f"command_failed:{Path(args[0]).name}:{p.returncode}:{detail[:500]}")
    return p.stdout or ""


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def _fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _write_json_atomic(path: Path, payload: dict) -> None:
    tmp = path.with_name(path.name + f".{os.getpid()}.{uuid.uuid4().hex}.tmp")
    data = (json.dumps(payload, sort_keys=True, indent=2) + "\n").encode()
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        os.close(fd)
    os.replace(tmp, path)
    _fsync_dir(path.parent)


def _xattrs(path: Path) -> list[list[str]]:
    try:
        names = os.listxattr(path, follow_symlinks=False)
    except (AttributeError, OSError):
        return []
    result = []
    for name in sorted(names):
        value = os.getxattr(path, name, follow_symlinks=False)
        result.append([name, hashlib.sha256(value).hexdigest()])
    return result


def _assert_link_stays_inside(rel: str, target_text: str, *, symlink: bool) -> None:
    target = PurePosixPath(target_text)
    if target.is_absolute():
        raise BackupError("absolute_link_target_rejected")
    depth = len(PurePosixPath(rel).parent.parts) if symlink else 0
    for part in target.parts:
        if part in ("", "."):
            continue
        depth += -1 if part == ".." else 1
        if depth < 0:
            raise BackupError("link_target_outside_volume_rejected")


def tree_inventory(root: Path) -> dict:
    """Content and metadata fingerprint; does not follow symlinks."""
    root = root.resolve(strict=True)
    if not root.is_dir():
        raise BackupError("volume_mount_not_directory")
    entries: list[dict] = []

    def visit(path: Path, rel: str) -> None:
        before = path.lstat()
        mode = stat.S_IFMT(before.st_mode) | stat.S_IMODE(before.st_mode)
        entry = {"path": rel, "mode": mode, "uid": before.st_uid, "gid": before.st_gid,
                 "size": before.st_size, "mtime_ns": before.st_mtime_ns,
                 "xattrs": _xattrs(path)}
        if stat.S_ISLNK(before.st_mode):
            target = os.readlink(path)
            _assert_link_stays_inside(rel, target, symlink=True)
            entry.update(type="symlink", target=target)
        elif stat.S_ISREG(before.st_mode):
            h = hashlib.sha256()
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
            fd = os.open(path, flags)
            try:
                opened = os.fstat(fd)
                if (opened.st_ino, opened.st_dev, opened.st_size, opened.st_mtime_ns) != (
                    before.st_ino, before.st_dev, before.st_size, before.st_mtime_ns
                ):
                    raise BackupError("file_changed_during_inventory")
                with os.fdopen(fd, "rb", closefd=False) as stream:
                    for block in iter(lambda: stream.read(1024 * 1024), b""):
                        h.update(block)
                after = os.fstat(fd)
                if (after.st_ino, after.st_dev, after.st_size, after.st_mtime_ns) != (
                    opened.st_ino, opened.st_dev, opened.st_size, opened.st_mtime_ns
                ):
                    raise BackupError("file_changed_during_inventory")
            finally:
                os.close(fd)
            entry.update(type="file", sha256=h.hexdigest())
        elif stat.S_ISDIR(before.st_mode):
            entry["type"] = "directory"
        else:
            raise BackupError("unsupported_special_file_type")
        entries.append(entry)
        if stat.S_ISDIR(before.st_mode):
            for child in sorted(path.iterdir(), key=lambda p: p.name):
                child_rel = f"{rel}/{child.name}" if rel != "." else f"./{child.name}"
                visit(child, child_rel)

    visit(root, ".")
    encoded = json.dumps(entries, sort_keys=True, separators=(",", ":")).encode()
    return {"entry_count": len(entries),
            "file_count": sum(e["type"] == "file" for e in entries),
            "logical_bytes": sum(e["size"] for e in entries if e["type"] == "file"),
            "tree_sha256": hashlib.sha256(encoded).hexdigest()}


def _running_or_armed_containers(volumes: tuple[str, ...], expected: tuple[str, ...],
                                roots: dict[str, Path]) -> list[str]:
    ids = _run(["docker", "ps", "-aq"]).split()
    offenders = []
    refs = {name: 0 for name in expected}
    for cid in ids:
        fields = _run(["docker", "inspect", "--format",
                       "{{.Name}}|{{.State.Running}}|{{.HostConfig.RestartPolicy.Name}}|{{.Image}}|{{.HostConfig.Privileged}}|{{range .Mounts}}{{.Name}}={{.Source}}=>{{.Destination}};{{end}}",
                       cid]).strip().split("|", 4)
        # Format field 5 may itself contain pipes only if an operator creates an
        # unusual mount path; reject ambiguous output instead of guessing.
        if len(fields) != 5:
            raise BackupError("container_inspect_unparseable")
        name, running, restart, image, privileged_and_mounts = fields
        name = name.lstrip("/")
        if name in refs:
            refs[name] += 1
        privileged, sep, mounts = privileged_and_mounts.partition("|")
        if not sep:
            raise BackupError("container_inspect_unparseable")
        mounted = set()
        docker_socket = False
        for part in mounts.split(";"):
            if "=" not in part or "=>" not in part:
                continue
            vol_name, details = part.split("=", 1)
            source, _, _dest = details.partition("=>")
            if vol_name in volumes:
                mounted.add(vol_name)
            if any(source == str(root) or source.startswith(str(root) + os.sep)
                   for root in roots.values()):
                mounted.add("bind-under-volume")
            if source == "/var/run/docker.sock" or source == "/run/docker.sock":
                docker_socket = True
        if (mounted and (running == "true" or restart not in ("no", ""))):
            offenders.append(f"{name}:volume_mount:running={running}:restart={restart}:image={image}")
        if running == "true" and (privileged == "true" or docker_socket):
            offenders.append(f"{name}:administrative_container:privileged={privileged}:docker_socket={docker_socket}")
    missing = [name for name, count in refs.items() if count != 1]
    if missing:
        offenders.append("expected_container_reference_count:" + ",".join(missing))
    return offenders


def _volume_mount(name: str) -> Path:
    raw = _run(["docker", "volume", "inspect", "--format", "{{.Name}}|{{.Mountpoint}}", name]).strip()
    parts = raw.split("|", 1)
    if len(parts) != 2 or parts[0] != name:
        raise BackupError(f"volume_identity_mismatch:{name}")
    p = Path(parts[1]).resolve(strict=True)
    if not p.is_dir():
        raise BackupError(f"volume_mount_invalid:{name}")
    return p


def _processes_with_paths(mounts: list[Path]) -> list[str]:
    """Fail closed if any host process still has cwd/fd/map below a volume."""
    found = []
    proc = Path("/proc")
    for item in proc.iterdir():
        if not item.name.isdigit() or int(item.name) == os.getpid():
            continue
        pid = item.name
        candidates = [item / "cwd"]
        fd_dir = item / "fd"
        try:
            candidates.extend(fd_dir.iterdir())
        except OSError:
            pass
        try:
            for line in (item / "maps").read_text(errors="ignore").splitlines():
                suffix = line.rsplit(" ", 1)[-1]
                if suffix.startswith("/"):
                    candidates.append(Path(suffix.removesuffix(" (deleted)")))
        except OSError:
            pass
        for candidate in candidates:
            try:
                target = Path(os.readlink(candidate)) if candidate.parent.name == "fd" or candidate.name == "cwd" else candidate
                raw = str(target).removesuffix(" (deleted)")
                if any(raw == str(root) or raw.startswith(str(root) + os.sep) for root in mounts):
                    found.append(f"pid={pid}:path_under_volume")
                    break
            except OSError:
                continue
    return found


def _docker_archive(volume: str, destination: Path, helper: str) -> None:
    _run(["docker", "run", "--rm", "--network", "none", "--read-only",
          "--mount", f"type=volume,src={volume},dst=/source,readonly",
          "--mount", f"type=bind,src={destination.parent},dst=/backup",
          "--entrypoint", "tar", helper, "--create", f"--file=/backup/{destination.name}",
          "--directory=/source", "--numeric-owner", "--xattrs", "--acls", "--selinux",
          "--sparse", "."], capture=True)


def _image_info(image: str) -> dict:
    raw = _run(["docker", "image", "inspect", "--format",
                "{{.Id}}|{{.Os}}|{{.Architecture}}|{{json .Config.Labels}}", image]).strip()
    parts = raw.split("|", 3)
    if len(parts) != 4:
        raise BackupError("image_inspect_unparseable")
    labels = json.loads(parts[3] or "{}")
    return {"id": parts[0], "os": parts[1], "architecture": parts[2],
            "labels": {key: labels[key] for key in LABELS if key in labels}}


def _verify_tar(path: Path) -> None:
    # GNU tar rejects most traversal paths, but explicitly reject unsafe member names.
    import tarfile
    with tarfile.open(path, "r:") as archive:
        for member in archive:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts:
                raise BackupError("archive_path_escape")
            if not (member.isdir() or member.isfile() or member.issym() or member.islnk()):
                raise BackupError("archive_contains_special_file")
            if member.issym() or member.islnk():
                target = PurePosixPath(member.linkname)
                try:
                    _assert_link_stays_inside(member.name, str(target), symlink=member.issym())
                except BackupError as exc:
                    raise BackupError("archive_link_escape") from exc


def verify_backup(path: Path) -> dict:
    path = path.resolve(strict=True)
    if not path.is_dir() or (path.stat().st_mode & 0o077):
        raise BackupError("backup_directory_not_private")
    manifest = json.loads((path / "manifest.json").read_text())
    if manifest.get("format") != "odisseu-a6-cold-backup-v1" or manifest.get("status") != "complete":
        raise BackupError("backup_manifest_invalid")
    if set(manifest.get("volumes", {})) != set(ROLES):
        raise BackupError("backup_volume_set_mismatch")
    for role, item in manifest["volumes"].items():
        archive = path / item["archive"]
        if archive.parent != path or _sha256(archive) != item["archive_sha256"]:
            raise BackupError(f"archive_checksum_mismatch:{role}")
        _verify_tar(archive)
    return {"ok": True, "volumes": len(VOLUMES),
            "logical_bytes": sum(v["inventory"]["logical_bytes"] for v in manifest["volumes"].values()),
            "created_at_utc": manifest["created_at_utc"]}


def publish_backup(source_roots: dict[str, Path], destination: Path, metadata: dict,
                   archive_func, *, reserve_bytes: int = RESERVE_BYTES) -> dict:
    if len(source_roots) != len(ROLES) or {name.rsplit("-", 1)[-1] for name in source_roots} != set(ROLES):
        raise BackupError("volume_set_mismatch")
    destination = destination.absolute()
    parent = destination.parent
    if not parent.is_dir() or (parent.stat().st_mode & 0o077):
        raise BackupError("backup_parent_must_exist_and_be_mode_0700_or_stricter")
    if destination.exists():
        raise BackupError("destination_exists_refusing_overwrite")
    before = {name: tree_inventory(root) for name, root in source_roots.items()}
    required = sum(v["logical_bytes"] for v in before.values()) + max(64 * 1024**2, int(sum(v["logical_bytes"] for v in before.values()) * .05)) + reserve_bytes
    if shutil.disk_usage(parent).free < required:
        raise BackupError("insufficient_free_space_for_backup_and_reserve")
    stage = parent / f".{destination.name}.staging.{uuid.uuid4().hex}"
    stage.mkdir(mode=0o700)
    _write_json_atomic(stage / "INCOMPLETE.json", {"status": "incomplete", "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat()})
    try:
        volumes = {}
        for name in source_roots:
            role = name.rsplit("-", 1)[-1]
            archive_name = f"{name}.tar"
            archive = stage / archive_name
            archive_func(name, archive)
            with archive.open("rb") as f:
                os.fsync(f.fileno())
            _verify_tar(archive)
            volumes[role] = {"source_volume": name, "archive": archive_name,
                             "archive_sha256": _sha256(archive), "inventory": before[name]}
        after = {name: tree_inventory(root) for name, root in source_roots.items()}
        if before != after:
            raise BackupError("volume_changed_during_backup")
        manifest = {"format": "odisseu-a6-cold-backup-v1", "status": "complete",
                    "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
                    "host": metadata.get("host", "unknown"), "architecture": metadata.get("architecture", "unknown"),
                    "versions": metadata.get("versions", {}), "images": metadata.get("images", {}),
                    "containers": metadata.get("containers", {}), "volumes": volumes,
                    "helper_image": metadata.get("helper_image"),
                    "tool_sha256": metadata.get("tool_sha256")}
        _write_json_atomic(stage / "manifest.json", manifest)
        (stage / "INCOMPLETE.json").unlink()
        _fsync_dir(stage)
        # Same-parent rename: final backup appears atomically and is never replaced.
        if destination.exists():
            raise BackupError("destination_appeared_refusing_overwrite")
        os.rename(stage, destination)
        _fsync_dir(parent)
        return verify_backup(destination)
    except Exception as exc:
        try:
            _write_json_atomic(stage / "FAILURE.json", {"status": "failed", "error": str(exc)[:500],
                                                         "at_utc": dt.datetime.now(dt.timezone.utc).isoformat()})
        except Exception:
            pass
        raise


def _metadata(helper: str, containers_to_record: tuple[str, ...] = CONTAINERS) -> dict:
    _run(["docker", "image", "inspect", helper])
    infos = {}
    containers = {}
    for name in containers_to_record:
        raw = _run(["docker", "inspect", "--format",
                    "{{.Id}}|{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}|{{.State.Error}}|{{.HostConfig.RestartPolicy.Name}}|{{.Image}}|{{.Config.Image}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}",
                    name]).strip().split("|", 8)
        # Health is handled separately; split with a final remainder field so
        # formatting changes fail closed instead of shifting image identities.
        if len(raw) != 9 or raw[1] != "exited" or raw[3] == "true" or raw[4] or raw[5] != "no":
            raise BackupError(f"container_not_safely_stopped:{name}")
        containers[name] = {"id": raw[0], "state": raw[1], "exit_code": int(raw[2]),
                            "oom_killed": raw[3] == "true", "restart_policy": raw[5],
                            "image_id": raw[6], "image_ref": raw[7], "last_health": raw[8]}
        if name == "odisseu-a6-node" and int(raw[2]) != 0:
            raise BackupError("node_shutdown_exit_not_clean")
        if name == "odisseu-a6-jvm":
            stop_log = _run(["docker", "logs", name], capture=True)
            if "[odisseu-bridge] stopped" not in stop_log:
                raise BackupError("jvm_shutdown_hook_completion_not_confirmed")
        infos[raw[6]] = _image_info(raw[6])
    helper_info = _image_info(helper)
    if helper_info["os"] != "linux" or helper_info["architecture"] != "amd64":
        raise BackupError("helper_image_must_be_linux_amd64")
    if platform.machine() not in ("x86_64", "amd64"):
        raise BackupError("backup_must_run_on_native_linux_amd64_host")
    return {"host": _run(["hostname"]).strip(), "architecture": "linux/amd64",
            "images": infos, "containers": containers, "helper_image": helper_info,
            "tool_sha256": _sha256(Path(__file__).resolve())}


def validate_context(context: dict) -> dict:
    if not isinstance(context, dict):
        raise BackupError("operational_context_must_be_object")
    def forbidden(value):
        if isinstance(value, dict):
            return any(any(word in str(key).lower() for word in ("token", "secret", "password", "credential", "cookie"))
                       or forbidden(item) for key, item in value.items())
        if isinstance(value, list):
            return any(forbidden(item) for item in value)
        return False
    if forbidden(context):
        raise BackupError("operational_context_must_not_contain_secrets")
    pre = context.get("preflight", {})
    drain = context.get("drain", {})
    gh = context.get("github", {})
    required = (
        context.get("exclusive_operator_confirmed") is True,
        gh.get("hold") is True,
        gh.get("ci_workflow") == "disabled_manually",
        gh.get("active_runs") == 0,
        gh.get("queued_runs") == 0,
        pre.get("node_health") == "healthy",
        pre.get("whatsapp") == "READY",
        pre.get("jvm_health") == "healthy",
        pre.get("node_maintenance_phase") == "open",
        pre.get("node_active_operations") == 0,
        pre.get("jvm_maintenance_phase") == "open",
        pre.get("jvm_active_requests") == 0,
        drain.get("node_phase") == "quiescent",
        drain.get("node_active_operations") == 0,
        drain.get("node_durable") is True,
        drain.get("jvm_phase") == "quiescent",
        drain.get("jvm_active_requests") == 0,
    )
    if not all(required):
        raise BackupError("operational_context_gates_not_proven")
    if not isinstance(context.get("flags"), dict) or not context["flags"]:
        raise BackupError("operational_flags_not_recorded")
    return context


def backup_docker_volumes(destination: Path, volumes: tuple[str, ...],
                          containers_to_record: tuple[str, ...], helper: str,
                          reserve_bytes: int, operational_context: dict | None = None) -> dict:
    if os.geteuid() != 0:
        raise BackupError("run_as_root_on_docker_host")
    if len(volumes) != len(ROLES) or len(set(volumes)) != len(ROLES):
        raise BackupError("volume_set_mismatch")
    roots = {name: _volume_mount(name) for name in volumes}
    offenders = _running_or_armed_containers(volumes, containers_to_record, roots)
    if offenders:
        raise BackupError("writers_or_restart_policies_not_contained:" + ";".join(offenders))
    active = _processes_with_paths(list(roots.values()))
    if active:
        raise BackupError("host_processes_still_use_volumes:" + ";".join(active[:12]))
    metadata = _metadata(helper, containers_to_record)
    if operational_context is not None:
        metadata["operational_context"] = operational_context
    def archive_guarded(name: str, archive: Path) -> None:
        current = _running_or_armed_containers(volumes, containers_to_record, roots)
        if current:
            raise BackupError("writer_started_during_backup:" + ";".join(current))
        if _processes_with_paths(list(roots.values())):
            raise BackupError("process_started_using_volume_during_backup")
        _docker_archive(name, archive, helper)
        current = _running_or_armed_containers(volumes, containers_to_record, roots)
        if current or _processes_with_paths(list(roots.values())):
            raise BackupError("writer_or_process_detected_after_volume_copy")

    return publish_backup(roots, destination, metadata,
                          archive_guarded,
                          reserve_bytes=reserve_bytes)


def backup_live(destination: Path, helper: str = DEFAULT_HELPER, reserve_bytes: int = RESERVE_BYTES,
                operational_context: dict | None = None) -> dict:
    context = validate_context(operational_context)
    return backup_docker_volumes(destination, VOLUMES, CONTAINERS, helper, reserve_bytes, context)


def restore_isolated(backup: Path, prefix: str, helper: str = DEFAULT_HELPER) -> list[str]:
    if os.geteuid() != 0:
        raise BackupError("run_as_root_on_docker_host")
    if not prefix.startswith("odisseu-a6-restore-") or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in prefix):
        raise BackupError("restore_volume_prefix_must_be_unique_and_isolated")
    verified = verify_backup(backup)
    _ = verified
    _image_info(helper)
    created = []
    try:
        manifest = json.loads((backup / "manifest.json").read_text())
        for role in ROLES:
            item = manifest["volumes"][role]
            volume = f"{prefix}-{role}"
            existing = _run(["docker", "volume", "ls", "-q", "--filter", f"name=^{volume}$"]).split()
            if existing:
                raise BackupError(f"restore_volume_exists_refusing_overwrite:{volume}")
            _run(["docker", "volume", "create", volume])
            created.append(volume)
            archive = (Path(backup).resolve() / item["archive"]).resolve(strict=True)
            _run(["docker", "run", "--rm", "--network", "none", "--read-only",
                  "--mount", f"type=volume,src={volume},dst=/target",
                  "--mount", f"type=bind,src={archive.parent},dst=/backup,readonly",
                  "--entrypoint", "tar", helper, "--extract", f"--file=/backup/{archive.name}",
                  "--directory=/target", "--numeric-owner", "--same-owner", "--same-permissions",
                  "--xattrs", "--acls", "--selinux"], capture=True)
            restored = tree_inventory(_volume_mount(volume))
            if restored != item["inventory"]:
                raise BackupError(f"restored_tree_mismatch:{role}")
        return created
    except Exception:
        # Preserve partial restore volumes for explicit forensic/recovery handling.
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p_backup = sub.add_parser("backup")
    p_backup.add_argument("destination", type=Path)
    p_backup.add_argument("--helper-image", default=DEFAULT_HELPER)
    p_backup.add_argument("--reserve-bytes", type=int, default=RESERVE_BYTES)
    p_backup.add_argument("--context-json", type=Path, required=True)
    p_verify = sub.add_parser("verify")
    p_verify.add_argument("backup", type=Path)
    p_restore = sub.add_parser("restore-isolated")
    p_restore.add_argument("backup", type=Path)
    p_restore.add_argument("--volume-prefix", required=True)
    p_restore.add_argument("--helper-image", default=DEFAULT_HELPER)
    p_test_backup = sub.add_parser("backup-disposable", help="Backup only prefixed rehearsal volumes; never production volumes.")
    p_test_backup.add_argument("destination", type=Path)
    p_test_backup.add_argument("--volume-prefix", required=True)
    p_test_backup.add_argument("--helper-image", default=DEFAULT_HELPER)
    p_test_backup.add_argument("--reserve-bytes", type=int, default=0)
    args = parser.parse_args(argv)
    try:
        if args.command == "backup":
            context = json.loads(args.context_json.read_text())
            result = backup_live(args.destination, args.helper_image, args.reserve_bytes, context)
        elif args.command == "verify":
            result = verify_backup(args.backup)
        elif args.command == "restore-isolated":
            result = {"created_volumes": restore_isolated(args.backup, args.volume_prefix, args.helper_image)}
        else:
            if not args.volume_prefix.startswith("odisseu-a6-rehearsal-"):
                raise BackupError("disposable_backup_prefix_must_start_odisseu-a6-rehearsal-")
            volumes = tuple(f"{args.volume_prefix}{role}" for role in ROLES)
            result = backup_docker_volumes(args.destination, volumes, (), args.helper_image, args.reserve_bytes)
        print(json.dumps(result, sort_keys=True))
        return 0
    except Exception as exc:
        # Avoid printing raw subprocess commands/env; all arguments here exclude secrets.
        print(json.dumps({"ok": False, "error": str(exc)[:600]}, sort_keys=True), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
