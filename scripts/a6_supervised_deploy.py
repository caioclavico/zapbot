"""Janela supervisionada do deploy A6.

Cada passo só começa depois do anterior. A evidência vem do runner.
Um mapa montado à mão não entra. O backup restaurado fica num diretório
novo: o volume de origem não é recolocado no lugar.
"""

from __future__ import annotations

import contextvars
import hashlib
import json
import os
from pathlib import Path

from a6_maintenance_ops import (
    enter_jvm,
    enter_node,
    jvm_health,
    resume_jvm,
    resume_node,
)
from a6_mount_policy import LIVE_VOLUME, SYS_ADMIN_BIT, Collection, assess_observed
from a6_production_backup import (
    BACKUP_AUTHORIZATION,
    MARGIN,
    ProductionBackupError,
    execute_copy,
    restore_isolated,
)
from a6_production_deploy import (
    FORBIDDEN,
    DeployError,
    apply_commands,
    image_tag,
    observe_image,
    observe_vm,
    plan,
    preflight,
    recover_after,
    _prefix,
)
from a6_writer_policy import INVENTORY


WINDOW = "janela-supervisionada-a6"
RESUME_PHRASE = "confirmar-resume-a6"
SUPERVISED_STEPS = (
    "authorization",
    "preflight",
    "images",
    "node_maintenance",
    "jvm_drain",
    "quiescence",
    "stop_jvm",
    "exclusive",
    "backup",
    "manifest",
    "restore_isolated",
    "update_jvm",
    "validate_jvm",
    "update_node",
    "validate_whatsapp",
    "resume",
    "confirm_open",
    "validate_game",
)
CONTRACT = (
    "require('/app/scripts/lib/pokemon-jvm-client.cjs')"
    ".createClient({baseUrl:process.env.POKEMON_JVM_URL,token:process.env.POKEMON_JVM_TOKEN,allowDockerJvmHost:true})"
    ".health().then(function(data){if(!data||data.status!=='up')process.exit(2);process.stdout.write('up')})"
    ".catch(function(){process.exit(2)})"
)

_RUNS = set()
_DEST = contextvars.ContextVar("a6_window_destination", default=None)


def evaluate_supervised(checkpoints):
    """Só aceita a lista emitida por esta execução. Lista colada é recusada."""
    if id(checkpoints) not in _RUNS:
        raise DeployError("declared_evidence")
    names = [item.get("step") for item in checkpoints]
    if names != list(SUPERVISED_STEPS[: len(names)]):
        return {"deployComplete": False, "reason": "sequence_interrupted", "next": _next(names)}
    if len(checkpoints) < len(SUPERVISED_STEPS):
        return {
            "deployComplete": False,
            "reason": "sequence_incomplete",
            "next": SUPERVISED_STEPS[len(checkpoints)],
        }
    last = checkpoints[-1]
    complete = (
        last.get("step") == "validate_game"
        and last.get("jvmPhase") == "open"
        and last.get("nodePhase") == "open"
        and last.get("whatsapp") == "READY"
        and last.get("chromium") is True
        and last.get("jvmHealth") == "healthy"
        and last.get("nodeHealth") == "healthy"
        and last.get("dispatcher") == "open"
        and last.get("datomic") == "up"
    )
    return {
        "deployComplete": complete,
        "reason": None if complete else "not_open",
        "next": None if complete else "validate_game",
    }


def _next(names):
    if not names:
        return SUPERVISED_STEPS[0]
    if names[-1] not in SUPERVISED_STEPS:
        return "sequence_interrupted"
    index = SUPERVISED_STEPS.index(names[-1]) + 1
    return SUPERVISED_STEPS[index] if index < len(SUPERVISED_STEPS) else None


def _finish(checkpoints, nxt, blocked, commands, **extra):
    _RUNS.add(id(checkpoints))
    verdict = evaluate_supervised(checkpoints)
    report = {
        "mode": "supervised",
        "admitted": extra.pop("admitted", False),
        "deployComplete": verdict["deployComplete"],
        "executeAllowed": False,
        "steps": [item.get("step") for item in checkpoints],
        "next": None if verdict["deployComplete"] else nxt,
        "blocked": sorted(set(blocked)),
        "commands": commands,
        "datomicRestored": False,
        "sourcePreserved": True,
        "lastCompleted": checkpoints[-1].get("step") if checkpoints else None,
        "checkpoints": checkpoints,
    }
    report.update(extra)
    if "TOKEN=" in str(report.get("commands")):
        raise DeployError("secret_in_output")
    _record(report)
    return report


