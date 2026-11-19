"""Runner do stack real odisseu-a6. Dry-run é o padrão.

Não usa Compose como fonte. Não remove volume, não chama compose down e
não passa --remove-orphans. A execução só é admitida com hold solto,
autorização, entrega validada, um serviço por vez e a coleta ao vivo.
Este módulo não abre Docker por conta própria.
"""

from __future__ import annotations

import json

from a6_mount_policy import LIVE_VOLUME, assess, assess_observed
from a6_production_contract import (
    CURRENT,
    JVM_MOUNTS,
    NODE_MOUNTS,
    mounts_match,
    vm_matches,
)
from a6_writer_policy import INVENTORY


FORBIDDEN = ("volume rm", "volume prune", "compose down", "--remove-orphans", "system prune")
STEPS = (
    "node_maintenance",
    "drain",
    "quiescence",
    "backup",
    "update_jvm",
    "validate_jvm",
    "update_node",
    "validate_whatsapp",
    "resume",
    "confirm_open",
)


class DeployError(Exception):
    pass


def image_tag(kind, sha):
    if len(sha) < 12 or any(char not in "0123456789abcdef" for char in sha):
        raise DeployError("invalid_sha")
    if kind == "jvm":
        return f"odisseu-jvm:a6-{sha[:12]}-amd64"
    if kind == "node":
        return f"odisseu-node:a6-{sha[:12]}"
    raise DeployError("service_required")


def _valid_digest(digest):
    text = str(digest or "")
    if text.startswith("sha256:"):
        text = text[7:]
    return len(text) == 64 and all(char in "0123456789abcdef" for char in text)


def image_ref(kind, sha, digest):
    tag = image_tag(kind, sha)
    if not _valid_digest(digest):
        return tag
    hexpart = str(digest).split(":", 1)[-1]
    return f"{tag}@sha256:{hexpart}"


def _safe(argv):
    text = " ".join(argv)
    for token in FORBIDDEN:
        if token in text:
            raise DeployError("destructive_command")
    return argv


def _prefix(docker_direct):
    if docker_direct:
        return ["docker"]
    return ["sudo", "-n", "docker"]


def _socket_exposed(mounts):
    for mount in mounts or []:
        haystack = " ".join(str(mount.get(key) or "") for key in ("source", "destination", "name"))
        if "docker.sock" in haystack:
            return True
    return False


def recreate_argv(service, image, live, docker_direct=False):
    prefix = _prefix(docker_direct)
    if service == "jvm":
        if not mounts_match(live["mounts"], JVM_MOUNTS):
            raise DeployError("mount_mismatch")
        if _socket_exposed(live.get("mounts")):
            raise DeployError("docker_socket_exposed")
        argv = prefix + [
            "run", "-d",
            "--name", "odisseu-a6-jvm",
            "--pull", "never",
            "--network", live["network"],
            "--restart", live["restart"],
            "--memory", str(live["memory"]),
            "--publish", live["publish"],
            "--user", live["user"] or "root",
            "--cap-drop", "SYS_ADMIN",
            "--security-opt", "no-new-privileges:true",
            "--mount", "type=volume,source=odisseu-a6-cutover-datomic,target=/data/datomic-local",
            "--mount", "type=volume,source=odisseu-a6-cutover-journal,target=/data/journal",
            "--mount", "type=volume,source=odisseu-a6-cutover-maint,target=/data/maint",
            "--mount", "type=bind,source=/run/odisseu-control,target=/run/odisseu-control",
            "--env-file", "<inspect-ao-vivo-sem-log>",
            image,
        ]
    elif service == "node":
        if not mounts_match(live["mounts"], NODE_MOUNTS):
            raise DeployError("mount_mismatch")
        if "ALL" not in (live.get("capDrop") or []):
            raise DeployError("node_caps_not_preserved")
        if "no-new-privileges:true" not in (live.get("security") or []):
            raise DeployError("node_security_not_preserved")
        if _socket_exposed(live.get("mounts")):
            raise DeployError("docker_socket_exposed")
        argv = prefix + [
            "run", "-d",
            "--name", "odisseu-a6-node",
            "--pull", "never",
            "--network", live["network"],
            "--restart", live["restart"],
            "--memory", str(live["memory"]),
            "--publish", live["publish"],
            "--user", live["user"],
            "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges:true",
            "--security-opt", "<seccomp-copiado-do-inspect-sem-log>",
            "--mount", "type=bind,source=/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core,target=/app/.wwebjs_auth_a6_cutover",
            "--mount", "type=volume,source=odisseu-a6-cutover-data,target=/app/data",
            "--env-file", "<inspect-ao-vivo-sem-log>",
            image,
        ]
    else:
        raise DeployError("service_required")
    return _safe(argv)


