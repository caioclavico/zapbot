#!/usr/bin/env python3
"""Testes do runner de produção. Só volumes e containers temporários."""

import json
import shutil
import subprocess
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

from a6_consistent_backup import BackupError, consistent_copy
from a6_mount_policy import LIVE_VOLUME, Collection, assess
from a6_production_backup import (
    BACKUP_AUTHORIZATION,
    ProductionBackupError,
    admit,
    copy_authorized_isolated,
    execute_copy,
    rehearse_isolated_copy,
    restore_isolated,
)
from a6_production_contract import CURRENT, DATOMIC_SOURCE, VM
from a6_production_deploy import (
    DeployError,
    apply_commands,
    current_dry_run,
    evaluate_sequence,
    observe_vm,
    plan,
    rollback_allowed,
    run_apply,
)


ROOT = Path(__file__).resolve().parents[1]
SHA = "96a7e48d06e1bf6e6a23ce65256301efdd724d9c"
DIGEST = "sha256:" + ("ab" * 32)
BACKUP = {"manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True, "jvmStopped": True}
IMAGE = {"_observed": True, "architecture": "amd64", "revision": SHA}
ENV = {
    "A6_EXECUTE": "1",
    "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
    "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
    "ODISSEU_A6_DELIVERY_VALIDATED": "true",
}


def jvm_document(privileged=False, cap_add=None, volume=LIVE_VOLUME):
    return {
        "Id": "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
        "Name": "/odisseu-a6-jvm",
        "Mounts": [{"Name": volume, "Destination": "/data/datomic-local", "RW": True}],
        "HostConfig": {"Privileged": privileged, "CapAdd": cap_add or []},
        "CapEff": "00000000a80425fb",
    }


def collection(documents=None, phase="quiescent", node="draining", processes="clojure.main -m odisseu.bridge.http-server\n", writes=0):
    return Collection(
        source="docker-inspect-subprocess",
        documents=tuple(documents or [jvm_document()]),
        processes=processes,
        jvm_phase=phase,
        node_phase=node,
        active_jvm_writes=writes,
        active_node_ops=0,
        volume=LIVE_VOLUME,
        authorized="odisseu-a6-jvm",
    )


def admitted():
    return admit(
        collection(),
        VM,
        DATOMIC_SOURCE,
        "/var/backups/odisseu-a6/0507831d1b6e",
        free_bytes=2_000_000_000,
        source_bytes=63_000_000,
    )


def vm_runner():
    jvm = _inspect(CURRENT["jvm"], "127.0.0.1:18092:18090")
    node = _inspect(CURRENT["node"], "127.0.0.1:3003:3001")

    class Result:
        def __init__(self, stdout, returncode=0):
            self.stdout = stdout
            self.returncode = returncode

    def runner(args):
        if args == ["docker", "info"]:
            return Result("", 1)
        if args == ["sudo", "-n", "docker", "info"]:
            return Result("")
        if args[-2:] == ["odisseu-a6-jvm", "odisseu-a6-node"]:
            return Result(json.dumps([jvm, node]))
        if args[-1] == "/proc/self/status":
            return Result("CapEff:\t00000000a80425fb\n")
        if args[-1] == "args":
            return Result("clojure.main -m odisseu.bridge.http-server\n")
        if str(args[-1]).endswith("bridge-maintenance.edn"):
            return Result("{:phase :quiescent :active-writes 0}\n")
        if str(args[-1]).endswith("a6-maintenance.json"):
            return Result('{"phase":"draining","activeOps":0}\n')
        if args == ["hostname"]:
            return Result("odisseu-core\n")
        if args == ["metadata"]:
            return Result(json.dumps({"instance": "odisseu-core", "zone": "us-central1-a", "project": VM["project"]}))
        return Result("", 1)

    return runner


def _inspect(service, publish):
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
            "Privileged": service["privileged"],
            "CapAdd": service["capAdd"],
            "CapDrop": service["capDrop"],
            "SecurityOpt": security,
            "RestartPolicy": {"Name": service["restart"]},
            "Memory": service["memory"],
            "NetworkMode": service["network"],
            "PortBindings": {f"{container_port}/tcp": [{"HostIp": host, "HostPort": host_port}]},
        },
        "Mounts": mounts,
        "State": {"Health": {"Status": service["health"]}},
    }