def _record(report):
    """Guarda o último passo fora do volume. Não grava token nem marcador."""
    destination = _DEST.get()
    if not destination or _live_marker(str(destination)):
        return
    parent = Path(destination).resolve().parent
    target = parent / "a6-window-checkpoint.json"
    if not parent.is_dir() or _live_marker(str(target)):
        return
    payload = {
        "lastCompleted": report.get("lastCompleted"),
        "next": report.get("next"),
        "blocked": report.get("blocked"),
        "datomicRestored": False,
        "deployComplete": report.get("deployComplete") is True,
    }
    try:
        target.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    except OSError:
        return


def _live_marker(path):
    text = str(path)
    return "odisseu-a6-cutover" in text or "/var/lib/docker/volumes" in text


def _avail(text):
    for line in reversed(str(text or "").splitlines()):
        stripped = line.strip()
        if stripped.isdigit():
            return int(stripped)
    return None


def _bytes(root):
    return sum(path.stat().st_size for path in Path(root).rglob("*") if path.is_file())


def _ok(completed):
    return getattr(completed, "returncode", 1) == 0


def _out(completed):
    return getattr(completed, "stdout", "") or ""


def _jvm_running(observation):
    for item in observation.get("documents") or []:
        if str(item.get("Name") or "").lstrip("/") != "odisseu-a6-jvm":
            continue
        state = item.get("State")
        if not isinstance(state, dict) or "Running" not in state:
            return None
        return bool(state.get("Running")) or state.get("Status") == "running"
    return None


def _external(processes):
    for row in INVENTORY:
        if row["transact"] and row["path"] in (processes or ""):
            return True
    return False


def _cap_sys_admin(text):
    line = next((item for item in str(text or "").splitlines() if item.startswith("CapEff:")), "")
    parts = line.split()
    if len(parts) != 2:
        return None
    try:
        return bool(int(parts[1], 16) & SYS_ADMIN_BIT)
    except ValueError:
        return None


def _ready(completed):
    if not _ok(completed):
        return False
    text = _out(completed)
    return '"whatsapp":"READY"' in text.replace(" ", "") and '"chromium":true' in text.replace(" ", "")


def _collection(observation):
    documents = []
    for item in observation["documents"]:
        copied = dict(item)
        if str(copied.get("Name") or "").lstrip("/") == "odisseu-a6-jvm":
            copied["CapEff"] = observation["jvm"].get("capEff")
        documents.append(copied)
    writes = observation["jvm"].get("activeWrites")
    ops = observation["node"].get("activeOps")
    if not isinstance(writes, int) or not isinstance(ops, int):
        raise DeployError("observation_incomplete")
    return Collection(
        source="docker-inspect-subprocess",
        documents=tuple(documents),
        processes=observation.get("processes") or "",
        jvm_phase=observation["jvm"].get("phase") or "",
        node_phase=observation["node"].get("phase") or "",
        active_jvm_writes=writes,
        active_node_ops=ops,
        volume=LIVE_VOLUME,
        authorized="odisseu-a6-jvm",
    )


def _separate(source, destination):
    source = Path(source).resolve()
    destination = Path(destination).resolve()
    if destination == source or source in destination.parents or destination in source.parents:
        raise DeployError("restore_would_replace_source")
    if _live_marker(destination):
        raise DeployError("live_path_refused")


def _restart_old_jvm(wrapped, direct):
    recovery = recover_after("jvm", ["stop"], False, direct)
    for command in recovery["commands"]:
        wrapped(command)
    return recovery


def _rollback(wrapped, service, battle, direct):
    recovery = recover_after(service, ["stop", "rename", "update", "run"], battle, direct)
    for command in recovery["commands"]:
        wrapped(command)
    return recovery


def _battle(wrapped, prefix):
    completed = wrapped(prefix + ["exec", "odisseu-a6-jvm", "cat", "/data/datomic-local/battle-format.edn"])
    return _ok(completed) and ":volatil" in _out(completed)


