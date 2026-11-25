"""Exercise the real JVM and Node maintenance module without a live Docker mount."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time

import a6_maintenance_ops as ops
import a6_v3_release_gate as release
from a6_production_deploy import DeployError

PREFIX = ["docker"]
DIGEST = re.compile(r"sha256:[0-9a-f]{64}")
STAGING = Path("/tmp")


def lab_mount(root, path, target, readonly=False):
    root = Path(root)
    path = Path(path)
    if (root.parent != STAGING or not root.name.startswith("a6-integrated-")
            or root.resolve() != root or path.resolve() != path or root not in path.parents
            or target not in {"/data/datomic-local", "/app/data", "/lab/node.cjs"}
            or any(piece in str(path) for piece in ("odisseu-a6-cutover", "docker.sock", "wwebjs", ","))):
        raise ValueError("unsafe_lab_mount")
    return f"type=bind,source={path},target={target}" + (",readonly" if readonly else "")


def restore_source(path):
    path = Path(path)
    if (path.parent != STAGING or not re.fullmatch(r"a6-lab-input-[a-f0-9]{12}", path.name)
            or path.resolve() != path or not path.is_dir()
            or any(p.is_symlink() or not (p.is_file() or p.is_dir()) for p in path.rglob("*"))):
        raise ValueError("unsafe_restore_source")
    return path


def manifest(path):
    hashes = {}
    for file in sorted(path.rglob("*")):
        if file.is_file():
            with file.open("rb") as stream:
                hashes[str(file.relative_to(path))] = hashlib.file_digest(stream, "sha256").hexdigest()
    return hashes


def docker(args, check=True):
    env = dict(os.environ)
    for key in ("DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH"):
        env.pop(key, None)
    return subprocess.run(["docker", "--host", "unix:///var/run/docker.sock", *args],
                          check=check, capture_output=True, text=True, timeout=150, env=env)


class Lab:
    def __init__(self, root):
        self.root = root
        self.names = {ops.NODE: root.name + "-node", ops.JVM: root.name + "-jvm"}
        self.created = set()
        self.datastore = root / "datomic"
        self.node_state = root / "node"
        self.node_state.mkdir()
        shutil.copyfile(Path(__file__).with_name("a6-lab-node.cjs"), root / "node.cjs")
        self.env = root / "tokens.env"
        self.env.write_text("ODISSEU_BRIDGE_MAINTENANCE_TOKEN=" + secrets.token_hex(32)
                            + "\nODISSEU_BRIDGE_TOKEN=" + secrets.token_hex(32)
                            + "\nODISSEU_NODE_MAINTENANCE_TOKEN=" + secrets.token_hex(32) + "\n")

    def exec(self, argv):
        if argv[:2] != ["docker", "exec"] or argv[2] not in self.names:
            raise ValueError("lab_only_exec")
        name = self.names[argv[2]]
        if name not in self.created:
            raise ValueError("unowned_lab_container")
        return docker(["exec", name, *argv[3:]], check=False)

    def replace(self, service, image, candidate):
        if not DIGEST.fullmatch(image):
            raise ValueError("immutable_image_required")
        name = self.names[service]
        if service == ops.JVM:
            args = ["--network", "none", "--memory", "950m", "--cap-add", "DAC_OVERRIDE",
                    "--mount", lab_mount(self.root, self.datastore, "/data/datomic-local"),
                    "-e", "ODISSEU_BRIDGE_HOST=127.0.0.1", "-e", "JAVA_TOOL_OPTIONS=-Xms64m -Xmx512m",
                    "-e", "ODISSEU_BRIDGE_RAID_AUTO_TICK=true", "-e", "ADVENTURE_AUTO_HATCH=true"]
            if candidate:
                args += ["-e", "POKEMON_PROGRESSIVE_XP_V3=true",
                         "-e", "A6_V3_RECONCILIATION_ENABLED=true",
                         "-e", "A6_V3_RECONCILIATION_ALLOWED_ID=" + release.POKEMON_ID]
            command = []
        else:
            jvm = self.names[ops.JVM]
            if jvm not in self.created:
                raise ValueError("unowned_lab_network")
            args = ["--network", "container:" + jvm, "--memory", "160m", "--user", f"{os.getuid()}:{os.getgid()}",
                    "--mount", lab_mount(self.root, self.node_state, "/app/data"),
                    "--mount", lab_mount(self.root, self.root / "node.cjs", "/lab/node.cjs", True),
                    "-e", "ODISSEU_NODE_MAINTENANCE_FILE=/app/data/a6-maintenance.json"]
            command = ["node", "/lab/node.cjs"]
        docker(["run", "-d", "--name", name, "--pull", "never", "--cpus", "0.8",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--env-file", str(self.env), *args, image, *command])
        self.created.add(name)
        self.inspect(service)

    def inspect(self, service):
        name = self.names[service]
        obj = json.loads(docker(["inspect", name]).stdout)[0]
        config = obj["HostConfig"]
        expected = "none" if service == ops.JVM else "container:" + self.names[ops.JVM]
        actual = config["NetworkMode"]
        if service == ops.NODE and actual.startswith("container:"):
            jvm_id = docker(["inspect", "--format", "{{.Id}}", self.names[ops.JVM]]).stdout.strip()
            if actual == "container:" + jvm_id:
                actual = expected
        if config["Privileged"] or config["PortBindings"] or actual != expected:
            raise RuntimeError("lab_network_or_privileges")
        allowed = {"/data/datomic-local"} if service == ops.JVM else {"/app/data", "/lab/node.cjs"}
        for mount in obj["Mounts"]:
            if mount["Type"] != "bind" or mount["Destination"] not in allowed:
                raise RuntimeError("unexpected_lab_mount")
            lab_mount(self.root, mount["Source"], mount["Destination"])
        return {"container": name, "network": actual, "mounts": [m["Source"] for m in obj["Mounts"]]}

    def stop(self, service):
        name = self.names[service]
        if name in self.created:
            docker(["stop", "--time", "25", name])
            docker(["rm", name])
            self.created.remove(name)

    def wait(self, probe):
        for _ in range(105):
            try:
                return probe()
            except (DeployError, subprocess.SubprocessError, ValueError):
                time.sleep(2)
        raise RuntimeError("lab_readiness_timeout")

    def health(self):
        result = self.exec(["docker", "exec", ops.NODE, "node", "-e",
                            "fetch('http://127.0.0.1:3001/lab/health').then(async r=>{if(!r.ok)process.exit(1);console.log(await r.text())}).catch(()=>process.exit(1))"])
        if result.returncode:
            raise DeployError("lab_node_unavailable")
        return json.loads(result.stdout)

    def sole_writer(self):
        names = docker(["ps", "-q"]).stdout.split()
        docs = json.loads(docker(["inspect", *names]).stdout)
        holders = [d["Name"].lstrip("/") for d in docs
                   if any(m["Source"] == str(self.datastore) for m in d["Mounts"])]
        if holders != [self.names[ops.JVM]]:
            raise RuntimeError("lab_writer_not_exclusive")

    def rehearse_release(self):
        before = release.prepare(self.exec, PREFIX)
        release.validate_pre_audit(before)
        try:
            release.reconcile(self.exec, PREFIX, "0" * 64)
        except DeployError:
            if release.audit(self.exec, PREFIX) != before:
                raise RuntimeError("rejected_fingerprint_changed_restore")
        else:
            raise RuntimeError("accepted_wrong_fingerprint")
        evidence = release.commit_once(self.exec, PREFIX, before)
        print("LAB_RECONCILIATION_VERIFIED", flush=True)
        self.sole_writer()
        ops.resume_jvm(self.exec, PREFIX)
        ops.resume_node(self.exec, PREFIX)
        return evidence

    def run(self, source, args):
        shutil.copytree(source, self.datastore)
        # The original T0 was taken quiescent. Only the disposable copy changes here.
        (self.datastore / ".a6-maintenance" / "bridge-maintenance.edn").write_text("{:phase :open}\n")
        self.replace(ops.JVM, args.old_jvm, False)
        self.replace(ops.NODE, args.old_node, False)
        self.wait(self.health)
        self.sole_writer()
        print("LAB_PREFLIGHT_OK", flush=True)
        ops.enter_node(self.exec, PREFIX)
        ops.enter_jvm(self.exec, PREFIX)
        release.freeze(self.exec, PREFIX)
        self.stop(ops.NODE)
        readable = self.exec(["docker", "exec", ops.JVM, "find", "/data/datomic-local",
                              "-uid", "0", "-exec", "chmod", "a+rX", "{}", "+"])
        readable.check_returncode()
        self.stop(ops.JVM)
        cold = self.root / "backup"
        shutil.copytree(self.datastore, cold)
        restored = self.root / "restored"
        shutil.copytree(cold, restored)
        files = manifest(cold)
        if files != manifest(restored):
            raise RuntimeError("lab_restore_integrity")
        self.datastore = restored
        print("LAB_COLD_RESTORE_VERIFIED", flush=True)
        self.replace(ops.JVM, args.jvm, True)
        self.replace(ops.NODE, args.node, True)
        self.wait(self.health)
        self.sole_writer()
        print("LAB_CANDIDATE_READY", flush=True)
        evidence = self.rehearse_release()
        health = self.wait(self.health)
        if health != {"whatsapp": "SIMULATED", "bridge": "up", "phase": "open"}:
            raise RuntimeError("lab_final_health_invalid")
        return {"scope": "isolated maintenance rehearsal", "hold": "untouched",
                "head": args.sha, "images": {"node": args.node, "jvm": args.jvm},
                "isolation": [self.inspect(ops.JVM), self.inspect(ops.NODE)],
                "backupSha256": hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(),
                "backupFiles": len(files), "badFingerprint": "REJECTED",
                "reconciliation": evidence, "health": health}


def main():
    os.umask(0o077)
    args = argparse.ArgumentParser()
    for name in ("source", "old-jvm", "old-node", "jvm", "node", "sha"):
        args.add_argument("--" + name, required=True)
    args = args.parse_args()
    source = restore_source(args.source)
    for image in (args.jvm, args.node):
        identity = docker(["image", "inspect", "--format",
                           '{{.Architecture}} {{index .Config.Labels "org.opencontainers.image.revision"}}', image])
        if identity.stdout.strip() != "amd64 " + args.sha:
            raise ValueError("release_image_mismatch")
    lab = Lab(Path(tempfile.mkdtemp(prefix="a6-integrated-", dir=STAGING)))
    print(str(lab.root), flush=True)
    try:
        report = lab.run(source, args)
        (lab.root / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report), flush=True)
    finally:
        lab.stop(ops.NODE)
        lab.stop(ops.JVM)


if __name__ == "__main__":
    main()
