"""Janela supervisionada do deploy A6.

Cada passo só começa depois do anterior. A evidência vem do runner.
Um mapa montado à mão não entra. O backup restaurado fica num diretório
novo: o volume de origem não é recolocado no lugar.

Toda mudança na VM grava a intenção antes e a conclusão depois de
observar o resultado. Na retomada, intenção aberta é resolvida olhando a
VM; o que não dá para provar bloqueia em vez de repetir.
"""

from __future__ import annotations

import contextvars
import copy
import hashlib
import json
import os
import time
import re
from pathlib import Path

import a6_v3_release_gate as v3_release

from a6_maintenance_ops import (
    enter_jvm,
    enter_node,
    jvm_health,
    jvm_status,
    node_marker_phase,
    node_status,
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
    image_ref,
    image_tag,
    observe_image,
    observe_vm,
    plan,
    preflight,
    recover_after,
    recreate_argv,
    _prefix,
    _publish,
    _service,
)
from a6_window_journal import NullJournal, atomic_write_json
from a6_writer_policy import INVENTORY


WINDOW = "janela-supervisionada-a6"
RESUME_PHRASE = "confirmar-resume-a6"
HEALTH_WAIT_SECONDS = 180
HEALTH_POLL_SECONDS = 5
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
    "v3_release_gate",
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
QUIESCENT = {"node_phase": "quiescent", "jvm_phase": "quiescent"}
OPEN = {"node_phase": "open", "jvm_phase": "open"}
REPEATABLE = {"hold_release", "hold_restore", "node_maintenance", "jvm_drain", "resume_jvm", "resume_node"}
JOURNAL_ERRORS = {"checkpoint_write_failed", "checkpoint_pending_unresolved", "checkpoint_sequence_mismatch"}
STOPPED_STATES = {"exited", "created", "dead"}
PLACEHOLDER = re.compile(r"<[^<>]+>")

_RUNS = set()
_DEST = contextvars.ContextVar("a6_window_destination", default=None)
_META = contextvars.ContextVar("a6_window_meta", default=None)
_JOURNAL = contextvars.ContextVar("a6_window_journal", default=None)


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
        and (not checkpoints[0].get("v3ReleaseRequired")
             or any(item.get("step") == "v3_release_gate"
                    and item.get("verified") is True
                    for item in checkpoints))
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


def _j():
    return _JOURNAL.get() or NullJournal()


def _mark(checkpoints, item):
    """O passo só conta depois de gravado no diário."""
    _j().step(item)
    checkpoints.append(item)


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
    try:
        _record(report)
    except DeployError as exc:
        report["deployComplete"] = False
        report["blocked"] = sorted(set(report["blocked"]) | {str(exc)})
        report["next"] = report["next"] or "validate_game"
    return report


def _record(report):
    """Guarda o último passo fora do volume. Gravação recusada vira bloqueio."""
    journal = _JOURNAL.get()
    if journal is not None:
        journal.update(
            lastCompleted=report.get("lastCompleted"),
            next=report.get("next"),
            blocked=report.get("blocked"),
            deployComplete=report.get("deployComplete") is True,
        )
        return
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
        "steps": report.get("steps") or [],
    }
    meta = _META.get() or {}
    if meta.get("windowId"):
        payload["windowId"] = meta["windowId"]
    if meta.get("vmHostname"):
        payload["vmHostname"] = meta["vmHostname"]
    payload["holdRestored"] = meta.get("holdRestored") is True
    atomic_write_json(target, payload)


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


def _count(value):
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


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


def _live_counters(wrapped, prefix):
    """Contadores da API autenticada. O marcador durável não carrega todos."""
    return {"node": node_status(wrapped, prefix), "jvm": jvm_status(wrapped, prefix)}


def _apply_confirmed(observation, confirmed, live=None):
    """Fase do marcador, fase da API e contadores. Ausente nunca vira zero."""
    node = observation.get("node") or {}
    jvm = observation.get("jvm") or {}
    if node.get("phase") != confirmed["node_phase"] or jvm.get("phase") != confirmed["jvm_phase"]:
        raise DeployError("maintenance_marker_mismatch")
    if node.get("activeOpsMarked") and not _count(node.get("activeOps")):
        raise DeployError("quiescence_counters_unreadable")
    if jvm.get("activeWritesMarked") and not _count(jvm.get("activeWrites")):
        raise DeployError("quiescence_counters_unreadable")
    closed = confirmed["jvm_phase"] != "open"
    if closed and _count(node.get("activeOps")) and node["activeOps"] != 0:
        raise DeployError("writes_in_flight")
    if closed and _count(jvm.get("activeWrites")) and jvm["activeWrites"] != 0:
        raise DeployError("writes_in_flight")
    if not isinstance(live, dict) or not isinstance(live.get("node"), dict) or not isinstance(live.get("jvm"), dict):
        raise DeployError("quiescence_counters_missing")
    node_live = live["node"]
    jvm_live = live["jvm"]
    if node_live.get("phase") != confirmed["node_phase"] or jvm_live.get("phase") != confirmed["jvm_phase"]:
        raise DeployError("maintenance_phase_mismatch")
    if node_live.get("durable") is not True:
        raise DeployError("maintenance_marker_unreadable")
    counters = (
        node_live.get("activeOperations"),
        jvm_live.get("activeWrites"),
        jvm_live.get("admittedWrites"),
        jvm_live.get("completedWrites"),
        jvm_live.get("failedWrites"),
    )
    if not all(_count(value) for value in counters):
        raise DeployError("quiescence_counters_missing")
    ops, writes, admitted, completed, failed = counters
    if closed and (ops != 0 or writes != 0):
        raise DeployError("writes_in_flight")
    if closed and failed != 0:
        raise DeployError("jvm_writes_failed")
    if admitted != completed + failed + writes:
        raise DeployError("jvm_writes_incomplete")
    return {
        "activeOperations": ops,
        "activeWrites": writes,
        "admittedWrites": admitted,
        "completedWrites": completed,
        "failedWrites": failed,
    }