def _images(wrapped, sha, jvm_digest, node_digest, direct):
    images = {}
    for service, digest in (("jvm", jvm_digest), ("node", node_digest)):
        ref = image_tag(service, sha)
        image = observe_image(wrapped, ref, direct)
        if not str(image.get("revision") or "").startswith(sha):
            raise DeployError("image_revision_mismatch")
        raw = wrapped(_prefix(direct) + ["image", "inspect", "--format", "{{.Id}}", ref])
        if not _ok(raw):
            raise DeployError("image_not_observed")
        seen = str(_out(raw)).strip()
        if seen.startswith("sha256:"):
            seen = seen[7:]
        expected = str(digest).split(":", 1)[-1]
        if seen != expected:
            raise DeployError("image_digest_mismatch")
        images[service] = image
    return images


def run_supervised(runner, sha, jvm_digest, node_digest, env, destination, isolated_root=None, event=None):
    """Executa a janela. O relatório final não autoriza uma segunda execução."""
    token = _DEST.set(destination)
    try:
        return _run_supervised(runner, sha, jvm_digest, node_digest, env, destination, isolated_root, event)
    finally:
        _DEST.reset(token)


def _run_supervised(runner, sha, jvm_digest, node_digest, env, destination, isolated_root=None, event=None):
    checkpoints = []
    seen = []
    if event == "push" or os.environ.get("GITHUB_EVENT_NAME") == "push":
        raise DeployError("push_not_authorized")
    if not callable(runner):
        raise DeployError("declared_evidence")
    if os.environ.get("ODISSEU_PRODUCTION_DEPLOY_HOLD") == "true":
        return _finish(checkpoints, "preflight", ["hold_active"], seen)
    required = {
        "A6_SUPERVISED_WINDOW": WINDOW,
        "A6_EXECUTE": "1",
        "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
        "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
        "ODISSEU_A6_DELIVERY_VALIDATED": "true",
    }
    if any(env.get(key) != value for key, value in required.items()):
        reason = "hold_active" if env.get("ODISSEU_PRODUCTION_DEPLOY_HOLD") != "false" else "window_not_authorized"
        return _finish(checkpoints, "authorization", [reason], seen)
    if isolated_root is None and env.get("A6_LIVE_BACKUP") != BACKUP_AUTHORIZATION:
        return _finish(checkpoints, "authorization", ["backup_not_authorized"], seen)
    checkpoints.append({"step": "authorization", "hold": "released", "window": True})
    if _live_marker(destination) or (isolated_root is not None and _live_marker(isolated_root)):
        return _finish(checkpoints, "backup", ["live_path_refused"], seen)

    def wrapped(args):
        text = " ".join(str(part) for part in args)
        for token in FORBIDDEN:
            if token in text:
                raise DeployError("destructive_command")
        seen.append(list(args))
        return runner(args)

    try:
        observation = observe_vm(wrapped)
    except DeployError as exc:
        return _finish(checkpoints, "preflight", [str(exc)], seen)
    direct = bool(observation.get("dockerDirect"))
    blocked = preflight(observation)
    if blocked:
        return _finish(checkpoints, "preflight", blocked, seen)
    checkpoints.append({"step": "preflight"})

    try:
        images = _images(wrapped, sha, jvm_digest, node_digest, direct)
    except DeployError as exc:
        return _finish(checkpoints, "images", [str(exc)], seen)
    checkpoints.append({
        "step": "images",
        "jvmArchitecture": images["jvm"].get("architecture"),
        "nodeArchitecture": images["node"].get("architecture"),
    })

    prefix = _prefix(direct)
    try:
        node_state = enter_node(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "node_maintenance", [str(exc)], seen, admitted=True, maintenance=True)
    checkpoints.append({
        "step": "node_maintenance",
        "phase": node_state["phase"],
        "activeOperations": node_state["activeOperations"],
        "durable": True,
    })
    try:
        jvm_state = enter_jvm(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "jvm_drain", [str(exc)], seen, admitted=True, maintenance=True)
    checkpoints.append({
        "step": "jvm_drain",
        "phase": jvm_state["phase"],
        "activeWrites": jvm_state["activeWrites"],
        "admittedWrites": jvm_state["admittedWrites"],
        "completedWrites": jvm_state["completedWrites"],
        "failedWrites": jvm_state["failedWrites"],
        "rejectedWrites": jvm_state["rejectedWrites"],
    })
    confirmed = {
        "node_phase": "quiescent",
        "jvm_phase": "quiescent",
        "node_ops": 0,
        "jvm_writes": 0,
    }
    try:
        drained = observe_vm(wrapped)
        _apply_confirmed(drained, confirmed)
    except DeployError as exc:
        return _finish(checkpoints, "quiescence", [str(exc)], seen, admitted=True, maintenance=True)
    if _external(drained.get("processes")):
        return _finish(checkpoints, "quiescence", ["external_datomic_client"], seen, admitted=True, maintenance=True)
    checkpoints.append({
        "step": "quiescence",
        "jvmPhase": "quiescent",
        "nodePhase": "quiescent",
        "activeWrites": 0,
        "activeOperations": 0,
    })

    stopped_cmd = wrapped(prefix + ["stop", "--time", "30", "odisseu-a6-jvm"])
    if not _ok(stopped_cmd):
        return _finish(checkpoints, "stop_jvm", ["docker_failed"], seen, admitted=True, maintenance=True)
    try:
        stopped = observe_vm(wrapped)
    except DeployError as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "stop_jvm", [str(exc)], seen, admitted=True, maintenance=True)
    if _jvm_running(stopped) is not False:
        return _finish(checkpoints, "stop_jvm", ["writers_still_running"], seen, admitted=True, maintenance=True)
    try:
        _apply_confirmed(stopped, confirmed)
    except DeployError as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "stop_jvm", [str(exc)], seen, admitted=True, maintenance=True)
    checkpoints.append({"step": "stop_jvm"})
    try:
        exclusive_collection = _collection(stopped)
    except DeployError as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "exclusive", [str(exc)], seen, admitted=True, maintenance=True)
    exclusive = assess_observed(exclusive_collection.documents, LIVE_VOLUME, "odisseu-a6-jvm")
    if not exclusive["exclusive"]:
        _restart_old_jvm(wrapped, direct)
        return _finish(
            checkpoints,
            "exclusive",
            [exclusive.get("reason") or "writable_mount_not_exclusive"],
            seen,
            admitted=True,
            maintenance=True,
        )
    checkpoints.append({"step": "exclusive", "holders": exclusive.get("holders") or []})

    parent = str(Path(destination).resolve().parent)
    space = wrapped(["df", "-B1", "--output=avail", parent])
    free_bytes = _avail(_out(space) if _ok(space) else "")
    if free_bytes is None:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "backup", ["insufficient_space"], seen, admitted=True, maintenance=True)
    try:
        source = isolated_root if isolated_root is not None else "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data"
        _separate(source, destination)
        measured = _bytes(source) if isolated_root is not None else measured_live(wrapped, source)
        copied = execute_copy(
            _collection(stopped),
            drained["vm"],
            destination,
            "false",
            True,
            BACKUP_AUTHORIZATION,
            free_bytes,
            measured,
            isolated_root=isolated_root,
        )
    except (DeployError, ProductionBackupError, OSError) as exc:
        _restart_old_jvm(wrapped, direct)
        reason = str(exc) if str(exc) else "backup_not_verified"
        return _finish(checkpoints, "backup", [reason], seen, admitted=True, maintenance=True)
    checkpoints.append({
        "step": "backup",
        "manifestVerified": True,
        "sourcePreserved": True,
        "jvmStopped": True,
    })
    try:
        _reread_manifest(copied["manifest"], destination)
    except (DeployError, ProductionBackupError, OSError) as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "manifest", [str(exc) or "backup_invalid"], seen, admitted=True, maintenance=True)
    checkpoints.append({"step": "manifest", "manifestVerified": True})

    restore_dir = Path(destination).resolve().parent / "restore-isolated"
    try:
        _separate(isolated_root or source, restore_dir)
        restored = restore_isolated(copied["manifest"], destination, restore_dir)
    except (DeployError, ProductionBackupError, OSError) as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "restore_isolated", [str(exc) or "backup_invalid"], seen, admitted=True, maintenance=True)
    checkpoints.append({"step": "restore_isolated", "restoreIsolated": True, "restoredTo": restored["restored"]})

    backup = {
        "manifestVerified": True,
        "sourcePreserved": True,
        "restoreIsolated": True,
        "jvmStopped": True,
    }
    update = _update_service(
        wrapped, stopped, sha, "jvm", jvm_digest, images["jvm"], env, backup, False, direct, seen, checkpoints
    )
    if update is not None:
        return update
    validated = _validate_jvm(wrapped, prefix, direct, checkpoints, seen)
    if validated is not None:
        return validated

    node_update = _update_service(
        wrapped, stopped, sha, "node", node_digest, images["node"], env, backup, True, direct, seen, checkpoints
    )
    if node_update is not None:
        return node_update
    whatsapp = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    if not _ready(whatsapp):
        _rollback(wrapped, "node", False, direct)
        return _finish(checkpoints, "validate_whatsapp", ["whatsapp_not_ready"], seen, admitted=True, maintenance=True)
    checkpoints.append({"step": "validate_whatsapp", "whatsapp": "READY", "health": "healthy"})

    if env.get("A6_RESUME_EXPLICIT") != RESUME_PHRASE:
        return _finish(checkpoints, "resume", ["resume_not_explicit"], seen, admitted=True, maintenance=True)
    try:
        integrity = jvm_health(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "resume", [str(exc)], seen, admitted=True, maintenance=True)
    if integrity["phase"] != "quiescent" or integrity["activeWrites"] != 0:
        return _finish(checkpoints, "resume", ["datomic_integrity_unknown"], seen, admitted=True, maintenance=True)
    try:
        resume_jvm(wrapped, prefix)
        resume_node(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "resume", [str(exc)], seen, admitted=True, maintenance=True)
    checkpoints.append({"step": "resume", "resumeExplicit": True})
    opened = {"node_phase": "open", "jvm_phase": "open", "node_ops": 0, "jvm_writes": 0}

    try:
        final = observe_vm(wrapped)
        _apply_confirmed(final, opened)
    except DeployError as exc:
        return _finish(checkpoints, "confirm_open", [str(exc)], seen, admitted=True, maintenance=True)
    again = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    jvm_container_health = final["jvm"].get("health")
    node_container_health = final["node"].get("health")
    if not (
        final["jvm"].get("phase") == "open"
        and final["node"].get("phase") == "open"
        and _ready(again)
        and jvm_container_health == "healthy"
        and node_container_health == "healthy"
    ):
        return _finish(checkpoints, "confirm_open", ["not_open"], seen, admitted=True, maintenance=True)
    checkpoints.append({
        "step": "confirm_open",
        "jvmPhase": "open",
        "nodePhase": "open",
        "whatsapp": "READY",
        "chromium": True,
        "jvmHealth": "healthy",
        "nodeHealth": "healthy",
    })
    try:
        game = jvm_health(wrapped, prefix)
        dispatcher = resume_node(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "validate_game", [str(exc)], seen, admitted=True, maintenance=True)
    final_ready = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    if (
        game["status"] == "up"
        and game["phase"] == "open"
        and game["activeWrites"] == 0
        and dispatcher["phase"] == "open"
        and dispatcher["activeOperations"] == 0
        and _ready(final_ready)
    ):
        checkpoints.append({
            "step": "validate_game",
            "jvmPhase": "open",
            "nodePhase": "open",
            "whatsapp": "READY",
            "chromium": True,
            "jvmHealth": "healthy",
            "nodeHealth": "healthy",
            "dispatcher": "open",
            "datomic": "up",
        })
        return _finish(checkpoints, None, [], seen, admitted=True, maintenance=False)
    return _finish(checkpoints, "validate_game", ["game_not_ready"], seen, admitted=True, maintenance=False)


