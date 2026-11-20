"""Root-owned one-time authority for A6 Docker transitions."""
from __future__ import annotations

import fcntl
import json
import os
import re
import tempfile
import time
from pathlib import Path


ROOT = Path("/run/odisseu-a6-mediator")
PROTOCOL = 1
WINDOW = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")


class AuthorityError(ValueError):
    pass


def _write(path, value):
    data = (json.dumps(value, sort_keys=True) + "\n").encode()
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".authority-", text=False)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data); handle.flush(); os.fsync(handle.fileno())
        os.replace(tmp, path)
        directory = os.open(path.parent, os.O_RDONLY); os.fsync(directory); os.close(directory)
    except BaseException:
        try: os.unlink(tmp)
        except OSError: pass
        raise


class Authority:
    def __init__(self, root=ROOT, now=time.time):
        self.root = Path(root); self.now = now

    def _paths(self, window):
        if not isinstance(window, str) or not WINDOW.fullmatch(window):
            raise AuthorityError("window_refused")
        return self.root / (window + ".json"), self.root / ".lock"

    def issue(self, state):
        window = state.get("windowId") if isinstance(state, dict) else None
        path, lock = self._paths(window)
        if set(state) != {"windowId", "vm", "expiresAt", "containers", "images", "sequence"}:
            raise AuthorityError("authority_schema")
        if not isinstance(state["expiresAt"], (int, float)) or state["expiresAt"] <= self.now():
            raise AuthorityError("authority_expired")
        if not isinstance(state["sequence"], list) or not state["sequence"]:
            raise AuthorityError("authority_sequence")
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.root, 0o700)
        fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if path.exists(): raise AuthorityError("authority_exists")
            _write(path, {"protocol": PROTOCOL, "state": "authorized", "next": 0, "pending": None, "observed": [], **state})
        finally:
            os.close(fd)

    def transition(self, window, operation, container_id):
        path, lock = self._paths(window)
        fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            state = json.loads(path.read_text())
            if state.get("protocol") != PROTOCOL or state.get("state") != "authorized": raise AuthorityError("authority_unavailable")
            if state.get("expiresAt", 0) <= self.now(): raise AuthorityError("authority_expired")
            if state.get("pending") is not None: raise AuthorityError("authority_pending")
            if state["next"] >= len(state["sequence"]) or state["sequence"][state["next"]] != operation: raise AuthorityError("transition_refused")
            expected = state["containers"].get(operation)
            if expected != container_id: raise AuthorityError("container_changed")
            state["pending"] = {"operation": operation, "containerId": container_id}
            _write(path, state)
            return state
        finally:
            os.close(fd)

    def complete(self, window, operation, observed):
        path, lock = self._paths(window)
        fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            state = json.loads(path.read_text())
            if state.get("pending", {}).get("operation") != operation: raise AuthorityError("completion_refused")
            if state.get("expiresAt", 0) <= self.now(): raise AuthorityError("authority_expired")
            state["pending"] = None; state["next"] += 1; state["observed"].append({"operation": operation, "result": observed})
            if state["next"] == len(state["sequence"]): state["state"] = "consumed"
            _write(path, state)
        finally:
            os.close(fd)
