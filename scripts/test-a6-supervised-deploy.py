#!/usr/bin/env python3
"""Janela supervisionada. Volumes temporários e runner falso; nada de produção."""

import json
import os
import shutil
import subprocess
import tempfile
import unittest
import uuid
from pathlib import Path
from unittest.mock import patch

from a6_image_transfer import (
    CANDIDATES,
    PREVIOUS_IMAGES,
    execute_transfer,
    transfer_commands,
    verify_transfer,
)
from a6_production_contract import CURRENT, VM
from a6_production_deploy import DeployError
from a6_supervised_deploy import (
    QUIESCENT,
    RESUME_PHRASE,
    SUPERVISED_STEPS,
    _apply_confirmed,
    evaluate_supervised,
    run_supervised,
)
from a6_writer_policy import INVENTORY


ROOT = Path(__file__).resolve().parents[1]
SHA = "faedebd5036124eb4f946147c5012c48122acc69"
JVM_DIGEST = "sha256:" + ("ab" * 32)
NODE_DIGEST = "sha256:" + ("cd" * 32)
ENV = {
    "A6_EXECUTE": "1",
    "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
    "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
    "ODISSEU_A6_DELIVERY_VALIDATED": "true",
    "A6_SUPERVISED_WINDOW": "janela-supervisionada-a6",
    "A6_RESUME_EXPLICIT": RESUME_PHRASE,
}
SAVE = "{:player \"ada\" :coins 40}\n"


class Result:
    def __init__(self, stdout="", returncode=0):
        self.stdout = stdout
        self.returncode = returncode


def _inspect(service, publish, running, privileged, cap_add, health):
    host, host_port, container_port = publish.split(":")
    mounts = [{
        "Type": item["type"],
        "Name": item.get("name") or "",
        "Source": item.get("source") or "",
        "Destination": item["destination"],
        "RW": item["rw"],
    } for item in service["mounts"]]
    env = []
    for key in service["envKeys"]:
        env.append(f"{key}=super-secret-value" if "TOKEN" in key or key.endswith("_KEY") else f"{key}=publico")
    security = ["no-new-privileges:true", "seccomp={\"profile\":true}"] if "seccomp" in service["security"] else service["security"]
    return {
        "Name": "/" + service["name"],
        "Id": service["imageId"],
        "Image": service["imageId"],
        "Config": {"Image": service["image"], "User": service["user"], "Env": env},
        "HostConfig": {
            "Privileged": privileged,
            "CapAdd": cap_add,
            "CapDrop": service["capDrop"],
            "SecurityOpt": security,
            "RestartPolicy": {"Name": service["restart"]},
            "Memory": service["memory"],
            "NetworkMode": service["network"],
            "PortBindings": {f"{container_port}/tcp": [{"HostIp": host, "HostPort": host_port}]},
        },
        "Mounts": mounts,
        "State": {
            "Running": running,
            "Status": "running" if running else "exited",
            "Health": {"Status": health},
        },
    }