def _apply_confirmed(observation, confirmed):
    if observation["node"].get("phase") != confirmed["node_phase"]:
        raise DeployError("maintenance_marker_mismatch")
    if observation["jvm"].get("phase") != confirmed["jvm_phase"]:
        raise DeployError("maintenance_marker_mismatch")
    if observation["node"].get("activeOps") is None:
        observation["node"]["activeOps"] = confirmed["node_ops"]
    if observation["jvm"].get("activeWrites") is None:
        observation["jvm"]["activeWrites"] = confirmed["jvm_writes"]
    if observation["node"].get("activeOps") != confirmed["node_ops"]:
        raise DeployError("writes_in_flight")
    if observation["jvm"].get("activeWrites") != confirmed["jvm_writes"]:
        raise DeployError("writes_in_flight")


def _reread_manifest(manifest_path, destination):
    path = Path(manifest_path)
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not lines:
        raise DeployError("backup_invalid")
    root = Path(destination)
    for line in lines:
        digest, relative = line.split("  ", 1)
        if hashlib.sha256((root / relative).read_bytes()).hexdigest() != digest:
            raise DeployError("backup_invalid")


def measured_live(wrapped, source):
    completed = wrapped(["du", "-sb", source])
    if not _ok(completed):
        raise DeployError("source_unreadable")
    token = _out(completed).strip().split()
    if not token or not token[0].isdigit():
        raise DeployError("source_unreadable")
    return int(token[0])


