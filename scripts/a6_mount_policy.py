"""Volumes graváveis lidos de um inspect executado, não de um mapa declarado.

O isolamento por container impede outro container, sem o volume, de gravar.
Não impede o administrador do host nem quem controla o Docker de montar o
volume ou escrever em /var/lib/docker/volumes. Esses atores ficam fora da
garantia. Por isso a autorização de produção permanece bloqueada.
"""

from __future__ import annotations

import json
from dataclasses import dataclass


LIVE_VOLUME = "odisseu-a6-cutover-datomic"
SYS_ADMIN_BIT = 1 << 21


@dataclass(frozen=True)
class Collection:
    source: str
    documents: tuple
    processes: str
    jvm_phase: str
    node_phase: str
    active_jvm_writes: int
    active_node_ops: int
    volume: str
    authorized: str


def normalize_name(name):
    text = str(name or "").strip()
    while text.startswith("/"):
        text = text[1:]
    if (not text) or ("/" in text):
        return ""
    return text


def _identity(container):
    name = normalize_name(container.get("Name") or container.get("name"))
    raw_id = str(container.get("Id") or container.get("id") or "")
    return name, raw_id


def _is_authorized(container, authorized):
    name, raw_id = _identity(container)
    token = str(authorized or "").strip()
    if name and name == normalize_name(token):
        return True
    compact = token.lower()
    if (
        raw_id
        and len(compact) >= 12
        and all(char in "0123456789abcdef" for char in compact)
        and (raw_id == compact or raw_id.startswith(compact))
    ):
        return True
    return False


def _sys_admin_added(caps):
    for cap in caps or []:
        text = str(cap).upper()
        if text == "SYS_ADMIN" or text.endswith("/SYS_ADMIN"):
            return True
    return False


def _effective_sys_admin(container):
    if "CapEff" not in container or container.get("CapEff") in (None, ""):
        return None
    try:
        return bool(int(str(container.get("CapEff")).strip(), 16) & SYS_ADMIN_BIT)
    except ValueError:
        return None


def writable_holders(containers, volume):
    holders = []
    for container in containers or []:
        name, raw_id = _identity(container)
        for mount in container.get("Mounts") or []:
            haystack = " ".join(
                str(mount.get(key) or "") for key in ("Name", "Source", "Destination")
            )
            writable = mount.get("RW", mount.get("rw", True))
            if volume and volume in haystack and writable is not False:
                holders.append(name or raw_id)
    return holders


def assess_observed(containers, volume, authorized):
    """Checa holders e capabilities. Não decide se o volume live pode ser usado."""
    holders = writable_holders(containers, volume)
    if any(name and "node" in name for name in holders):
        return {"exclusive": False, "reason": "node_has_datomic", "holders": holders}
    privileged = False
    added = False
    effective_unknown = False
    effective_set = False
    matched = False
    matched_name = ""
    for container in containers or []:
        if not _is_authorized(container, authorized):
            continue
        matched = True
        matched_name = _identity(container)[0] or matched_name
        host = container.get("HostConfig") or {}
        if host.get("Privileged") is True:
            privileged = True
        if _sys_admin_added(host.get("CapAdd")):
            added = True
        effective = _effective_sys_admin(container)
        if effective is None:
            effective_unknown = True
        elif effective:
            effective_set = True
    if not matched or not matched_name:
        return {"exclusive": False, "reason": "writer_not_identified", "holders": holders}
    if privileged or added or effective_set:
        return {"exclusive": False, "reason": "privileged_or_sys_admin", "holders": holders}
    if holders != [matched_name]:
        return {"exclusive": False, "reason": "writable_mount_not_exclusive", "holders": holders}
    if effective_unknown:
        return {"exclusive": False, "reason": "cap_eff_unknown", "holders": holders}
    return {"exclusive": True, "reason": None, "holders": holders}


def assess(containers, volume, authorized):
    """O helper de laboratório continua recusando o volume live."""
    if volume == LIVE_VOLUME:
        return {"exclusive": False, "reason": "live_volume_refused", "holders": []}
    return assess_observed(containers, volume, authorized)


def _run(runner, args):
    completed = runner(args)
    if getattr(completed, "returncode", 1) != 0:
        return None
    return completed.stdout


def _edn_token(text, key):
    if not text or key not in text:
        return None
    rest = text.split(key, 1)[1].strip()
    if not rest:
        return None
    return rest.split()[0].rstrip(",}")


def collect_from_runner(runner, volume, authorized):
    """Executa docker inspect, CapEff, ps e os marcadores. JSON colado não entra aqui."""
    name = normalize_name(authorized)
    if not name or not volume or volume == LIVE_VOLUME:
        return None
    raw = _run(runner, ["docker", "inspect", name])
    status = _run(runner, ["docker", "exec", name, "cat", "/proc/self/status"])
    processes = _run(runner, ["docker", "exec", name, "ps", "-eo", "args"])
    jvm_marker = _run(
        runner,
        ["docker", "exec", name, "cat", "/data/datomic-local/.a6-maintenance/bridge-maintenance.edn"],
    )
    node_name = name[:-4] + "-node" if name.endswith("-jvm") else ""
    node_marker = _run(
        runner,
        ["docker", "exec", node_name, "cat", "/app/data/a6-maintenance.json"],
    ) if node_name else None
    if None in (raw, status, processes, jvm_marker, node_marker):
        return None
    try:
        documents = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(documents, list) or not documents:
        return None
    cap_line = next((line for line in status.splitlines() if line.startswith("CapEff:")), "")
    cap_eff = cap_line.split()[1] if len(cap_line.split()) == 2 else ""
    enriched = []
    for document in documents:
        if not isinstance(document, dict):
            return None
        host = document.get("HostConfig")
        if not isinstance(host, dict) or "Privileged" not in host or "CapAdd" not in host:
            return None
        if "Id" not in document or "Name" not in document or "Mounts" not in document:
            return None
        copied = dict(document)
        if _is_authorized(copied, name):
            copied["CapEff"] = cap_eff
        enriched.append(copied)
    jvm_phase = (_edn_token(jvm_marker, ":phase") or "").lstrip(":")
    try:
        node = json.loads(node_marker)
    except json.JSONDecodeError:
        return None
    if "phase" not in node or "activeOps" not in node:
        return None
    try:
        active_jvm = int(_edn_token(jvm_marker, ":active-writes"))
        active_node = int(node["activeOps"])
    except (TypeError, ValueError):
        return None
    return Collection(
        source="docker-inspect-subprocess",
        documents=tuple(enriched),
        processes=processes,
        jvm_phase=jvm_phase,
        node_phase=str(node["phase"]),
        active_jvm_writes=active_jvm,
        active_node_ops=active_node,
        volume=volume,
        authorized=name,
    )
