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
    RESUME_PHRASE,
    SUPERVISED_STEPS,
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
        self.jvm_running = True
        self.health = "healthy"
        self.contract = "up"
        self.whatsapp = "READY"
        self.chromium = True
        self.battle = ""
        self.entrypoint = "/usr/local/bin/jvm-storage-entrypoint.sh"
        self.open = False
        self.ran_jvm = False

    def documents(self):
        jvm = _inspect(CURRENT["jvm"], CURRENT["jvm"]["publish"], self.jvm_running, self.privileged, self.cap_add, self.health)
        node = _inspect(CURRENT["node"], CURRENT["node"]["publish"], True, False, [], self.health)
        return [jvm, node]

    def runner(self, args):
        if args == ["docker", "info"]:
            return Result("", 1)
        if args == ["sudo", "-n", "docker", "info"]:
            return Result("ok")
        if args == ["hostname"]:
            return Result(self.hostname + "\n")
        if args == ["metadata"]:
            return Result(json.dumps({"instance": "odisseu-core", "zone": "us-central1-a", "project": VM["project"]}))
        if args[:1] == ["df"]:
            return Result("Avail\n2000000000\n")
        if "inspect" in args and "--format" not in args and args[-2:] == ["odisseu-a6-jvm", "odisseu-a6-node"]:
            return Result(json.dumps(self.documents()))
        if "--format" in args:
            template = args[args.index("--format") + 1]
            if template == RESUME_PHRASE:
                self.open = True
                return Result(RESUME_PHRASE + "\n")
            if "Architecture" in template:
                return Result(f"{self.architecture}\t{self.revision}\n")
            if "{{.Id}}" in template:
                identity = self.node_id if "odisseu-node" in args[-1] else self.jvm_id
                return Result(identity + "\n")
            if "Health.Status" in template:
                return Result(self.health + "\n")
            if "Privileged" in template:
                return Result("true\n" if self.privileged else "false\n")
            if "Entrypoint" in template or "Cmd" in template:
                return Result(self.entrypoint + "\n")
        if args[-1] == "/proc/self/status":
            cap = self.cap_after_run if self.ran_jvm and self.cap_after_run else self.cap_eff
            return Result(f"CapEff:\t{cap}\n")
        if args[-1] == "args":
            return Result(self.processes)
        if str(args[-1]).endswith("bridge-maintenance.edn"):
            phase = "open" if self.open else "quiescent"
            return Result("{:phase :%s :active-writes %s}\n" % (phase, self.writes))
        if str(args[-1]).endswith("a6-maintenance.json"):
            phase = "open" if self.open else "draining"
            return Result(json.dumps({"phase": phase, "activeOps": self.ops}))
        if args[-1] == "/data/datomic-local/battle-format.edn":
            if not self.battle:
                return Result("", 1)
            return Result(self.battle)
        if "stop" in args and args[-1] == "odisseu-a6-jvm":
            self.jvm_running = False
            return Result("")
        if "docker" in args and args[args.index("docker") + 1] == "run" and "odisseu-a6-jvm" in args:
            self.ran_jvm = True
            self.jvm_running = True
            return Result("")
        if args[-1] == CONTRACT_MARKER or (len(args) >= 2 and args[-2] == "-e"):
            if self.contract != "up":
                return Result("", 2)
            return Result("up")
        if args[-1] == "/app/scripts/healthcheck.js":
            body = json.dumps({"status": "ok", "whatsapp": self.whatsapp, "chromium": self.chromium})
            code = 0 if self.whatsapp == "READY" and self.chromium else 1
            return Result(body + "\n", code)
        if "docker" in args:
            verb = args[args.index("docker") + 1]
            if verb in {"stop", "rename", "update", "start", "run", "exec", "inspect"}:
                return Result("")
        return Result("comando inesperado: " + " ".join(args), 1)


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
    )


class SupervisedWindowTests(unittest.TestCase):
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
            self.assertLess(stop, jvm_run)
            self.assertLess(jvm_run, node_run)
            self.assertFalse(any(command.endswith("start odisseu-a6-jvm-previous") or command.endswith("start odisseu-a6-jvm-failed") for command in joined))

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
        cases.append((drain, "writes_in_flight", "drain"))
        writer = World()
        writer.processes = INVENTORY[0]["path"] + "\n"
        cases.append((writer, "external_datomic_client", "drain"))
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
            self.assertNotIn(RESUME_PHRASE, " ".join(" ".join(command) for command in resume["commands"]))
            self.assertNotIn("confirm_open", resume["steps"])

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
                    ["docker", "run", "--rm", "-v", f"{name}:/from:ro", "-v", f"{export}:/to", "ubuntu:24.04", "cp", "-a", "/from/.", "/to/"],
                    check=False, capture_output=True, text=True,
                )
                self.assertEqual(copy.returncode, 0, copy.stderr)
                before = (export / "save.edn").read_text(encoding="utf-8")
                world = World()
                world.health = "unhealthy"
                unhealthy = run_supervised(
                    world.runner, SHA, JVM_DIGEST, NODE_DIGEST, dict(ENV), root / "bad", export,
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


if __name__ == "__main__":
    unittest.main(verbosity=2)
