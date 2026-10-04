#!/usr/bin/python3
"""Root-owned entry point installed under a service-specific sudo allowlist."""
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


APP_DIRS = {
    "odisseu": "/home/ubuntu/zapbot",
    "pokemon": "/home/caiohclavico/pokemon-service",
}


def deployment_args(service, args, config):
    if service not in APP_DIRS:
        raise ValueError("service")
    if set(config) != {"ghcr_owner"} or not re.fullmatch(
        r"[a-z0-9][a-z0-9-]*", config.get("ghcr_owner", "")
    ):
        raise ValueError("configuration")
    if args == ["rollback"]:
        return [service, "rollback", APP_DIRS[service]]
    if len(args) == 2 and args[0] == "deploy" and re.fullmatch(r"[0-9a-f]{40}", args[1]):
        name = "zapbot" if service == "odisseu" else "zapbot-pokemon"
        image = f"ghcr.io/{config['ghcr_owner']}/{name}:{args[1]}"
        return [service, "deploy", image, APP_DIRS[service]]
    raise ValueError("command")


def private_config(path):
    # O_NOFOLLOW and ownership checks prevent a deploy account from redirecting
    # the root process to its own registry or configuration file.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
            raise ValueError("private configuration")
        with os.fdopen(fd, "r") as stream:
            fd = None
            return json.load(stream)
    finally:
        if fd is not None:
            os.close(fd)


def main():
    try:
        if os.geteuid() != 0:
            raise ValueError("privileges")
        service = Path(sys.argv[0]).name.removeprefix("zapbot-deploy-")
        if service not in APP_DIRS:
            raise ValueError("service")
        config = private_config(f"/etc/zapbot-deploy/{service}/config.json")
        args = deployment_args(service, sys.argv[1:], config)
    except (ValueError, OSError, json.JSONDecodeError):
        print("Invalid deployment command or private configuration.", file=sys.stderr)
        return 2
    return subprocess.call(
        ["/bin/bash", "/usr/local/lib/zapbot-deploy/deploy-vm.sh", *args],
        env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
        stdin=subprocess.DEVNULL,
    )


if __name__ == "__main__":
    sys.exit(main())
