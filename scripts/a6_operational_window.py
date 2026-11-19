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

import json
import os
import sys
from pathlib import Path

if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from a6_production_contract import VM
from a6_production_deploy import DeployError
from a6_supervised_deploy import RESUME_PHRASE, WINDOW, run_supervised
from a6_window_journal import (
    JOURNAL,
    WindowJournal,
    WindowLock,
    archive,
    binding_mismatch,
    load,
    state_dir,
    write_audit,
)


CONFIRM = "confirmo-alterar-vm-a6"
HOLD_NAME = "ODISSEU_PRODUCTION_DEPLOY_HOLD"
REPO = "caioclavico/zapbot"
HOLD_OPS = ("hold_release", "hold_restore")
DEFAULT_STATE = "~/.odisseu-a6-window"


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
        completed = self.runner(["gh", "variable", "get", HOLD_NAME, "--repo", REPO])
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
        if _in_ci(env, event):
            raise DeployError("push_not_authorized")
        if not callable(runner):
            raise DeployError("declared_evidence")
        window_id = str(window_id or "").strip()
        if not window_id or any(character.isspace() for character in window_id):
            raise DeployError("window_id_required")
        if not _authorized(env):
            raise DeployError("window_not_authorized")
        directory = state_dir(state or Path(destination).resolve().parent)
        lock = WindowLock(directory).acquire()
        binding = {
            "vmHostname": VM["hostname"],
            "imageSha": sha,
            "jvmDigest": jvm_digest,
            "nodeDigest": node_digest,
            "destination": str(Path(destination).resolve()),
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
        os.environ[HOLD_NAME] = "false"
        run_env = dict(env)
        run_env[HOLD_NAME] = "false"
        report = run_supervised(
            runner,
            sha,
            jvm_digest,
            node_digest,
            run_env,
            destination,
            isolated_root,
            event,
            hold_gate=hold,
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


def cli(argv=None):
    """Entrada do processo. CI e um terminal sem confirmação não chegam aqui pelo shell."""
    argv = list(sys.argv[1:] if argv is None else argv)
    if os.environ.get("GITHUB_ACTIONS") == "true" or os.environ.get("GITHUB_EVENT_NAME") == "push":
        print("push_not_authorized", file=sys.stderr)
        return 2
    if os.environ.get("A6_HOLD_STORE") != "github":
        print("hold_store_required", file=sys.stderr)
        return 2
    if argv[:1] == ["--recover-hold"]:
        try:
            audit = recover_hold(GithubHold(_subprocess_runner), _state_from_env())
        except DeployError as exc:
            print(str(exc), file=sys.stderr)
            return 2
        print(json.dumps(audit, ensure_ascii=False))
        return 0 if not audit.get("decisions") else 2
    if not sys.stdin.isatty():
        print("interactive_confirmation_required", file=sys.stderr)
        return 2
    report = execute_window(
        _subprocess_runner,
        GithubHold(_subprocess_runner),
        input,
        os.environ.get("A6_IMAGE_SHA") or "",
        os.environ.get("A6_JVM_DIGEST") or "",
        os.environ.get("A6_NODE_DIGEST") or "",
        dict(os.environ),
        os.environ.get("A6_BACKUP_DESTINATION") or "",
        None,
        window_id=os.environ.get("A6_WINDOW_ID"),
        state=_state_from_env(),
    )
    public = {
        key: report.get(key)
        for key in ("deployComplete", "blocked", "hold", "steps", "next", "datomicRestored", "decisions")
    }
    print(json.dumps(public, ensure_ascii=False))
    return 0 if report.get("deployComplete") else 2


if __name__ == "__main__":
    raise SystemExit(cli())