def _exclusive(documents, cap_eff):
    copied = []
    for document in documents:
        item = dict(document)
        item["CapEff"] = cap_eff
        copied.append(item)
    return assess_observed(copied, LIVE_VOLUME, "odisseu-a6-jvm")


def preflight(observation):
    reasons = []
    if not vm_matches(observation.get("vm") or {}):
        reasons.append("vm_mismatch")
    jvm = observation.get("jvm") or {}
    node = observation.get("node") or {}
    if jvm.get("name") != "odisseu-a6-jvm" or node.get("name") != "odisseu-a6-node":
        reasons.append("container_mismatch")
    if not mounts_match(jvm.get("mounts") or [], JVM_MOUNTS):
        reasons.append("jvm_mount_mismatch")
    if not mounts_match(node.get("mounts") or [], NODE_MOUNTS):
        reasons.append("node_mount_mismatch")
    if _socket_exposed(jvm.get("mounts")) or _socket_exposed(node.get("mounts")):
        reasons.append("docker_socket_exposed")
    for key in ("ODISSEU_BRIDGE_TOKEN", "ODISSEU_BRIDGE_STORAGE"):
        if key not in (jvm.get("envKeys") or []):
            reasons.append("jvm_env_missing")
    for key in ("POKEMON_JVM_TOKEN", "WWEBJS_DATA_PATH"):
        if key not in (node.get("envKeys") or []):
            reasons.append("node_env_missing")
    if jvm.get("privileged") or node.get("privileged"):
        reasons.append("privileged_or_sys_admin")
    if "SYS_ADMIN" in (jvm.get("capAdd") or []) or "SYS_ADMIN" in (node.get("capAdd") or []):
        reasons.append("privileged_or_sys_admin")
    exclusive = _exclusive(
        observation.get("documents") or [_document(jvm), _document(node)],
        jvm.get("capEff"),
    )
    if not exclusive["exclusive"]:
        reasons.append(exclusive["reason"] or "not_exclusive")
    if assess(observation.get("documents") or [], LIVE_VOLUME, "odisseu-a6-jvm")["reason"] != "live_volume_refused":
        reasons.append("lab_gate_removed")
    return reasons


def _document(service):
    mounts = []
    for mount in service.get("mounts") or []:
        mounts.append({
            "Name": mount.get("name"),
            "Source": mount.get("source"),
            "Destination": mount.get("destination"),
            "RW": mount.get("rw", True),
        })
    return {
        "Id": service.get("imageId") or "",
        "Name": "/" + service.get("name", ""),
        "Mounts": mounts,
        "HostConfig": {
            "Privileged": bool(service.get("privileged")),
            "CapAdd": service.get("capAdd") or [],
        },
    }


def execute_allowed(env, reasons, service):
    if service not in ("jvm", "node"):
        return False
    required = {
        "A6_EXECUTE": "1",
        "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
        "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
        "ODISSEU_A6_DELIVERY_VALIDATED": "true",
    }
    if any(env.get(key) != value for key, value in required.items()):
        return False
    return not reasons


