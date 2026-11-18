#!/usr/bin/env python3
"""Testes isolados da entrega A6. Não usam Docker, SSH nem a VM."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from a6_delivery import drain, health, rollback, update


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "a6-delivery.sh"


def isolated_state():
    return {
        "environment": "isolated",
        "project": "odisseu-lab",
        "zone": "us-central1-a",
        "instanceName": "odisseu-lab",
        "containers": [
            {"name": "odisseu-a6-node", "status": "running", "image": "odisseu-node:old",
             "health": "healthy", "restart": "no"},
            {"name": "odisseu-a6-jvm", "status": "running", "image": "odisseu-jvm:old",
             "health": "healthy", "restart": "no"},
        ],
        "datomic": "odisseu-a6-cutover-datomic",
        "journal": "odisseu-a6-cutover-journal",
        "maint": "odisseu-a6-cutover-maint",
        "session": "/var/lib/odisseu/session",
        "network": "odisseu-a6",
        "env": ["ALLOW_ALL_GROUPS=true"],
        "restart": {"node": "no", "jvm": "no"},
        "externalWriters": [],
        "activeNodeOps": 0,
        "activeJvmWrites": 0,
        "storageGate": {
            "othersSeeReadOnly": True,
            "capSysAdmin": False,
            "bridgeMountPrivate": True,
            "laterProcessWrite": "erofs",
            "ownerCanRearmWrite": False,
        },
        "nodePhase": "draining",
        "jvmPhase": "open",
    }


def run(command, state, *extra):
    with tempfile.TemporaryDirectory() as tmp:
        fixture = Path(tmp) / "fixture.json"
        fixture.write_text(json.dumps(state), encoding="utf-8")
        completed = subprocess.run(
            ["bash", str(SCRIPT), command, "--fixture", str(fixture), *extra],
            check=False, capture_output=True, text=True,
        )
        return completed


class DeliveryTests(unittest.TestCase):
    def test_production_fixture_is_refused(self):
        state = isolated_state()
        state["environment"] = "production"
        result = run("preflight", state)
        self.assertEqual(result.returncode, 2)
        self.assertIn("production_refused", result.stderr)

    def test_ip_is_not_an_identity(self):
        state = isolated_state()
        state["instanceName"] = "35.192.113.146"
        result = run("preflight", state)
        self.assertEqual(result.returncode, 2)
        self.assertIn("identity_is_ip", result.stderr)

    def test_preflight_preserves_volumes_and_does_not_prove_quiescence(self):
        result = run("preflight", isolated_state())
        self.assertEqual(result.returncode, 0)
        report = json.loads(result.stdout)
        self.assertFalse(report["quiescenceProven"])
        self.assertEqual(report["preserve"]["datomic"], "odisseu-a6-cutover-datomic")
        self.assertEqual(report["preserve"]["session"], "/var/lib/odisseu/session")
        self.assertEqual(report["preserve"]["restart"], {"node": "no", "jvm": "no"})

    def test_external_writer_blocks_update_and_keeps_draining(self):
        state = isolated_state()
        state["externalWriters"] = ["scripts/a64_apply_028_schema_only.clj"]
        report = drain(state)
        self.assertFalse(report["quiescenceProven"])
        self.assertEqual(report["phase"], "draining")
        with self.assertRaises(Exception):
            update(state, "node", "b1d8db29c0d14788f5426a0779ef2e2dda07dd0f")
        self.assertEqual(state["containers"][0]["image"], "odisseu-node:old")

    def test_update_is_idempotent_and_rollback_touches_one_service(self):
        state = isolated_state()
        sha = "b1d8db29c0d14788f5426a0779ef2e2dda07dd0f"
        first = update(state, "node", sha)
        self.assertTrue(first["changed"])
        self.assertEqual(first["untouched"], "odisseu-a6-jvm")
        self.assertEqual(state["datomic"], "odisseu-a6-cutover-datomic")
        second = update(state, "node", sha)
        self.assertFalse(second["changed"])
        rolled = rollback(state, "node")
        self.assertEqual(rolled["image"], "odisseu-node:old")
        self.assertEqual(rolled["otherImage"], "odisseu-jvm:old")

    def test_resume_requires_open_jvm_and_health_reads_both(self):
        state = isolated_state()
        state["jvmPhase"] = "draining"
        result = run("resume", state, "--service", "node")
        self.assertEqual(result.returncode, 2)
        state["jvmPhase"] = "open"
        resumed = run("resume", state, "--service", "node")
        self.assertEqual(resumed.returncode, 0)
        self.assertTrue(health(state)["ok"])

    def test_apply_writes_only_inside_isolated_root(self):
        state = isolated_state()
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / "fixture.json"
            fixture.write_text(json.dumps(state), encoding="utf-8")
            root = Path(tmp) / "root"
            root.mkdir()
            completed = subprocess.run(
                ["bash", str(SCRIPT), "preflight", "--fixture", str(fixture),
                 "--apply", "--isolated-root", str(root)],
                check=False, capture_output=True, text=True,
            )
            self.assertEqual(completed.returncode, 0)
            saved = json.loads((root / "delivery-state.json").read_text(encoding="utf-8"))
            self.assertEqual(saved["datomic"], state["datomic"])
            self.assertEqual(saved["journal"], state["journal"])
            self.assertEqual(saved["maint"], state["maint"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