def _volume_holders(wrapped, prefix):
    """Todo container que monta o Datomic, rodando ou não."""
    completed = wrapped(prefix + ["ps", "-a", "--filter", "volume=" + LIVE_VOLUME, "--format", "{{.Names}}\t{{.State}}"])
    if not _ok(completed):
        raise DeployError("holders_unreadable")
    holders = []
    for line in _out(completed).splitlines():
        if not line.strip():
            continue
        parts = line.strip().split("\t")
        if len(parts) != 2 or not parts[0] or not parts[1]:
            raise DeployError("holders_unreadable")
        holders.append((parts[0].lstrip("/"), parts[1].strip()))
    return holders


def _single_writer(holders, expected):
    """expected é o único que pode estar ativo. Os outros ficam parados."""
    active = sorted(name for name, state in holders if state not in STOPPED_STATES)
    if active != sorted(expected):
        raise DeployError("datomic_writable" if not expected else "writable_mount_not_exclusive")
    return active


def _collection(observation, live):
    documents = []
    for item in observation["documents"]:
        copied = dict(item)
        if str(copied.get("Name") or "").lstrip("/") == "odisseu-a6-jvm":
            copied["CapEff"] = observation["jvm"].get("capEff")
        documents.append(copied)
    if not isinstance(live, dict):
        raise DeployError("quiescence_counters_missing")
    writes = (live.get("jvm") or {}).get("activeWrites")
    ops = (live.get("node") or {}).get("activeOperations")
    if not _count(writes) or not _count(ops):
        raise DeployError("quiescence_counters_missing")
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


def _observe_stopped(wrapped, prefix, drained):
    """JVM parada não aceita exec. Inspect e o marcador do Node bastam."""
    raw = wrapped(prefix + ["inspect", "odisseu-a6-jvm", "odisseu-a6-node"])
    if not _ok(raw):
        raise DeployError("observation_incomplete")
    try:
        documents = json.loads(_out(raw))
    except ValueError as exc:
        raise DeployError("observation_incomplete") from exc
    if not isinstance(documents, list) or len(documents) != 2:
        raise DeployError("observation_incomplete")
    by_name = {str(item.get("Name") or "").lstrip("/"): item for item in documents}
    if "odisseu-a6-jvm" not in by_name or "odisseu-a6-node" not in by_name:
        raise DeployError("container_mismatch")
    marker = wrapped(prefix + ["exec", "odisseu-a6-node", "cat", "/app/data/a6-maintenance.json"])
    if not _ok(marker):
        raise DeployError("maintenance_marker_unreadable")
    jvm = _service(by_name["odisseu-a6-jvm"], _publish(by_name["odisseu-a6-jvm"].get("HostConfig", {}).get("PortBindings"), "18090/tcp"))
    node = _service(by_name["odisseu-a6-node"], _publish(by_name["odisseu-a6-node"].get("HostConfig", {}).get("PortBindings"), "3001/tcp"))
    jvm["capEff"] = drained["jvm"].get("capEff")
    jvm["phase"] = drained["jvm"].get("phase")
    jvm["phaseObservedBeforeStop"] = True
    jvm["activeWrites"] = None
    node["phase"] = node_marker_phase(_out(marker))
    node["activeOps"] = None
    redacted = []
    for item in documents:
        copied = dict(item)
        config = dict(item.get("Config") or {})
        config.pop("Env", None)
        copied["Config"] = config
        redacted.append(copied)
    return {
        "_observed": True,
        "source": "docker-inspect-subprocess",
        "dockerDirect": drained.get("dockerDirect"),
        "vm": drained["vm"],
        "jvm": jvm,
        "node": node,
        "processes": drained.get("processes") or "",
        "documents": redacted,
    }


def _separate(source, destination):
    source = Path(source).resolve()
    destination = Path(destination).resolve()
    if destination == source or source in destination.parents or destination in source.parents:
        raise DeployError("restore_would_replace_source")
    if _live_marker(destination):
        raise DeployError("live_path_refused")


def _recover(wrapped, op, recovery):
    """Recuperação também registra intenção. Diário quebrado: não mexe mais na VM."""
    journal = _j()
    if journal.broken:
        recovery["skipped"] = "checkpoint_write_failed"
        return recovery
    journal.intent(op, mode=recovery["mode"])
    results = []
    for command in recovery["commands"]:
        completed = wrapped(command)
        results.append(_ok(completed))
        if not _ok(completed):
            break
    journal.done(op, ok=all(results), commands=len(results))
    return recovery


def _restart_old_jvm(wrapped, direct):
    return _recover(wrapped, "restart_old_jvm", recover_after("jvm", ["stop"], False, direct))


def _rollback(wrapped, service, battle, direct):
    recovery = recover_after(service, ["stop", "rename", "update", "run"], battle, direct)
    return _recover(wrapped, "rollback_" + service, recovery)


def _battle(wrapped, prefix):
    completed = wrapped(prefix + ["exec", "odisseu-a6-jvm", "cat", "/data/datomic-local/battle-format.edn"])
    return _ok(completed) and ":volatil" in _out(completed)


def _digest_hex(value):
    text = str(value or "").strip()
    return text[7:] if text.startswith("sha256:") else text


def _images(wrapped, sha, jvm_digest, node_digest, direct):
    """Tag, Image ID e a referência exata que o docker run vai usar."""
    images = {}
    for service, digest in (("jvm", jvm_digest), ("node", node_digest)):
        ref = image_tag(service, sha)
        image = observe_image(wrapped, ref, direct)
        if not str(image.get("revision") or "").startswith(sha):
            raise DeployError("image_revision_mismatch")
        expected = str(digest).split(":", 1)[-1]
        for probe in (ref, image_ref(service, sha, digest)):
            raw = wrapped(_prefix(direct) + ["image", "inspect", "--format", "{{.Id}}", probe])
            if not _ok(raw):
                raise DeployError("image_not_observed")
            if _digest_hex(_out(raw)) != expected:
                raise DeployError("image_digest_mismatch")
        image["runRef"] = image_ref(service, sha, digest)
        images[service] = image
    return images