class World:
    """VM falsa com o comportamento do Docker 29 que a janela depende."""

    def __init__(self):
        self.hostname = "odisseu-core"
        self.architecture = "amd64"
        self.revision = SHA
        self.jvm_id = JVM_DIGEST
        self.node_id = NODE_DIGEST
        self.privileged = False
        self.cap_add = []
        self.cap_eff = "00000000a80425fb"
        self.cap_after_run = None
        self.processes = "clojure.main -m odisseu.bridge.http-server\n"
        self.writes = 0
        self.ops = 0
        self.admitted = 0
        self.completed = 0
        self.failed = 0
        self.rejected = 0
        self.node_phase = "open"
        self.jvm_phase = "open"
        self.unauthorized = False
        self.jvm_open_zero = False
        self.datomic_up = True
        self.health = "healthy"
        self.contract = "up"
        self.whatsapp = "READY"
        self.chromium = True
        self.battle = ""
        self.entrypoint = "/usr/local/bin/jvm-storage-entrypoint.sh"
        self.open = False
        self.ran_jvm = False
        self.status_fault = None
        self.status_calls = {"node": 0, "jvm": 0}
        self.jvm_marker_fault = None
        self.extra_holders = []
        self.containers = {
            "odisseu-a6-jvm": {"image": CURRENT["jvm"]["imageId"], "running": True},
            "odisseu-a6-node": {"image": CURRENT["node"]["imageId"], "running": True},
        }

    @property
    def jvm_running(self):
        return bool((self.containers.get("odisseu-a6-jvm") or {}).get("running"))

    def state(self):
        return dict(self.__dict__)

    def load(self, state):
        self.__dict__.update(state)
        return self

    def _tags(self):
        return {
            "odisseu-jvm:a6-%s-amd64" % SHA[:12]: self.jvm_id,
            "odisseu-node:a6-%s" % SHA[:12]: self.node_id,
            CURRENT["jvm"]["image"]: CURRENT["jvm"]["imageId"],
            CURRENT["node"]["image"]: CURRENT["node"]["imageId"],
        }

    def _resolve(self, ref):
        """tag@sha256 só resolve se a tag aponta para esse ID, como no containerd store."""
        tags = self._tags()
        if ref.startswith("sha256:"):
            return ref if ref in tags.values() else None
        if "@sha256:" in ref:
            tag, digest = ref.split("@", 1)
            return digest if tags.get(tag) == digest else None
        return tags.get(ref)

    def documents(self):
        documents = []
        for name, service, publish, privileged, cap_add in (
            ("odisseu-a6-jvm", CURRENT["jvm"], CURRENT["jvm"]["publish"], self.privileged, self.cap_add),
            ("odisseu-a6-node", CURRENT["node"], CURRENT["node"]["publish"], False, []),
        ):
            container = self.containers.get(name)
            if container is None:
                return None
            document = _inspect(service, publish, container["running"], privileged, cap_add, self.health)
            document["Image"] = container["image"]
            documents.append(document)
        return documents

    def _holders(self):
        rows = []
        for name, container in sorted(self.containers.items()):
            if name.startswith("odisseu-a6-jvm"):
                rows.append("%s\t%s" % (name, "running" if container["running"] else "exited"))
        rows.extend(self.extra_holders)
        return Result("\n".join(rows) + ("\n" if rows else ""))

    def _docker(self, args):
        index = args.index("docker")
        return args[index + 1], args[index + 2:]

    def runner(self, args):
        if args == ["docker", "info"]:
            return Result("", 1)
        if args == ["sudo", "-n", "docker", "info"]:
            return Result("ok")
        if args == ["hostname"]:
            return Result(self.hostname + "\n")
        if args[:1] == ["curl"] and args[-1].startswith("http://metadata.google.internal/computeMetadata/v1/"):
            values = {"instance/name": "odisseu-core", "instance/zone": "projects/123/zones/us-central1-a",
                      "project/project-id": VM["project"], "instance/id": "123"}
            path = args[-1].split("/v1/", 1)[1]
            return Result("HTTP/1.1 200 OK\r\nMetadata-Flavor: Google\r\n\r\n" + values[path])
        if args[:1] == ["df"]:
            return Result("Avail\n2000000000\n")
        if "docker" not in args:
            return Result("comando inesperado: " + " ".join(args), 1)
        verb, rest = self._docker(args)
        if verb == "exec":
            name = rest[0]
            container = self.containers.get(name)
            if container is None or not container["running"]:
                return Result("Error response from daemon: container %s is not running" % name, 1)
            return self._exec(name, rest[1:])
        if verb == "ps":
            if "volume=odisseu-a6-cutover-datomic" in rest:
                return self._holders()
            if "name=odisseu-a6-" in rest:
                return Result("".join(name + "\n" for name in sorted(self.containers)))
            return Result("", 1)
        if verb == "image":
            return self._image(rest)
        if verb == "inspect":
            return self._inspect_cmd(rest)
        if verb == "stop":
            container = self.containers.get(rest[-1])
            if container is None:
                return Result("No such container", 1)
            container["running"] = False
            return Result(rest[-1] + "\n")
        if verb == "rename":
            source, target = rest
            if source not in self.containers or target in self.containers:
                return Result("Error response from daemon: rename conflict", 1)
            self.containers[target] = self.containers.pop(source)
            return Result("")
        if verb == "update":
            return Result("") if rest[-1] in self.containers else Result("No such container", 1)
        if verb == "start":
            container = self.containers.get(rest[-1])
            if container is None:
                return Result("No such container", 1)
            container["running"] = True
            return Result("")
        if verb == "run":
            return self._run(rest)
        return Result("comando inesperado: " + " ".join(args), 1)

    def _run(self, rest):
        name = rest[rest.index("--name") + 1]
        if name in self.containers:
            return Result("Conflict. The container name is already in use", 125)
        if "--pull" not in rest or rest[rest.index("--pull") + 1] != "never":
            return Result("pull de registry não permitido no teste", 125)
        if any("<" in str(part) and ">" in str(part) for part in rest):
            return Result("invalid argument", 125)
        if "--env-file" in rest and not Path(rest[rest.index("--env-file") + 1]).is_file():
            return Result("open env file: no such file or directory", 125)
        image = self._resolve(rest[-1])
        if image is None:
            return Result("Error response from daemon: No such image: " + rest[-1], 125)
        self.containers[name] = {"image": image, "running": True}
        if name == "odisseu-a6-jvm":
            self.ran_jvm = True
            self.writes = 0
            self.admitted = self.completed = self.failed = self.rejected = 0
        return Result("container-id\n")

    def _image(self, rest):
        template = rest[rest.index("--format") + 1]
        ref = rest[-1]
        image = self._resolve(ref)
        if image is None:
            return Result("Error response from daemon: No such image: " + ref, 1)
        if "Architecture" in template:
            return Result(f"{self.architecture}\t{self.revision}\n")
        if "{{.Id}}" in template:
            return Result(image + "\n")
        return Result("", 1)

    def _inspect_cmd(self, rest):
        if "--format" not in rest:
            if rest[-2:] == ["odisseu-a6-jvm", "odisseu-a6-node"]:
                documents = self.documents()
                if documents is None:
                    return Result("Error: No such object", 1)
                return Result(json.dumps(documents))
            return Result("", 1)
        template = rest[rest.index("--format") + 1]
        name = rest[-1]
        if template == RESUME_PHRASE:
            self.open = True
            return Result(RESUME_PHRASE + "\n")
        container = self.containers.get(name)
        if container is None:
            return Result("Error: No such object: " + name, 1)
        if template == "{{.Image}}":
            return Result(container["image"] + "\n")
        if template == "{{.State.Running}}":
            return Result(("true" if container["running"] else "false") + "\n")
        if "Health.Status" in template:
            return Result(self.health + "\n")
        if "Privileged" in template:
            return Result("true\n" if self.privileged else "false\n")
        if "Entrypoint" in template or "Cmd" in template:
            return Result(self.entrypoint + "\n")
        return Result("", 1)

    def _exec(self, name, rest):
        script = str(rest[-1]) if rest else ""
        if len(rest) >= 2 and rest[-2] == "-c" and ("curl" in script or "node -e" in script):
            return self._http(script)
        if rest[-1] == "/proc/self/status":
            cap = self.cap_after_run if self.ran_jvm and self.cap_after_run else self.cap_eff
            return Result(f"CapEff:\t{cap}\n")
        if rest[-1] == "args":
            return Result(self.processes)
        if str(rest[-1]).endswith("bridge-maintenance.edn"):
            if self.jvm_marker_fault == "garbage":
                return Result("{:phase")
            if self.jvm_marker_fault == "nil":
                return Result("{:phase :%s :active-writes nil}\n" % self.jvm_phase)
            if self.jvm_phase == "quiescent":
                return Result("{:phase :quiescent :quiescent-at \"t\" :active-writes %s}\n" % self.writes)
            return Result("{:phase :%s :updated-at \"t\"}\n" % self.jvm_phase)
        if str(rest[-1]).endswith("a6-maintenance.json"):
            return Result(json.dumps({"phase": self.node_phase, "updatedAt": "t"}))
        if rest[-1] == "/data/datomic-local/battle-format.edn":
            if not self.battle:
                return Result("", 1)
            return Result(self.battle)
        if rest[-1] == CONTRACT_MARKER or (len(rest) >= 2 and rest[-2] == "-e"):
            if self.contract != "up":
                return Result("", 2)
            return Result("up")
        if rest[-1] == "/app/scripts/healthcheck.js":
            body = json.dumps({"status": "ok", "whatsapp": self.whatsapp, "chromium": self.chromium})
            code = 0 if self.whatsapp == "READY" and self.chromium else 1
            return Result(body + "\n", code)
        return Result("")

    def _jvm_body(self, quiescent):
        data = {
            "phase": self.jvm_phase,
            "active-writes": self.writes,
            "admitted-writes": self.admitted,
            "completed-writes": self.completed,
            "failed-writes": self.failed,
            "rejected-writes": self.rejected,
        }
        if quiescent:
            data["quiescent?"] = True
        return data

    def _fault(self, service, body):
        """Falha só depois do status que o próprio enter lê."""
        fault = self.status_fault
        if not fault or fault[0] != service:
            return body
        self.status_calls[service] += 1
        if self.status_calls[service] <= fault[2]:
            return body
        kind = fault[1]
        data = body if service == "node" else body["data"]
        key = "activeOperations" if service == "node" else "active-writes"
        if kind == "missing":
            data.pop(key, None)
        elif kind == "null":
            data[key] = None
        elif kind == "garbage":
            return None
        elif kind == "admitted":
            data.pop("admitted-writes", None)
        return body

    def _http(self, script):
        if self.unauthorized and "/ops/maintenance/" in script:
            return Result(json.dumps({"status": "error", "code": "unauthorized"}) + "\n401")
        if "/health" in script and "/ops/" not in script:
            status = "up" if self.datomic_up else "down"
            body = {
                "ok": self.datomic_up,
                "data": {
                    "status": status,
                    "maintenance": {"phase": self.jvm_phase, "active-writes": self.writes},
                },
            }
            return Result(json.dumps(body) + "\n" + ("200" if self.datomic_up else "503"))
        node = "3001" in script
        post = " -X POST " in script or 'method:"POST"' in script or "method:'POST'" in script
        if node and post and "/enter" in script:
            if self.ops != 0:
                self.node_phase = "draining"
                body = {"phase": "draining", "activeOperations": self.ops, "durable": True, "quiescent": False}
                return Result(json.dumps(body) + "\n409")
            self.node_phase = "quiescent"
            self.ops = 0
            body = {"phase": "quiescent", "activeOperations": 0, "durable": True, "quiescent": True}
            return Result(json.dumps(body) + "\n200")
        if node and post and "/resume" in script:
            if self.node_phase not in ("quiescent", "frozen") or self.ops != 0:
                return Result(json.dumps({"status": "error", "code": "maintenance_resume_blocked"}) + "\n409")
            self.node_phase = "open"
            return Result(json.dumps({"phase": "open", "activeOperations": 0, "durable": True}) + "\n200")
        if node:
            body = self._fault("node", {"phase": self.node_phase, "activeOperations": self.ops, "durable": True})
            if body is None:
                return Result("<html>erro\n200")
            return Result(json.dumps(body) + "\n200")
        if post and "/enter" in script:
            if self.jvm_open_zero:
                self.jvm_phase = "open"
                self.writes = 0
                return Result(json.dumps({"ok": True, "data": self._jvm_body(False)}) + "\n200")
            if self.jvm_phase == "frozen":
                return Result(json.dumps({"ok": False, "error": {"code": "maintenance_enter_forbidden_while_frozen"}}) + "\n500")
            if self.writes != 0:
                self.jvm_phase = "draining"
                return Result(json.dumps({
                    "ok": False,
                    "error": {"code": "maintenance_drain_timeout", "details": {"active-writes": self.writes, "phase": "draining"}},
                }) + "\n409")
            self.jvm_phase = "quiescent"
            return Result(json.dumps({"ok": True, "data": self._jvm_body(True)}) + "\n200")
        if post and "/resume" in script:
            if self.jvm_phase not in ("quiescent", "frozen") or self.writes != 0:
                return Result(json.dumps({"ok": False, "error": {"code": "maintenance_resume_blocked"}}) + "\n409")
            self.jvm_phase = "open"
            return Result(json.dumps({"ok": True, "data": {"phase": "open", "resumed-at": "t"}}) + "\n200")
        body = self._fault("jvm", {"ok": True, "data": self._jvm_body(False)})
        if body is None:
            return Result("<html>erro\n200")
        return Result(json.dumps(body) + "\n200")


