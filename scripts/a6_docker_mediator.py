#!/usr/bin/env python3
"""Root-owned, structured Docker boundary for the A6 operational runner.

The unprivileged caller sends JSON for one named operation.  This program
reconstructs every Docker command itself.  It deliberately has no generic
``docker`` or shell operation.
"""
from __future__ import annotations

import json
import re
import subprocess
import sys

SERVICES = {"node": "odisseu-a6-node", "jvm": "odisseu-a6-jvm"}
READ_FILES = {
    ("jvm", "/proc/self/status"),
    ("jvm", "/data/datomic-local/.a6-maintenance/bridge-maintenance.edn"),
    ("node", "/app/data/a6-maintenance.json"),
}
NAME = re.compile(r"odisseu-a6-(?:node|jvm)(?:-(?:previous|failed))?$")
DIGEST_IMAGE = re.compile(r"odisseu-(?:node|jvm):a6-[0-9a-f]{12}(?:-amd64)?@sha256:[0-9a-f]{64}$")


class MediatorError(ValueError):
    pass


def _service(value):
    if value not in SERVICES:
        raise MediatorError("service_refused")
    return SERVICES[value]


def command(request):
    if not isinstance(request, dict) or set(request) - {"op", "service", "name", "image", "path", "timeout"}:
        raise MediatorError("request_invalid")
    op = request.get("op")
    service = request.get("service")
    if op == "inspect":
        return ["docker", "inspect", _service(service)]
    if op == "health":
        return ["docker", "inspect", "--format", "{{.State.Health.Status}}", _service(service)]
    if op == "image":
        image = request.get("image")
        if not isinstance(image, str) or not DIGEST_IMAGE.fullmatch(image):
            raise MediatorError("image_refused")
        return ["docker", "image", "inspect", image]
    if op == "read":
        path = request.get("path")
        if (service, path) not in READ_FILES:
            raise MediatorError("read_refused")
        return ["docker", "exec", _service(service), "cat", path]
    if op == "processes":
        return ["docker", "exec", _service(service), "ps", "-eo", "args"]
    if op == "stop":
        timeout = request.get("timeout")
        if timeout != 30:
            raise MediatorError("stop_timeout_refused")
        return ["docker", "stop", "--time", "30", _service(service)]
    if op in {"start", "rename", "disable_restart"}:
        name = request.get("name")
        if not isinstance(name, str) or not NAME.fullmatch(name):
            raise MediatorError("container_refused")
        if op == "start":
            return ["docker", "start", name]
        if op == "disable_restart":
            return ["docker", "update", "--restart=no", name]
        return ["docker", "rename", _service(service), name]
    raise MediatorError("operation_refused")


def main():
    try:
        request = json.load(sys.stdin)
        result = subprocess.run(command(request), capture_output=True, text=True, check=False)
    except (MediatorError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "error": str(error)}))
        return 2
    print(json.dumps({"ok": result.returncode == 0, "stdout": result.stdout, "stderr": result.stderr}))
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
