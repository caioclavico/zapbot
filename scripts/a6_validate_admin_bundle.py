#!/usr/bin/env python3
"""Validate and extract the fixed-format A6 administrative bundle.

This intentionally understands only the tiny USTAR subset emitted for the
bundle.  Supporting PAX/GNU extensions would make the path policy ambiguous,
so extension records and every non-regular member are rejected.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile

BLOCK = 512
MAX_MEMBER_BYTES = 16 * 1024 * 1024
MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_ARCHIVE_BYTES = MAX_TOTAL_BYTES + 64 * 1024
ZERO_BLOCK = bytes(BLOCK)
EXPECTED = {
    "a6-root-backup",
    "a6_root_backup.py",
    "a6_production_backup.py",
    "a6_production_contract.py",
    "a6_production_deploy.py",
    "a6_mount_policy.py",
    "a6_writer_policy.py",
    "a6_post_v3_backup.py",
    "a6_post_v3_restore_audit.clj",
    "xp_reconciliation.clj",
    "chromium-seccomp.json",
}


class InvalidBundle(Exception):
    pass


def _octal(field, label):
    value = field.rstrip(b"\0 ")
    if not re.fullmatch(rb"[0-7]+", value):
        raise InvalidBundle(f"invalid {label}")
    return int(value, 8)


def validate(data, expected):
    if hashlib.sha256(data).hexdigest() != expected:
        raise InvalidBundle("bundle SHA-256 mismatch")
    if len(data) > MAX_ARCHIVE_BYTES or len(data) % BLOCK:
        raise InvalidBundle("invalid archive length")

    members = {}
    offset = total = 0
    while offset + 2 * BLOCK <= len(data):
        header = data[offset:offset + BLOCK]
        if header == ZERO_BLOCK:
            if len(data) - offset < 2 * BLOCK or any(data[offset:]):
                raise InvalidBundle("invalid archive terminator")
            if members.keys() != EXPECTED:
                raise InvalidBundle("bundle allowlist is incomplete")
            return members

        checksum = _octal(header[148:156], "header checksum")
        if checksum != sum(header[:148] + b" " * 8 + header[156:]):
            raise InvalidBundle("header checksum mismatch")
        if (header[257:265] != b"ustar\0" + b"00"
                or header[156:157] != b"0"
                or any(header[157:257]) or any(header[345:512])):
            raise InvalidBundle("unsupported tar header, member type or extension")
        for start, end in ((100, 108), (108, 116), (116, 124), (136, 148)):
            _octal(header[start:end], "header metadata")
        name = header[:100].rstrip(b"\0").decode("ascii")
        if name not in EXPECTED or name in members:
            raise InvalidBundle("unknown, unsafe or duplicate member")
        size = _octal(header[124:136], "member size")
        total += size
        if size > MAX_MEMBER_BYTES or total > MAX_TOTAL_BYTES:
            raise InvalidBundle("archive size limit exceeded")
        start = offset + BLOCK
        end = start + size
        offset = start + ((size + BLOCK - 1) // BLOCK) * BLOCK
        if offset > len(data) or any(data[end:offset]):
            raise InvalidBundle("truncated member or invalid padding")
        members[name] = memoryview(data)[start:end]
    raise InvalidBundle("missing archive terminator")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--stage-parent", default="/root")
    arguments = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", arguments.sha256):
        raise InvalidBundle("invalid expected SHA-256")
    descriptor = os.open(arguments.bundle, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        if not stat.S_ISREG(os.fstat(source.fileno()).st_mode):
            raise InvalidBundle("bundle is not a regular file")
        data = source.read(MAX_ARCHIVE_BYTES + 1)
    members = validate(data, arguments.sha256)
    stage = tempfile.mkdtemp(prefix="odisseu-a6-admin.", dir=arguments.stage_parent)
    try:
        for name, contents in members.items():
            path = os.path.join(stage, name)
            with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as output:
                output.write(contents)
        print(json.dumps({"stage": stage}, separators=(",", ":")))
    except BaseException:
        shutil.rmtree(stage)
        raise


if __name__ == "__main__":
    try:
        main()
    except (InvalidBundle, OSError, UnicodeError) as error:
        print(f"invalid administrative bundle: {error}", file=sys.stderr)
        sys.exit(2)