def _update_service(wrapped, observation, sha, service, digest, image, env, backup, jvm_updated, direct, seen, checkpoints):
    planned = plan(
        observation,
        sha,
        service,
        env,
        digest=digest,
        backup=backup,
        jvm_updated=jvm_updated,
        image=image,
    )
    if not planned["executeAllowed"]:
        if service == "jvm":
            _restart_old_jvm(wrapped, direct)
        return _finish(
            checkpoints,
            "update_jvm" if service == "jvm" else "update_node",
            planned["blocked"],
            seen,
            admitted=True,
            maintenance=True,
        )
    verbs = ["stop", "rename", "update", "run", "inspect"]
    completed = []
    health = ""
    for command, verb in zip(apply_commands(planned), verbs):
        result = wrapped(command)
        if not _ok(result):
            recovery = recover_after(service, completed, False, direct)
            for item in recovery["commands"]:
                wrapped(item)
            return _finish(
                checkpoints,
                "update_jvm" if service == "jvm" else "update_node",
                ["docker_failed"],
                seen,
                admitted=True,
                maintenance=True,
                recovery=recovery["mode"],
            )
        completed.append(verb)
        if verb == "inspect":
            health = _out(result).strip()
    if "run" in completed:
        checkpoints.append({
            "step": "update_jvm" if service == "jvm" else "update_node",
            "service": service,
            "mountsPreserved": True,
            "jvmUntouched": service == "node",
        })
    if health != "healthy":
        battle = _battle(wrapped, _prefix(direct)) if service == "jvm" else False
        recovery = _rollback(wrapped, service, battle, direct)
        reason = "rollback_incompatible_battle_edn" if battle else "health_unhealthy"
        return _finish(
            checkpoints,
            "validate_jvm" if service == "jvm" else "validate_whatsapp",
            [reason],
            seen,
            admitted=True,
            maintenance=True,
            recovery=recovery["mode"],
        )
    return None


