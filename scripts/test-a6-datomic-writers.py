#!/usr/bin/env python3
"""Classifica clientes Datomic. Quiescência não é comprovada enquanto houver bypass."""

from pathlib import Path
import unittest

from a6_writer_policy import inventory_report, prove_quiescence


ROOT = Path(__file__).resolve().parents[1]
RUNTIME = (
    "modules/pokemon/infrastructure/",
    "modules/platform/infrastructure/",
    "shared/persistence/",
    "apps/odisseu/src/odisseu/bridge/",
)


def classify():
    runtime, tests, bypass = [], [], []
    for path in ROOT.rglob("*.clj"):
        text = path.read_text(encoding="utf-8", errors="replace")
        if "datomic.client.api" not in text:
            continue
        relative = path.relative_to(ROOT).as_posix()
        if "/tests/" in f"/{relative}" or relative.startswith("test/"):
            tests.append(relative)
        elif relative.startswith(RUNTIME):
            runtime.append(relative)
        else:
            bypass.append(relative)
    return {
        "runtime": sorted(runtime),
        "tests": sorted(tests),
        "bypass": sorted(bypass),
        "quiescenceProven": False,
        "note": "Inventário de código não comprova writer ativo nem quiescência.",
    }


class DatomicWriterTests(unittest.TestCase):
    def test_bridge_is_not_the_only_writer_and_source_list_does_not_prove_quiescence(self):
        found = classify()
        listed = inventory_report()
        self.assertTrue(any(item.endswith("http_server.clj") for item in found["runtime"]))
        self.assertTrue(found["bypass"])
        self.assertIn("scripts/a64_apply_028_schema_only.clj", found["bypass"])
        self.assertIn("apps/odisseu/src/odisseu/cutover/rehearsal.clj", found["bypass"])
        self.assertFalse(found["quiescenceProven"])
        self.assertFalse(listed["quiescenceProven"])
        self.assertEqual(listed["clients"], 24)
        self.assertEqual(listed["transactPaths"], 13)
        self.assertGreaterEqual(len(found["bypass"]), 5)

    def test_the_thirteen_transact_tools_do_not_open_a_client_directly(self):
        tools = [row["path"] for row in inventory_report()["rows"] if row["transact"]]
        self.assertEqual(len(tools), 13)
        for relative in tools:
            text = (ROOT / relative).read_text(encoding="utf-8")
            self.assertNotIn("(d/client", text, relative)
            self.assertTrue(
                "persistence/client" in text or "(p/client" in text or "live-guard/assert-external!" in text,
                relative,
            )

    def test_quiescence_requires_an_observed_exclusive_mount(self):
        observed = {
            "nodePhase": "draining",
            "jvmPhase": "quiescent",
            "activeNodeOps": 0,
            "activeJvmWrites": 0,
            "authorizedWriter": "odisseu-a6-jvm",
            "writableMounts": ["odisseu-a6-jvm"],
            "capSysAdmin": False,
            "maintenanceToolsRunning": [],
            "laterWriterResult": "no_mount",
            "mountPolicyHeld": True,
            "mountsObserved": True,
        }
        self.assertFalse(prove_quiescence({"quiescenceProven": True})["quiescenceProven"])
        self.assertEqual(prove_quiescence(observed)["phase"], "quiescent")
        second = dict(observed)
        second["writableMounts"] = ["odisseu-a6-jvm", "odisseu-a6-extra"]
        self.assertEqual(prove_quiescence(second)["reason"], "writable_mount_not_exclusive")
        later = dict(observed)
        later["laterWriterResult"] = "writable"
        self.assertFalse(prove_quiescence(later)["quiescenceProven"])
        declared = dict(observed)
        declared["mountsObserved"] = False
        self.assertEqual(prove_quiescence(declared)["reason"], "mounts_not_observed")

    def test_recovery_gate_remains_closed_after_a_bad_marker(self):
        source = (ROOT / "modules/pokemon/infrastructure/pokemon/infrastructure/http/maintenance_gate.clj").read_text(encoding="utf-8")
        self.assertIn("unreadable-maintenance-state", source)
        self.assertIn("A malformed marker must never silently reopen writes.", source)
        server = (ROOT / "apps/odisseu/src/odisseu/bridge/http_server.clj").read_text(encoding="utf-8")
        self.assertIn(".odisseu-writer.lock", server)


if __name__ == "__main__":
    unittest.main(verbosity=2)