def _unresolved(observation, sha, jvm_digest, node_digest, materialize):
    """O argv do run não pode levar placeholder. Sem resolvedor, para antes do hold."""
    direct = bool(observation.get("dockerDirect"))
    for service, digest in (("jvm", jvm_digest), ("node", node_digest)):
        argv = recreate_argv(service, image_ref(service, sha, digest), observation[service], direct)
        if materialize is not None and hasattr(materialize, "preflight"):
            materialize.preflight(service)
        if PLACEHOLDER.search(" ".join(argv)) and materialize is None:
            return "run_argv_unresolved"
    return None


def run_supervised(
    runner, sha, jvm_digest, node_digest, env, destination, isolated_root=None, event=None,
    hold_gate=None, prior=None, window_id=None, journal=None, materialize=None,
):
    """Executa a janela. O relatório final não autoriza uma segunda execução."""
    # O destino live é root-only. Relatórios sem WindowJournal ficam no estado
    # do operador, nunca ao lado do backup administrativo.
    record_destination = destination
    if isolated_root is None and str(destination).startswith("/var/backups/odisseu-a6/"):
        record_destination = str(Path(env.get("A6_STATE_DIR") or "~/.odisseu-a6-window").expanduser() / "report")
    token = _DEST.set(record_destination)
    meta = {"windowId": window_id, "vmHostname": None, "holdRestored": False}
    meta_token = _META.set(meta)
    journal_token = _JOURNAL.set(journal)
    checkpoints = []
    seen = []
    try:
        return _run_supervised(
            runner, sha, jvm_digest, node_digest, env, destination, isolated_root, event, hold_gate, prior, meta,
            materialize, checkpoints, seen,
        )
    except DeployError as exc:
        if str(exc) not in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, _next([item.get("step") for item in checkpoints]), [str(exc)], seen,
                       admitted=True, maintenance=True)
    finally:
        _JOURNAL.reset(journal_token)
        _META.reset(meta_token)
        _DEST.reset(token)


def _op(op, call, **detail):
    """Intenção, chamada e conclusão. Erro observado fecha a intenção como falha."""
    journal = _j()
    journal.intent(op, **detail)
    try:
        result = call()
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        journal.done(op, ok=False, reason=str(exc))
        raise
    journal.done(op, ok=True)
    return result


