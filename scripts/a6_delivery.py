#!/usr/bin/env python3
"""Plano idempotente da entrega A6. Não contacta produção nem altera volumes reais.

O alvo é identificado por projeto, zona e nome. Apply só grava uma cópia do
estado dentro de um diretório isolado quando o fixture declara environment=isolated.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from copy import deepcopy
from pathlib import Path


PRESERVE = ("datomic", "journal", "maint", "session", "network", "env", "restart")
SERVICES = ("node", "jvm")


class DeliveryError(Exception):
    pass


def _require_identity(state):
    for key in ("project", "zone", "instanceName"):
        value = str(state.get(key) or "")
        if not value:
            raise DeliveryError("identity_missing")
        if value.count(".") == 3 and all(part.isdigit() for part in value.split(".")):
            raise DeliveryError("identity_is_ip")
    if state.get("environment") != "isolated":
        raise DeliveryError("production_refused")


def _containers(state):
    by_name = {item["name"]: item for item in state.get("containers") or []}
    node = by_name.get("odisseu-a6-node")
    jvm = by_name.get("odisseu-a6-jvm")
    if node is None or jvm is None:
        raise DeliveryError("a6_containers_missing")
    return node, jvm


def _preserve_snapshot(state):
    return {key: deepcopy(state.get(key)) for key in PRESERVE}


def _tag(kind, sha):
    if len(sha) < 12 or any(ch not in "0123456789abcdef" for ch in sha):
        raise DeliveryError("invalid_sha")
    return f"odisseu-{kind}:a6-{sha[:12]}"


def _external_writers(state):
    return list(state.get("externalWriters") or [])


def preflight(state):
    _require_identity(state)
    node, jvm = _containers(state)
    report = {
        "command": "preflight",
        "apply": False,
        "identity": {
            "project": state["project"],
            "zone": state["zone"],
            "instanceName": state["instanceName"],
        },
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
        "externalWriters": _external_writers(state),
        "node": node["name"],
        "jvm": jvm["name"],
    }
    if node.get("status") != "running" or jvm.get("status") != "running":
        raise DeliveryError("container_not_running")
    return report


def build_plan(state, sha):
    preflight(state)
    report = {
        "command": "build",
        "apply": False,
        "images": {"node": _tag("node", sha), "jvm": _tag("jvm", sha)},
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }
    return report


def verify(state, service):
    preflight(state)
    if service not in SERVICES:
        raise DeliveryError("service_required")
    container = _containers(state)[0 if service == "node" else 1]
    return {
        "command": "verify",
        "service": service,
        "image": container.get("image"),
        "health": container.get("health"),
        "restart": container.get("restart"),
        "ok": container.get("health") == "healthy" and container.get("status") == "running",
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }


def drain(state):
    preflight(state)
    writers = _external_writers(state)
    proven = not writers and state.get("activeNodeOps") == 0 and state.get("activeJvmWrites") == 0
    phase = "quiescent" if proven else "draining"
    return {
        "command": "drain",
        "phase": phase,
        "quiescenceProven": False if writers else proven,
        "externalWriters": writers,
        "reason": "external_datomic_client" if writers else None,
        "preserve": _preserve_snapshot(state),
    }


def update(state, service, sha):
    if service not in SERVICES:
        raise DeliveryError("service_required")
    drained = drain(state)
    if not drained["quiescenceProven"] or drained["phase"] != "quiescent":
        raise DeliveryError("quiescence_not_proven")
    node, jvm = _containers(state)
    target = node if service == "node" else jvm
    candidate = _tag(service, sha)
    changed = target.get("image") != candidate
    if changed:
        target["previousImage"] = target.get("image")
        target["image"] = candidate
        target["updated"] = True
    return {
        "command": "update",
        "service": service,
        "changed": changed,
        "image": target.get("image"),
        "untouched": "odisseu-a6-jvm" if service == "node" else "odisseu-a6-node",
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }


def resume(state, service):
    if service != "node":
        raise DeliveryError("resume_is_node_only")
    preflight(state)
    jvm_phase = state.get("jvmPhase")
    if jvm_phase != "open":
        raise DeliveryError("jvm_not_open")
    state["nodePhase"] = "open"
    state["jvmPhase"] = "open"
    return {
        "command": "resume",
        "service": "node",
        "nodePhase": "open",
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }


def health(state):
    node = verify(state, "node")
    jvm = verify(state, "jvm")
    return {
        "command": "health",
        "ok": bool(node["ok"] and jvm["ok"]),
        "node": node["health"],
        "jvm": jvm["health"],
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }


def rollback(state, service):
    if service not in SERVICES:
        raise DeliveryError("service_required")
    preflight(state)
    node, jvm = _containers(state)
    target = node if service == "node" else jvm
    other = jvm if service == "node" else node
    previous = target.get("previousImage")
    if not previous:
        raise DeliveryError("rollback_image_missing")
    other_image = other.get("image")
    target["image"] = previous
    target["previousImage"] = None
    if other.get("image") != other_image:
        raise DeliveryError("rollback_touched_other_service")
    return {
        "command": "rollback",
        "service": service,
        "image": target.get("image"),
        "otherImage": other.get("image"),
        "preserve": _preserve_snapshot(state),
        "quiescenceProven": False,
    }


COMMANDS = {
    "preflight": lambda state, args: preflight(state),
    "build": lambda state, args: build_plan(state, args.sha),
    "verify": lambda state, args: verify(state, args.service),
    "drain": lambda state, args: drain(state),
    "update": lambda state, args: update(state, args.service, args.sha),
    "resume": lambda state, args: resume(state, args.service),
    "health": lambda state, args: health(state),
    "rollback": lambda state, args: rollback(state, args.service),
}


def main(argv):
    parser = argparse.ArgumentParser(description="Entrega A6 isolada")
    parser.add_argument("command", choices=sorted(COMMANDS))
    parser.add_argument("--fixture", required=True)
    parser.add_argument("--service", choices=["node", "jvm"])
    parser.add_argument("--sha", default="")
    parser.add_argument("--isolated-root", default="")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args(argv)
    fixture = Path(args.fixture)
    state = json.loads(fixture.read_text(encoding="utf-8"))
    before = _preserve_snapshot(state) if state.get("environment") == "isolated" else None
    try:
        report = COMMANDS[args.command](state, args)
    except DeliveryError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    if args.apply:
        root = Path(args.isolated_root)
        if state.get("environment") != "isolated" or not args.isolated_root:
            print("apply_refused", file=sys.stderr)
            return 2
        if not root.is_dir():
            print("isolated_root_missing", file=sys.stderr)
            return 2
        report["apply"] = True
        (root / "delivery-state.json").write_text(json.dumps(state, indent=2, sort_keys=True), encoding="utf-8")
        (root / "delivery-report.json").write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
        if before != _preserve_snapshot(state):
            print("preserve_mismatch", file=sys.stderr)
            return 2
    if args.command == "build" and os.environ.get("ODISSEU_A6_ISOLATED_DOCKER") == "1":
        root = Path(args.isolated_root)
        if not root.is_dir():
            print("isolated_root_missing", file=sys.stderr)
            return 2
        (root / "docker-build.txt").write_text(
            "docker build --tag {node} --tag {jvm}\n".format(**report["images"]),
            encoding="utf-8",
        )
    json.dump(report, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    if args.command == "health" and not report["ok"]:
        return 3
    if args.command == "verify" and not report["ok"]:
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