def plan(observation, sha, service, env=None, digest=None, backup=None, jvm_updated=False, image=None):
    env = env or {}
    reasons = preflight(observation)
    if observation.get("jvm", {}).get("phase") != "quiescent":
        reasons.append("jvm_not_quiescent")
    if observation.get("node", {}).get("phase") not in ("draining", "quiescent", "frozen"):
        reasons.append("node_not_draining")
    for row in INVENTORY:
        if row["transact"] and row["path"] in (observation.get("processes") or ""):
            reasons.append("external_datomic_client")
    if not observation.get("_observed"):
        reasons.append("declared_evidence")
    if service == "node" and not jvm_updated:
        reasons.append("jvm_not_updated")
    if not _valid_digest(digest):
        reasons.append("image_digest_missing")
    if not isinstance(image, dict) or not image.get("_observed"):
        reasons.append("image_not_observed")
    elif image.get("architecture") != "amd64":
        reasons.append("image_not_amd64")
    elif not str(image.get("revision") or "").startswith(sha):
        reasons.append("image_revision_mismatch")
    verified = (
        isinstance(backup, dict)
        and backup.get("manifestVerified") is True
        and backup.get("sourcePreserved") is True
        and backup.get("restoreIsolated") is True
        and backup.get("jvmStopped") is True
    )
    if not verified:
        reasons.append("backup_not_verified")
    direct = bool(observation.get("dockerDirect"))
    try:
        tag = image_tag(service, sha)
        argv = recreate_argv(service, image_ref(service, sha, digest), observation[service], direct)
    except DeployError as exc:
        reasons.append(str(exc))
        tag = ""
        argv = []
    allowed = execute_allowed(env, reasons, service)
    previous = observation.get(service, {}).get("image")
    rollback = []
    if previous and argv:
        try:
            rollback = recreate_argv(service, previous, observation[service], direct)
        except DeployError as exc:
            reasons.append(str(exc))
            allowed = False
    return {
        "executeAllowed": allowed,
        "deployComplete": False,
        "dryRun": not allowed,
        "dockerDirect": direct,
        "service": service,
        "previousImage": previous,
        "previousDigest": observation.get(service, {}).get("imageId") or "",
        "nextImage": tag,
        "imageDigest": digest if _valid_digest(digest) else "",
        "argv": argv,
        "rollbackArgv": rollback,
        "blocked": sorted(set(reasons)),
        "oneService": service,
        "jvmBeforeNode": service == "jvm" or bool(jvm_updated),
        "untouched": "odisseu-a6-node" if service == "jvm" else "odisseu-a6-jvm",
    }


def _names(service):
    name = "odisseu-a6-node" if service == "node" else "odisseu-a6-jvm"
    return name, name + "-previous", name + "-failed"


def _guard_start(command):
    if len(command) >= 2 and command[-2] == "start" and (
        command[-1].endswith("-previous") or command[-1].endswith("-failed")
    ):
        raise DeployError("parallel_start_refused")
    return _safe(command)


def apply_commands(report):
    """Para o container antigo antes de criar o novo. Os dois não ficam ativos."""
    if not report.get("executeAllowed"):
        raise DeployError("execute_refused")
    name, previous, _failed = _names(report["service"])
    prefix = _prefix(report.get("dockerDirect"))
    commands = [
        prefix + ["stop", "--time", "30", name],
        prefix + ["rename", name, previous],
        prefix + ["update", "--restart=no", previous],
        report["argv"],
        prefix + ["inspect", "--format", "{{.State.Health.Status}}", name],
    ]
    for command in commands:
        _guard_start(command)
    return commands


def rollback_commands(service, docker_direct=False):
    name, previous, failed = _names(service)
    prefix = _prefix(docker_direct)
    commands = [
        prefix + ["stop", "--time", "30", name],
        prefix + ["rename", name, failed],
        prefix + ["update", "--restart=no", failed],
        prefix + ["rename", previous, name],
        prefix + ["start", name],
    ]
    for command in commands:
        _guard_start(command)
    return commands


