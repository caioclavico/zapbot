#!/usr/bin/env python3
"""Plano do hotfix Sinistea: alvo, disco, rollback e recusa de apply."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import importlib.util

ROOT = Path(__file__).resolve().parents[1]
PLAN = ROOT / "scripts" / "a6-node-hotfix-plan.py"
spec = importlib.util.spec_from_file_location("a6_node_hotfix_plan", PLAN)
plan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plan)


def snapshot(free=14 * 1024 ** 3):
    return {
        "project": "project-e8cd7809-1145-4be9-b8d",
        "zone": "us-central1-a",
        "instanceName": "odisseu-core",
        "sha": "b04682f1a238" + "a" * 28,
        "network": "a6-cutover",
        "envFile": "/home/caiohclavico/odisseu-recovery/runtime.env",
        "instance": {
            "name": "odisseu-core",
            "status": "RUNNING",
            "networkInterfaces": [
                {"accessConfigs": [{"natIP": "192.0.2.10"}]}
            ],
        },
        "containers": [
            {
                "name": "odisseu-a6-node",
                "image": "odisseu-node:a6-b04682f1a238-permissions-amd64",
                "imageId": "sha256:37349ec10775",
                "status": "running",
            },
            {
                "name": "odisseu-a6-jvm",
                "image": "odisseu-jvm:a6-b04682f1a238-amd64",
                "imageId": "sha256:c495184169fb",
                "status": "running",
            },
            {"name": "odisseu-core-node", "image": "legacy", "status": "exited"},
            {"name": "zapbot", "image": "legacy", "status": "exited"},
        ],
        "nodeMounts": [
            {
                "source": "/home/caiohclavico/odisseu-recovery/a6-cutover-session-from-core",
                "destination": "/app/.wwebjs_auth_a6_cutover",
            },
            {
                "name": "odisseu-a6-cutover-data",
                "destination": "/app/data",
            },
        ],
        "diskFreeBytes": free,
    }


class PlanTests(unittest.TestCase):
    def test_resolves_by_name_and_keeps_legacy_out(self):
        result = plan.build_plan(snapshot())
        self.assertFalse(result["apply"])
        self.assertEqual(result["target"], "odisseu-a6-node")
        self.assertEqual(result["instance"]["name"], "odisseu-core")
        self.assertNotIn("35.238.24.225", json.dumps(result))
        self.assertIn("odisseu-a6-jvm", result["untouched"])
        self.assertIn("odisseu-core-node", result["untouched"])
        self.assertIn("zapbot", result["untouched"])
        self.assertEqual(result["recreate"], ["odisseu-a6-node"])
        self.assertEqual(result["datomicBackup"], "refused")
        self.assertTrue(result["jvmUntouched"])
        self.assertEqual(
            result["rollbackImage"],
            "odisseu-node:a6-b04682f1a238-permissions-amd64",
        )
        self.assertTrue(result["candidateImage"].startswith("odisseu-node:hotfix-sinistea-"))
        self.assertIn("odisseu-a6-cutover-data", result["preserve"]["volumes"])
        self.assertTrue(result["go"])

    def test_low_disk_blocks_without_touching_jvm(self):
        result = plan.build_plan(snapshot(free=1024))
        self.assertFalse(result["go"])
        self.assertEqual(result["block"], "insufficient_free_space_for_node_image")
        self.assertEqual(result["datomicBackup"], "refused")
        self.assertTrue(result["jvmUntouched"])

    def test_node_with_datomic_mount_is_rejected(self):
        raw = snapshot()
        raw["nodeMounts"].append({"name": "odisseu-a6-cutover-datomic", "destination": "/data/datomic-local"})
        with self.assertRaises(plan.PlanError):
            plan.build_plan(raw)

    def test_apply_flag_exits_before_any_change(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "snap.json"
            path.write_text(json.dumps(snapshot()), encoding="utf-8")
            done = subprocess.run(
                ["python3", str(PLAN), "--fixture", str(path), "--apply"],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(done.returncode, 2)
        self.assertIn("apply desligado", done.stderr)

    def test_legacy_runner_refuses_a6_target(self):
        done = subprocess.run(
            ["bash", str(ROOT / "scripts" / "ci-ssh-deploy.sh")],
            capture_output=True, text=True, check=False,
            env={"DEPLOY_TARGET": "odisseu-a6-node", "PATH": "/usr/bin:/bin"},
        )
        self.assertEqual(done.returncode, 2)
        self.assertIn("a6-node-hotfix-plan.py", done.stderr)


if __name__ == "__main__":
    unittest.main()
