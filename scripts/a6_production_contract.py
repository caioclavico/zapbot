"""Contrato estrutural do stack A6 observado na VM. Não contém segredos.

Os valores de token, chave e URL são lidos do container vivo na hora da
execução e não entram neste módulo nem no log.
"""

from __future__ import annotations


VM = {
    "hostname": "odisseu-core",
    "instance": "odisseu-core",
    "zone": "us-central1-a",
    "project": "project-e8cd7809-1145-4be9-b8d",
}

DATOMIC_VOLUME = "odisseu-a6-cutover-datomic"
DATOMIC_SOURCE = "/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data"
NETWORK = "odisseu-a6-cutover_default"

JVM_MOUNTS = (
    {"type": "volume", "name": DATOMIC_VOLUME, "destination": "/data/datomic-local", "rw": True},
    {"type": "volume", "name": "odisseu-a6-cutover-journal", "destination": "/data/journal", "rw": True},
    {"type": "volume", "name": "odisseu-a6-cutover-maint", "destination": "/data/maint", "rw": True},
    {"type": "bind", "source": "/run/odisseu-control", "destination": "/run/odisseu-control", "rw": True},
)

NODE_MOUNTS = (
    {
        "type": "bind",
        "source": "/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core",
        "destination": "/app/.wwebjs_auth_a6_cutover",
        "rw": True,
    },
    {"type": "volume", "name": "odisseu-a6-cutover-data", "destination": "/app/data", "rw": True},
)

JVM_ENV_KEYS = (
    "ODISSEU_BRIDGE_STORAGE",
    "ODISSEU_BRIDGE_SYSTEM",
    "ODISSEU_BRIDGE_DB",
    "ODISSEU_BRIDGE_TOKEN",
    "ODISSEU_JOURNAL_DIR",
    "ODISSEU_MAINT_DIR",
    "ODISSEU_BRIDGE_MAINTENANCE_SOCKET",
    "ODISSEU_PLATFORM_DB",
    "JAVA_TOOL_OPTIONS",
)

NODE_ENV_KEYS = (
    "POKEMON_ADAPTER",
    "POKEMON_JVM_URL",
    "POKEMON_JVM_TOKEN",
    "PLATFORM_ADAPTER",
    "PLATFORM_JVM_URL",
    "WWEBJS_DATA_PATH",
    "ODISSEU_NODE_MAINTENANCE_FILE",
)

CURRENT = {
    "vm": dict(VM),
    "jvm": {
        "name": "odisseu-a6-jvm",
        "image": "odisseu-jvm:a6-b04682f1a238-amd64",
        "imageId": "sha256:c495184169fb5259816e7c6c7eae0c67e2198dbc08883aebaff249f7d15dba7f",
        "user": "",
        "network": NETWORK,
        "restart": "no",
        "privileged": False,
        "capAdd": [],
        "capDrop": [],
        "capEff": "00000000a80425fb",
        "security": [],
        "publish": "127.0.0.1:18092:18090",
        "memory": 805306368,
        "health": "healthy",
        "phase": "open",
        "mounts": [dict(item) for item in JVM_MOUNTS],
        "envKeys": list(JVM_ENV_KEYS),
    },
    "node": {
        "name": "odisseu-a6-node",
        "image": "odisseu-node:hotfix-sinistea-33eea61a8a45",
        "imageId": "sha256:fe92b76796b46dd172e388174827f1e1a7169124c29a6ad381db3f003d345858",
        "user": "1000:1000",
        "network": NETWORK,
        "restart": "no",
        "privileged": False,
        "capAdd": [],
        "capDrop": ["ALL"],
        "security": ["no-new-privileges:true", "seccomp"],
        "publish": "127.0.0.1:3003:3001",
        "memory": 2684354560,
        "health": "healthy",
        "phase": "open",
        "whatsapp": "READY",
        "mounts": [dict(item) for item in NODE_MOUNTS],
        "envKeys": list(NODE_ENV_KEYS),
    },
}


def _mount_key(mount):
    return (
        mount.get("type"),
        mount.get("name") or "",
        (mount.get("source") or "") if mount.get("type") != "volume" else "",
        mount.get("destination"),
        bool(mount.get("rw", True)),
    )


def mounts_match(live, expected):
    return [_mount_key(item) for item in live] == [_mount_key(item) for item in expected]


def vm_matches(observed):
    return all(observed.get(key) == value for key, value in VM.items())