def recover_after(service, completed, battle_written=False, docker_direct=False):
    """Interrupção entre stop, rename, run e health deixa um único writer."""
    name, previous, failed = _names(service)
    prefix = _prefix(docker_direct)
    if battle_written:
        commands = [
            prefix + ["update", "--restart=no", previous],
            prefix + ["update", "--restart=no", failed],
        ]
        return {"mode": "forward_only", "maintenance": True, "commands": commands}
    if "run" in completed:
        return {"mode": "rollback", "maintenance": True, "commands": rollback_commands(service, docker_direct)}
    if "rename" in completed:
        commands = [
            prefix + ["rename", previous, name],
            prefix + ["start", name],
        ]
        return {"mode": "rollback", "maintenance": True, "commands": [_guard_start(item) for item in commands]}
    if "stop" in completed:
        return {"mode": "rollback", "maintenance": True, "commands": [_guard_start(prefix + ["start", name])]}
    return {"mode": "unchanged", "maintenance": False, "commands": []}


def run_apply(runner, report, health="healthy", fail_at=None, battle_written=False):
    """Para, renomeia e só então cria. Falha no meio devolve um plano recuperável."""
    commands = apply_commands(report)
    executed = []
    completed = []
    verbs = ["stop", "rename", "update", "run", "inspect"]
    for command, verb in zip(commands, verbs):
        if fail_at == verb:
            recovery = recover_after(report["service"], completed, battle_written, report.get("dockerDirect"))
            for item in recovery["commands"]:
                runner(item)
                executed.append(item)
            recovery["commands"] = executed
            recovery["applied"] = False
            recovery["rolledBack"] = recovery["mode"] == "rollback"
            return recovery
        executed.append(command)
        completed.append(verb)
        completed_run = runner(command)
        if getattr(completed_run, "returncode", 1) != 0:
            raise DeployError("docker_failed")
        if verb == "run" and health != "healthy":
            if battle_written:
                recovery = recover_after(report["service"], completed, True, report.get("dockerDirect"))
                for item in recovery["commands"]:
                    runner(item)
                    executed.append(item)
                recovery["commands"] = executed
                recovery["applied"] = False
                recovery["rolledBack"] = False
                return recovery
            for item in rollback_commands(report["service"], report.get("dockerDirect")):
                executed.append(item)
                runner(item)
            return {"applied": False, "rolledBack": True, "mode": "rollback", "maintenance": True, "commands": executed}
    return {"applied": True, "rolledBack": False, "mode": "updated", "maintenance": False, "commands": executed}


def rollback_allowed(evidence):
    if evidence.get("newBattleFormatWritten"):
        raise DeployError("rollback_incompatible_battle_edn")
    if not evidence.get("previousImage"):
        raise DeployError("rollback_image_missing")
    if evidence.get("otherServiceChanged"):
        raise DeployError("rollback_touched_other_service")
    return rollback_commands(evidence["service"], evidence.get("dockerDirect", False))


def evaluate_sequence(checkpoints):
    names = [item.get("step") for item in checkpoints]
    if names != list(STEPS[: len(names)]):
        return {"deployComplete": False, "reason": "sequence_interrupted", "next": _next(names)}
    for item in checkpoints:
        if not _step_ok(item):
            return {"deployComplete": False, "reason": item.get("reason") or item["step"], "next": item["step"]}
    if len(checkpoints) < len(STEPS):
        return {"deployComplete": False, "reason": "sequence_incomplete", "next": STEPS[len(checkpoints)]}
    last = checkpoints[-1]
    complete = (
        last.get("jvmPhase") == "open"
        and last.get("nodePhase") == "open"
        and last.get("whatsapp") == "READY"
        and last.get("jvmHealth") == "healthy"
        and last.get("nodeHealth") == "healthy"
    )
    return {"deployComplete": complete, "reason": None if complete else "not_open", "next": None if complete else "confirm_open"}


def _next(names):
    if not names:
        return STEPS[0]
    if names[-1] not in STEPS:
        return "sequence_interrupted"
    index = STEPS.index(names[-1]) + 1
    return STEPS[index] if index < len(STEPS) else None


