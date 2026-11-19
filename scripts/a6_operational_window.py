"""Entrada manual da janela de deploy do A6.

O script dry-run continua recusando Docker. Este módulo só corre quando
um operador confirma a frase e o hold observado era true. O hold volta
a true antes do resume e também se a janela quebra no meio.
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from a6_production_contract import VM
from a6_production_deploy import DeployError
from a6_supervised_deploy import RESUME_PHRASE, WINDOW, run_supervised


CONFIRM = "confirmo-alterar-vm-a6"
HOLD_NAME = "ODISSEU_PRODUCTION_DEPLOY_HOLD"
REPO = "caioclavico/zapbot"


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
    return Path(destination).resolve().parent / "a6-window-checkpoint.json"


def load_checkpoint(destination):
    path = checkpoint_path(destination)
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DeployError("checkpoint_indeterminate") from exc
    if not isinstance(data, dict):
        raise DeployError("checkpoint_indeterminate")
    return data


def _authorized(env):
    required = {
        "A6_SUPERVISED_WINDOW": WINDOW,
        "A6_EXECUTE": "1",
        "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
        "ODISSEU_A6_DELIVERY_VALIDATED": "true",
        "A6_RESUME_EXPLICIT": RESUME_PHRASE,
    }
    return all(env.get(key) == value for key, value in required.items())


def _blocked(reason, decisions, hold, commands=None):
    try:
        current = hold.read()
    except DeployError:
        current = "indeterminate"
    return {
        "deployComplete": False,
        "executeAllowed": False,
        "blocked": [str(reason)],
        "decisions": list(decisions),
        "hold": current,
        "datomicRestored": False,
        "commands": commands or [],
        "steps": [],
    }


def _write_checkpoint(destination, window_id, report, hold_value):
    if not destination or "odisseu-a6-cutover" in str(destination) or "/var/lib/docker/volumes" in str(destination):
        return
    path = checkpoint_path(destination)
    if not path.parent.is_dir() or "/var/lib/docker/volumes" in str(path):
        return
    payload = {
        "windowId": window_id,
        "vmHostname": None,
        "lastCompleted": None,
        "next": None,
        "steps": [],
        "blocked": [],
        "deployComplete": False,
        "datomicRestored": False,
        "holdRestored": hold_value == "true",
        "holdReleased": True,
    }
    if isinstance(report, dict):
        payload.update({
            "lastCompleted": report.get("lastCompleted"),
            "next": report.get("next"),
            "steps": report.get("steps") or [],
            "blocked": report.get("blocked") or [],
            "deployComplete": report.get("deployComplete") is True,
        })
        for item in report.get("checkpoints") or []:
            if item.get("step") == "preflight" and item.get("vmHostname"):
                payload["vmHostname"] = item.get("vmHostname")
    try:
        path.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    except OSError:
        return


def execute_window(runner, hold, confirmer, sha, jvm_digest, node_digest, env, destination, isolated_root=None, window_id=None, prior=None, event=None):
    """Corre a janela. Sem confirmação, sem hold true ou em CI, não muda a VM."""
    decisions = []
    report = None
    failure = None
    previous = os.environ.get(HOLD_NAME)
    try:
        if (
            event == "push"
            or env.get("GITHUB_EVENT_NAME") == "push"
            or env.get("GITHUB_ACTIONS") == "true"
            or os.environ.get("GITHUB_ACTIONS") == "true"
            or os.environ.get("GITHUB_EVENT_NAME") == "push"
        ):
            raise DeployError("push_not_authorized")
        if not callable(runner):
            raise DeployError("declared_evidence")
        window_id = str(window_id or "").strip()
        if not window_id or any(character.isspace() for character in window_id):
            raise DeployError("window_id_required")
        if not _authorized(env):
            raise DeployError("window_not_authorized")
        record = prior if prior is not None else load_checkpoint(destination)
        if record is not None:
            if record.get("steps") and not record.get("windowId"):
                raise DeployError("checkpoint_indeterminate")
            if record.get("holdReleased") is True and record.get("holdRestored") is not True:
                hold.restore()
                decisions.append("hold_restored_after_interruption")
            if record.get("windowId") not in (None, window_id):
                reason = "window_already_complete" if record.get("deployComplete") is True else "window_already_open"
                raise DeployError(reason)
            if record.get("vmHostname") not in (None, VM["hostname"]):
                raise DeployError("checkpoint_incompatible")
            if record.get("deployComplete") is True:
                raise DeployError("window_already_complete")
        observed = hold.read()
        decisions.append("hold_observed_" + observed)
        if observed != "true":
            raise DeployError("hold_not_protected" if observed == "false" else "hold_indeterminate")
        if not callable(confirmer):
            raise DeployError("interactive_confirmation_required")
        if str(confirmer("Digite confirmo-alterar-vm-a6 para alterar a VM: ")).strip() != CONFIRM:
            raise DeployError("interactive_confirmation_rejected")
        decisions.append("interactive_confirmed")
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
            prior=record,
            window_id=window_id,
        )
    except DeployError as exc:
        failure = exc
    finally:
        if previous is None:
            os.environ.pop(HOLD_NAME, None)
        else:
            os.environ[HOLD_NAME] = previous
        if getattr(hold, "released", False):
            try:
                if hold.read() != "true":
                    hold.restore()
            except DeployError as exc:
                failure = exc if failure is None else failure
        if getattr(hold, "released", False) or report is not None:
            try:
                current = hold.read()
            except DeployError:
                current = "indeterminate"
            _write_checkpoint(destination, window_id, report, current)
    if getattr(hold, "released", False):
        decisions.append("hold_released")
    if getattr(hold, "restored", False) and isinstance(report, dict) and "resume" in (report.get("steps") or []):
        decisions.append("hold_restored_before_resume")
    elif getattr(hold, "restored", False):
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
    result["hold"] = hold.read()
    result["executeAllowed"] = False
    result["datomicRestored"] = False
    return result


def _subprocess_runner(args):
    import subprocess
    return subprocess.run(args, check=False, capture_output=True, text=True)


def cli():
    """Entrada do processo. CI e um terminal sem confirmação não chegam aqui pelo shell."""
    if os.environ.get("GITHUB_ACTIONS") == "true" or os.environ.get("GITHUB_EVENT_NAME") == "push":
        print("push_not_authorized", file=sys.stderr)
        return 2
    if os.environ.get("A6_HOLD_STORE") != "github":
        print("hold_store_required", file=sys.stderr)
        return 2
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
    )
    public = {
        key: report.get(key)
        for key in ("deployComplete", "blocked", "hold", "steps", "next", "datomicRestored", "decisions")
    }
    print(json.dumps(public, ensure_ascii=False))
    return 0 if report.get("deployComplete") else 2


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    raise SystemExit(cli())
