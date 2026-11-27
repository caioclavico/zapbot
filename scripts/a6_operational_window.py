"""Entrada manual da janela de deploy do A6.

O script dry-run continua recusando Docker. Este módulo só corre quando
um operador confirma a frase e o hold observado era true. O hold volta
a true antes do resume e também se a janela quebra no meio.

SIGKILL não executa finally. Por isso a liberação do hold grava a
intenção antes de escrever false no GitHub, e recover_hold devolve o hold
a true lendo só o GitHub: funciona sem diário, com diário quebrado e sem
nenhuma frase de autorização.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
from pathlib import Path

if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from a6_production_contract import JVM_MOUNTS, NODE_MOUNTS, VM, mounts_match
from a6_production_deploy import DeployError
from a6_supervised_deploy import RESUME_PHRASE, WINDOW, run_supervised
from a6_v3_release_gate import RELEASE_ENV, RELEASE_MODE, PROTOCOL_ENV, POST_V3_UPGRADE
from a6_window_journal import (
    JOURNAL,
    WindowJournal,
    WindowLock,
    archive,
    binding_mismatch,
    load,
    state_dir,
    _contains_secret_material,
    validate_oauth_checkpoint,
    write_audit,
)


CONFIRM = "confirmo-alterar-vm-a6"
HOLD_NAME = "ODISSEU_PRODUCTION_DEPLOY_HOLD"
REPO = "caioclavico/zapbot"
HOLD_OPS = ("hold_release", "hold_restore")
DEFAULT_STATE = "~/.odisseu-a6-window"
EXPECTED_SERVICE_ACCOUNT = "320125207473-compute@developer.gserviceaccount.com"
OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
_DATOMIC_VOLUME = "odisseu-a6-cutover-datomic"


class MemoryHold:
    """Hold de teste. Não fala com o GitHub."""

    def __init__(self, value="true"):
        self.value = value
        self.writes = []
        self.released = False
        self.restored = False

    def read(self):
        if self.value not in ("true", "false"):
            raise DeployError("hold_indeterminate")
        return self.value

    def release(self):
        if self.read() != "true":
            raise DeployError("hold_not_protected")
        self.value = "false"
        self.writes.append("false")
        self.released = True
        if self.read() != "false":
            raise DeployError("hold_release_unconfirmed")

    def ensure_true(self):
        if self.read() != "true":
            raise DeployError("hold_not_protected")
        self.value = "true"
        self.writes.append("true")
        if self.read() != "true":
            raise DeployError("hold_restore_unconfirmed")

    def restore(self):
        self.value = "true"
        self.writes.append("true")
        self.restored = True
        if self.read() != "true":
            raise DeployError("hold_restore_unconfirmed")


class GithubHold:
    """Lê e grava a variável do repositório. O token do gh não entra no argv."""

    def __init__(self, runner):
        self.runner = runner
        self.released = False
        self.restored = False

    def read(self):
        # The GitHub CLI installed on the production VM predates
        # `gh variable get`. Use the stable Actions Variables REST endpoint
        # via `gh api` so the exact variable is read (never list others).
        # GH_TOKEN is inherited from the operator's environment, not argv.
        completed = self.runner([
            "gh", "api", "repos/" + REPO + "/actions/variables/" + HOLD_NAME,
            "--jq", ".value",
        ])
        if getattr(completed, "returncode", 1) != 0:
            raise DeployError("hold_indeterminate")
        value = str(getattr(completed, "stdout", "") or "").strip()
        if value not in ("true", "false"):
            raise DeployError("hold_indeterminate")
        return value

    def release(self):
        if self.read() != "true":
            raise DeployError("hold_not_protected")
        self._write("false")
        self.released = True
        if self.read() != "false":
            raise DeployError("hold_release_unconfirmed")

    def restore(self):
        self._write("true")
        self.restored = True
        if self.read() != "true":
            raise DeployError("hold_restore_unconfirmed")

    def ensure_true(self):
        if self.read() != "true":
            raise DeployError("hold_not_protected")
        self._write("true")
        if self.read() != "true":
            raise DeployError("hold_restore_unconfirmed")

    def _write(self, value):
        if value not in ("true", "false"):
            raise DeployError("hold_indeterminate")
        completed = self.runner(["gh", "variable", "set", HOLD_NAME, "--repo", REPO, "--body", value])
        if getattr(completed, "returncode", 1) != 0:
            raise DeployError("hold_indeterminate")


def checkpoint_path(destination):
    return Path(destination).resolve().parent / JOURNAL


def load_checkpoint(destination):
    return load(Path(destination).resolve().parent)


def _authorized(env):
    required = {
        "A6_SUPERVISED_WINDOW": WINDOW,
        "A6_EXECUTE": "1",
        "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
        "ODISSEU_A6_DELIVERY_VALIDATED": "true",
        "A6_RESUME_EXPLICIT": RESUME_PHRASE,
    }
    return all(env.get(key) == value for key, value in required.items())


def _in_ci(env, event=None):
    return (
        event == "push"
        or env.get("GITHUB_EVENT_NAME") == "push"
        or env.get("GITHUB_ACTIONS") == "true"
        or os.environ.get("GITHUB_ACTIONS") == "true"
        or os.environ.get("GITHUB_EVENT_NAME") == "push"
    )


def _read(hold):
    try:
        return hold.read()
    except DeployError:
        return "indeterminate"


def _blocked(reason, decisions, hold, commands=None):
    return {
        "deployComplete": False,
        "executeAllowed": False,
        "blocked": [str(reason)],
        "decisions": list(decisions),
        "hold": _read(hold),
        "datomicRestored": False,
        "commands": commands or [],
        "steps": [],
    }


def _interrupted(record):
    if not isinstance(record, dict):
        return False
    pending = record.get("pending") if isinstance(record.get("pending"), dict) else {}
    return (
        (record.get("holdReleased") is True and record.get("holdRestored") is not True)
        or pending.get("op") in HOLD_OPS
    )


def _protect(hold, journal, decisions, label="hold_restored"):
    """Devolve o hold a true. O diário registra quando consegue; o hold não espera por ele."""
    if _read(hold) == "true":
        if journal is not None and (journal.data.get("pending") or {}).get("op") in HOLD_OPS:
            try:
                journal.resolve("hold_observed_true")
            except DeployError:
                decisions.append("checkpoint_write_failed")
        return False
    pending = (journal.data.get("pending") or {}).get("op") if journal is not None else None
    if journal is not None and pending is None:
        try:
            journal.intent("hold_restore")
            pending = "hold_restore"
        except DeployError:
            decisions.append("checkpoint_write_failed")
    hold.restore()
    decisions.append(label)
    if journal is not None:
        try:
            if pending in HOLD_OPS:
                journal.resolve("hold_restored", hold="true")
            else:
                journal.note("hold_restored", pendingKept=pending)
            journal.update(holdRestored=True)
        except DeployError:
            decisions.append("checkpoint_write_failed")
    return True


def execute_window(
    runner, hold, confirmer, sha, jvm_digest, node_digest, env, destination, isolated_root=None,
    window_id=None, prior=None, event=None, state=None, materialize=None,
):
    """Corre a janela. Sem confirmação, sem hold true, com outra janela ou em CI, não muda a VM."""
    decisions = []
    report = None
    failure = None
    previous = os.environ.get(HOLD_NAME)
    lock = None
    journal = None
    try:
        oauth_stage = env.get("A6_OAUTH_STAGE", "")
        protection_stage = oauth_stage == "protection"
        if oauth_stage not in ("", "protection", "cutover"):
            raise DeployError("oauth_stage_invalid")
        if _in_ci(env, event):
            raise DeployError("push_not_authorized")
        if not callable(runner):
            raise DeployError("declared_evidence")
        window_id = str(window_id or "").strip()
        if not window_id or any(character.isspace() for character in window_id):
            raise DeployError("window_id_required")
        if not _authorized(env):
            raise DeployError("window_not_authorized")
        if protection_stage and (
                env.get(PROTOCOL_ENV) != POST_V3_UPGRADE
                or env.get(HOLD_NAME) != "true"
                or env.get("A6_HOLD_EXTERNAL_VERIFIED") != "true"
                or not re.fullmatch(r"[0-9a-f]{64}", str(env.get("A6_HOLD_PROOF_SHA256") or ""))):
            raise DeployError("oauth_protection_authorization_invalid")
        if state is None and str(destination).startswith("/var/backups/odisseu-a6/"):
            raise DeployError("state_dir_required")
        directory = state_dir(state or Path(destination).resolve().parent)
        lock = WindowLock(directory).acquire()
        binding = {
            "vmHostname": VM["hostname"],
            "imageSha": sha,
            "jvmDigest": jvm_digest,
            "nodeDigest": node_digest,
            "destination": str(destination),
            "v3Protocol": env.get(PROTOCOL_ENV),
        }
        from_disk = prior is None
        try:
            record = load(directory) if from_disk else prior
        except DeployError:
            _protect(hold, None, decisions, "hold_restored_after_interruption")
            raise
        if record is not None:
            other = record.get("windowId") not in (None, window_id)
            if record.get("steps") and not record.get("windowId"):
                _protect(hold, None, decisions, "hold_restored_after_interruption")
                raise DeployError("checkpoint_indeterminate")
            owner = WindowJournal(directory, record.get("windowId"), binding, data=record)
            if _interrupted(record):
                _protect(hold, owner, decisions, "hold_restored_after_interruption")
            if other:
                if record.get("deployComplete") is not True:
                    raise DeployError("window_already_open")
                if from_disk:
                    archive(directory, record)
                decisions.append("previous_window_archived")
                record = None
            else:
                if record.get("vmHostname") not in (None, VM["hostname"]):
                    raise DeployError("checkpoint_incompatible")
                if binding_mismatch(record, binding):
                    raise DeployError("checkpoint_incompatible")
                if record.get("deployComplete") is True:
                    raise DeployError("window_already_complete")
                journal = owner
        if protection_stage:
            observed = env[HOLD_NAME]
            decisions.append("hold_externally_verified_true")
        else:
            observed = hold.read()
            decisions.append("hold_observed_" + observed)
            if observed != "true":
                raise DeployError("hold_not_protected" if observed == "false" else "hold_indeterminate")
        if not callable(confirmer):
            raise DeployError("interactive_confirmation_required")
        if str(confirmer("Digite confirmo-alterar-vm-a6 para alterar a VM: ")).strip() != CONFIRM:
            raise DeployError("interactive_confirmation_rejected")
        decisions.append("interactive_confirmed")
        if journal is None:
            journal = WindowJournal(directory, window_id, binding)
        journal.note("window_opened", pid=os.getpid())
        prior_snapshot = journal.snapshot() if journal.data.get("steps") or journal.data.get("pending") else None
        run_env = dict(env)
        if protection_stage:
            validate_oauth_checkpoint(journal.data, window_id)
            if journal.data.get("phase") not in ("PREPARATION", "PREFLIGHT_OK", "QUIESCENT", "T0_VERIFIED"):
                raise DeployError("oauth_protection_stage_invalid")
            evidence = {
                "holdEvidence": {
                    "verified": True,
                    "repository": REPO,
                    "variable": HOLD_NAME,
                    "value": "true",
                    "windowId": window_id,
                    "proofSha256": env["A6_HOLD_PROOF_SHA256"],
                }
            }
            journal.update(**evidence)
            run_env[HOLD_NAME] = "true"
        else:
            oauth_checkpoint = "oauthTransition" in journal.data
            if (oauth_stage == "cutover") != oauth_checkpoint:
                raise DeployError("oauth_checkpoint_required")
            if oauth_checkpoint:
                validate_oauth_checkpoint(journal.data, window_id)
                if (journal.data.get("phase") not in ("AUTH_OK", "CUTOVER")
                        or journal.data.get("oauthTransition", {}).get("authVerified") is not True):
                    raise DeployError("oauth_authentication_required")
                if not callable(getattr(hold, "ensure_true", None)):
                    raise DeployError("hold_idempotent_write_unavailable")
                hold.ensure_true()
                if journal.data.get("phase") == "AUTH_OK":
                    journal.advance_phase("CUTOVER")
            run_env[HOLD_NAME] = "false"
            os.environ[HOLD_NAME] = "false"
        report = run_supervised(
            runner,
            sha,
            jvm_digest,
            node_digest,
            run_env,
            destination,
            isolated_root,
            event,
            hold_gate=None if protection_stage else hold,
            prior=prior_snapshot,
            window_id=window_id,
            journal=journal,
            materialize=materialize,
        )
    except DeployError as exc:
        failure = exc
    finally:
        if previous is None:
            os.environ.pop(HOLD_NAME, None)
        else:
            os.environ[HOLD_NAME] = previous
        released = getattr(hold, "released", False) or (journal is not None and _interrupted(journal.data))
        if released:
            try:
                _protect(hold, journal, decisions)
            except DeployError as exc:
                failure = exc if failure is None else failure
        if journal is not None and failure is not None and report is None:
            try:
                journal.update(blocked=[str(failure)])
            except DeployError:
                decisions.append("checkpoint_write_failed")
        if lock is not None:
            lock.release()
    if getattr(hold, "released", False):
        decisions.append("hold_released")
    if getattr(hold, "restored", False) and isinstance(report, dict) and "resume" in (report.get("steps") or []):
        decisions.append("hold_restored_before_resume")
    elif getattr(hold, "restored", False) and "hold_restored" not in decisions:
        decisions.append("hold_restored")
    if failure is not None:
        blocked = _blocked(failure, decisions, hold, (report or {}).get("commands") if isinstance(report, dict) else [])
        if isinstance(report, dict):
            blocked["steps"] = report.get("steps") or []
            blocked["next"] = report.get("next")
            blocked["datomicRestored"] = False
        return blocked
    result = dict(report)
    result["decisions"] = decisions
    result["hold"] = _read(hold)
    result["executeAllowed"] = False
    result["datomicRestored"] = False
    return result


def recover_hold(hold, state, env=None, event=None):
    """Recuperação administrativa: só o hold. Não toca container nem faz rollback."""
    env = env if env is not None else dict(os.environ)
    if _in_ci(env, event):
        raise DeployError("push_not_authorized")
    directory = state_dir(state)
    audit = {
        "action": "hold_recovery",
        "pid": os.getpid(),
        "containersTouched": False,
        "rollback": False,
        "datomicRestored": False,
    }
    with WindowLock(directory):
        journal = None
        try:
            record = load(directory)
            audit["checkpoint"] = "absent" if record is None else "present"
        except DeployError:
            record = None
            audit["checkpoint"] = "indeterminate"
        if record is not None:
            audit["windowId"] = record.get("windowId")
            audit["pending"] = record.get("pending")
            audit["lastCompleted"] = record.get("lastCompleted")
            audit["interruptedWindow"] = record.get("deployComplete") is not True
            journal = WindowJournal(directory, record.get("windowId"), {}, data=record)
        before = _read(hold)
        audit["holdBefore"] = before
        decisions = []
        if before != "true":
            hold.restore()
        after = hold.read()
        if after != "true":
            raise DeployError("hold_restore_unconfirmed")
        audit["holdAfter"] = after
        audit["restored"] = before != "true"
        if journal is not None:
            try:
                journal.note("hold_recovery", holdBefore=before, holdAfter=after)
                journal.update(holdRestored=True)
            except DeployError:
                decisions.append("checkpoint_write_failed")
        audit["decisions"] = decisions
        try:
            audit["audit"] = write_audit(directory, audit)
        except DeployError:
            audit["audit"] = None
            audit["decisions"].append("audit_write_failed")
    return audit


def _subprocess_runner(args):
    import subprocess
    return subprocess.run(args, check=False, capture_output=True, text=True)


def _state_from_env():
    return os.environ.get("A6_STATE_DIR") or DEFAULT_STATE


def github_hold():
    mode = os.environ.get("A6_GITHUB_AUTH", "app")
    if mode == "app":
        from a6_github_app import AppGhRunner
        return GithubHold(AppGhRunner())
    raise DeployError("github_auth_mode_refused")


def _unique_pairs(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("duplicate_key")
        value[key] = item
    return value


def _read_evidence(path, state, expected_action):
    source = Path(path)
    directory = state_dir(state)
    if source.parent.resolve() != directory.resolve():
        raise DeployError("oauth_evidence_invalid")
    try:
        info = source.lstat()
    except OSError as exc:
        raise DeployError("oauth_evidence_invalid") from exc
    expected_name = re.fullmatch(
        r"\.a6-oauth-([A-Za-z0-9][A-Za-z0-9_-]{0,63})-(snapshot|pause|scope-ready)\.json",
        source.name,
    )
    if (expected_name is None or expected_name.group(2) != expected_action
            or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1 or not stat.S_ISREG(info.st_mode) or info.st_size > 65536):
        raise DeployError("oauth_evidence_invalid")
    try:
        value = json.loads(source.read_text(encoding="utf-8"), object_pairs_hook=_unique_pairs)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise DeployError("oauth_evidence_invalid") from exc
    if (not isinstance(value, dict) or value.get("windowId") != expected_name.group(1)
            or _contains_secret_material(value)):
        raise DeployError("oauth_evidence_invalid")
    return value


def _open_oauth_journal(state, window_id, required_phase):
    directory = state_dir(state)
    lock = WindowLock(directory).acquire()
    try:
        record = load(directory)
        validate_oauth_checkpoint(record, window_id)
        phases = {required_phase} if isinstance(required_phase, str) else set(required_phase)
        if record.get("pending") is not None or record.get("phase") not in phases:
            raise DeployError("oauth_checkpoint_state_invalid")
        return directory, lock, WindowJournal(directory, window_id, {}, data=record)
    except BaseException:
        lock.release()
        raise


def record_oauth_snapshot(state, window_id, evidence):
    """Vincula snapshots READY ao T0 existente; nunca cria uma segunda janela."""
    directory, lock, journal = _open_oauth_journal(
        state, window_id, ("T0_VERIFIED", "SNAPSHOT_READY", "PAUSED_FOR_OAUTH"))
    try:
        proof = (journal.data.get("postV3") or {}).get("proof") or {}
        snapshots = evidence.get("snapshots")
        if (journal.data.get("t0ProofSha256") != proof.get("proofSha256")
                or not isinstance(snapshots, list) or not snapshots):
            raise DeployError("oauth_snapshot_evidence_invalid")
        names, disks = set(), set()
        for item in snapshots:
            if (not isinstance(item, dict)
                    or set(item) != {"snapshotName", "snapshotId", "sourceDiskName", "sourceDiskId", "status"}
                    or item.get("status") != "READY"
                    or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", str(item.get("snapshotName") or ""))
                    or not re.fullmatch(r"[0-9]+", str(item.get("snapshotId") or ""))
                    or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", str(item.get("sourceDiskName") or ""))
                    or not re.fullmatch(r"[0-9]+", str(item.get("sourceDiskId") or ""))
                    or item["snapshotName"] in names or item["sourceDiskName"] in disks):
                raise DeployError("oauth_snapshot_evidence_invalid")
            names.add(item["snapshotName"])
            disks.add(item["sourceDiskName"])
        journal.advance_phase("SNAPSHOT_READY", snapshotEvidence=snapshots)
        if journal.data.get("phase") == "SNAPSHOT_READY":
            journal.update(next="pause_oauth")
        phase = journal.data["phase"]
    finally:
        lock.release()
    return {"windowId": window_id, "phase": phase, "snapshotCount": len(snapshots)}


def oauth_checkpoint_status(state, window_id):
    directory = state_dir(state)
    with WindowLock(directory):
        record = load(directory)
        validate_oauth_checkpoint(record, window_id)
        proof = (record.get("postV3") or {}).get("proof") or {}
        transition = record.get("oauthTransition") or {}
        return {
            "windowId": record["windowId"],
            "phase": record["phase"],
            "binding": record.get("binding"),
            "pending": record.get("pending"),
            "lastCompleted": record.get("lastCompleted"),
            "next": record.get("next"),
            "holdReleased": record.get("holdReleased"),
            "holdRestored": record.get("holdRestored"),
            "t0ProofSha256": record.get("t0ProofSha256"),
            "backupManifestSha256": proof.get("manifestSha256"),
            "restoreManifestSha256": proof.get("restoreManifestSha256"),
            "rollbackV3Aware": proof.get("rollbackV3Aware"),
            "rollbackJvmDigest": proof.get("rollbackJvmDigest"),
            "rollbackNodeDigest": proof.get("rollbackNodeDigest"),
            "snapshotEvidence": record.get("snapshotEvidence"),
            "vmStopped": record.get("vmStopped") is True,
            "vmStarted": record.get("vmStarted") is True,
            "oauthTransition": {
                key: transition.get(key)
                for key in ("windowId", "project", "zone", "instance", "instanceId",
                            "serviceAccount", "originalScopes",
                            "disks", "runtimeBeforeStop", "observedScopes",
                            "scopeVerified", "runtimeStatus",
                            "authVerified")
            },
        }


def oauth_checkpoint_hash(state):
    path = state_dir(state) / JOURNAL
    try:
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as exc:
        raise DeployError("checkpoint_indeterminate") from exc
    return {"sha256": digest}


def pause_for_oauth(state, window_id, identity):
    """Registra identidade, escopos e discos antes de autorizar o stop externo."""
    directory, lock, journal = _open_oauth_journal(state, window_id, ("SNAPSHOT_READY", "PAUSED_FOR_OAUTH"))
    try:
        scopes = identity.get("scopes")
        disks = identity.get("disks")
        runtime = identity.get("runtimeStatus")
        snapshots = journal.data.get("snapshotEvidence")
        if (set(identity) != {"windowId", "project", "zone", "instance", "instanceId",
                              "serviceAccount", "scopes", "disks", "runtimeStatus"}
                or identity.get("project") != VM["project"] or identity.get("zone") != VM["zone"]
                or identity.get("instance") != VM["instance"]
                or not re.fullmatch(r"[0-9]+", str(identity.get("instanceId") or ""))
                or identity.get("serviceAccount") != EXPECTED_SERVICE_ACCOUNT
                or not isinstance(scopes, list) or not scopes
                or any(not isinstance(scope, str) or not scope.startswith("https://www.googleapis.com/auth/")
                       for scope in scopes)
                or OAUTH_SCOPE in scopes
                or not isinstance(disks, list) or not disks):
            raise DeployError("oauth_vm_identity_invalid")
        if not isinstance(runtime, dict) or runtime != oauth_runtime_status():
            raise DeployError("oauth_runtime_identity_mismatch")
        plan = (journal.data.get("postV3") or {}).get("plan") or {}
        containers = {item.get("name"): item for item in runtime.get("containers", [])
                      if isinstance(item, dict)}
        if any(containers.get("odisseu-a6-" + service, {}).get("imageId")
               != plan.get("rollback" + service.capitalize() + "Digest")
               for service in ("jvm", "node")):
            raise DeployError("oauth_runtime_identity_mismatch")
        disk_map = {}
        for disk in disks:
            if (not isinstance(disk, dict)
                    or set(disk) != {"name", "id", "boot"}
                    or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", str(disk.get("name") or ""))
                    or not re.fullmatch(r"[0-9]+", str(disk.get("id") or ""))
                    or type(disk.get("boot")) is not bool
                    or disk["name"] in disk_map):
                raise DeployError("oauth_vm_identity_invalid")
            disk_map[disk["name"]] = str(disk["id"])
        if {item["sourceDiskName"]: item["sourceDiskId"] for item in snapshots} != disk_map:
            raise DeployError("oauth_snapshot_disk_mismatch")
        transition = {
            "windowId": window_id,
            "project": VM["project"],
            "zone": VM["zone"],
            "instance": VM["instance"],
            "instanceId": str(identity["instanceId"]),
            "serviceAccount": identity["serviceAccount"],
            "originalScopes": sorted(set(scopes)),
            "disks": sorted(disks, key=lambda item: item["name"]),
            "snapshotEvidence": snapshots,
            "runtimeBeforeStop": runtime,
            "authVerified": False,
        }
        journal.advance_phase("PAUSED_FOR_OAUTH", oauthTransition=transition)
        journal.update(next="stop_vm")
    finally:
        lock.release()
    return {"windowId": window_id, "phase": "PAUSED_FOR_OAUTH",
            "instanceId": transition["instanceId"], "snapshotCount": len(snapshots)}


def _docker_prefix(runner):
    direct = runner(["docker", "info"])
    if getattr(direct, "returncode", 1) == 0:
        return ["docker"]
    elevated = runner(["sudo", "-n", "docker", "info"])
    if getattr(elevated, "returncode", 1) != 0:
        raise DeployError("docker_unavailable")
    return ["sudo", "-n", "docker"]


def oauth_runtime_status(runner=None):
    """Retorna só estados e mounts autorizados, nunca Env ou conteúdo dos volumes."""
    runner = runner or _subprocess_runner
    prefix = _docker_prefix(runner)
    required = ("odisseu-a6-jvm", "odisseu-a6-node")
    known = (*required, "odisseu-a6-jvm-previous", "odisseu-a6-node-previous")
    listed = runner(prefix + ["ps", "-a", "--filter", "name=odisseu-a6-",
                              "--format", "{{.Names}}"])
    if getattr(listed, "returncode", 1) != 0:
        raise DeployError("oauth_container_inventory_incomplete")
    names = set((getattr(listed, "stdout", "") or "").splitlines())
    if names - set(known):
        raise DeployError("oauth_container_inventory_incomplete")
    if not set(required).issubset(names):
        raise DeployError("oauth_container_inventory_incomplete")
    inspected_names = [name for name in known if name in names]
    inspected = runner(prefix + ["inspect", *inspected_names])
    if getattr(inspected, "returncode", 1) != 0:
        raise DeployError("oauth_container_inventory_incomplete")
    try:
        docs = json.loads(inspected.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise DeployError("oauth_container_inventory_incomplete") from exc
    if not isinstance(docs, list):
        raise DeployError("oauth_container_inventory_incomplete")
    by_name = {str(item.get("Name") or "").lstrip("/"): item for item in docs}
    for service in ("jvm", "node"):
        name = "odisseu-a6-" + service
        item = by_name.get(name)
        if not isinstance(item, dict) or item.get("State", {}).get("Running") is not False:
            raise DeployError("oauth_service_must_remain_stopped")
        expected = JVM_MOUNTS if service == "jvm" else NODE_MOUNTS
        observed_mounts = [{
            "type": mount.get("Type"),
            "name": mount.get("Name") or "",
            "source": mount.get("Source") or "",
            "destination": mount.get("Destination"),
            "rw": mount.get("RW", True),
        } for mount in item.get("Mounts", [])]
        if not mounts_match(observed_mounts, expected):
            raise DeployError("oauth_live_mount_mismatch")
    volumes = sorted({
        mount["name"]
        for expected in (JVM_MOUNTS, NODE_MOUNTS)
        for mount in expected
        if mount["type"] == "volume"
    })
    volume_result = runner(prefix + ["volume", "inspect", *volumes])
    if getattr(volume_result, "returncode", 1) != 0:
        raise DeployError("oauth_live_volume_missing")
    try:
        volume_docs = json.loads(volume_result.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise DeployError("oauth_live_volume_missing") from exc
    if {item.get("Name") for item in volume_docs if isinstance(item, dict)} != set(volumes):
        raise DeployError("oauth_live_volume_missing")
    for name in known[2:]:
        if name not in names:
            continue
        item = by_name.get(name)
        if item is not None and item.get("State", {}).get("Running") is not False:
            raise DeployError("oauth_legacy_container_running")
    holders = runner(prefix + ["ps", "-q", "--filter", "volume=" + _DATOMIC_VOLUME])
    if getattr(holders, "returncode", 1) != 0 or (getattr(holders, "stdout", "") or "").strip():
        raise DeployError("oauth_datomic_writer_present")
    return {
        "dockerAvailable": True,
        "datomicWriterContainers": [],
        "containers": [{
            "name": name,
            "containerId": str(item.get("Id") or ""),
            "imageId": str(item.get("Image") or ""),
            "running": item.get("State", {}).get("Running"),
            "mounts": [{
                "type": mount.get("Type"),
                "name": mount.get("Name") or "",
                "source": mount.get("Source") or "",
                "destination": mount.get("Destination"),
                "rw": mount.get("RW", True),
            } for mount in item.get("Mounts", [])],
        } for name, item in sorted(by_name.items()) if name in names],
    }


def record_oauth_scope_ready(state, window_id, evidence, runtime=None):
    """Registra stop/start observados e exige containers e mounts protegidos."""
    directory = state_dir(state)
    lock = WindowLock(directory).acquire()
    try:
        record = load(directory)
        validate_oauth_checkpoint(record, window_id)
        if record.get("pending") is not None:
            raise DeployError("oauth_checkpoint_state_invalid")
        transition = record.get("oauthTransition") or {}
        scopes = evidence.get("scopes")
        if (record.get("phase") not in ("PAUSED_FOR_OAUTH", "VM_STOPPED", "VM_STARTED", "OAUTH_SCOPE_READY")
                or evidence.get("instanceId") != transition.get("instanceId")
                or evidence.get("serviceAccount") != EXPECTED_SERVICE_ACCOUNT
                or not isinstance(scopes, list)
                or set(scopes) != set(transition.get("originalScopes", [])) | {OAUTH_SCOPE}
                or evidence.get("stopped") is not True or evidence.get("started") is not True):
            raise DeployError("oauth_scope_evidence_invalid")
        if (not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z",
                             str(evidence.get("stoppedAt") or ""))
                or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d+)?Z",
                                    str(evidence.get("startedAt") or ""))):
            raise DeployError("oauth_scope_evidence_invalid")
        runtime = runtime or oauth_runtime_status()
        if not isinstance(runtime, dict):
            raise DeployError("oauth_runtime_not_quiescent")
        plan = (record.get("postV3") or {}).get("plan") or {}
        containers = {item.get("name"): item for item in runtime.get("containers", [])
                      if isinstance(item, dict)}
        for service in ("jvm", "node"):
            container = containers.get("odisseu-a6-" + service) or {}
            if (container.get("running") is not False
                    or container.get("imageId") != plan.get("rollback" + service.capitalize() + "Digest")):
                raise DeployError("oauth_runtime_identity_mismatch")
        if runtime != transition.get("runtimeBeforeStop"):
            raise DeployError("oauth_runtime_identity_changed")
        if (runtime.get("dockerAvailable") is not True
                or runtime.get("datomicWriterContainers") != []):
            raise DeployError("oauth_runtime_not_quiescent")
        journal = WindowJournal(directory, window_id, {}, data=record)
        journal.advance_phase("VM_STOPPED", vmStopped=True, vmStoppedAt=evidence["stoppedAt"])
        journal.advance_phase("VM_STARTED", vmStarted=True, vmStartedAt=evidence["startedAt"])
        transition.update({
            "observedScopes": sorted(set(scopes)),
            "runtimeStatus": runtime,
            "scopeVerified": True,
        })
        journal.advance_phase("OAUTH_SCOPE_READY", oauthTransition=transition)
        journal.update(next="verify_github_app")
    finally:
        lock.release()
    return {"windowId": window_id, "phase": "OAUTH_SCOPE_READY",
            "runtimeContainers": len(runtime["containers"])}


def verify_github_app(state, hold=None, recovery_runner=None):
    """Verifica read/write idempotente e recuperação em processo independente."""
    hold = hold or github_hold()
    if hold.read() != "true":
        raise DeployError("hold_not_protected")
    if not callable(getattr(hold, "ensure_true", None)):
        raise DeployError("hold_idempotent_write_unavailable")
    hold.ensure_true()
    if hold.read() != "true":
        raise DeployError("hold_restore_unconfirmed")
    if recovery_runner is None:
        import subprocess
        environment = {key: value for key, value in os.environ.items()
                       if key not in ("GH_TOKEN", "GITHUB_TOKEN", "GIT_TRACE", "SSLKEYLOGFILE")}
        for key in ("A6_OAUTH_STAGE", "A6_HOLD_EXTERNAL_VERIFIED", "A6_HOLD_PROOF_SHA256"):
            environment.pop(key, None)
        environment["A6_HOLD_STORE"] = "github"
        environment["A6_STATE_DIR"] = str(Path(state).expanduser())
        recovery_runner = lambda: subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--recover-hold"],
            check=False, capture_output=True, text=True, timeout=90, env=environment,
        )
    recovered = recovery_runner()
    if getattr(recovered, "returncode", 1) != 0:
        raise DeployError("oauth_independent_recovery_failed")
    try:
        audit = json.loads(recovered.stdout)
    except (TypeError, json.JSONDecodeError) as exc:
        raise DeployError("oauth_independent_recovery_invalid") from exc
    if (audit.get("holdBefore") != "true" or audit.get("holdAfter") != "true"
            or audit.get("restored") is not False
            or audit.get("containersTouched") is not False
            or audit.get("rollback") is not False
            or audit.get("datomicRestored") is not False
            or audit.get("decisions") != [] or audit.get("pid") == os.getpid()):
        raise DeployError("oauth_independent_recovery_invalid")
    if hold.read() != "true":
        raise DeployError("hold_restore_unconfirmed")
    directory, lock, journal = _open_oauth_journal(
        state, audit.get("windowId"), ("OAUTH_SCOPE_READY", "AUTH_OK"))
    try:
        transition = journal.data.get("oauthTransition") or {}
        if transition.get("authVerified") is True:
            return {"windowId": audit["windowId"], "phase": "AUTH_OK",
                    "hold": "true", "independentRecovery": True}
        transition.update({
            "authVerified": True,
            "holdReadBefore": True,
            "holdWriteTrueVerified": True,
            "holdReadAfter": True,
            "independentRecoveryAudit": audit.get("audit"),
        })
        journal.advance_phase("AUTH_OK", oauthTransition=transition)
        journal.update(next="cutover")
    finally:
        lock.release()
    return {"windowId": audit["windowId"], "phase": "AUTH_OK",
            "hold": "true", "independentRecovery": True}


def cli(argv=None):
    """Entrada do processo. CI e um terminal sem confirmação não chegam aqui pelo shell."""
    argv = list(sys.argv[1:] if argv is None else argv)
    if os.environ.get("GITHUB_ACTIONS") == "true" or os.environ.get("GITHUB_EVENT_NAME") == "push":
        print("push_not_authorized", file=sys.stderr)
        return 2
    try:
        if argv[:1] == ["--oauth-runtime-status"]:
            print(json.dumps(oauth_runtime_status(), ensure_ascii=False))
            return 0
        if argv[:1] == ["--oauth-checkpoint-status"] and len(argv) == 2:
            print(json.dumps(oauth_checkpoint_status(_state_from_env(), argv[1]), ensure_ascii=False))
            return 0
        if argv[:1] == ["--oauth-checkpoint-hash"] and len(argv) == 1:
            print(json.dumps(oauth_checkpoint_hash(_state_from_env()), ensure_ascii=False))
            return 0
        if argv[:1] == ["--record-oauth-snapshot"] and len(argv) == 2:
            evidence = _read_evidence(argv[1], _state_from_env(), "snapshot")
            result = record_oauth_snapshot(_state_from_env(), evidence.get("windowId"), evidence)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if argv[:1] == ["--record-oauth-pause"] and len(argv) == 2:
            evidence = _read_evidence(argv[1], _state_from_env(), "pause")
            result = pause_for_oauth(_state_from_env(), evidence.get("windowId"), evidence)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if argv[:1] == ["--record-oauth-scope-ready"] and len(argv) == 2:
            evidence = _read_evidence(argv[1], _state_from_env(), "scope-ready")
            result = record_oauth_scope_ready(_state_from_env(), evidence.get("windowId"), evidence)
            print(json.dumps(result, ensure_ascii=False))
            return 0
        if argv[:1] == ["--verify-github-app"]:
            result = verify_github_app(_state_from_env())
            print(json.dumps(result, ensure_ascii=False))
            return 0
    except (DeployError, OSError, ValueError) as exc:
        print(str(exc) or type(exc).__name__, file=sys.stderr)
        return 2
    protection_stage = os.environ.get("A6_OAUTH_STAGE") == "protection"
    expected_store = "external-github-read" if protection_stage else "github"
    if os.environ.get("A6_HOLD_STORE") != expected_store:
        print("hold_store_required", file=sys.stderr)
        return 2
    if argv[:1] == ["--recover-hold"]:
        try:
            audit = recover_hold(github_hold(), _state_from_env())
        except DeployError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        print(json.dumps(audit, ensure_ascii=False))
        return 0 if not audit.get("decisions") else 2
    if not sys.stdin.isatty():
        print("interactive_confirmation_required", file=sys.stderr)
        return 2
    from a6_runtime_materialize import Materializer
    direct = _subprocess_runner(["docker", "info"]).returncode == 0
    prefix = ["docker"] if direct else ["sudo", "-n", "docker"]
    profiles = {}
    for service in ("jvm", "node"):
        path = os.environ.get("A6_" + service.upper() + "_SECCOMP_PATH")
        digest = os.environ.get("A6_" + service.upper() + "_SECCOMP_SHA256")
        if path and digest:
            profiles[service] = (path, digest)
    materializer = Materializer(prefix, profiles=profiles,
                               v3_release=os.environ.get(RELEASE_ENV) == RELEASE_MODE)
    report = execute_window(
        _subprocess_runner,
        MemoryHold("true") if protection_stage else github_hold(),
        input,
        os.environ.get("A6_IMAGE_SHA") or "",
        os.environ.get("A6_JVM_DIGEST") or "",
        os.environ.get("A6_NODE_DIGEST") or "",
        dict(os.environ),
        os.environ.get("A6_BACKUP_DESTINATION") or "",
        None,
        window_id=os.environ.get("A6_WINDOW_ID"),
        state=_state_from_env(),
        materialize=materializer,
    )
    public = {
        key: report.get(key)
        for key in ("deployComplete", "blocked", "hold", "steps", "next", "datomicRestored", "decisions")
    }
    print(json.dumps(public, ensure_ascii=False))
    return 0 if report.get("deployComplete") else 2


if __name__ == "__main__":
    raise SystemExit(cli())