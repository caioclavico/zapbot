#!/usr/bin/python3
"""Forced SSH command: allow fixed deploy, rollback and Odisseu check operations."""
import os
import re
import subprocess
import sys


def arguments(service, original):
    if service not in ("odisseu", "pokemon"):
        raise ValueError("service")
    if service == "odisseu" and original == "check":
        return ["check"]
    if re.fullmatch(r"deploy [0-9a-f]{40}", original):
        return ["deploy", original.split(" ")[1]]
    if original == "rollback":
        return ["rollback"]
    raise ValueError("command")


def main():
    try:
        service = sys.argv[1] if len(sys.argv) == 2 else ""
        args = arguments(service, os.environ.get("SSH_ORIGINAL_COMMAND", ""))
    except ValueError:
        allowed = "Only deploy <40-character SHA> or rollback is allowed."
        if service == "odisseu":
            allowed = "Only check, deploy <40-character SHA> or rollback is allowed."
        print(allowed, file=sys.stderr)
        return 2
    if args == ["check"]:
        print("SSH check succeeded; no deployment action was run.")
        return 0
    return subprocess.call(
        ["/usr/bin/sudo", "-n", "/usr/local/sbin/zapbot-deploy-" + service, *args],
        env={"PATH": "/usr/bin:/bin", "LANG": "C.UTF-8"},
        stdin=subprocess.DEVNULL,
    )


if __name__ == "__main__":
    sys.exit(main())
