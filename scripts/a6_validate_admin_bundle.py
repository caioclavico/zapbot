#!/usr/bin/env python3
"""Validate and snapshot the fixed-format A6 administrative bundle.

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
EXPECTED = {
    "a6-root-backup",
    "a6_root_backup.py",
    "a6_production_backup.py",
    "a6_production_contract.py",
    "a6_production_deploy.py",
    "a6_mount_policy.py",
    "a6_writer_policy.py",
    "chromium-seccomp.json",
}


class InvalidBundle(Exception):
    pass


def _read_exact(stream, count):
    value = stream.read(count)
    if len(value) != count:
        raise InvalidBundle("archive truncated")
    return value


def _octal(field, label):
    value = field.rstrip(b"\0 ")
    if not value or not re.fullmatch(rb"[0-7]+", value):
        raise InvalidBundle(f"invalid {label}")
    return int(value, 8)


def _name(field, label):
    value = field.split(b"\0", 1)[0]
    try:
        return value.decode("ascii")
    except UnicodeDecodeError as error:
        raise InvalidBundle(f"non-ASCII {label}") from error


def _header_checksum(header):
    expected = _octal(header[148:156], "header checksum")
    actual = sum(header[:148]) + (32 * 8) + sum(header[156:])
    if actual != expected:
        raise InvalidBundle("header checksum mismatch")


def _validate_stream(stream):
    seen = set()
    total = 0
    zero_blocks = 0
    while True:
        header = _read_exact(stream, BLOCK)
        if header == b"\0" * BLOCK:
            zero_blocks += 1
            if zero_blocks == 2:
                trailing = stream.read()
                if trailing.strip(b"\0"):
                    raise InvalidBundle("data after archive terminator")
                break
            continue
        if zero_blocks:
            raise InvalidBundle("single zero block inside archive")
        _header_checksum(header)
        name = _name(header[:100], "member name")
        prefix = _name(header[345:500], "member prefix")
        member_type = header[156:157]
        link_name = _name(header[157:257], "link name")
        if prefix or member_type not in (b"\0", b"0") or link_name:
            raise InvalidBundle("unsupported tar member type or extension")
        if not name or name.startswith("/") or "/" in name or name in (".", "..") or ".." in name.split("/"):
            raise InvalidBundle("unsafe member path")
        if name.startswith("._") or name == "__MACOSX" or name.startswith("__MACOSX/"):
            raise InvalidBundle("macOS metadata is forbidden")
        if name not in EXPECTED:
            raise InvalidBundle("unknown member")
        if name in seen:
            raise InvalidBundle("duplicate member")
        size = _octal(header[124:136], "member size")
        if size > MAX_MEMBER_BYTES or total + size > MAX_TOTAL_BYTES:
            raise InvalidBundle("archive size limit exceeded")
        _read_exact(stream, ((size + BLOCK - 1) // BLOCK) * BLOCK)
        seen.add(name)
        total += size
    if seen != EXPECTED:
        raise InvalidBundle("bundle allowlist is incomplete")


def _digest_and_validate(fd, expected):
    fd.seek(0)
    digest = hashlib.sha256()
    while chunk := fd.read(1024 * 1024):
        digest.update(chunk)
    if digest.hexdigest() != expected:
        raise InvalidBundle("bundle SHA-256 mismatch")
    fd.seek(0)
    _validate_stream(fd)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--stage-parent", default="/root")
    arguments = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", arguments.sha256):
        raise InvalidBundle("invalid expected SHA-256")
    descriptor = os.open(arguments.bundle, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    try:
        source_stat = os.fstat(descriptor)
        if not stat.S_ISREG(source_stat.st_mode):
            raise InvalidBundle("bundle is not a regular file")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            _digest_and_validate(source, arguments.sha256)
            stage = tempfile.mkdtemp(prefix="odisseu-a6-admin.", dir=arguments.stage_parent)
            os.chmod(stage, 0o700)
            snapshot = os.path.join(stage, "bundle.tar")
            with open(snapshot, "xb") as destination:
                source.seek(0)
                shutil.copyfileobj(source, destination, 1024 * 1024)
                destination.flush()
                os.fsync(destination.fileno())
            os.chmod(snapshot, 0o600)
        with open(snapshot, "rb") as copied:
            _digest_and_validate(copied, arguments.sha256)
        print(json.dumps({"stage": stage}, separators=(",", ":")))
    finally:
        os.close(descriptor)


if __name__ == "__main__":
    try:
        main()
    except (InvalidBundle, OSError) as error:
        print(f"invalid administrative bundle: {error}", file=sys.stderr)
        sys.exit(2)