def _step_ok(item):
    step = item.get("step")
    if step == "node_maintenance":
        return item.get("nodePhase") == "draining"
    if step == "drain":
        return item.get("activeNodeOps") == 0 and item.get("activeJvmWrites") == 0
    if step == "quiescence":
        return item.get("jvmPhase") == "quiescent" and item.get("exclusive") is True and not item.get("externalWriters")
    if step == "backup":
        return (
            item.get("manifestVerified") is True
            and item.get("sourcePreserved") is True
            and item.get("restoreIsolated") is True
            and item.get("jvmStopped") is True
        )
    if step == "update_jvm":
        return item.get("service") == "jvm" and item.get("previousImage") and item.get("mountsPreserved") is True
    if step == "validate_jvm":
        return (
            item.get("health") == "healthy"
            and item.get("entrypoint") is True
            and item.get("capDropSysAdmin") is True
            and item.get("privileged") is False
        )
    if step == "update_node":
        return item.get("service") == "node" and item.get("jvmUntouched") is True
    if step == "validate_whatsapp":
        return item.get("whatsapp") == "READY" and item.get("health") == "healthy"
    if step == "resume":
        return item.get("resumeExplicit") is True
    if step == "confirm_open":
        return item.get("jvmPhase") == "open" and item.get("nodePhase") == "open" and item.get("whatsapp") == "READY"
    return False


def _run(runner, args):
    if not callable(runner):
        raise DeployError("declared_evidence")
    completed = runner(args)
    if getattr(completed, "returncode", 1) != 0:
        return None
    return completed.stdout or ""


def _publish(bindings, container_port):
    spec = (bindings or {}).get(container_port) or []
    if not spec:
        return ""
    host = spec[0].get("HostIp") or "127.0.0.1"
    return f"{host}:{spec[0].get('HostPort')}:{container_port.split('/')[0]}"


def _security(options):
    normalized = []
    for option in options or []:
        if str(option).startswith("seccomp="):
            normalized.append("seccomp")
        else:
            normalized.append(option)
    return normalized


def _mounts(document):
    mounts = []
    for mount in document.get("Mounts") or []:
        mounts.append({
            "type": mount.get("Type"),
            "name": mount.get("Name") or "",
            "source": mount.get("Source") or "",
            "destination": mount.get("Destination"),
            "rw": mount.get("RW", True),
        })
    return mounts


def _service(document, publish):
    host = document.get("HostConfig") or {}
    config = document.get("Config") or {}
    keys = []
    for item in config.get("Env") or []:
        keys.append(str(item).split("=", 1)[0])
    return {
        "name": str(document.get("Name") or "").lstrip("/"),
        "image": config.get("Image") or "",
        "imageId": document.get("Image") or "",
        "user": config.get("User") or "",
        "network": host.get("NetworkMode") or "",
        "restart": (host.get("RestartPolicy") or {}).get("Name") or "",
        "privileged": bool(host.get("Privileged")),
        "capAdd": host.get("CapAdd") or [],
        "capDrop": host.get("CapDrop") or [],
        "security": _security(host.get("SecurityOpt")),
        "publish": publish,
        "memory": host.get("Memory") or 0,
        "health": ((document.get("State") or {}).get("Health") or {}).get("Status") or "",
        "mounts": _mounts(document),
        "envKeys": keys,
    }