def _run_supervised(runner, sha, jvm_digest, node_digest, env, destination, isolated_root, event, hold_gate, prior, meta,
                    materialize, checkpoints, seen):
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
    if isinstance(prior, dict) and prior.get("deployComplete") is True:
        return _finish(checkpoints, "authorization", ["window_already_complete"], seen)
    try:
        v3_required = v3_release.release_mode(env, isolated_root)
        rehearsal = (v3_release.verify_rehearsal(env, sha, jvm_digest, node_digest)
                     if v3_required else None)
    except DeployError as exc:
        return _finish(checkpoints, "authorization", [str(exc)], seen)
    prior = copy.deepcopy(prior) if isinstance(prior, dict) else None
    _mark(checkpoints, {"step": "authorization", "window": True,
                        "v3ReleaseRequired": v3_required})
    if _live_marker(destination) or (isolated_root is not None and _live_marker(isolated_root)):
        return _finish(checkpoints, "backup", ["live_path_refused"], seen)

    def wrapped(args):
        text = " ".join(str(part) for part in args)
        for token in FORBIDDEN:
            if token in text:
                raise DeployError("destructive_command")
        seen.append(list(args))
        return runner(args)

    pending = (prior or {}).get("pending") if isinstance((prior or {}).get("pending"), dict) else None
    try:
        observation = observe_vm(wrapped)
    except DeployError as exc:
        if pending or (_prior_has(prior, "stop_jvm") and not _prior_has(prior, "validate_jvm")):
            return _finish(checkpoints, "preflight", ["checkpoint_indeterminate"], seen,
                           pendingOp=(pending or {}).get("op"), observed=str(exc))
        return _finish(checkpoints, "preflight", [str(exc)], seen)
    direct = bool(observation.get("dockerDirect"))
    prefix = _prefix(direct)
    meta["vmHostname"] = (observation.get("vm") or {}).get("hostname")
    if isinstance(prior, dict) and prior.get("vmHostname") and prior.get("vmHostname") != meta["vmHostname"]:
        return _finish(checkpoints, "preflight", ["checkpoint_incompatible"], seen)
    if pending:
        try:
            prior, resolution = _reconcile(wrapped, prefix, prior, pending, observation,
                                           jvm_digest, node_digest, rehearsal)
        except DeployError as exc:
            if str(exc) in JOURNAL_ERRORS:
                raise
            return _finish(checkpoints, "preflight", [str(exc)], seen, pendingOp=pending.get("op"))
        resolution_detail = ({"v3ReleaseEvidence": prior["v3ReleaseEvidence"]}
                             if resolution == "v3_receipt_observed" else {})
        _j().resolve(resolution, **resolution_detail)
    if _prior_has(prior, "update_jvm") and not _image_matches(observation, "jvm", jvm_digest):
        # update_jvm marcado + imagem antiga = rollback por health; retenta o replace.
        failed = _failed_digest(wrapped, prefix, "jvm")
        expected = str(jvm_digest).split("sha256:")[-1]
        if failed == expected and (observation.get("jvm") or {}).get("health") == "healthy":
            prior = _clear_rolled_back_update(prior, "jvm")
            _j().update(steps=prior.get("steps"), lastCompleted=prior.get("lastCompleted"),
                        next=prior.get("next"), blocked=[])
            _j().note("update_jvm_rolled_back_retry", failedDigest=failed)
        else:
            return _finish(checkpoints, "images", ["checkpoint_indeterminate"], seen)
    if _prior_has(prior, "update_node") and not _image_matches(observation, "node", node_digest):
        failed = _failed_digest(wrapped, prefix, "node")
        expected = str(node_digest).split("sha256:")[-1]
        if failed == expected and (observation.get("node") or {}).get("health") == "healthy":
            prior = _clear_rolled_back_update(prior, "node")
            _j().update(steps=prior.get("steps"), lastCompleted=prior.get("lastCompleted"),
                        next=prior.get("next"), blocked=[])
            _j().note("update_node_rolled_back_retry", failedDigest=failed)
        else:
            return _finish(checkpoints, "images", ["checkpoint_indeterminate"], seen)
    if isolated_root is not None and Path(destination).exists() and not _prior_has(prior, "manifest"):
        return _finish(checkpoints, "backup", ["checkpoint_indeterminate"], seen)
    blocked = preflight(observation)
    if not blocked:
        try:
            blocked = _leftovers(wrapped, prefix, prior)
        except DeployError as exc:
            blocked = [str(exc)]
    if blocked:
        return _finish(checkpoints, "preflight", blocked, seen)
    _mark(checkpoints, {"step": "preflight", "vmHostname": meta.get("vmHostname")})

    try:
        if isolated_root is None:
            from a6_admin_backup_client import preflight as backup_preflight
            try:
                backup_preflight(destination)
            except (ProductionBackupError, OSError) as exc:
                raise DeployError('backup_admin_not_ready') from exc
        images = _images(wrapped, sha, jvm_digest, node_digest, direct)
        unresolved = _unresolved(observation, sha, jvm_digest, node_digest, materialize)
    except DeployError as exc:
        return _finish(checkpoints, "images", [str(exc)], seen)
    if unresolved:
        return _finish(checkpoints, "images", [unresolved], seen)
    _mark(checkpoints, {
        "step": "images",
        "jvmArchitecture": images["jvm"].get("architecture"),
        "nodeArchitecture": images["node"].get("architecture"),
        "jvmRunRef": images["jvm"]["runRef"],
        "nodeRunRef": images["node"]["runRef"],
    })

    if v3_required and _prior_has(prior, "v3_release_gate") and not _resumed(prior):
        return _forward_after_v3_gate(
            wrapped, prefix, observation, jvm_digest, node_digest,
            hold_gate, meta, checkpoints, seen, prior, rehearsal, env,
        )

    if _resumed(prior) and _prior_has(prior, "update_jvm") and _prior_has(prior, "update_node"):
        forward = _forward_after_resume(wrapped, prefix, observation, jvm_digest, node_digest, hold_gate, meta, checkpoints, seen, prior, rehearsal)
        if forward is not None:
            return forward
        if v3_required:
            return _finish(checkpoints, "resume", ["v3_resume_state_indeterminate"], seen,
                           admitted=True, maintenance=True)

    if hold_gate is not None:
        try:
            if hold_gate.read() != "true":
                return _finish(checkpoints, "node_maintenance", ["hold_not_protected"], seen)
            _j().intent("hold_release")
            hold_gate.release()
            observed_hold = hold_gate.read()
            if observed_hold != "false":
                return _finish(checkpoints, "node_maintenance", ["hold_release_unconfirmed"], seen)
            _j().done("hold_release", hold=observed_hold)
        except DeployError as exc:
            if str(exc) in JOURNAL_ERRORS:
                raise
            return _finish(checkpoints, "node_maintenance", [str(exc)], seen)

    try:
        node_state = _op("node_maintenance", lambda: enter_node(wrapped, prefix))
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "node_maintenance", [str(exc)], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {
        "step": "node_maintenance",
        "phase": node_state["phase"],
        "activeOperations": node_state["activeOperations"],
        "durable": True,
    })
    try:
        jvm_state = _op("jvm_drain", lambda: enter_jvm(wrapped, prefix))
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "jvm_drain", [str(exc)], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {
        "step": "jvm_drain",
        "phase": jvm_state["phase"],
        "activeWrites": jvm_state["activeWrites"],
        "admittedWrites": jvm_state["admittedWrites"],
        "completedWrites": jvm_state["completedWrites"],
        "failedWrites": jvm_state["failedWrites"],
        "rejectedWrites": jvm_state["rejectedWrites"],
    })
    try:
        drained = observe_vm(wrapped)
        quiesced = _live_counters(wrapped, prefix)
        counters = _apply_confirmed(drained, QUIESCENT, quiesced)
        if not str(drained.get("processes") or "").strip():
            raise DeployError("processes_unreadable")
        holders = _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
        exclusive = assess_observed(_collection(drained, quiesced).documents, LIVE_VOLUME, "odisseu-a6-jvm")
        if not exclusive["exclusive"]:
            raise DeployError(exclusive.get("reason") or "writable_mount_not_exclusive")
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "quiescence", [str(exc)], seen, admitted=True, maintenance=True)
    if _external(drained.get("processes")):
        return _finish(checkpoints, "quiescence", ["external_datomic_client"], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {
        "step": "quiescence",
        "jvmPhase": "quiescent",
        "nodePhase": "quiescent",
        "markerPhases": True,
        "holders": holders,
        **counters,
    })

    jvm_kept = _prior_has(prior, "update_jvm") and _image_matches(drained, "jvm", jvm_digest)
    if jvm_kept:
        kept = _accept_replaced_jvm(wrapped, prefix, checkpoints, destination, isolated_root, drained, quiesced)
        if kept is not None:
            return _finish(checkpoints, "backup", [kept], seen, admitted=True, maintenance=True)
        stopped = drained
        backup = {
            "manifestVerified": True,
            "sourcePreserved": True,
            "restoreIsolated": True,
            "jvmStopped": True,
        }
        validated = _validate_jvm(wrapped, prefix, direct, checkpoints, seen)
        if validated is not None:
            return validated
    else:
        replaced = _stop_and_backup(
            wrapped, prefix, drained, quiesced, destination, isolated_root, direct, seen, checkpoints,
        )
        if replaced[0] is not None:
            return replaced[0]
        stopped, backup = replaced[1], replaced[2]
        update = _update_service(
            wrapped, stopped, sha, "jvm", jvm_digest, images["jvm"], env, backup, False, direct, seen, checkpoints,
            materialize,
        )
        if update is not None:
            return update
        validated = _validate_jvm(wrapped, prefix, direct, checkpoints, seen)
        if validated is not None:
            return validated

    node_kept = _prior_has(prior, "update_node") and _image_matches(drained, "node", node_digest)
    if node_kept:
        _mark(checkpoints, {"step": "update_node", "service": "node", "observed": True, "jvmUntouched": True})
    else:
        node_update = _update_service(
            wrapped, stopped, sha, "node", node_digest, images["node"], env, backup, True, direct, seen, checkpoints,
            materialize,
        )
        if node_update is not None:
            return node_update
    whatsapp = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    if not _ready(whatsapp):
        _rollback(wrapped, "node", False, direct)
        return _finish(checkpoints, "validate_whatsapp", ["whatsapp_not_ready"], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {"step": "validate_whatsapp", "whatsapp": "READY", "health": "healthy"})

    if v3_required:
        try:
            before = _op("v3_release_prepare", lambda: v3_release.prepare(wrapped, prefix))
            _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
            v3_release.validate_pre_audit(before)
            candidate = v3_release.preview(wrapped, prefix)
            # Durable BEFORE the first possible Datomic CAS. This stores the
            # baseline audit and exact pre-CAS fingerprint for crash recovery.
            _j().intent(
                "v3_release_reconcile",
                before=before,
                snapshotSha256=candidate["snapshotSha256"],
                receiptId=v3_release.RECEIPT_ID,
            )
            evidence = v3_release.commit_once(wrapped, prefix, before, candidate=candidate)
            _j().done("v3_release_reconcile", ok=True, receiptId=evidence["receiptId"])
            _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
        except DeployError as exc:
            if str(exc) in JOURNAL_ERRORS:
                raise
            return _finish(checkpoints, "v3_release_gate", [str(exc)], seen,
                           admitted=True, maintenance=True)
        _mark(checkpoints, {
            "step": "v3_release_gate",
            "verified": True,
            "pokemonCount": evidence["pokemonCount"],
            "protectedSha256": evidence["protectedSha256"],
            "receiptId": evidence["receiptId"],
            "rehearsalManifestSha256": rehearsal["manifestSha256"],
        })
    else:
        _mark(checkpoints, {"step": "v3_release_gate", "verified": False,
                            "reason": "not-applicable"})

    if hold_gate is not None:
        try:
            _j().intent("hold_restore")
            hold_gate.restore()
            observed_hold = hold_gate.read()
            _j().done("hold_restore", hold=observed_hold)
        except DeployError as exc:
            if str(exc) in JOURNAL_ERRORS:
                raise
            return _finish(checkpoints, "resume", [str(exc)], seen, admitted=True, maintenance=True)
        meta["holdRestored"] = observed_hold == "true"
        if meta["holdRestored"] is not True:
            return _finish(checkpoints, "resume", ["hold_not_restored"], seen, admitted=True, maintenance=True)
    if env.get("A6_RESUME_EXPLICIT") != RESUME_PHRASE:
        return _finish(checkpoints, "resume", ["resume_not_explicit"], seen, admitted=True, maintenance=True)
    try:
        integrity = jvm_health(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "resume", [str(exc)], seen, admitted=True, maintenance=True)
    required_phase = "frozen" if v3_required else "quiescent"
    if integrity["phase"] != required_phase or integrity["activeWrites"] != 0:
        return _finish(checkpoints, "resume", ["datomic_integrity_unknown"], seen, admitted=True, maintenance=True)
    try:
        _op("resume_jvm", lambda: resume_jvm(wrapped, prefix))
        _op("resume_node", lambda: resume_node(wrapped, prefix))
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "resume", [str(exc)], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {"step": "resume", "resumeExplicit": True,
                        "v3GateVerified": v3_required})
    return _confirm_and_validate(wrapped, prefix, checkpoints, seen)


