"""Transferência das imagens AMD64 para a VM.

Salva e carrega as imagens. Não cria container, não apaga imagem antiga e
não mexe em volume. O digest é lido de novo depois do load.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import tarfile

from a6_production_deploy import DeployError, _prefix, _run, observe_image, image_tag
from a6_production_contract import JVM_MOUNTS, NODE_MOUNTS
from a6_capacity import budget


CANDIDATES = (
    {
        "service": "jvm",
        "ref": "odisseu-jvm:a6-faedebd50361-amd64",
        "digest": "sha256:4be43c6b5e26c37691ce002ec6458b60b8186f1409b6e1ec519e82fc5c667891",
        "revision": "faedebd5036124eb4f946147c5012c48122acc69",
    },
    {
        "service": "node",
        "ref": "odisseu-node:a6-faedebd50361",
        "digest": "sha256:3dc29e8f07f1a615e6aff529f6effd0b2257bc123fd3cc58e2671d7adeb5af5e",
        "revision": "faedebd5036124eb4f946147c5012c48122acc69",
    },
)

PREVIOUS_IMAGES = (
    "sha256:fe92b76796b46dd172e388174827f1e1a7169124c29a6ad381db3f003d345858",
    "sha256:c495184169fb5259816e7c6c7eae0c67e2198dbc08883aebaff249f7d15dba7f",
)

ALLOWED_VERBS = {"save", "load", "image", "inspect", "info"}


def _verb(command):
    if "docker" not in command:
        return str(command[0]) if command else ""
    return command[command.index("docker") + 1] if command.index("docker") + 1 < len(command) else ""


def _refuse(command):
    verb = _verb(command)
    if verb not in ALLOWED_VERBS:
        raise DeployError("destructive_command")
    text = " ".join(str(part) for part in command)
    for token in ("volume rm", "volume prune", "compose down", "rmi", "--remove-orphans"):
        if token in text:
            raise DeployError("destructive_command")
    return command


def _digest(text):
    raw = str(text or "").strip()
    if raw.startswith("sha256:"):
        raw = raw[7:]
    return raw


def transfer_commands(archive, refs, docker_direct=False):
    """Comandos de save e load. Nenhum deles inicia container."""
    archive_text = str(archive)
    if "odisseu-a6-cutover" in archive_text or "/var/lib/docker/volumes" in archive_text:
        raise DeployError("live_path_refused")
    prefix = _prefix(docker_direct)
    commands = [
        prefix + ["save", "-o", archive_text, *refs],
        prefix + ["load", "-i", archive_text],
    ]
    for ref in refs:
        commands.append(prefix + ["image", "inspect", "--format", "{{.Id}}", ref])
    for command in commands:
        _refuse(command)
    return commands


def execute_transfer(runner, archive, refs, docker_direct=False, execute=False):
    """Carrega o arquivo e devolve os ids lidos depois. Não apaga imagem."""
    if not callable(runner):
        raise DeployError("declared_evidence")
    if execute is not True:
        raise DeployError("dry_run")
    commands = transfer_commands(archive, refs, docker_direct)
    loaded = []
    for command in commands:
        completed = runner(_refuse(command))
        if getattr(completed, "returncode", 1) != 0:
            raise DeployError("docker_failed")
        if _verb(command) == "image":
            loaded.append((command[-1], str(getattr(completed, "stdout", "") or "").strip()))
    return {"commands": commands, "loaded": loaded, "started": False, "removed": False}


def verify_transfer(runner, ref, digest, revision, preserve_ids, docker_direct=False):
    """Confere arquitetura, revisão, digest e a presença das imagens antigas."""
    if not callable(runner):
        raise DeployError("declared_evidence")
    image = observe_image(runner, ref, docker_direct)
    if not str(image.get("revision") or "").startswith(str(revision)):
        raise DeployError("image_revision_mismatch")
    raw = _run(
        runner,
        _prefix(docker_direct) + ["image", "inspect", "--format", "{{.Id}}", ref],
    )
    if _digest(raw) != _digest(digest):
        raise DeployError("image_digest_mismatch")
    for previous in preserve_ids:
        seen = _run(
            runner,
            _prefix(docker_direct) + ["image", "inspect", "--format", "{{.Id}}", previous],
        )
        if _digest(seen) != _digest(previous):
            raise DeployError("previous_image_missing")
    return {
        "ref": ref,
        "digest": "sha256:" + _digest(digest),
        "architecture": image["architecture"],
        "revision": image["revision"],
        "started": False,
        "previousPreserved": True,
    }


def _sha(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(data)
    return h.hexdigest()


def _json_sha(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def _docker(args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=1200)
    if result.returncode:
        raise DeployError("image_transport_process_failed")
    return result.stdout


def _image(ref):
    docs = json.loads(_docker(["image", "inspect", ref]))
    if len(docs) != 1:
        raise DeployError("image_transport_identity_invalid")
    return docs[0]


def _content(doc):
    return _json_sha({k: doc[k] for k in ("Architecture", "Os", "Config", "RootFS", "Created")})


def runtime_contract(service):
    flags = ({"POKEMON_ADAPTER": "jvm", "PLATFORM_ADAPTER": "datomic"}
             if service == "node" else
             {"POKEMON_PROGRESSIVE_XP_V3": "true", "ADVENTURE_AUTO_HATCH": "true",
              "ODISSEU_BRIDGE_RAID_AUTO_TICK": "true", "ODISSEU_BRIDGE_ALLOW_TEST_RNG": "false",
              "ODISSEU_BRIDGE_SEED": "false", "ODISSEU_BRIDGE_RESET": "false"})
    return {"mounts": list(NODE_MOUNTS if service == "node" else JVM_MOUNTS), "flags": flags}


def _directory(path):
    path = Path(path).absolute()
    if (path.resolve() != path or not path.is_dir()
            or "/var/lib/docker/volumes" in str(path) or "odisseu-a6-cutover" in str(path)):
        raise DeployError("image_transport_path_invalid")
    return path


def image_storage_bytes(archive, reported_bytes):
    expanded = 0
    with tarfile.open(archive, "r:") as source:
        for member in source:
            if not member.isfile():
                continue
            with source.extractfile(member) as stream:
                magic = stream.read(4)
                stream.seek(0)
                if magic == b"\x28\xb5\x2f\xfd":
                    raise DeployError("image_storage_zstd_measurement_unsupported")
                if magic[:2] == b"\x1f\x8b":
                    with gzip.GzipFile(fileobj=stream) as layer:
                        expanded += sum(len(chunk) for chunk in iter(lambda: layer.read(1024 * 1024), b""))
                else:
                    expanded += member.size
    # Containerd pode reportar Size comprimido; Docker clássico, expandido.
    # Reservar ambos evita depender da implementação do daemon de destino.
    return reported_bytes + expanded


def export_artifact(directory, service, sha, ref):
    directory = _directory(directory)
    if (service not in ("node", "jvm") or not re.fullmatch(r"[0-9a-f]{40}", sha)
            or ref != image_tag(service, sha)):
        raise DeployError("image_transport_request_invalid")
    doc = _image(ref)
    if (doc["Architecture"] != "amd64" or doc["Os"] != "linux"
            or doc["Config"]["Labels"].get("org.opencontainers.image.revision") != sha):
        raise DeployError("image_transport_revision_mismatch")
    archive = directory / (service + ".tar.gz")
    manifest = directory / (service + ".json")
    if archive.exists() or manifest.exists():
        raise DeployError("image_transport_output_exists")
    with tempfile.TemporaryDirectory(prefix="image-export-", dir=directory) as work:
        tar = Path(work) / "image.tar"
        _docker(["save", "-o", str(tar), ref])
        storage_bytes = image_storage_bytes(tar, doc["Size"])
        with tar.open("rb") as src, archive.open("xb") as dst:
            with gzip.GzipFile(filename="", mode="wb", fileobj=dst, mtime=0) as zipped:
                shutil.copyfileobj(src, zipped)
    value = {"schema": 1, "service": service, "revision": sha, "platform": "linux/amd64",
             "ref": ref, "archive": archive.name, "archiveSha256": _sha(archive),
             "archiveBytes": archive.stat().st_size, "imageBytes": storage_bytes,
             "reportedImageBytes": doc["Size"],
             "sourceImageId": doc["Id"], "contentSha256": _content(doc),
             "runtimeContract": runtime_contract(service)}
    manifest.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")
    return value


def validate_artifacts(directory, sha, expected_manifest_hashes):
    directory = _directory(directory)
    if (not re.fullmatch(r"[0-9a-f]{40}", sha)
            or set(expected_manifest_hashes) != {"node", "jvm"}):
        raise DeployError("image_transport_pair_required")
    rows = []
    for service in ("node", "jvm"):
        manifest = directory / (service + ".json")
        if not manifest.is_file() or manifest.is_symlink() or _sha(manifest) != expected_manifest_hashes[service]:
            raise DeployError("image_transport_manifest_mismatch")
        value = json.loads(manifest.read_text())
        archive = directory / (service + ".tar.gz")
        if (value["schema"] != 1 or value["service"] != service or value["revision"] != sha
                or value["platform"] != "linux/amd64" or value["archive"] != archive.name
                or value["ref"] != image_tag(service, sha)
                or not archive.is_file() or archive.is_symlink() or archive.stat().st_size != value["archiveBytes"]
                or _sha(archive) != value["archiveSha256"]
                or value["runtimeContract"] != runtime_contract(service)
                or not re.fullmatch(r"sha256:[0-9a-f]{64}", value["sourceImageId"])
                or not re.fullmatch(r"[0-9a-f]{64}", value["contentSha256"])
                or any(type(value[k]) is not int or value[k] <= 0
                       for k in ("archiveBytes", "imageBytes"))):
            raise DeployError("image_transport_artifact_mismatch")
        rows.append(value)
    return rows


def import_artifacts(directory, sha, manifest_hashes, datomic_bytes, rollback_ids,
                     rollback_sha, authorize=False):
    if (authorize is not True or len(set(rollback_ids)) != 2
            or not re.fullmatch(r"[0-9a-f]{40}", rollback_sha)):
        raise DeployError("image_import_not_authorized")
    rows = validate_artifacts(directory, sha, manifest_hashes)
    before = {}
    for ref in rollback_ids:
        doc = _image(ref)
        if (doc["Id"] != ref or doc["Architecture"] != "amd64" or doc["Os"] != "linux"
                or doc["Config"]["Labels"].get("org.opencontainers.image.revision") != rollback_sha):
            raise DeployError("image_import_rollback_incompatible")
        before[ref] = doc["Id"]
    docker_root = _docker(["info", "--format", "{{.DockerRootDir}}"]).strip()
    free = min(shutil.disk_usage(directory).free, shutil.disk_usage(docker_root).free,
               shutil.disk_usage("/var/backups").free)
    capacity = budget(free, datomic_bytes,
                      sum(v["archiveBytes"] for v in rows), sum(v["imageBytes"] for v in rows))
    # Recusar sobrescrita de tag desconhecida, inclusive numa retomada.
    names = set(_docker(["image", "ls", "--format", "{{.Repository}}:{{.Tag}}"]).splitlines())
    for value in rows:
        if value["ref"] in names and _content(_image(value["ref"])) != value["contentSha256"]:
            raise DeployError("image_import_tag_conflict")
    loaded = []
    for value in rows:
        _docker(["load", "-i", str(Path(directory) / value["archive"])])
        doc = _image(value["ref"])
        if (doc["Architecture"] != "amd64" or doc["Os"] != "linux"
                or doc["Config"]["Labels"].get("org.opencontainers.image.revision") != sha
                or _content(doc) != value["contentSha256"]):
            raise DeployError("image_import_content_mismatch")
        loaded.append({"service": value["service"], "imageId": doc["Id"],
                       "sourceImageId": value["sourceImageId"], "contentSha256": _content(doc)})
    if any(_image(ref)["Id"] != identity for ref, identity in before.items()):
        raise DeployError("image_import_rollback_changed")
    validate_artifacts(directory, sha, manifest_hashes)
    return {"revision": sha, "loaded": loaded, "capacity": capacity,
            "started": False, "rollbackPreserved": True}


def main():
    parser = argparse.ArgumentParser(description="Artefatos A6; nunca inicia containers")
    sub = parser.add_subparsers(dest="action", required=True)
    export = sub.add_parser("export")
    export.add_argument("--service", choices=("node", "jvm"), required=True)
    export.add_argument("--ref", required=True)
    for p in (export, sub.add_parser("import")):
        p.add_argument("--directory", required=True)
        p.add_argument("--sha", required=True)
    load = sub.choices["import"]
    load.add_argument("--node-manifest-sha256", required=True)
    load.add_argument("--jvm-manifest-sha256", required=True)
    load.add_argument("--datomic-bytes", type=int, required=True)
    load.add_argument("--rollback-id", action="append", required=True)
    load.add_argument("--rollback-sha", required=True)
    load.add_argument("--authorization", required=True)
    a = parser.parse_args()
    if a.action == "export":
        value = export_artifact(a.directory, a.service, a.sha, a.ref)
    else:
        value = import_artifacts(a.directory, a.sha,
            {"node": a.node_manifest_sha256, "jvm": a.jvm_manifest_sha256},
            a.datomic_bytes, a.rollback_id, a.rollback_sha,
            a.authorization == "autorizar-importacao-imagens-a6")
    print(json.dumps(value, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (DeployError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        reason = str(exc) if isinstance(exc, DeployError) else type(exc).__name__
        print("image_transport_blocked:" + reason, file=sys.stderr)
        sys.exit(2)