class ProductionDeployTests(unittest.TestCase):
    def test_dry_run_preserves_mounts_and_refuses_execution(self):
        report = current_dry_run(SHA)
        text = json.dumps(report)
        self.assertFalse(report["executeAllowed"])
        self.assertFalse(report["deployComplete"])
        self.assertIn("jvm_not_quiescent", report["blocked"])
        self.assertIn("declared_evidence", report["blocked"])
        self.assertEqual(report["argv"][:3], ["sudo", "-n", "docker"])
        self.assertIn("type=volume,source=odisseu-a6-cutover-datomic,target=/data/datomic-local", report["argv"])
        self.assertIn("type=bind,source=/run/odisseu-control,target=/run/odisseu-control", report["argv"])
        self.assertIn("--cap-drop", report["argv"])
        self.assertIn("SYS_ADMIN", report["argv"])
        self.assertIn("no-new-privileges:true", report["argv"])
        self.assertNotIn("volume rm", text)
        self.assertNotIn("compose down", text)
        self.assertNotIn("--remove-orphans", text)
        self.assertNotIn("TOKEN=", text)
        self.assertEqual(report["previousImage"], CURRENT["jvm"]["image"])
        self.assertEqual(report["untouched"], "odisseu-a6-node")
        self.assertIn("image_digest_missing", report["blocked"])
        self.assertIn("image_not_observed", report["blocked"])
        self.assertIn("backup_not_verified", report["blocked"])
        self.assertNotIn("@sha256:", " ".join(report["argv"]))
        arm = plan(
            CURRENT, SHA, "jvm", {}, digest=DIGEST, backup=BACKUP,
            image={"_observed": True, "architecture": "arm64", "revision": SHA},
        )
        self.assertIn("image_not_amd64", arm["blocked"])
        self.assertFalse(arm["executeAllowed"])
        skipped = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True},
        ])
        self.assertFalse(skipped["deployComplete"])
        self.assertEqual(skipped["next"], "backup")

    def test_missing_mount_and_wrong_volume_are_refused(self):
        missing = deepcopy(CURRENT)
        missing["jvm"]["mounts"] = [item for item in missing["jvm"]["mounts"] if item["destination"] != "/data/datomic-local"]
        self.assertIn("jvm_mount_mismatch", plan(missing, SHA, "jvm")["blocked"])
        wrong = deepcopy(CURRENT)
        wrong["jvm"]["mounts"][0]["name"] = "outro-volume"
        self.assertIn("jvm_mount_mismatch", plan(wrong, SHA, "jvm")["blocked"])

    def test_forbidden_capability_and_second_writer_block_admission(self):
        privileged = admit(
            collection([jvm_document(privileged=True, cap_add=["SYS_ADMIN"])]),
            VM, DATOMIC_SOURCE, "/var/backups/odisseu-a6/cap", 2_000_000_000, 1000,
        )
        self.assertEqual(privileged["reason"], "privileged_or_sys_admin")
        extra = jvm_document()
        other = dict(extra)
        other["Name"] = "/outra-ferramenta"
        other["Id"] = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        second = admit(
            collection([extra, other]),
            VM, DATOMIC_SOURCE, "/var/backups/odisseu-a6/second", 2_000_000_000, 1000,
        )
        self.assertEqual(second["reason"], "writable_mount_not_exclusive")
        self.assertEqual(assess([extra], LIVE_VOLUME, "odisseu-a6-jvm")["reason"], "live_volume_refused")

    def test_incomplete_drain_insufficient_space_and_declared_map(self):
        draining = admit(
            collection(phase="open", node="open"),
            VM, DATOMIC_SOURCE, "/var/backups/odisseu-a6/open", 2_000_000_000, 1000,
        )
        self.assertEqual(draining["reason"], "node_not_draining")
        small = admit(collection(), VM, DATOMIC_SOURCE, "/var/backups/odisseu-a6/small", 100, 63_000_000)
        self.assertEqual(small["reason"], "insufficient_space")
        declared = admit({"mountsObserved": True}, VM, DATOMIC_SOURCE, "/var/backups/odisseu-a6/declared", 2_000_000_000, 1000)
        self.assertEqual(declared["reason"], "declared_evidence")

    def test_lab_helper_still_refuses_the_live_path_and_isolated_restore_keeps_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "source"
            source.mkdir()
            (source / "save.edn").write_text('{:player "p"}', encoding="utf-8")
            with self.assertRaises(BackupError):
                consistent_copy(Path(DATOMIC_SOURCE), Path(tmp) / "live", {"quiescenceProven": True})
            copied = rehearse_isolated_copy(source, Path(tmp) / "backup")
            self.assertTrue((source / "save.edn").exists())
            restored = restore_isolated(copied["manifest"], Path(tmp) / "backup", Path(tmp) / "restored")
            self.assertEqual((Path(restored["restored"]) / "save.edn").read_text(encoding="utf-8"), '{:player "p"}')
            manifest = Path(copied["manifest"])
            manifest.write_text("0" * 64 + "  save.edn\n", encoding="utf-8")
            with self.assertRaises(ProductionBackupError):
                restore_isolated(manifest, Path(tmp) / "backup", Path(tmp) / "bad")
            self.assertTrue((source / "save.edn").exists())
            self.assertFalse((Path(tmp) / "bad").exists())
            isolated = Path(tmp) / "isolated-source"
            isolated.mkdir()
            (isolated / "save.edn").write_text('{:player "p"}', encoding="utf-8")
            with self.assertRaises(ProductionBackupError):
                copy_authorized_isolated(isolated, Path(tmp) / "isolated-copy", collection(), VM, "nao", 2_000_000_000, 100)
            with self.assertRaises(ProductionBackupError):
                copy_authorized_isolated(DATOMIC_SOURCE, Path(tmp) / "live-copy", collection(), VM, BACKUP_AUTHORIZATION, 2_000_000_000, 100)
            produced = copy_authorized_isolated(
                isolated, Path(tmp) / "isolated-copy", collection(), VM, BACKUP_AUTHORIZATION, 2_000_000_000, 100,
            )
            self.assertTrue(produced["sourcePreserved"])
            self.assertEqual((isolated / "save.edn").read_text(encoding="utf-8"), '{:player "p"}')
            self.assertTrue(Path(produced["manifest"]).is_file())

    def test_execute_copy_refuses_a_running_writer_and_recovers_isolated_bytes(self):
        with self.assertRaises(ProductionBackupError) as dry:
            execute_copy(collection(), VM, "/tmp/odisseu-a6-backup-dry", "false", False, BACKUP_AUTHORIZATION, 2_000_000_000, 100)
        self.assertEqual(str(dry.exception), "dry_run")
        with self.assertRaises(ProductionBackupError) as held:
            execute_copy(collection(), VM, "/tmp/odisseu-a6-backup-hold", "true", True, BACKUP_AUTHORIZATION, 2_000_000_000, 100)
        self.assertEqual(str(held.exception), "hold_active")
        running = jvm_document()
        running["State"] = {"Running": True, "Status": "running"}
        with self.assertRaises(ProductionBackupError) as writable:
            execute_copy(
                collection([running]), VM, "/tmp/odisseu-a6-backup-run", "false", True,
                BACKUP_AUTHORIZATION, 2_000_000_000, 100, isolated_root="/tmp/does-not-matter",
            )
        self.assertEqual(str(writable.exception), "datomic_writable")
        self.assertIn("invalid_sha", plan(CURRENT, "nao-e-sha", "jvm")["blocked"])
        stopped = jvm_document()
        stopped["State"] = {"Running": False, "Status": "exited"}
        try:
            live_readable = Path(DATOMIC_SOURCE).is_dir()
        except OSError:
            live_readable = False
        self.assertFalse(live_readable, "volume live legível; a suíte não copia")
        with self.assertRaises(ProductionBackupError) as missing:
            execute_copy(
                collection([stopped]), VM, "/tmp/odisseu-a6-backup-missing", "false", True,
                BACKUP_AUTHORIZATION, 2_000_000_000, 100,
            )
        self.assertIn(str(missing.exception), {"source_missing", "source_unreadable"})
        external = collection(
            [stopped],
            processes="clojure apps/odisseu/src/odisseu/smoke/datomic_local.clj\n",
        )
        with self.assertRaises(ProductionBackupError) as writer:
            execute_copy(external, VM, "/tmp/odisseu-a6-backup-ext", "false", True, BACKUP_AUTHORIZATION, 2_000_000_000, 100)
        self.assertEqual(str(writer.exception), "external_datomic_client")
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "volume"
            source.mkdir()
            (source / "save.edn").write_text('{:coins 7}', encoding="utf-8")
            produced = execute_copy(
                collection([stopped]), VM, Path(tmp) / "backup", "false", True,
                BACKUP_AUTHORIZATION, 2_000_000_000, 100, isolated_root=source,
            )
            self.assertTrue(produced["manifestVerified"])
            self.assertTrue(produced["sourcePreserved"])
            self.assertEqual((source / "save.edn").read_text(encoding="utf-8"), "{:coins 7}")
            restored = restore_isolated(produced["manifest"], Path(tmp) / "backup", Path(tmp) / "restored")
            self.assertEqual((Path(restored["restored"]) / "save.edn").read_text(encoding="utf-8"), "{:coins 7}")
            self.assertEqual(produced["source"], "isolated")

    def test_health_failure_rolls_back_one_service_and_interruption_blocks_completion(self):
        ready = deepcopy(CURRENT)
        ready["jvm"]["phase"] = "quiescent"
        ready["node"]["phase"] = "draining"
        env = dict(ENV)
        declared = plan(ready, SHA, "jvm", env, digest=DIGEST, backup=BACKUP)
        self.assertIn("declared_evidence", declared["blocked"])
        self.assertFalse(declared["executeAllowed"])
        observed = observe_vm(vm_runner())
        self.assertNotIn("super-secret", json.dumps(observed))
        observed_plan = plan(observed, SHA, "jvm", env, digest=DIGEST, backup=BACKUP, image=IMAGE)
        self.assertTrue(observed_plan["executeAllowed"], observed_plan["blocked"])
        self.assertIn(f"@sha256:{DIGEST.split(':', 1)[1]}", observed_plan["argv"][-1])
        self.assertTrue(observed_plan["jvmBeforeNode"])
        calls = []

        class Result:
            returncode = 0

        def runner(command):
            calls.append(command)
            return Result()

        failed = run_apply(runner, observed_plan, health="unhealthy")
        self.assertTrue(failed["rolledBack"])
        self.assertTrue(failed["maintenance"])
        joined = " ".join(" ".join(command) for command in calls)
        stop_at = joined.index("stop --time 30 odisseu-a6-jvm")
        run_at = joined.index("run -d --name odisseu-a6-jvm")
        self.assertLess(stop_at, run_at)
        self.assertIn("docker rename odisseu-a6-jvm odisseu-a6-jvm-previous", joined)
        self.assertIn("docker start odisseu-a6-jvm", joined)
        self.assertNotIn("start odisseu-a6-jvm-previous", joined)
        self.assertNotIn("start odisseu-a6-jvm-failed", joined)
        self.assertNotIn("volume rm", joined)
        self.assertNotIn("odisseu-a6-node", joined)
        partial = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True, "jvmStopped": True},
            {"step": "update_jvm", "service": "jvm", "previousImage": CURRENT["jvm"]["image"], "mountsPreserved": True},
        ])
        self.assertFalse(partial["deployComplete"])
        self.assertEqual(partial["next"], "validate_jvm")

    def test_node_not_ready_and_incompatible_rollback_do_not_finish(self):
        not_ready = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True, "jvmStopped": True},
            {"step": "update_jvm", "service": "jvm", "previousImage": "old", "mountsPreserved": True},
            {"step": "validate_jvm", "health": "healthy", "entrypoint": True, "capDropSysAdmin": True, "privileged": False},
            {"step": "update_node", "service": "node", "jvmUntouched": True},
            {"step": "validate_whatsapp", "whatsapp": "STARTING", "health": "healthy"},
        ])
        self.assertFalse(not_ready["deployComplete"])
        self.assertEqual(not_ready["next"], "validate_whatsapp")
        with self.assertRaises(DeployError) as incompatible:
            rollback_allowed({"service": "jvm", "previousImage": CURRENT["jvm"]["image"], "newBattleFormatWritten": True})
        self.assertEqual(str(incompatible.exception), "rollback_incompatible_battle_edn")
        with self.assertRaises(DeployError) as destructive:
            apply_commands({
                "executeAllowed": True,
                "service": "jvm",
                "argv": ["docker", "volume", "rm", "odisseu-a6-cutover-datomic"],
            })
        self.assertEqual(str(destructive.exception), "destructive_command")
        resume = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True, "restoreIsolated": True, "jvmStopped": True},
            {"step": "update_jvm", "service": "jvm", "previousImage": "old", "mountsPreserved": True},
            {"step": "validate_jvm", "health": "healthy", "entrypoint": True, "capDropSysAdmin": True, "privileged": False},
            {"step": "update_node", "service": "node", "jvmUntouched": True},
            {"step": "validate_whatsapp", "whatsapp": "READY", "health": "healthy"},
            {"step": "resume", "resumeExplicit": False},
        ])
        self.assertFalse(resume["deployComplete"])
        self.assertEqual(resume["next"], "resume")

    def test_interruption_keeps_one_writer_and_new_battle_stays_in_maintenance(self):
        observed = plan(observe_vm(vm_runner()), SHA, "jvm", dict(ENV), digest=DIGEST, backup=BACKUP, image=IMAGE)
        self.assertTrue(observed["executeAllowed"], observed["blocked"])

        class Result:
            returncode = 0

        def apply(fail_at=None, health="healthy", battle=False):
            calls = []

            def runner(command):
                calls.append(command)
                return Result()

            report = run_apply(runner, observed, health=health, fail_at=fail_at, battle_written=battle)
            return report, calls

        before_run, calls = apply(fail_at="run")
        self.assertEqual(before_run["mode"], "rollback")
        self.assertTrue(any(command[-2:] == ["start", "odisseu-a6-jvm"] for command in calls))
        self.assertFalse(any(command[-1].endswith("-previous") and command[-2] == "start" for command in calls))
        stopped, stopped_calls = apply(fail_at="stop")
        self.assertEqual(stopped["mode"], "unchanged")
        self.assertEqual(stopped_calls, [])
        during_rename, renamed_calls = apply(fail_at="rename")
        self.assertEqual(during_rename["mode"], "rollback")
        self.assertEqual(renamed_calls[-1], ["sudo", "-n", "docker", "start", "odisseu-a6-jvm"])
        self.assertFalse(any(len(command) > 3 and command[3] == "run" for command in renamed_calls))
        after_rename, parked = apply(fail_at="update")
        self.assertTrue(any(command[-3:] == ["rename", "odisseu-a6-jvm-previous", "odisseu-a6-jvm"] for command in parked))
        self.assertFalse(any(command[-2] == "run" for command in parked))
        forward, kept = apply(health="unhealthy", battle=True)
        self.assertEqual(forward["mode"], "forward_only")
        self.assertTrue(forward["maintenance"])
        self.assertFalse(any(command[-2] == "start" for command in kept))
        node = plan(
            observe_vm(vm_runner()), SHA, "node", dict(ENV),
            digest=DIGEST, backup=BACKUP, jvm_updated=True, image=IMAGE,
        )
        early = plan(observe_vm(vm_runner()), SHA, "node", dict(ENV), digest=DIGEST, backup=BACKUP, image=IMAGE)
        self.assertIn("jvm_not_updated", early["blocked"])
        self.assertFalse(early["executeAllowed"])
        node_fail = run_apply(lambda command: Result(), node, fail_at="run")
        self.assertEqual(node_fail["mode"], "rollback")
        self.assertTrue(node_fail["maintenance"])
        sock = observe_vm(vm_runner())
        sock["node"]["mounts"].append({"type": "bind", "source": "/var/run/docker.sock", "destination": "/var/run/docker.sock", "rw": True})
        blocked = plan(sock, SHA, "node", {})
        self.assertIn("docker_socket_exposed", blocked["blocked"])
        self.assertIn("node_mount_mismatch", blocked["blocked"])

    def test_shell_dry_run_and_entrypoint_refuse_a_missing_mount(self):
        script = ROOT / "scripts" / "a6-production-deploy.sh"
        done = subprocess.run(["bash", str(script)], check=False, capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        report = json.loads(done.stdout)
        self.assertFalse(report["executeAllowed"])
        self.assertFalse(report["deployComplete"])
        refused = subprocess.run(["bash", str(script), "--execute"], check=False, capture_output=True, text=True)
        self.assertEqual(refused.returncode, 2)
        self.assertFalse(json.loads(refused.stdout)["executeAllowed"])
        workflow = (ROOT / ".github/workflows/a6-pr-validation.yml").read_text(encoding="utf-8")
        self.assertNotIn("--execute", workflow)
        self.assertNotIn("- master", workflow.split("pull_request:")[0])
        destroyed = subprocess.run(["bash", str(script), "volume rm odisseu-a6-cutover-datomic"], check=False, capture_output=True, text=True)
        self.assertEqual(destroyed.returncode, 2)
        if shutil.which("docker") is None:
            self.skipTest("docker ausente")
        entry = ROOT / "docker" / "jvm-storage-entrypoint.sh"
        missing = subprocess.run(
            ["docker", "run", "--rm", "--cap-drop", "SYS_ADMIN",
             "-v", f"{entry}:/usr/local/bin/jvm-storage-entrypoint.sh:ro",
             "ubuntu:24.04", "/usr/local/bin/jvm-storage-entrypoint.sh", "true"],
            check=False, capture_output=True, text=True,
        )
        self.assertNotEqual(missing.returncode, 0)
        self.assertIn("montado", missing.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