def _resumed(prior):
    return _prior_has(prior, "resume") or (isinstance(prior, dict) and prior.get("resumeAttempted") is True)


def _forward_after_v3_gate(wrapped, prefix, observation, jvm_digest, node_digest,
                           hold_gate, meta, checkpoints, seen, prior, rehearsal, env):
    """Read-only post-CAS proof, then resume. NEVER repeat backup, image
    replacement or Charmander CAS after a verified v3 gate."""
    evidence = (prior or {}).get("v3ReleaseEvidence")
    required_steps = list(SUPERVISED_STEPS[:16])
    if (not rehearsal or not v3_release.valid_checkpoint(evidence, rehearsal)
            or not all(_prior_has(prior, name) for name in required_steps)
            or not _image_matches(observation, "jvm", jvm_digest)
            or not _image_matches(observation, "node", node_digest)):
        return _finish(checkpoints, "v3_release_gate", ["v3_resume_evidence_invalid"], seen,
                       admitted=True, maintenance=True)
    try:
        _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
        v3_release.verify_final_state(wrapped, prefix, evidence, rehearsal)
        if hold_gate is not None:
            # The interrupted window was re-entered with deployment HOLD true.
            # Never release it merely to repeat already completed operations.
            if hold_gate.read() != "true":
                raise DeployError("hold_not_protected")
            meta["holdRestored"] = True
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "v3_release_gate", [str(exc)], seen,
                       admitted=True, maintenance=True)
    # Reconstruct the *observed* prefix in the new attempt's checkpoint list,
    # without performing any of its side effects a second time.
    for name in SUPERVISED_STEPS[3:16]:
        marker = {"step": name, "observed": "verified_v3_recovery"}
        if name == "v3_release_gate":
            marker.update(evidence)
        _mark(checkpoints, marker)
    if env.get("A6_RESUME_EXPLICIT") != RESUME_PHRASE:
        return _finish(checkpoints, "resume", ["resume_not_explicit"], seen,
                       admitted=True, maintenance=True)
    try:
        integrity = jvm_health(wrapped, prefix)
        if integrity["phase"] != "frozen" or integrity["activeWrites"] != 0:
            raise DeployError("datomic_integrity_unknown")
        _op("resume_jvm", lambda: resume_jvm(wrapped, prefix))
        _op("resume_node", lambda: resume_node(wrapped, prefix))
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "resume", [str(exc)], seen,
                       admitted=True, maintenance=True)
    _mark(checkpoints, {"step": "resume", "resumeExplicit": True,
                        "v3GateVerified": True, "recovered": True})
    return _confirm_and_validate(wrapped, prefix, checkpoints, seen)


