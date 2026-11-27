"""Kit administrativo determinístico; não instala nem acessa produção."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tarfile
import subprocess

from a6_validate_admin_bundle import EXPECTED, validate


def build(directory, revision):
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("admin_revision_invalid")
    root = Path(__file__).resolve().parent.parent
    directory = Path(directory)
    payload = {}
    for name in EXPECTED - {"bundle-manifest.json"}:
        source = (root / "docker" / name if name == "chromium-seccomp.json" else
                  root / "modules/pokemon/infrastructure/pokemon/infrastructure/datomic" / name
                  if name == "xp_reconciliation.clj" else root / "scripts" / name)
        payload[name] = source.read_bytes()
    payload["bundle-manifest.json"] = json.dumps({
        "schema": 1, "revision": revision,
        "files": {n: hashlib.sha256(v).hexdigest() for n, v in sorted(payload.items())}
    }, sort_keys=True, separators=(",", ":")).encode()
    archive = directory / "admin.tar"
    with tarfile.open(archive, "w", format=tarfile.USTAR_FORMAT) as out:
        for name, data in sorted(payload.items()):
            info = tarfile.TarInfo(name)
            info.size, info.mode = len(data), 0o600
            out.addfile(info, io.BytesIO(data))
    bundle_hash = hashlib.sha256(archive.read_bytes()).hexdigest()
    validate(archive.read_bytes(), bundle_hash)
    kit = {"revision": revision, "bundleSha256": bundle_hash, "files": {}}
    for name in ("a6-provision-admin.sh", "a6_validate_admin_bundle.py"):
        shutil.copyfile(root / "scripts" / name, directory / name)
    for name in ("admin.tar", "a6-provision-admin.sh", "a6_validate_admin_bundle.py"):
        kit["files"][name] = hashlib.sha256((directory / name).read_bytes()).hexdigest()
    kit["seccompSha256"] = hashlib.sha256(payload["chromium-seccomp.json"]).hexdigest()
    (directory / "admin-kit.json").write_text(json.dumps(kit, sort_keys=True, indent=2) + "\n")
    return kit


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--directory", required=True)
    p.add_argument("--sha", required=True)
    a = p.parse_args()
    root = Path(__file__).resolve().parent.parent
    actual = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, check=True,
                            text=True, capture_output=True).stdout.strip()
    if actual != a.sha or subprocess.run(["git", "diff", "--quiet", "HEAD"], cwd=root).returncode:
        raise SystemExit("admin_source_revision_mismatch")
    print(json.dumps(build(a.directory, a.sha), sort_keys=True))
