"""Diário durável da janela A6.

Toda operação que muda a VM grava a intenção antes de começar e a
conclusão só depois de observar o resultado. Intenção sem conclusão quer
dizer resultado desconhecido: a retomada observa a VM e não repete a
operação às cegas. A gravação usa arquivo temporário, fsync, os.replace e
fsync do diretório. Disco que recusa a gravação para a janela.
"""

from __future__ import annotations

import contextlib
import copy
import errno
import fcntl
import json
import os
import tempfile
import time
from pathlib import Path

from a6_production_deploy import DeployError


JOURNAL = "a6-window-checkpoint.json"
LOCK = "a6-window.lock"
VERSION = 1
BINDING_KEYS = ("vmHostname", "imageSha", "jvmDigest", "nodeDigest", "destination")


def _live(path):
    text = str(path)
    return "odisseu-a6-cutover" in text or "/var/lib/docker/volumes" in text


def _now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _fsync_dir(directory):
    try:
        fd = os.open(str(directory), os.O_RDONLY)
    except OSError as exc:
        raise DeployError("checkpoint_write_failed") from exc
    try:
        os.fsync(fd)
    except OSError as exc:
        if exc.errno not in (errno.EINVAL, errno.ENOTSUP, errno.EBADF):
            raise DeployError("checkpoint_write_failed") from exc
    finally:
        os.close(fd)


def atomic_write_json(path, payload):
    """Grava o JSON inteiro ou nada. Quem lê nunca vê metade do arquivo."""
    path = Path(path)
    if _live(path):
        raise DeployError("live_path_refused")
    data = (json.dumps(payload, ensure_ascii=False, sort_keys=True) + "\n").encode("utf-8")
    if b"TOKEN=" in data:
        raise DeployError("secret_in_output")
    try:
        fd, tmp = tempfile.mkstemp(prefix="." + path.name + ".", suffix=".tmp", dir=str(path.parent))
    except OSError as exc:
        raise DeployError("checkpoint_write_failed") from exc
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(tmp, path)
    except OSError as exc:
        with contextlib.suppress(OSError):
            os.unlink(tmp)
        raise DeployError("checkpoint_write_failed") from exc
    _fsync_dir(path.parent)


def state_dir(path):
    """Diretório do diário. Fica fora dos volumes e só o operador lê."""
    directory = Path(path).expanduser()
    if _live(directory.resolve()):
        raise DeployError("live_path_refused")
    try:
        directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    except OSError as exc:
        raise DeployError("checkpoint_write_failed") from exc
    if not directory.is_dir():
        raise DeployError("checkpoint_write_failed")
    return directory


def load(directory):
    """Lê o diário. Arquivo ilegível é indeterminado, nunca janela nova."""
    path = Path(directory) / JOURNAL
    if not path.exists():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DeployError("checkpoint_indeterminate") from exc
    if not isinstance(data, dict) or not isinstance(data.get("steps", []), list):
        raise DeployError("checkpoint_indeterminate")
    return data


class WindowLock:
    """flock do processo. O kernel solta a trava se o processo morre."""

    def __init__(self, directory):
        self.path = Path(directory) / LOCK
        self.fd = None

    def acquire(self):
        try:
            fd = os.open(str(self.path), os.O_RDWR | os.O_CREAT, 0o600)
        except OSError as exc:
            raise DeployError("checkpoint_write_failed") from exc
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as exc:
            os.close(fd)
            if exc.errno in (errno.EWOULDBLOCK, errno.EAGAIN, errno.EACCES):
                raise DeployError("window_in_progress") from exc
            raise DeployError("checkpoint_write_failed") from exc
        self.fd = fd
        return self

    def release(self):
        if self.fd is None:
            return
        with contextlib.suppress(OSError):
            fcntl.flock(self.fd, fcntl.LOCK_UN)
        with contextlib.suppress(OSError):
            os.close(self.fd)
        self.fd = None

    def __enter__(self):
        return self.acquire()

    def __exit__(self, *_exc):
        self.release()


class NullJournal:
    """Sem diário: usado pela janela supervisionada antiga e pelos testes dela."""

    broken = False

    def intent(self, op, **detail):
        return None

    def done(self, op, **observed):
        return None

    def step(self, item):
        return None

    def note(self, kind, **detail):
        return None

    def update(self, **fields):
        return None

    def resolve(self, resolution, **detail):
        return None