def materialize(argv, service):
    """Resolvedor de teste: arquivos temporários no lugar dos placeholders."""
    root = Path(tempfile.mkdtemp(prefix="a6sup-env-"))
    env_file = root / "env"
    env_file.write_text("PUBLICO=1\n", encoding="utf-8")
    os.chmod(env_file, 0o600)
    seccomp = root / "seccomp.json"
    seccomp.write_text("{}\n", encoding="utf-8")
    resolved = []
    for part in argv:
        if part == "<inspect-ao-vivo-sem-log>":
            resolved.append(str(env_file))
        elif part == "<seccomp-copiado-do-inspect-sem-log>":
            resolved.append("seccomp=" + str(seccomp))
        else:
            resolved.append(part)
    return resolved, lambda: shutil.rmtree(root, ignore_errors=True)


CONTRACT_MARKER = "pokemon-jvm-client.cjs"


def run_case(world, root, env=None, isolated=None):
    isolated_root = isolated or (root / "export")
    isolated_root.mkdir(parents=True, exist_ok=True)
    target = isolated_root / "save.edn"
    if not target.exists():
        target.write_text(SAVE, encoding="utf-8")
    return run_supervised(
        world.runner,
        SHA,
        JVM_DIGEST,
        NODE_DIGEST,
        dict(ENV if env is None else env),
        root / "backup",
        isolated_root,
        materialize=materialize,
    )


