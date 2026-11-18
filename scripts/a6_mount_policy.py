"""Volumes graváveis observados via inspect. Não consulta a VM."""

from __future__ import annotations


LIVE_VOLUME = "odisseu-a6-cutover-datomic"


def writable_holders(containers, volume):
    holders = []
    for container in containers or []:
        name = str(container.get("Name") or container.get("name") or "").lstrip("/")
        for mount in container.get("Mounts") or []:
            haystack = " ".join(str(mount.get(key) or "") for key in ("Name", "Source", "Destination"))
            writable = mount.get("RW", mount.get("rw", True))
            if volume in haystack and writable is not False:
                holders.append(name)
    return holders


def assess(containers, volume, authorized):
    if volume == LIVE_VOLUME and authorized != "odisseu-a6-jvm":
        return {"exclusive": False, "reason": "live_volume_refused", "holders": []}
    holders = writable_holders(containers, volume)
    node_holders = [name for name in holders if name and "node" in name]
    if node_holders:
        return {"exclusive": False, "reason": "node_has_datomic", "holders": holders}
    if holders != [authorized]:
        return {"exclusive": False, "reason": "writable_mount_not_exclusive", "holders": holders}
    privileged = []
    for container in containers or []:
        if (container.get("Name") or container.get("name")) != authorized:
            continue
        host = container.get("HostConfig") or {}
        caps = set(host.get("CapAdd") or [])
        if host.get("Privileged") is True or "SYS_ADMIN" in caps:
            privileged.append(authorized)
    if privileged:
        return {"exclusive": False, "reason": "cap_sys_admin", "holders": holders}
    return {"exclusive": True, "reason": None, "holders": holders}
