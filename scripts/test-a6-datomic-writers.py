#!/usr/bin/env python3
"""Classifica clientes Datomic. Quiescência não é comprovada enquanto houver bypass."""

from pathlib import Path
import json
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
            self.assertNotIn("runtime-client", text, relative)
            self.assertTrue(
                "persistence/client" in text or "(p/client" in text or "live-guard/assert-external!" in text,
                relative,
            )

    def test_declared_map_does_not_prove_quiescence(self):
        declared = {
            "quiescenceProven": True,
            "mountsObserved": True,
            "environment": "isolated",
            "nodePhase": "draining",
            "jvmPhase": "quiescent",
        }
        report = prove_quiescence(declared, {"environment": "isolated"})
        self.assertFalse(report["quiescenceProven"])
        self.assertEqual(report["reason"], "declared_evidence")
        blocked = prove_quiescence(None, {"environment": "production"})
        self.assertEqual(blocked["reason"], "production_not_authorized")

    def test_slashed_privileged_name_is_not_exclusive(self):
        from a6_mount_policy import assess
        containers = [{
            "Id": "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
            "Name": "/odisseu-a6-jvm",
            "Mounts": [{"Name": "a6-lab-datomic", "Destination": "/data/datomic-local", "RW": True}],
            "HostConfig": {"Privileged": True, "CapAdd": ["SYS_ADMIN"]},
            "CapEff": "00000000a80425fb",
        }]
        result = assess(containers, "a6-lab-datomic", "odisseu-a6-jvm")
        self.assertFalse(result["exclusive"])
        self.assertEqual(result["reason"], "privileged_or_sys_admin")
        quiet = {
            "Id": "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
            "Name": "/odisseu-a6-jvm",
            "Mounts": [{"Name": "a6-lab-datomic", "Destination": "/data/datomic-local", "RW": True}],
            "HostConfig": {"Privileged": False, "CapAdd": []},
            "CapEff": "00000000a80425fb",
        }
        extra = {
            "Id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "Name": "/outra-ferramenta",
            "Mounts": [{"Name": "a6-lab-datomic", "RW": True}],
            "HostConfig": {"Privileged": False, "CapAdd": ["SYS_ADMIN"]},
            "CapEff": "00000000a80425fb",
        }
        second = assess([quiet, extra], "a6-lab-datomic", "odisseu-a6-jvm")
        self.assertFalse(second["exclusive"])
        self.assertEqual(second["reason"], "writable_mount_not_exclusive")

    def test_collected_observation_can_prove_an_isolated_lab_only(self):
        from a6_mount_policy import collect_from_runner
        document = {
            "Id": "abc123abc123abc123abc123abc123abc123abc123abc123abc123abc123abcd",
            "Name": "/odisseu-a6-jvm",
            "Mounts": [{"Name": "a6-lab-datomic", "Destination": "/data/datomic-local", "RW": True}],
            "HostConfig": {"Privileged": False, "CapAdd": []},
        }

        class Result:
            def __init__(self, stdout, returncode=0):
                self.stdout = stdout
                self.returncode = returncode

        def runner(args, processes="clojure.main -m odisseu.bridge.http-server\n"):
            if args[:2] == ["docker", "inspect"]:
                return Result(json.dumps([document]))
            if args[-1] == "/proc/self/status":
                return Result("CapEff:\t00000000a80425fb\n")
            if "ps" in args:
                return Result(processes)
            if str(args[-1]).endswith("bridge-maintenance.edn"):
                return Result("{:phase :quiescent :active-writes 0}\n")
            if str(args[-1]).endswith("a6-maintenance.json"):
                return Result('{"phase":"draining","activeOps":0}\n')
            return Result("", 1)

        collection = collect_from_runner(lambda args: runner(args), "a6-lab-datomic", "odisseu-a6-jvm")
        proven = prove_quiescence(collection, {"environment": "isolated"})
        self.assertTrue(proven["quiescenceProven"])
        self.assertIsNone(proven["reason"])
        busy = collect_from_runner(
            lambda args: runner(args, processes="scripts/a64_apply_028_schema_only.clj\n"),
            "a6-lab-datomic",
            "odisseu-a6-jvm",
        )
        blocked = prove_quiescence(busy, {"environment": "isolated"})
        self.assertFalse(blocked["quiescenceProven"])
        self.assertEqual(blocked["reason"], "external_datomic_client")
        live = collect_from_runner(lambda args: runner(args), "odisseu-a6-cutover-datomic", "odisseu-a6-jvm")
        self.assertIsNone(live)

    def test_recovery_gate_remains_closed_after_a_bad_marker(self):
        source = (ROOT / "modules/pokemon/infrastructure/pokemon/infrastructure/http/maintenance_gate.clj").read_text(encoding="utf-8")
        self.assertIn("unreadable-maintenance-state", source)
        self.assertIn("A malformed marker must never silently reopen writes.", source)
        server = (ROOT / "apps/odisseu/src/odisseu/bridge/http_server.clj").read_text(encoding="utf-8")
        self.assertIn(".odisseu-writer.lock", server)


if __name__ == "__main__":
    unittest.main(verbosity=2)