class WindowJournal:
    """Diário de uma janela. Vinculado a janela, VM, imagens e destino."""

    def __init__(self, directory, window_id, binding, data=None):
        self.directory = Path(directory)
        self.path = self.directory / JOURNAL
        self.broken = False
        if data is None:
            data = {
                "version": VERSION,
                "windowId": window_id,
                "binding": {key: binding.get(key) for key in BINDING_KEYS},
                "vmHostname": binding.get("vmHostname"),
                "steps": [],
                "pending": None,
                "events": [],
                "holdReleased": False,
                "holdRestored": False,
                "deployComplete": False,
                "datomicRestored": False,
                "blocked": [],
                "next": None,
                "lastCompleted": None,
            }
        self.data = data

    def snapshot(self):
        return copy.deepcopy(self.data)

    def _persist(self):
        self.data["datomicRestored"] = False
        self.data["updatedAt"] = _now()
        try:
            atomic_write_json(self.path, self.data)
        except DeployError:
            self.broken = True
            raise

    def _event(self, kind, op=None, **detail):
        event = {"at": _now(), "kind": kind}
        if op is not None:
            event["op"] = op
        event.update({key: value for key, value in detail.items() if value is not None})
        self.data.setdefault("events", []).append(event)

    def intent(self, op, **detail):
        """Antes da operação. Uma intenção aberta impede a próxima."""
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        if self.data.get("pending") is not None:
            raise DeployError("checkpoint_pending_unresolved")
        self.data["pending"] = {"op": op, **detail, "at": _now()}
        if op == "hold_release":
            self.data["holdReleased"] = True
            self.data["holdRestored"] = False
        self._event("intent", op, **detail)
        self._persist()

    def done(self, op, **observed):
        """Depois de observar o resultado. Só fecha a intenção da mesma operação."""
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        pending = self.data.get("pending") or {}
        if pending.get("op") != op:
            raise DeployError("checkpoint_sequence_mismatch")
        self.data["pending"] = None
        if op == "hold_restore" and observed.get("hold") == "true":
            self.data["holdRestored"] = True
        self._event("done", op, **observed)
        self._persist()

    def step(self, item):
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        name = item.get("step")
        steps = self.data.setdefault("steps", [])
        if name not in steps:
            steps.append(name)
        if name == "v3_release_gate" and item.get("verified") is True:
            self.data["v3ReleaseEvidence"] = {
                key: item[key] for key in ("verified", "receiptId", "pokemonCount",
                                          "protectedSha256", "rehearsalManifestSha256")
            }
        self.data["lastCompleted"] = name
        self._event("step", name)
        self._persist()

    def note(self, kind, **detail):
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        self._event(kind, **detail)
        self._persist()

    def update(self, **fields):
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        self.data.update(fields)
        self._persist()

    def resolve(self, resolution, **detail):
        """Fecha uma intenção antiga depois de observar a VM na retomada."""
        if self.broken:
            raise DeployError("checkpoint_write_failed")
        pending = self.data.get("pending")
        self.data["pending"] = None
        self._event("reconciled", (pending or {}).get("op"), resolution=resolution, **detail)
        self._persist()


def binding_mismatch(data, binding):
    """Diário de outra VM, outras imagens ou outro destino não serve."""
    saved = data.get("binding") if isinstance(data.get("binding"), dict) else {}
    for key in BINDING_KEYS:
        if saved.get(key) not in (None, binding.get(key)):
            return key
    return None


def archive(directory, data):
    """Janela concluída sai do caminho de uma nova. Nada é apagado."""
    target = Path(directory) / ("a6-window-%s.done.json" % str(data.get("windowId") or "sem-id"))
    if target.exists():
        raise DeployError("checkpoint_indeterminate")
    try:
        os.replace(Path(directory) / JOURNAL, target)
    except OSError as exc:
        raise DeployError("checkpoint_write_failed") from exc
    _fsync_dir(directory)


def write_audit(directory, payload):
    name = "a6-hold-recovery-%s-%d.json" % (time.strftime("%Y%m%dT%H%M%SZ", time.gmtime()), os.getpid())
    path = Path(directory) / name
    atomic_write_json(path, payload)
    return str(path)
