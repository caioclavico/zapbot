"""Transferência das imagens AMD64 para a VM.

Salva e carrega as imagens. Não cria container, não apaga imagem antiga e
não mexe em volume. O digest é lido de novo depois do load.
"""

from __future__ import annotations

from a6_production_deploy import DeployError, _prefix, _run, observe_image


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