def _forward_after_resume(wrapped, prefix, observation, jvm_digest, node_digest, hold_gate, meta, checkpoints, seen,
                          prior, rehearsal):
    """Resume já enviado: imagens abertas só podem ser aceitas com gate v3 persistido."""
    evidence = (prior or {}).get("v3ReleaseEvidence")
    if rehearsal and (not _prior_has(prior, "v3_release_gate")
                      or not v3_release.valid_checkpoint(evidence, rehearsal)):
        return _finish(checkpoints, "v3_release_gate", ["v3_gate_checkpoint_missing"], seen,
                       admitted=True, maintenance=True)
    if not (_image_matches(observation, "jvm", jvm_digest) and _image_matches(observation, "node", node_digest)):
        return None
    try:
        live = _live_counters(wrapped, prefix)
    except DeployError:
        return None
    if live["node"].get("phase") != "open" or live["jvm"].get("phase") != "open":
        return None
    if hold_gate is not None:
        if hold_gate.read() != "true":
            return _finish(checkpoints, "resume", ["hold_not_restored"], seen, admitted=True, maintenance=True)
        meta["holdRestored"] = True
    for name in SUPERVISED_STEPS[3:17]:
        marker = {"step": name, "observed": "after_resume"}
        if name == "v3_release_gate" and rehearsal:
            marker.update(evidence)
        _mark(checkpoints, marker)
    return _confirm_and_validate(wrapped, prefix, checkpoints, seen)


