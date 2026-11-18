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
    ProductionBackupError,
    admit,
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
    plan,
    rollback_allowed,
    run_apply,
)


ROOT = Path(__file__).resolve().parents[1]
SHA = "0507831d1b6ec8f35aac1794256e94de2b7a0e20"


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


class ProductionDeployTests(unittest.TestCase):
    def test_dry_run_preserves_mounts_and_refuses_execution(self):
        report = current_dry_run(SHA)
        text = json.dumps(report)
        self.assertFalse(report["executeAllowed"])
        self.assertFalse(report["deployComplete"])
        self.assertIn("jvm_not_quiescent", report["blocked"])
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

    def test_execute_copy_does_not_run_and_wrong_image_is_rejected(self):
        with self.assertRaises(ProductionBackupError):
            execute_copy(admitted(), "true", False, False)
        with self.assertRaises(ProductionBackupError):
            execute_copy(admitted(), "true", True, True)
        self.assertIn("invalid_sha", plan(CURRENT, "nao-e-sha", "jvm")["blocked"])

    def test_health_failure_rolls_back_one_service_and_interruption_blocks_completion(self):
        ready = deepcopy(CURRENT)
        ready["jvm"]["phase"] = "quiescent"
        ready["node"]["phase"] = "draining"
        env = {
            "A6_EXECUTE": "1",
            "ODISSEU_PRODUCTION_DEPLOY_HOLD": "false",
            "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
            "ODISSEU_A6_DELIVERY_VALIDATED": "true",
        }
        report = plan(ready, SHA, "jvm", env)
        self.assertTrue(report["executeAllowed"])
        calls = []

        class Result:
            returncode = 0

        def runner(command):
            calls.append(command)
            return Result()

        failed = run_apply(runner, report, health="unhealthy")
        self.assertTrue(failed["rolledBack"])
        joined = " ".join(" ".join(command) for command in calls)
        self.assertIn("docker rename odisseu-a6-jvm odisseu-a6-jvm-previous", joined)
        self.assertIn("docker start odisseu-a6-jvm", joined)
        self.assertNotIn("volume rm", joined)
        self.assertNotIn("odisseu-a6-node", joined)
        partial = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True},
            {"step": "update_jvm", "service": "jvm", "previousImage": CURRENT["jvm"]["image"], "mountsPreserved": True},
        ])
        self.assertFalse(partial["deployComplete"])
        self.assertEqual(partial["next"], "validate_jvm")

    def test_node_not_ready_and_incompatible_rollback_do_not_finish(self):
        not_ready = evaluate_sequence([
            {"step": "node_maintenance", "nodePhase": "draining"},
            {"step": "drain", "activeNodeOps": 0, "activeJvmWrites": 0},
            {"step": "quiescence", "jvmPhase": "quiescent", "exclusive": True, "externalWriters": []},
            {"step": "backup", "manifestVerified": True, "sourcePreserved": True},
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

    def test_shell_dry_run_and_entrypoint_refuse_a_missing_mount(self):
        script = ROOT / "scripts" / "a6-production-deploy.sh"
        done = subprocess.run(["bash", str(script)], check=False, capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        report = json.loads(done.stdout)
        self.assertFalse(report["executeAllowed"])
        self.assertFalse(report["deployComplete"])
        refused = subprocess.run(["bash", str(script), "volume rm odisseu-a6-cutover-datomic"], check=False, capture_output=True, text=True)
        self.assertEqual(refused.returncode, 2)
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
