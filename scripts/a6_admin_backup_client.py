"""Cliente sem privilégio para o helper administrativo instalado na VM.

O runner nunca importa o código que lê o volume live. Ele conversa somente
com o executável root-owned instalado pelo provisionador.
"""
from __future__ import annotations

import json
import re
import subprocess

from a6_production_backup import ProductionBackupError


HELPER = "/usr/local/libexec/odisseu-a6/a6-root-backup"
BACKUP_ROOT = "/var/backups/odisseu-a6"


def _window(destination):
    path = str(destination).rstrip("/")
    name = path.split("/")[-2] if path.endswith("/data") else ""
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", name):
        raise ProductionBackupError("backup_destination_invalid")
    if path != BACKUP_ROOT + "/" + name + "/data":
        raise ProductionBackupError("backup_destination_invalid")
    return name


def _call(arguments):
    completed = subprocess.run(["sudo", "-n", HELPER, *arguments], capture_output=True, text=True, check=False)
    if completed.returncode:
        raise ProductionBackupError("backup_admin_unavailable")
    try:
        value = json.loads(completed.stdout)
    except ValueError as exc:
        raise ProductionBackupError("backup_admin_invalid") from exc
    if not isinstance(value, dict):
        raise ProductionBackupError("backup_admin_invalid")
    return value


def preflight(destination):
    """Valida o helper instalado antes que a janela solte o hold."""
    return _call(["--preflight", _window(destination)])


def backup(destination):
    return _call(["--backup", _window(destination)])
