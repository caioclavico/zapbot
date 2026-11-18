#!/usr/bin/env python3
"""Plano do hotfix Node do Sinistea. Não publica e não altera a VM.

A VM é resolvida por projeto, zona e nome. O alvo é só o container
`odisseu-a6-node`. JVM, volumes Datomic e o stack legado ficam de fora.
"""

import argparse
import json
import sys

NODE = "odisseu-a6-node"
JVM = "odisseu-a6-jvm"
LEGACY_MARKERS = ("odisseu-core-", "zapbot", "odisseu-pokemon")
MIN_FREE_BYTES = 4 * 1024 * 1024 * 1024


class PlanError(Exception):
    pass


def resolve_instance(describe, project, zone, name):
    if describe.get("name") != name:
        raise PlanError("instance_name_mismatch")
    if describe.get("status") != "RUNNING":
        raise PlanError("vm_not_running")
    nat = None
    for nic in describe.get("networkInterfaces") or []:
        for access in nic.get("accessConfigs") or []:
            if access.get("natIP"):
                nat = access["natIP"]
    if not nat:
        raise PlanError("nat_ip_missing")
    return {
        "project": project,
        "zone": zone,
        "name": name,
        "status": describe["status"],
        "natIP": nat,
    }


def classify_containers(containers):
    by_name = {item["name"]: item for item in containers}
    legacy = [
        item["name"] for item in containers
        if item["name"].startswith(LEGACY_MARKERS) or item["name"] in {"zapbot"}
    ]
    node = by_name.get(NODE)
    jvm = by_name.get(JVM)
    if node is None or jvm is None:
        raise PlanError("a6_containers_missing")
    if NODE in legacy or JVM in legacy:
        raise PlanError("a6_classified_as_legacy")
    return {"node": node, "jvm": jvm, "legacy": legacy}


def image_tag(sha):
    if len(sha) < 12 or any(ch not in "0123456789abcdef" for ch in sha):
        raise PlanError("invalid_sha")
    return f"odisseu-node:hotfix-sinistea-{sha[:12]}"


def build_plan(snapshot):
    instance = resolve_instance(
        snapshot["instance"],
        snapshot["project"],
        snapshot["zone"],
        snapshot["instanceName"],
    )
    containers = classify_containers(snapshot["containers"])
    free = int(snapshot["diskFreeBytes"])
    node = containers["node"]
    jvm = containers["jvm"]
    disk_ok = free >= MIN_FREE_BYTES
    jvm_untouched = True
    mounts = list(snapshot.get("nodeMounts") or [])
    if any(str(mount.get("destination", "")).startswith("/data/datomic") for mount in mounts):
        raise PlanError("node_mounts_datomic")
    plan = {
        "apply": False,
        "target": NODE,
        "untouched": [JVM, *containers["legacy"]],
        "instance": instance,
        "diskFreeBytes": free,
        "diskOk": disk_ok,
        "minFreeBytes": MIN_FREE_BYTES,
        "datomicBackup": "refused",
        "backupStrategy": (
            "rollback da imagem Node anterior; "
            "sem cópia de volume Datomic e sem parar a JVM"
        ),
        "candidateImage": image_tag(snapshot["sha"]),
        "rollbackImage": node["image"],
        "rollbackImageId": node.get("imageId"),
        "jvmImage": jvm["image"],
        "recreate": [NODE],
        "preserve": {
            "volumes": [mount.get("name") for mount in mounts if mount.get("name")],
            "binds": [mount.get("source") for mount in mounts if mount.get("source")],
            "network": snapshot.get("network"),
            "envFile": snapshot.get("envFile"),
        },
        "jvmUntouched": jvm_untouched,
        "go": bool(disk_ok and node.get("status") == "running" and jvm.get("status") == "running"),
    }
    if not disk_ok:
        plan["block"] = "insufficient_free_space_for_node_image"
    return plan


def main(argv):
    parser = argparse.ArgumentParser(description="Plano somente leitura do hotfix Sinistea")
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    if args.apply:
        print("apply desligado. Este script não publica imagem nem recreia container.", file=sys.stderr)
        return 2
    with open(args.fixture, encoding="utf-8") as handle:
        snapshot = json.load(handle)
    try:
        plan = build_plan(snapshot)
    except PlanError as exc:
        print(str(exc), file=sys.stderr)
        return 3
    json.dump(plan, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    return 0 if plan["go"] else 4


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
