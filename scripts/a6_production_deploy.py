"""Runner do stack real odisseu-a6. Dry-run é o padrão.

Não usa Compose como fonte. Não remove volume, não chama compose down e
não passa --remove-orphans. A execução só é admitida com hold solto,
autorização, entrega validada, um serviço por vez e a coleta ao vivo.
Este módulo não abre Docker por conta própria.
"""

from __future__ import annotations

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


def _safe(argv):
    text = " ".join(argv)
    for token in FORBIDDEN:
        if token in text:
            raise DeployError("destructive_command")
    return argv


def recreate_argv(service, image, live):
    if service == "jvm":
        if not mounts_match(live["mounts"], JVM_MOUNTS):
            raise DeployError("mount_mismatch")
        argv = [
            "docker", "run", "-d",
            "--name", "odisseu-a6-jvm",
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
        argv = [
            "docker", "run", "-d",
            "--name", "odisseu-a6-node",
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


def plan(observation, sha, service, env=None):
    env = env or {}
    reasons = preflight(observation)
    if observation.get("jvm", {}).get("phase") != "quiescent":
        reasons.append("jvm_not_quiescent")
    if observation.get("node", {}).get("phase") not in ("draining", "quiescent", "frozen"):
        reasons.append("node_not_draining")
    for row in INVENTORY:
        if row["transact"] and row["path"] in (observation.get("processes") or ""):
            reasons.append("external_datomic_client")
    try:
        tag = image_tag(service, sha)
        argv = recreate_argv(service, tag, observation[service])
    except DeployError as exc:
        reasons.append(str(exc))
        tag = ""
        argv = []
    allowed = execute_allowed(env, reasons, service)
    previous = observation.get(service, {}).get("image")
    return {
        "executeAllowed": allowed,
        "deployComplete": False,
        "dryRun": not allowed,
        "service": service,
        "previousImage": previous,
        "nextImage": tag,
        "argv": argv,
        "rollbackArgv": recreate_argv(service, previous, observation[service]) if previous and argv else [],
        "blocked": sorted(set(reasons)),
        "oneService": service,
        "untouched": "odisseu-a6-node" if service == "jvm" else "odisseu-a6-jvm",
    }


def apply_commands(report):
    """Comandos que o shell só pode emitir quando executeAllowed é verdadeiro."""
    if not report.get("executeAllowed"):
        raise DeployError("execute_refused")
    service = report["service"]
    name = "odisseu-a6-node" if service == "node" else "odisseu-a6-jvm"
    previous = name + "-previous"
    commands = [
        ["docker", "rename", name, previous],
        report["argv"],
        ["docker", "inspect", "--format", "{{.State.Health.Status}}", name],
    ]
    for command in commands:
        _safe(command)
    return commands


def run_apply(runner, report, health="healthy"):
    """Emite rename e run só quando o plano já foi admitido. Não decide o gate."""
    commands = apply_commands(report)
    executed = []
    for command in commands[:-1]:
        executed.append(command)
        completed = runner(command)
        if getattr(completed, "returncode", 1) != 0:
            raise DeployError("docker_failed")
    if health != "healthy":
        for command in rollback_commands(report["service"]):
            executed.append(command)
            runner(command)
        return {"applied": False, "rolledBack": True, "commands": executed}
    executed.append(commands[-1])
    runner(commands[-1])
    return {"applied": True, "rolledBack": False, "commands": executed}


def rollback_allowed(evidence):
    if evidence.get("newBattleFormatWritten"):
        raise DeployError("rollback_incompatible_battle_edn")
    if not evidence.get("previousImage"):
        raise DeployError("rollback_image_missing")
    if evidence.get("otherServiceChanged"):
        raise DeployError("rollback_touched_other_service")
    return rollback_commands(evidence["service"])


def rollback_commands(service):
    name = "odisseu-a6-jvm" if service == "jvm" else "odisseu-a6-node"
    previous = name + "-previous"
    commands = [
        ["docker", "rename", name, name + "-failed"],
        ["docker", "rename", previous, name],
        ["docker", "start", name],
    ]
    for command in commands:
        _safe(command)
    return commands


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
        return item.get("manifestVerified") is True and item.get("sourcePreserved") is True
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