class SupervisedWindowTests(unittest.TestCase):
    def test_live_single_cutover_refuses_legacy_unguarded_runner(self):
        env = dict(ENV, A6_LIVE_BACKUP="autorizar-backup-producao-a6")
        world = World()
        report = run_supervised(world.runner, SHA, JVM_DIGEST, NODE_DIGEST,
                                env, "/var/backups/odisseu-a6/janela-admin/data",
                                None, materialize=materialize)
        self.assertIn("v3_single_cutover_protocol_required", report["blocked"])
        self.assertNotIn("stop", [x[3] for x in report["commands"] if len(x) > 3])
        self.assertNotIn("resume", report["steps"])

    def test_v3_gate_failure_does_not_resume_any_container(self):
        env = dict(ENV, A6_V3_SINGLE_CUTOVER="a6-v3-raid-auto-136")
        with tempfile.TemporaryDirectory() as tmp, \
             patch("a6_v3_release_gate.verify_rehearsal",
                   return_value={"manifestSha256": "a" * 64, "pokemonCount": 1336}), \
             patch("a6_v3_release_gate.prepare",
                   side_effect=DeployError("v3_pre_audit_unexpected")):
            world = World()
            report = run_case(world, Path(tmp), env=env)
        self.assertIn("v3_pre_audit_unexpected", report["blocked"])
        self.assertNotIn("resume", report["steps"])
        self.assertNotIn("v3_release_gate", report["steps"])

    def test_v3_gate_evidence_required_before_successful_resume(self):
        env = dict(ENV, A6_V3_SINGLE_CUTOVER="a6-v3-raid-auto-136")
        before = {
            "status": "BLOCKED", "blocked": 1, "pokemonCount": 1336,
            "playerCount": 39, "saveCount": 102, "eggCount": 43,
            "incubatorCount": 12, "inventoryCount": 158,
            "coinsTotal": 3055, "receiptCount": 8599,
            "targetLevel": 6, "targetResidual": 21,
            "blockedDetails": [{"id": "abb8955f-7ebe-4084-a634-fd74cfa9bf62",
                                "reason": "flat9-ambiguous-legacy-xp"}],
            "protectedSha256": "c" * 64,
        }
        candidate = {
            "status": "ready", "pokemonId": "abb8955f-7ebe-4084-a634-fd74cfa9bf62",
            "level": 6, "rawXp": 21, "residualBefore": 21, "residualAfter": 8,
            "snapshotSha256": "a" * 64,
        }
        with tempfile.TemporaryDirectory() as tmp, \
             patch("a6_v3_release_gate.verify_rehearsal",
                   return_value={"manifestSha256": "b" * 64, "pokemonCount": 1336}), \
             patch("a6_v3_release_gate.prepare", return_value=before), \
             patch("a6_v3_release_gate.preview", return_value=candidate), \
             patch("a6_v3_release_gate.commit_once",
                   side_effect=lambda wrapped, prefix, audited, candidate=None: (
                       setattr(world, "jvm_phase", "frozen") or
                       {"receiptId": "only-once", "pokemonCount": 1336,
                        "protectedSha256": "c" * 64})):
            world = World()
            report = run_case(world, Path(tmp), env=env)
        self.assertTrue(report["deployComplete"], report["blocked"])
        gate = next(x for x in report["checkpoints"]
                    if x["step"] == "v3_release_gate")
        self.assertTrue(gate["verified"])
        self.assertEqual(gate["receiptId"], "only-once")


    def test_live_backup_uses_only_admin_attestation(self):
        """Runner sem root não toca paths de volume, backup, manifesto ou restore."""
        receipt = {
            "window": "janela-admin", "operation": "status", "reused": False,
            "manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True,
            "volume": "odisseu-a6-cutover-datomic", "files": 2, "manifestSha256": "a" * 64,
        }
        destination = "/var/backups/odisseu-a6/janela-admin/data"
        env = dict(ENV)
        env["A6_LIVE_BACKUP"] = "autorizar-backup-producao-a6"
        env["A6_STATE_DIR"] = "/tmp/a6-admin-journal"
        with patch("a6_v3_release_gate.release_mode", return_value=False), \
             patch("a6_admin_backup_client.preflight", return_value={"ready": True, "window": "janela-admin", "operation": "preflight"}) as ready, \
             patch("a6_admin_backup_client.backup", return_value=receipt) as copied:
            report = run_supervised(World().runner, SHA, JVM_DIGEST, NODE_DIGEST, env, destination,
                                    None, materialize=materialize, window_id="janela-admin")
        self.assertTrue(report["deployComplete"], report["blocked"])
        backup = next(item for item in report["checkpoints"] if item["step"] == "backup")
        self.assertEqual(backup["t0Evidence"]["manifestSha256"], "a" * 64)
        self.assertEqual(backup["t0Evidence"]["windowId"], "janela-admin")
        ready.assert_called_once_with(destination)
        copied.assert_called_once_with(destination)
        commands = "\n".join(" ".join(item) for item in report["commands"])
        self.assertNotIn("/var/lib/docker/volumes/odisseu-a6-cutover-datomic/_data", commands)
        self.assertNotIn("backup-manifest.txt", commands)

    def test_live_resume_rechecks_completed_backup_with_the_helper(self):
        receipt = {
            "window": "janela-admin", "operation": "status", "reused": True,
            "manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True,
            "volume": "odisseu-a6-cutover-datomic", "files": 2, "manifestSha256": "a" * 64,
        }
        destination = "/var/backups/odisseu-a6/janela-admin/data"
        env = dict(ENV, A6_LIVE_BACKUP="autorizar-backup-producao-a6", A6_STATE_DIR="/tmp/a6-admin-journal")
        world = World()
        world.containers["odisseu-a6-jvm"]["image"] = JVM_DIGEST
        world.containers["odisseu-a6-jvm-previous"] = {"image": CURRENT["jvm"]["imageId"], "running": False}
        with patch("a6_v3_release_gate.release_mode", return_value=False), \
             patch("a6_admin_backup_client.preflight", return_value={"ready": True, "window": "janela-admin", "operation": "preflight"}), \
             patch("a6_admin_backup_client.status", return_value=receipt) as checked, \
             patch("a6_admin_backup_client.backup") as copied:
            report = run_supervised(world.runner, SHA, JVM_DIGEST, NODE_DIGEST, env, destination, None,
                                    prior={"steps": ["update_jvm"], "t0Evidence": {
                                        "windowId": "janela-admin", "manifestSha256": "a" * 64,
                                        "imageSha": SHA, "jvmDigest": JVM_DIGEST, "nodeDigest": NODE_DIGEST}},
                                    materialize=materialize, window_id="janela-admin")
        self.assertTrue(report["deployComplete"], report["blocked"])
        checked.assert_called_once_with(destination)
        copied.assert_not_called()

    def test_live_backup_rejects_reuse_without_same_window_checkpoint(self):
        destination = "/var/backups/odisseu-a6/janela-admin/data"
        env = dict(ENV, A6_LIVE_BACKUP="autorizar-backup-producao-a6", A6_STATE_DIR="/tmp/a6-admin-journal")
        receipt = {"window": "janela-admin", "reused": True, "manifestSha256": "a" * 64}
        with patch("a6_v3_release_gate.release_mode", return_value=False), \
             patch("a6_admin_backup_client.preflight"), \
             patch("a6_admin_backup_client.backup", return_value=receipt):
            report = run_supervised(World().runner, SHA, JVM_DIGEST, NODE_DIGEST, env, destination,
                                    None, materialize=materialize, window_id="janela-admin")
        self.assertIn("t0_reuse_without_checkpoint", report["blocked"])
        self.assertNotIn("update_jvm", report["steps"])
        self.assertNotIn("resume", report["steps"])

    def test_live_resume_rejects_t0_from_another_manifest(self):
        destination = "/var/backups/odisseu-a6/janela-admin/data"
        env = dict(ENV, A6_LIVE_BACKUP="autorizar-backup-producao-a6", A6_STATE_DIR="/tmp/a6-admin-journal")
        world = World()
        world.containers["odisseu-a6-jvm"]["image"] = JVM_DIGEST
        world.containers["odisseu-a6-jvm-previous"] = {"image": CURRENT["jvm"]["imageId"], "running": False}
        receipt = {"window": "janela-admin", "manifestSha256": "b" * 64}
        prior = {"steps": ["update_jvm"], "t0Evidence": {
            "windowId": "janela-admin", "manifestSha256": "a" * 64,
            "imageSha": SHA, "jvmDigest": JVM_DIGEST, "nodeDigest": NODE_DIGEST}}
        with patch("a6_v3_release_gate.release_mode", return_value=False), \
             patch("a6_admin_backup_client.preflight"), \
             patch("a6_admin_backup_client.status", return_value=receipt):
            report = run_supervised(world.runner, SHA, JVM_DIGEST, NODE_DIGEST, env, destination, None,
                                    prior=prior, materialize=materialize, window_id="janela-admin")
        self.assertFalse(report["deployComplete"])
        self.assertIn("t0_checkpoint_mismatch", report["blocked"])
        self.assertNotIn("resume", report["steps"])

    def test_window_reaches_open_and_keeps_the_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = run_case(World(), root)
            self.assertTrue(report["deployComplete"], report["blocked"])
            self.assertEqual(report["steps"], list(SUPERVISED_STEPS))
            self.assertFalse(report["executeAllowed"])
            self.assertFalse(report["datomicRestored"])
            self.assertTrue(report["sourcePreserved"])
            self.assertEqual((root / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)
            self.assertEqual((root / "restore-isolated" / "save.edn").read_text(encoding="utf-8"), SAVE)
            self.assertIsNone(evaluate_supervised(report["checkpoints"])["reason"])
            joined = [" ".join(command) for command in report["commands"]]
            text = "\n".join(joined)
            self.assertNotIn("volume rm", text)
            self.assertNotIn("compose down", text)
            self.assertNotIn("TOKEN=", text)
            jvm_run = next(index for index, command in enumerate(joined) if " run " in f" {command} " and "odisseu-a6-jvm" in command and "odisseu-node" not in command)
            node_run = next(index for index, command in enumerate(joined) if " run " in f" {command} " and "odisseu-a6-node" in command)
            stop = next(index for index, command in enumerate(joined) if command.endswith("stop --time 30 odisseu-a6-jvm"))
            enter = next(index for index, command in enumerate(joined) if "/ops/maintenance/enter" in command and "odisseu-a6-node" in command)
            drain = next(index for index, command in enumerate(joined) if "/ops/maintenance/enter" in command and "odisseu-a6-jvm" in command)
            resume = [command for command in joined if "/ops/maintenance/resume" in command]
            self.assertLess(enter, drain)
            self.assertLess(drain, stop)
            self.assertLess(stop, jvm_run)
            self.assertLess(jvm_run, node_run)
            self.assertEqual(len(resume), 2)
            self.assertNotIn("inspect --format " + RESUME_PHRASE, text)
            self.assertFalse(any(command.endswith("start odisseu-a6-jvm-previous") or command.endswith("start odisseu-a6-jvm-failed") for command in joined))
            markers = [
                command for command in joined
                if "a6-maintenance.json" in command or "bridge-maintenance.edn" in command
            ]
            self.assertGreater(len(markers), 0)
            self.assertTrue(all(" cat " in f" {command} " for command in markers))
            self.assertNotIn(">", "\n".join(markers))
            self.assertEqual(report["lastCompleted"], "validate_game")

    def test_declared_map_push_and_hold_do_not_start(self):
        with self.assertRaises(DeployError) as declared:
            run_supervised({"jvm": {}}, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), "/tmp/a6-none", "/tmp/a6-none-src")
        self.assertEqual(str(declared.exception), "declared_evidence")
        calls = []

        def runner(args):
            calls.append(args)
            return Result()

        with self.assertRaises(DeployError) as pushed:
            run_supervised(runner, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), "/tmp/a6-none", "/tmp/a6-none-src", event="push")
        self.assertEqual(str(pushed.exception), "push_not_authorized")
        self.assertEqual(calls, [])
        with self.assertRaises(DeployError) as pasted:
            evaluate_supervised([{"step": step} for step in SUPERVISED_STEPS])
        self.assertEqual(str(pasted.exception), "declared_evidence")
        held = dict(ENV)
        held["ODISSEU_PRODUCTION_DEPLOY_HOLD"] = "true"
        with tempfile.TemporaryDirectory() as tmp:
            report = run_case(World(), Path(tmp), env=held)
        self.assertFalse(report["deployComplete"])
        self.assertEqual(report["commands"], [])
        self.assertIn("hold_active", report["blocked"])

    def test_wrong_vm_image_digest_arch_and_revision_stop_before_the_jvm(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wrong_vm = World()
            wrong_vm.hostname = "outra-vm"
            refused = run_case(wrong_vm, root / "vm")
            self.assertEqual(refused["next"], "preflight")
            self.assertNotIn("stop", " ".join(" ".join(command) for command in refused["commands"]))
            mismatch = World()
            mismatch.jvm_id = "sha256:" + ("ef" * 32)
            digest = run_case(mismatch, root / "digest")
            self.assertIn("image_digest_mismatch", digest["blocked"])
            self.assertNotIn(" stop ", " " + " ".join(" ".join(command) for command in digest["commands"]) + " ")
            arm = World()
            arm.architecture = "arm64"
            architecture = run_case(arm, root / "arm")
            self.assertIn("image_not_amd64", architecture["blocked"])
            self.assertFalse(any(command[-2:] == ["stop", "odisseu-a6-jvm"] for command in architecture["commands"]))
            revision = World()
            revision.revision = "b04682f1a238"
            revised = run_case(revision, root / "rev")
            self.assertIn("image_revision_mismatch", revised["blocked"])

    def test_drain_writer_capability_and_backup_failures_keep_the_source(self):
        cases = []
        drain = World()
        drain.writes = 1
        cases.append((drain, "jvm_drain_timeout", "jvm_drain"))
        writer = World()
        writer.processes = INVENTORY[0]["path"] + "\n"
        cases.append((writer, "external_datomic_client", "quiescence"))
        privileged = World()
        privileged.privileged = True
        cases.append((privileged, "privileged_or_sys_admin", "preflight"))
        admin = World()
        admin.cap_add = ["SYS_ADMIN"]
        cases.append((admin, "privileged_or_sys_admin", "preflight"))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for world, reason, nxt in cases:
                report = run_case(world, root / reason)
                self.assertIn(reason, report["blocked"], report)
                self.assertEqual(report["next"], nxt)
                self.assertFalse(report["datomicRestored"])
                self.assertFalse(any("stop" in command and command[-1] == "odisseu-a6-jvm" for command in report["commands"]))
                self.assertEqual((root / reason / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)
            missing = run_supervised(
                World().runner,
                SHA,
                JVM_DIGEST,
                NODE_DIGEST,
                dict(ENV),
                root / "missing-backup",
                root / "does-not-exist",
                materialize=materialize,
            )
            self.assertEqual(missing["next"], "backup")
            self.assertFalse(missing["datomicRestored"])
            self.assertTrue(any(command[-1] == "odisseu-a6-jvm" and "start" in command for command in missing["commands"]))
            self.assertFalse(any("run" in command and command[command.index("docker") + 1] == "run" for command in missing["commands"]))

    def test_health_contract_whatsapp_resume_and_battle_do_not_restore_datomic(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            unhealthy = World()
            unhealthy.health = "unhealthy"
            health = run_case(unhealthy, root / "health")
            self.assertIn("health_unhealthy", health["blocked"])
            self.assertEqual(health["recovery"], "rollback")
            self.assertFalse(health["datomicRestored"])
            self.assertEqual((root / "health" / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)
            battle = World()
            battle.health = "unhealthy"
            battle.battle = "{:volatil true}\n"
            forward = run_case(battle, root / "battle")
            self.assertEqual(forward["recovery"], "forward_only")
            self.assertIn("rollback_incompatible_battle_edn", forward["blocked"])
            self.assertFalse(any(len(command) >= 2 and command[-2] == "start" for command in forward["commands"]))
            contract = World()
            contract.contract = "down"
            incompatible = run_case(contract, root / "contract")
            self.assertIn("contract_incompatible", incompatible["blocked"])
            self.assertFalse(any("odisseu-a6-node" in " ".join(command) and " run " in f" {' '.join(command)} " for command in incompatible["commands"]))
            starting = World()
            starting.whatsapp = "STARTING"
            whatsapp = run_case(starting, root / "whatsapp")
            self.assertEqual(whatsapp["next"], "validate_whatsapp")
            self.assertIn("whatsapp_not_ready", whatsapp["blocked"])
            self.assertFalse(whatsapp["deployComplete"])
            quiet = dict(ENV)
            quiet.pop("A6_RESUME_EXPLICIT")
            resume = run_case(World(), root / "resume", env=quiet)
            self.assertEqual(resume["next"], "resume")
            self.assertIn("resume_not_explicit", resume["blocked"])
            joined_resume = " ".join(" ".join(command) for command in resume["commands"])
            self.assertNotIn(RESUME_PHRASE, joined_resume)
            self.assertNotIn("/ops/maintenance/resume", joined_resume)
            self.assertNotIn("confirm_open", resume["steps"])

    def test_maintenance_api_failures_stop_before_the_jvm(self):
        cases = []
        unauthorized = World()
        unauthorized.unauthorized = True
        cases.append((unauthorized, "maintenance_unauthorized", "node_maintenance"))
        busy = World()
        busy.ops = 1
        cases.append((busy, "node_maintenance_timeout", "node_maintenance"))
        open_zero = World()
        open_zero.jvm_open_zero = True
        cases.append((open_zero, "jvm_not_quiescent", "jvm_drain"))
        failed = World()
        failed.failed = 1
        failed.admitted = 1
        cases.append((failed, "jvm_writes_failed", "jvm_drain"))
        incomplete = World()
        incomplete.admitted = 2
        cases.append((incomplete, "jvm_writes_incomplete", "jvm_drain"))
        frozen = World()
        frozen.jvm_phase = "frozen"
        cases.append((frozen, "maintenance_state_unknown", "jvm_drain"))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for world, reason, nxt in cases:
                report = run_case(world, root / reason)
                self.assertIn(reason, report["blocked"], report)
                self.assertEqual(report["next"], nxt)
                self.assertFalse(report["deployComplete"])
                self.assertFalse(any(
                    "stop" in command and command[-1] == "odisseu-a6-jvm" for command in report["commands"]
                ))
                self.assertEqual((root / reason / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)

    def test_quiescence_without_counters_never_stops_the_jvm(self):
        cases = []
        for service in ("node", "jvm"):
            for kind in ("missing", "null", "garbage"):
                world = World()
                world.status_fault = (service, kind, 1)
                cases.append((world, f"{service}-{kind}"))
        admitted = World()
        admitted.status_fault = ("jvm", "admitted", 1)
        cases.append((admitted, "jvm-admitted"))
        garbage_marker = World()
        garbage_marker.jvm_marker_fault = "garbage"
        cases.append((garbage_marker, "marker-garbage"))
        nil_marker = World()
        nil_marker.jvm_marker_fault = "nil"
        cases.append((nil_marker, "marker-nil"))
        writer = World()
        writer.extra_holders = ["odisseu-datomic-console\trunning"]
        cases.append((writer, "second-holder"))
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for world, label in cases:
                report = run_case(world, root / label)
                self.assertFalse(report["deployComplete"], label)
                self.assertIn(report["next"], ("quiescence", "jvm_drain"), (label, report["blocked"]))
                self.assertTrue(report["blocked"], label)
                self.assertFalse(any(
                    "stop" in command and command[-1] == "odisseu-a6-jvm" for command in report["commands"]
                ), label)
                self.assertTrue(world.containers["odisseu-a6-jvm"]["running"], label)
                self.assertNotIn("quiescence", report["steps"], label)
        drained = {"node": {"phase": "quiescent"}, "jvm": {"phase": "quiescent"}}
        live = {
            "node": {"phase": "quiescent", "activeOperations": 0, "durable": True},
            "jvm": {"phase": "quiescent", "activeWrites": 0, "admittedWrites": 3, "completedWrites": 3, "failedWrites": 0},
        }
        self.assertEqual(_apply_confirmed(drained, QUIESCENT, live)["admittedWrites"], 3)
        for broken in (None, {}, {"node": live["node"]}):
            with self.assertRaises(DeployError) as missing:
                _apply_confirmed(drained, QUIESCENT, broken)
            self.assertEqual(str(missing.exception), "quiescence_counters_missing")
        for key, value in (("activeWrites", None), ("activeWrites", "0"), ("activeWrites", True), ("completedWrites", None)):
            changed = json.loads(json.dumps(live))
            changed["jvm"][key] = value
            with self.assertRaises(DeployError) as bad:
                _apply_confirmed(drained, QUIESCENT, changed)
            self.assertEqual(str(bad.exception), "quiescence_counters_missing")
        changed = json.loads(json.dumps(live))
        changed["node"].pop("activeOperations")
        with self.assertRaises(DeployError):
            _apply_confirmed(drained, QUIESCENT, changed)
        marked = {"node": {"phase": "quiescent", "activeOpsMarked": True, "activeOps": None}, "jvm": {"phase": "quiescent"}}
        with self.assertRaises(DeployError) as unreadable:
            _apply_confirmed(marked, QUIESCENT, live)
        self.assertEqual(str(unreadable.exception), "quiescence_counters_unreadable")

    def test_run_reference_is_pinned_and_local(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            report = run_case(World(), root / "ok")
            self.assertTrue(report["deployComplete"], report["blocked"])
            runs = [command for command in report["commands"] if "docker" in command and command[command.index("docker") + 1] == "run"]
            self.assertEqual(len(runs), 2)
            for command in runs:
                self.assertEqual(command[command.index("--pull") + 1], "never")
                self.assertIn("@sha256:", command[-1])
                self.assertNotIn("<", " ".join(command))
            self.assertTrue(any(command[-3:-1] == ["--format", "{{.Image}}"] for command in report["commands"]))
            unresolved = run_supervised(
                World().runner, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), root / "raw" / "backup", root / "export",
            )
            self.assertIn("run_argv_unresolved", unresolved["blocked"])
            self.assertEqual(unresolved["next"], "images")
            self.assertFalse(any("/ops/maintenance/enter" in " ".join(command) for command in unresolved["commands"]))
            moved = World()

            def swapping(args):
                if "docker" in args and args[args.index("docker") + 1] == "run" and "odisseu-a6-jvm" in args:
                    moved.jvm_id = "sha256:" + ("ef" * 32)
                return moved.runner(args)

            export = root / "moved-export"
            export.mkdir()
            (export / "save.edn").write_text(SAVE, encoding="utf-8")
            swapped = run_supervised(
                swapping, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), root / "moved" / "backup", export,
                materialize=materialize,
            )
            self.assertFalse(swapped["deployComplete"])
            self.assertIn("docker_failed", swapped["blocked"])
            self.assertEqual(moved.containers["odisseu-a6-jvm"]["image"], CURRENT["jvm"]["imageId"])
            self.assertTrue(moved.containers["odisseu-a6-jvm"]["running"])
            self.assertNotIn("odisseu-a6-jvm-previous", moved.containers)
            self.assertEqual((export / "save.edn").read_text(encoding="utf-8"), SAVE)

    def test_second_window_is_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            world = World()
            first = run_case(world, root / "first")
            second = run_case(world, root / "second")
            self.assertTrue(first["deployComplete"], first["blocked"])
            self.assertFalse(second["deployComplete"])
            self.assertIn("previous_container_exists", second["blocked"])
            self.assertEqual(second["next"], "preflight")
            self.assertFalse(any(
                command[command.index("docker") + 1] in {"stop", "rename", "run", "start"}
                for command in second["commands"] if "docker" in command
            ))
            self.assertTrue(world.containers["odisseu-a6-jvm"]["running"])
            self.assertEqual(world.containers["odisseu-a6-jvm"]["image"], JVM_DIGEST)
            self.assertEqual((root / "first" / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)
            self.assertEqual((root / "second" / "export" / "save.edn").read_text(encoding="utf-8"), SAVE)

    def test_temp_volume_bytes_survive_the_window(self):
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        name = "a6sup" + uuid.uuid4().hex[:10]
        self.assertTrue(name.startswith("a6sup"))
        self.assertNotIn("cutover", name)
        created = subprocess.run(["docker", "volume", "create", name], check=False, capture_output=True, text=True)
        if created.returncode != 0:
            self.skipTest(created.stderr)
        try:
            write = subprocess.run(
                ["docker", "run", "--rm", "-v", f"{name}:/data", "ubuntu:24.04", "sh", "-c", "printf '%s\\n' '{:player \"ada\" :coins 40}' > /data/save.edn"],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(write.returncode, 0, write.stderr)
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                export = root / "export"
                export.mkdir()
                copy = subprocess.run(
                    ["docker", "run", "--rm", "-v", f"{name}:/from:ro", "-v", f"{export}:/to", "ubuntu:24.04",
                     "sh", "-c", "cp -a /from/. /to && chmod -R a+rwX /to"],
                    check=False, capture_output=True, text=True,
                )
                self.assertEqual(copy.returncode, 0, copy.stderr)
                before = (export / "save.edn").read_text(encoding="utf-8")
                world = World()
                world.health = "unhealthy"
                unhealthy = run_supervised(
                    world.runner, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), root / "bad", export,
                    materialize=materialize,
                )
                healthy = run_case(World(), root / "good", isolated=export)
                self.assertFalse(unhealthy["datomicRestored"])
                self.assertTrue(healthy["deployComplete"], healthy["blocked"])
                self.assertEqual((export / "save.edn").read_text(encoding="utf-8"), before)
            read = subprocess.run(
                ["docker", "run", "--rm", "-v", f"{name}:/data:ro", "ubuntu:24.04", "cat", "/data/save.edn"],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(read.returncode, 0, read.stderr)
            self.assertEqual(read.stdout, '{:player "ada" :coins 40}\n')
        finally:
            subprocess.run(["docker", "volume", "rm", name], check=False, capture_output=True, text=True)

    def test_shell_and_workflow_cannot_push_the_window(self):
        script = ROOT / "scripts" / "a6-supervised-deploy.sh"
        env = os.environ.copy()
        for key in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME", "ODISSEU_PRODUCTION_DEPLOY_HOLD", "A6_SUPERVISED_CONFIRM", "A6_SUPERVISED_RUNNER", "A6_LIVE_BACKUP"):
            env.pop(key, None)
        refused = subprocess.run(["bash", str(script)], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(refused.returncode, 2)
        self.assertIn("window_not_authorized", refused.stderr)
        env["GITHUB_ACTIONS"] = "true"
        env["A6_SUPERVISED_CONFIRM"] = "executar-janela-a6"
        env["A6_SUPERVISED_RUNNER"] = "vm"
        env["ODISSEU_PRODUCTION_DEPLOY_HOLD"] = "false"
        env["A6_LIVE_BACKUP"] = "autorizar-backup-producao-a6"
        ci = subprocess.run(["bash", str(script), "--window"], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(ci.returncode, 2)
        self.assertIn("push_not_authorized", ci.stderr)
        live = os.environ.copy()
        for key in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME"):
            live.pop(key, None)
        live.update({
            "A6_SUPERVISED_CONFIRM": "executar-janela-a6",
            "A6_SUPERVISED_RUNNER": "vm",
            "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
            "A6_LIVE_BACKUP": "autorizar-backup-producao-a6",
            "A6_EXECUTE": "1",
            "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
        })
        refused_live = subprocess.run(
            ["bash", str(script), "--window"], check=False, capture_output=True, text=True, env=live,
        )
        self.assertEqual(refused_live.returncode, 2)
        self.assertIn("apply_requires_live_recheck", refused_live.stderr)
        workflow = (ROOT / ".github/workflows/a6-pr-validation.yml").read_text(encoding="utf-8")
        self.assertNotIn("--window", workflow)
        self.assertNotIn("executar-janela-a6", workflow)
        self.assertNotIn("A6_SUPERVISED_RUNNER", workflow)
        self.assertNotIn("- master", workflow.split("pull_request:")[0])


class ImageTransferTests(unittest.TestCase):
    def test_commands_do_not_start_or_delete(self):
        commands = transfer_commands("/tmp/a6sup-images.tar", ["odisseu-jvm:a6-faedebd50361-amd64"], docker_direct=True)
        verbs = [command[command.index("docker") + 1] for command in commands]
        self.assertEqual(verbs, ["save", "load", "image"])
        with self.assertRaises(DeployError):
            transfer_commands("/var/lib/docker/volumes/odisseu-a6-cutover-datomic/a.tar", ["odisseu-node:a6-faedebd50361"])

    def test_verify_requires_amd64_digest_and_previous_images(self):
        with self.assertRaises(DeployError) as declared:
            verify_transfer({"architecture": "amd64"}, CANDIDATES[0]["ref"], CANDIDATES[0]["digest"], CANDIDATES[0]["revision"], PREVIOUS_IMAGES)
        self.assertEqual(str(declared.exception), "declared_evidence")

        def runner(expected_id, architecture="amd64", present=True):
            def _run(args):
                if "Architecture" in " ".join(args):
                    return Result(architecture + "\t" + CANDIDATES[0]["revision"] + "\n")
                if args[-1] in PREVIOUS_IMAGES:
                    if not present:
                        return Result("", 1)
                    return Result(args[-1] + "\n")
                return Result(expected_id + "\n")
            return _run

        report = verify_transfer(
            runner(CANDIDATES[0]["digest"]),
            CANDIDATES[0]["ref"],
            CANDIDATES[0]["digest"],
            CANDIDATES[0]["revision"],
            PREVIOUS_IMAGES,
            docker_direct=True,
        )
        self.assertEqual(report["architecture"], "amd64")
        self.assertFalse(report["started"])
        with self.assertRaises(DeployError) as arm:
            verify_transfer(runner(CANDIDATES[0]["digest"], "arm64"), CANDIDATES[0]["ref"], CANDIDATES[0]["digest"], CANDIDATES[0]["revision"], [], docker_direct=True)
        self.assertEqual(str(arm.exception), "image_not_amd64")
        with self.assertRaises(DeployError) as digest:
            verify_transfer(runner("sha256:" + "11" * 32), CANDIDATES[0]["ref"], CANDIDATES[0]["digest"], CANDIDATES[0]["revision"], [], docker_direct=True)
        self.assertEqual(str(digest.exception), "image_digest_mismatch")
        with self.assertRaises(DeployError) as missing:
            verify_transfer(runner(CANDIDATES[0]["digest"], present=False), CANDIDATES[0]["ref"], CANDIDATES[0]["digest"], CANDIDATES[0]["revision"], PREVIOUS_IMAGES, docker_direct=True)
        self.assertEqual(str(missing.exception), "previous_image_missing")

    def test_local_load_preserves_digest_and_refuses_to_run(self):
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        tag = "a6sup-transfer:" + uuid.uuid4().hex[:8]
        previous = "a6sup-previous:" + uuid.uuid4().hex[:8]
        root = Path(tempfile.mkdtemp(prefix="a6sup-root-"))
        archive = root / "image.tar"
        try:
            (root / "save.txt").write_text("save\n", encoding="utf-8")
            packed = subprocess.run(["tar", "-C", str(root), "-cf", "-", "save.txt"], check=False, capture_output=True)
            imported = subprocess.run(["docker", "import", "-", tag], input=packed.stdout, check=False, capture_output=True)
            self.assertEqual(imported.returncode, 0, imported.stderr)
            original = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", tag], check=False, capture_output=True, text=True)
            self.assertEqual(original.returncode, 0, original.stderr)
            tagged = subprocess.run(["docker", "tag", tag, previous], check=False, capture_output=True, text=True)
            self.assertEqual(tagged.returncode, 0, tagged.stderr)
            removed = subprocess.run(["docker", "image", "rm", tag], check=False, capture_output=True, text=True)
            self.assertEqual(removed.returncode, 0, removed.stderr)
            calls = []

            def runner(args):
                calls.append(list(args))
                completed = subprocess.run(args, check=False, capture_output=True, text=True)
                return completed

            loaded = execute_transfer(runner, archive, [previous], docker_direct=True, execute=True)
            self.assertFalse(loaded["started"])
            self.assertFalse(any(command[command.index("docker") + 1] in {"run", "rmi", "rm"} for command in calls))
            current = subprocess.run(["docker", "image", "inspect", "--format", "{{.Id}}", previous], check=False, capture_output=True, text=True)
            self.assertEqual(current.stdout.strip(), original.stdout.strip())
            with self.assertRaises(DeployError) as arm:
                verify_transfer(runner, previous, original.stdout.strip(), "faedebd5036124eb4f946147c5012c48122acc69", [original.stdout.strip()], docker_direct=True)
            self.assertIn(str(arm.exception), {"image_not_amd64", "image_revision_mismatch", "image_not_observed"})
        finally:
            subprocess.run(["docker", "image", "rm", tag, previous], check=False, capture_output=True, text=True)
            shutil.rmtree(root, ignore_errors=True)



class HealthWaitAndRollbackRetryTests(unittest.TestCase):
    def test_await_healthy_polls_through_starting(self):
        from a6_supervised_deploy import _await_healthy
        from types import SimpleNamespace
        states = iter(["starting", "healthy"])
        sleeps = []

        def wrapped(args):
            return SimpleNamespace(returncode=0, stdout=next(states) + "\n", stderr="")

        def sleep(seconds):
            sleeps.append(seconds)

        self.assertEqual(_await_healthy(wrapped, ["docker"], "odisseu-a6-jvm", "starting", sleep=sleep), "healthy")
        self.assertEqual(len(sleeps), 2)  # first starting + one more starting then healthy

    def test_await_healthy_fails_fast_on_unhealthy(self):
        from a6_supervised_deploy import _await_healthy
        sleeps = []
        self.assertEqual(
            _await_healthy(lambda args: None, ["docker"], "odisseu-a6-jvm", "unhealthy", sleep=sleeps.append),
            "unhealthy",
        )
        self.assertEqual(sleeps, [])

    def test_clear_rolled_back_update_strips_jvm_step(self):
        from a6_supervised_deploy import _clear_rolled_back_update
        prior = {
            "steps": ["authorization", "backup", "update_jvm"],
            "lastCompleted": "update_jvm",
            "next": "validate_jvm",
            "blocked": ["health_unhealthy"],
        }
        adjusted = _clear_rolled_back_update(prior, "jvm")
        self.assertEqual(adjusted["steps"], ["authorization", "backup"])
        self.assertEqual(adjusted["lastCompleted"], "backup")
        self.assertEqual(adjusted["next"], "backup")
        self.assertEqual(adjusted["blocked"], [])

if __name__ == "__main__":
    unittest.main(verbosity=2)