def observe_vm(runner):
    """Lê inspect, CapEff e marcadores pelo runner. Mapa declarado não entra."""
    if not callable(runner):
        raise DeployError("declared_evidence")
    direct = _run(runner, ["docker", "info"]) is not None
    if not direct and _run(runner, ["sudo", "-n", "docker", "info"]) is None:
        raise DeployError("docker_unavailable")
    prefix = _prefix(direct)
    raw = _run(runner, prefix + ["inspect", "odisseu-a6-jvm", "odisseu-a6-node"])
    status = _run(runner, prefix + ["exec", "odisseu-a6-jvm", "cat", "/proc/self/status"])
    processes = _run(runner, prefix + ["exec", "odisseu-a6-jvm", "ps", "-eo", "args"])
    jvm_marker = _run(runner, prefix + ["exec", "odisseu-a6-jvm", "cat", "/data/datomic-local/.a6-maintenance/bridge-maintenance.edn"])
    node_marker = _run(runner, prefix + ["exec", "odisseu-a6-node", "cat", "/app/data/a6-maintenance.json"])
    hostname = _run(runner, ["hostname"])
    metadata = _run(runner, ["metadata"])
    if None in (raw, status, processes, jvm_marker, node_marker, hostname, metadata):
        raise DeployError("observation_incomplete")
    try:
        documents = json.loads(raw)
        node_state = json.loads(node_marker)
        vm = json.loads(metadata)
    except ValueError as exc:
        raise DeployError("observation_incomplete") from exc
    if not isinstance(node_state, dict) or not isinstance(vm, dict):
        raise DeployError("observation_incomplete")
    if not isinstance(documents, list) or len(documents) != 2:
        raise DeployError("observation_incomplete")
    by_name = {str(item.get("Name") or "").lstrip("/"): item for item in documents}
    if "odisseu-a6-jvm" not in by_name or "odisseu-a6-node" not in by_name:
        raise DeployError("container_mismatch")
    cap_line = next((line for line in status.splitlines() if line.startswith("CapEff:")), "")
    cap_eff = cap_line.split()[1] if len(cap_line.split()) == 2 else ""
    vm["hostname"] = hostname.strip()
    jvm = _service(by_name["odisseu-a6-jvm"], _publish(by_name["odisseu-a6-jvm"].get("HostConfig", {}).get("PortBindings"), "18090/tcp"))
    node = _service(by_name["odisseu-a6-node"], _publish(by_name["odisseu-a6-node"].get("HostConfig", {}).get("PortBindings"), "3001/tcp"))
    jvm["capEff"] = cap_eff
    jvm["phase"] = (_token(jvm_marker, ":phase") or "").lstrip(":")
    jvm["activeWrites"] = _int_token(jvm_marker, ":active-writes")
    jvm["activeWritesMarked"] = ":active-writes" in jvm_marker
    node["phase"] = str(node_state.get("phase") or "")
    node["activeOps"] = node_state.get("activeOps") if isinstance(node_state.get("activeOps"), int) else None
    node["activeOpsMarked"] = "activeOps" in node_state
    node["whatsapp"] = ""
    redacted = []
    for item in documents:
        copied = dict(item)
        config = dict(item.get("Config") or {})
        config.pop("Env", None)
        copied["Config"] = config
        redacted.append(copied)
    observation = {
        "_observed": True,
        "source": "docker-inspect-subprocess",
        "dockerDirect": direct,
        "vm": vm,
        "jvm": jvm,
        "node": node,
        "processes": processes,
        "documents": redacted,
    }
    serialized = json.dumps(observation)
    if "TOKEN=" in serialized or "super-secret" in serialized:
        raise DeployError("secret_in_observation")
    return observation


def _token(text, key):
    if key not in text:
        return ""
    words = text.split(key, 1)[1].strip().split()
    return words[0].rstrip(",}") if words else ""


def _int_token(text, key):
    raw = _token(text, key).lstrip(":")
    if raw == "":
        return None
    try:
        return int(raw)
    except ValueError:
        return None


def observe_image(runner, ref, docker_direct=False):
    """Lê arquitetura e revisão da imagem pelo runner. Um mapa declarado não serve."""
    if not callable(runner):
        raise DeployError("declared_evidence")
    raw = _run(runner, _prefix(docker_direct) + ["image", "inspect", "--format", "{{.Architecture}}\t{{index .Config.Labels \"org.opencontainers.image.revision\"}}", ref])
    if not raw or "\t" not in raw:
        raise DeployError("image_not_observed")
    architecture, revision = raw.split("\t", 1)
    architecture = architecture.strip()
    revision = revision.strip()
    if architecture != "amd64":
        raise DeployError("image_not_amd64")
    return {"_observed": True, "architecture": architecture, "revision": revision, "ref": ref}


def current_dry_run(sha):
    """Plano a partir do contrato lido na VM, sem contactar Docker de novo."""
    report = plan(CURRENT, sha, "jvm", {})
    sequence = evaluate_sequence([{
        "step": "node_maintenance",
        "nodePhase": CURRENT["node"]["phase"],
    }])
    report["sequence"] = sequence
    report["observedAt"] = "2026-11-18T20:50:00Z"
    report["liveRecheck"] = False
    return report