def _confirm_and_validate(wrapped, prefix, checkpoints, seen):
    try:
        final = observe_vm(wrapped)
        live = _live_counters(wrapped, prefix)
        _apply_confirmed(final, OPEN, live)
        _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
    except DeployError as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return _finish(checkpoints, "confirm_open", [str(exc)], seen, admitted=True, maintenance=True)
    again = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    if not (
        _ready(again)
        and final["jvm"].get("health") == "healthy"
        and final["node"].get("health") == "healthy"
    ):
        return _finish(checkpoints, "confirm_open", ["not_open"], seen, admitted=True, maintenance=True)
    _mark(checkpoints, {
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
        dispatcher = node_status(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "validate_game", [str(exc)], seen, admitted=True, maintenance=True)
    final_ready = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    if (
        game["status"] == "up"
        and game["phase"] == "open"
        and dispatcher["phase"] == "open"
        and _ready(final_ready)
    ):
        _mark(checkpoints, {
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


def _leftovers(wrapped, prefix, prior):
    """-previous faria o rename do apply falhar com a JVM já parada.

    -failed é artefato de rollback; o próximo rollback remove com rm -f.
    """
    completed = wrapped(prefix + ["ps", "-a", "--filter", "name=odisseu-a6-", "--format", "{{.Names}}"])
    if not _ok(completed):
        raise DeployError("containers_unreadable")
    names = {line.strip().lstrip("/") for line in _out(completed).splitlines() if line.strip()}
    for service in ("jvm", "node"):
        if _prior_has(prior, "update_" + service):
            continue
        if "odisseu-a6-" + service + "-previous" in names:
            return ["previous_container_exists"]
    return []


def _previous_stopped(wrapped, prefix, service):
    name = "odisseu-a6-%s-previous" % service
    completed = wrapped(prefix + ["inspect", "--format", "{{.State.Running}}", name])
    return _ok(completed) and _out(completed).strip() == "false"


def _reconcile(wrapped, prefix, prior, pending, observation, jvm_digest, node_digest, rehearsal=None):
    """Intenção sem conclusão. Só segue quando o inspect prova o estado."""
    op = pending.get("op")
    verb = pending.get("verb")
    adjusted = copy.deepcopy(prior)
    adjusted["pending"] = None
    if op == "v3_release_reconcile":
        # Crash may have happened AFTER the Datomic transaction committed but
        # BEFORE journal.done. Recovery is read-only; no second CAS.
        if (not rehearsal
                or not _prior_has(prior, "validate_whatsapp")
                or _prior_has(prior, "v3_release_gate")
                or not _image_matches(observation, "jvm", jvm_digest)
                or not _image_matches(observation, "node", node_digest)):
            raise DeployError("v3_recovery_preconditions_invalid")
        _single_writer(_volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
        evidence = v3_release.recover_committed(
            wrapped, prefix, pending.get("before"), pending.get("snapshotSha256"),
            rehearsal,
        )
        adjusted["v3ReleaseEvidence"] = evidence
        adjusted.setdefault("steps", []).append("v3_release_gate")
        adjusted["lastCompleted"] = "v3_release_gate"
        return adjusted, "v3_receipt_observed"
    if op in REPEATABLE:
        if op in ("resume_jvm", "resume_node"):
            adjusted["resumeAttempted"] = True
        return adjusted, "repeatable_observed"
    if op == "stop_jvm":
        if _jvm_running(observation) is True and not _image_matches(observation, "jvm", jvm_digest):
            return adjusted, "jvm_still_running"
        raise DeployError("checkpoint_indeterminate")
    if op in ("update_jvm", "update_node") and verb in ("run", "inspect"):
        service = op.split("_", 1)[1]
        digest = jvm_digest if service == "jvm" else node_digest
        if _image_matches(observation, service, digest) and _previous_stopped(wrapped, prefix, service):
            steps = adjusted.setdefault("steps", [])
            if op not in steps:
                steps.append(op)
            return adjusted, "replacement_observed"
    raise DeployError("checkpoint_indeterminate")


def _prior_has(prior, step):
    steps = prior.get("steps") if isinstance(prior, dict) else None
    return isinstance(steps, list) and step in steps


def _image_matches(observation, service, digest):
    seen = _digest_hex((observation.get(service) or {}).get("imageId"))
    expected = str(digest).split("sha256:")[-1]
    return bool(expected) and seen == expected and (observation.get(service) or {}).get("health") == "healthy"


def _reuse_verified_backup(destination):
    destination = Path(destination)
    manifest = destination.parent / "backup-manifest.txt"
    if not destination.is_dir() or not manifest.is_file():
        raise DeployError("checkpoint_indeterminate")
    _reread_manifest(manifest, destination)
    return {"manifest": str(manifest)}


def _accept_replaced_jvm(wrapped, prefix, checkpoints, destination, isolated_root, drained, quiesced):
    try:
        if isolated_root is None:
            from a6_admin_backup_client import status as administrative_status
            administrative_status(destination)
        else:
            copied = _reuse_verified_backup(destination)
            _reread_manifest(copied["manifest"], Path(destination).resolve().parent / "restore-isolated")
        collection = _collection(drained, quiesced)
        if not _previous_stopped(wrapped, prefix, "jvm"):
            raise DeployError("checkpoint_indeterminate")
    except (DeployError, OSError) as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        return str(exc) or "checkpoint_indeterminate"
    exclusive = assess_observed(collection.documents, LIVE_VOLUME, "odisseu-a6-jvm")
    if not exclusive["exclusive"]:
        return exclusive.get("reason") or "writable_mount_not_exclusive"
    _mark(checkpoints, {"step": "stop_jvm", "observed": "jvm_already_replaced"})
    _mark(checkpoints, {"step": "exclusive", "holders": exclusive.get("holders") or []})
    _mark(checkpoints, {
        "step": "backup",
        "manifestVerified": True,
        "sourcePreserved": True,
        "jvmStopped": True,
        "reused": True,
    })
    _mark(checkpoints, {"step": "manifest", "manifestVerified": True, "reused": True})
    _mark(checkpoints, {"step": "restore_isolated", "restoreIsolated": True, "reused": True})
    _mark(checkpoints, {"step": "update_jvm", "service": "jvm", "observed": True, "mountsPreserved": True})
    return None


def _stop_and_backup(wrapped, prefix, drained, quiesced, destination, isolated_root, direct, seen, checkpoints):
    journal = _j()
    journal.intent("stop_jvm")
    stopped_cmd = wrapped(prefix + ["stop", "--time", "30", "odisseu-a6-jvm"])
    if not _ok(stopped_cmd):
        return _finish(checkpoints, "stop_jvm", ["docker_failed"], seen, admitted=True, maintenance=True,
                       pendingOp="stop_jvm"), None, None
    try:
        stopped = _observe_stopped(wrapped, prefix, drained)
        holders = _volume_holders(wrapped, prefix)
    except DeployError as exc:
        return _finish(checkpoints, "stop_jvm", [str(exc)], seen, admitted=True, maintenance=True,
                       pendingOp="stop_jvm"), None, None
    if _jvm_running(stopped) is not False:
        return _finish(checkpoints, "stop_jvm", ["writers_still_running"], seen, admitted=True, maintenance=True,
                       pendingOp="stop_jvm"), None, None
    try:
        _single_writer(holders, [])
    except DeployError as exc:
        return _finish(checkpoints, "stop_jvm", [str(exc)], seen, admitted=True, maintenance=True,
                       pendingOp="stop_jvm"), None, None
    journal.done("stop_jvm", running=False)
    _mark(checkpoints, {"step": "stop_jvm", "holdersActive": []})
    try:
        exclusive_collection = _collection(stopped, quiesced)
    except DeployError as exc:
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "exclusive", [str(exc)], seen, admitted=True, maintenance=True), None, None
    exclusive = assess_observed(exclusive_collection.documents, LIVE_VOLUME, "odisseu-a6-jvm")
    if not exclusive["exclusive"]:
        _restart_old_jvm(wrapped, direct)
        return _finish(
            checkpoints, "exclusive", [exclusive.get("reason") or "writable_mount_not_exclusive"], seen,
            admitted=True, maintenance=True,
        ), None, None
    _mark(checkpoints, {"step": "exclusive", "holders": exclusive.get("holders") or []})
    try:
        if isolated_root is None:
            # O helper mede espaço, lê o volume, manifesta, restaura e atesta.
            # Este processo vê apenas o objeto JSON sem caminhos ou dados do save.
            from a6_admin_backup_client import backup as administrative_backup
            copied = _op_backup(lambda: administrative_backup(destination), destination)
        else:
            source = isolated_root
            _separate(source, destination)
            if Path(destination).exists() and (Path(destination).parent / "backup-manifest.txt").is_file():
                copied = _reuse_verified_backup(destination)
                journal.note("backup_reused", destination=str(destination))
            else:
                measured = _bytes(source)
                parent = str(Path(destination).resolve().parent)
                space = wrapped(["df", "-B1", "--output=avail", parent])
                free_bytes = _avail(_out(space) if _ok(space) else "")
                if free_bytes is None:
                    raise DeployError("insufficient_space")

                def copy_and_verify():
                    result = execute_copy(
                        exclusive_collection, drained["vm"], destination, "false", True, BACKUP_AUTHORIZATION,
                        free_bytes, measured, isolated_root=isolated_root,
                    )
                    _reread_manifest(result["manifest"], destination)
                    return result

                copied = _op_backup(copy_and_verify, destination)
    except (DeployError, ProductionBackupError, OSError) as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        _restart_old_jvm(wrapped, direct)
        reason = str(exc) if str(exc) else "backup_not_verified"
        return _finish(checkpoints, "backup", [reason], seen, admitted=True, maintenance=True), None, None
    _mark(checkpoints, {
        "step": "backup",
        "manifestVerified": True,
        "sourcePreserved": True,
        "jvmStopped": True,
    })
    _mark(checkpoints, {"step": "manifest", "manifestVerified": True})
    try:
        if isolated_root is None:
            # backup() já devolve atestado de manifesto, origem e restore isolado.
            restored = {"restored": "admin-verified"}
        else:
            restore_dir = Path(destination).resolve().parent / "restore-isolated"
            if restore_dir.exists():
                _reread_manifest(copied["manifest"], restore_dir)
                restored = {"restored": str(restore_dir)}
            else:
                _separate(isolated_root, restore_dir)
                restored = _op_restore(lambda: restore_isolated(copied["manifest"], destination, restore_dir))
    except (DeployError, ProductionBackupError, OSError) as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        _restart_old_jvm(wrapped, direct)
        return _finish(checkpoints, "restore_isolated", [str(exc) or "backup_invalid"], seen, admitted=True, maintenance=True), None, None
    _mark(checkpoints, {"step": "restore_isolated", "restoreIsolated": True, "restoredTo": restored["restored"]})
    backup = {
        "manifestVerified": True,
        "sourcePreserved": True,
        "restoreIsolated": True,
        "jvmStopped": True,
    }
    return None, stopped, backup


def _op_backup(call, destination):
    journal = _j()
    journal.intent("backup", destination=str(destination))
    try:
        result = call()
    except (DeployError, ProductionBackupError, OSError) as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        journal.done("backup", ok=False, reason=str(exc) or "backup_not_verified")
        raise
    journal.done("backup", ok=True, files=result.get("files"))
    return result


def _op_restore(call):
    journal = _j()
    journal.intent("restore_isolated")
    try:
        result = call()
    except (DeployError, ProductionBackupError, OSError) as exc:
        if str(exc) in JOURNAL_ERRORS:
            raise
        journal.done("restore_isolated", ok=False, reason=str(exc) or "backup_invalid")
        raise
    journal.done("restore_isolated", ok=True)
    return result


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



def _failed_digest(wrapped, prefix, service):
    name = "odisseu-a6-%s-failed" % service
    completed = wrapped(prefix + ["inspect", "--format", "{{.Image}}", name])
    if not _ok(completed):
        return None
    return _digest_hex(_out(completed))


def _await_healthy(wrapped, prefix, name, first_status, deadline=None, sleep=time.sleep):
    """Docker StartPeriod marca starting; um inspect imediato não é evidência."""
    health = (first_status or "").strip()
    limit = time.monotonic() + HEALTH_WAIT_SECONDS if deadline is None else deadline
    while health in ("", "starting") and time.monotonic() < limit:
        sleep(HEALTH_POLL_SECONDS)
        probed = wrapped(prefix + ["inspect", "--format", "{{.State.Health.Status}}", name])
        if not _ok(probed):
            return "unreadable"
        health = _out(probed).strip()
    return health


def _clear_rolled_back_update(prior, service):
    adjusted = copy.deepcopy(prior) if isinstance(prior, dict) else {}
    step = "update_" + service
    steps = [item for item in (adjusted.get("steps") or []) if item != step]
    adjusted["steps"] = steps
    if adjusted.get("lastCompleted") == step:
        adjusted["lastCompleted"] = steps[-1] if steps else None
    if adjusted.get("next") in ("validate_jvm", "validate_whatsapp"):
        adjusted["next"] = "backup" if service == "jvm" else "update_node"
    adjusted["blocked"] = []
    return adjusted

def _update_service(wrapped, observation, sha, service, digest, image, env, backup, jvm_updated, direct, seen, checkpoints,
                    materialize=None):
    step = "update_jvm" if service == "jvm" else "update_node"
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
        return _finish(checkpoints, step, planned["blocked"], seen, admitted=True, maintenance=True)
    commands = apply_commands(planned)
    cleanup = None
    if PLACEHOLDER.search(" ".join(commands[3])):
        resolved = materialize(commands[3], service) if materialize is not None else None
        if not resolved or PLACEHOLDER.search(" ".join(resolved[0])):
            if service == "jvm":
                _restart_old_jvm(wrapped, direct)
            return _finish(checkpoints, step, ["run_argv_unresolved"], seen, admitted=True, maintenance=True)
        commands[3], cleanup = resolved
    verbs = ["stop", "rename", "update", "run", "inspect"]
    completed = []
    health = ""
    journal = _j()
    try:
        for command, verb in zip(commands, verbs):
            journal.intent(step, verb=verb)
            result = wrapped(command)
            if verb == "run" and cleanup is not None:
                cleanup()
                cleanup = None
            if not _ok(result):
                journal.done(step, verb=verb, ok=False)
                recovery = recover_after(service, completed, False, direct)
                _recover(wrapped, "recover_" + service, recovery)
                return _finish(checkpoints, step, ["docker_failed"], seen, admitted=True, maintenance=True,
                               recovery=recovery["mode"])
            journal.done(step, verb=verb, ok=True)
            completed.append(verb)
            if verb == "inspect":
                health = _out(result).strip()
    finally:
        if cleanup is not None:
            cleanup()
    name = "odisseu-a6-" + service
    prefix_now = _prefix(direct)
    running = wrapped(prefix_now + ["inspect", "--format", "{{.Image}}", name])
    reason = None
    if not _ok(running) or _digest_hex(_out(running)) != str(digest).split(":", 1)[-1]:
        reason = "image_digest_mismatch_after_run"
    elif service == "jvm":
        try:
            _single_writer(_volume_holders(wrapped, prefix_now), [name])
        except DeployError as exc:
            reason = "second_writer" if str(exc) == "writable_mount_not_exclusive" else str(exc)
    if reason is not None:
        recovery = _rollback(wrapped, service, False, direct)
        return _finish(checkpoints, step, [reason], seen, admitted=True, maintenance=True, recovery=recovery["mode"])
    health = _await_healthy(wrapped, prefix_now, name, health)
    if health != "healthy":
        battle = _battle(wrapped, prefix_now) if service == "jvm" else False
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
    # Só marca update_* depois da saúde comprovada — evita journal envenenado pós-rollback.
    _mark(checkpoints, {
        "step": step,
        "service": service,
        "mountsPreserved": True,
        "jvmUntouched": service == "node",
        "imageId": "sha256:" + _digest_hex(_out(running)),
        "health": "healthy",
    })
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
        _mark(checkpoints, {
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