def _validate_jvm(wrapped, prefix, direct, checkpoints, seen):
    entry = wrapped(prefix + ["inspect", "--format", "{{json .Config.Cmd}}{{json .Config.Entrypoint}}", "odisseu-a6-jvm"])
    privileged = wrapped(prefix + ["inspect", "--format", "{{.HostConfig.Privileged}}", "odisseu-a6-jvm"])
    status = wrapped(prefix + ["exec", "odisseu-a6-jvm", "cat", "/proc/self/status"])
    contract = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "-e", CONTRACT])
    cap = _cap_sys_admin(_out(status) if _ok(status) else "")
    reason = None
    if not _ok(entry) or "jvm-storage-entrypoint.sh" not in _out(entry):
        reason = "entrypoint_missing"
    elif not _ok(privileged) or _out(privileged).strip() != "false":
        reason = "privileged_or_sys_admin"
    elif cap is None or cap is True:
        reason = "privileged_or_sys_admin"
    elif not _ok(contract) or _out(contract).strip() != "up":
        reason = "contract_incompatible"
    if reason is None:
        checkpoints.append({
            "step": "validate_jvm",
            "health": "healthy",
            "entrypoint": True,
            "capDropSysAdmin": True,
            "privileged": False,
            "contract": "compatible",
        })
        return None
    battle = _battle(wrapped, prefix)
    recovery = _rollback(wrapped, "jvm", battle, direct)
    if battle:
        reason = "rollback_incompatible_battle_edn"
    return _finish(
        checkpoints,
        "validate_jvm",
        [reason],
        seen,
        admitted=True,
        maintenance=True,
        recovery=recovery["mode"],
    )


def main(env, runner, sha, jvm_digest, node_digest, destination, isolated_root=None):
    """Entrada da janela. Runner ausente ou push na master não executa."""
    if not callable(runner):
        raise DeployError("declared_evidence")
    try:
        report = run_supervised(
            runner,
            sha,
            jvm_digest,
            node_digest,
            env,
            destination,
            isolated_root,
            event=env.get("GITHUB_EVENT_NAME"),
        )
    except DeployError as exc:
        return 2, {"deployComplete": False, "executeAllowed": False, "blocked": [str(exc)], "datomicRestored": False}
    return (0 if report["deployComplete"] else 2), report
