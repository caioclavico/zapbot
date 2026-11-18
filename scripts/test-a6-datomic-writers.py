#!/usr/bin/env python3
"""Classifica clientes Datomic. Quiescência não é comprovada enquanto houver bypass."""

from pathlib import Path
import unittest


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
    }


class DatomicWriterTests(unittest.TestCase):
    def test_bridge_is_not_the_only_writer_and_quiescence_stays_unproven(self):
        found = classify()
        self.assertTrue(any(item.endswith("http_server.clj") for item in found["runtime"]))
        self.assertTrue(found["bypass"])
        self.assertIn("scripts/a64_apply_028_schema_only.clj", found["bypass"])
        self.assertIn("apps/odisseu/src/odisseu/cutover/rehearsal.clj", found["bypass"])
        self.assertFalse(found["quiescenceProven"])
        self.assertGreaterEqual(len(found["bypass"]), 5)

    def test_recovery_gate_remains_closed_after_a_bad_marker(self):
        source = (ROOT / "modules/pokemon/infrastructure/pokemon/infrastructure/http/maintenance_gate.clj").read_text(encoding="utf-8")
        self.assertIn("unreadable-maintenance-state", source)
        self.assertIn("A malformed marker must never silently reopen writes.", source)
        server = (ROOT / "apps/odisseu/src/odisseu/bridge/http_server.clj").read_text(encoding="utf-8")
        self.assertIn(".odisseu-writer.lock", server)


if __name__ == "__main__":
    unittest.main(verbosity=2)
