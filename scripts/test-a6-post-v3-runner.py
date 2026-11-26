#!/usr/bin/env python3
"""Runner oficial com manutenção simulada e fronteira administrativa injetada."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import a6_post_v3_backup as backup
import a6_supervised_deploy as ops
from a6_operational_window import execute_window, MemoryHold, CONFIRM
from a6_production_contract import CURRENT
from a6_production_deploy import DeployError
from a6_window_journal import load, WindowJournal

spec = importlib.util.spec_from_file_location("post_v3_world", Path(__file__).with_name("test-a6-supervised-deploy.py"))
lab = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lab)

AUDIT = {"status": "PASS", "blocked": 0, "blockedReasons": {}, "fullSha256": "f" * 64,
         **{k: True for k in backup.CHECKS}, **{k: 1 for k in backup.COUNTS}}
PLAN = {"protocol": "POST_V3_UPGRADE", "windowId": "post-v3-test", "imageSha": lab.SHA,
        "jvmDigest": lab.JVM_DIGEST, "nodeDigest": lab.NODE_DIGEST,
        "rollbackSha": "d" * 40, "rollbackJvmDigest": CURRENT["jvm"]["imageId"],
        "rollbackNodeDigest": CURRENT["node"]["imageId"], "auditCodeSha256": "e" * 64,
        "system": "fixture", "database": "fixture"}


class World(lab.World):
    def __init__(self):
        super().__init__()
        self.audit = copy.deepcopy(AUDIT)
        self.calls = []
        self.fault = None
        self.flags_ok = True

    def runner(self, args):
        self.calls.append(list(args))
        if self.fault and self.fault(args, self):
            return lab.Result("", 1)
        if args[-1:] and "printf post_v3_flags_ok" in args[-1]:
            return lab.Result("post_v3_flags_ok" if self.flags_ok else "", 0 if self.flags_ok else 1)
        return super().runner(args)

    def _http(self, script):
        if "/a6-v3-xp-post-audit" in script:
            return lab.Result(json.dumps({"ok": True, "data": self.audit}) + "\n200")
        if "/ops/maintenance/freeze" in script:
            self.jvm_phase = "frozen"
            return lab.Result('{"ok":true,"data":{}}\n200')
        return super()._http(script)


class AdministrativeFixture:
    def __init__(self, world):
        self.world = world
        self.plan = copy.deepcopy(PLAN)
        self.audit = copy.deepcopy(AUDIT)
        self.proof = None
        self.calls = []
        self.fail = None
        self.gate = None

    def __call__(self, destination, action):
        self.calls.append(action)
        if self.fail == action:
            raise backup.ProductionBackupError("injected_" + action)
        if action == "preflight":
            return {"plan": self.plan, "planSha256": backup.digest(self.plan)}
        if action == "backup":
            if self.world.jvm_running or self.world.extra_holders:
                raise backup.ProductionBackupError("datomic_writable")
            return {"manifestSha256": "c" * 64}
        if action == "audit":
            if self.proof is None:
                body = backup._proof_payload(self.plan, {"manifestSha256": "c" * 64}, self.audit)
                self.proof = {**body, "proofSha256": backup.digest(body)}
            return copy.deepcopy(self.proof)
        if action == "live-gate":
            body = {"windowId": self.plan["windowId"], "proofSha256": self.proof["proofSha256"],
                    "planSha256": backup.digest(self.plan), "verified": True, "containers": {}}
            self.gate = {**body, "gateSha256": backup.digest(body)}
            return copy.deepcopy(self.gate)
        if action == "gate-status":
            if not self.gate:
                raise backup.ProductionBackupError("post_v3_record_invalid")
            return copy.deepcopy(self.gate)
        raise AssertionError(action)


class Interrupted(BaseException):
    pass


class PostV3RunnerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.world = World()
        self.helper = AdministrativeFixture(self.world)
        self.hold = MemoryHold()
        self.env = dict(lab.ENV, A6_V3_PROTOCOL="POST_V3_UPGRADE",
                        A6_LIVE_BACKUP="autorizar-backup-producao-a6")
        self.env.pop("A6_V3_SINGLE_CUTOVER", None)
        self.previous_ci = {k: os.environ.pop(k, None) for k in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME")}

    def tearDown(self):
        self.temp.cleanup()
        for k, v in self.previous_ci.items():
            if v is not None:
                os.environ[k] = v

    def run_window(self, journal_class=None):
        with patch("a6_post_v3_upgrade.post_v3", self.helper):
            return execute_window(self.world.runner, self.hold, lambda _: CONFIRM,
                lab.SHA, lab.JVM_DIGEST, lab.NODE_DIGEST, self.env,
                "/var/backups/odisseu-a6/post-v3-test/data", None,
                window_id="post-v3-test", state=str(self.root), materialize=lab.materialize)

    def assert_safe(self, report):
        self.assertFalse(report["deployComplete"], report)
        self.assertEqual(self.hold.value, "true")
        commands = "\n".join(" ".join(c) for c in self.world.calls)
        for forbidden in ("a6-v3-xp-reconcile", "a6-v3-xp-preview", "db/transact", "volume rm"):
            self.assertNotIn(forbidden, commands)
        self.assertFalse(report["datomicRestored"])

    def test_complete_official_window_without_historical_cas(self):
        report = self.run_window()
        self.assertTrue(report["deployComplete"], report["blocked"])
        self.assertEqual(report["steps"], list(ops.SUPERVISED_STEPS))
        self.assertEqual(report["hold"], "true")
        journal = load(self.root)
        self.assertTrue(journal["postV3"]["gateVerified"])
        self.assertEqual(journal["postV3"]["proof"], self.helper.proof)
        commands = "\n".join(" ".join(c) for c in self.world.calls)
        self.assertNotIn("a6-v3-xp-reconcile", commands)
        self.assertNotIn("a6-v3-xp-preview", commands)

    def test_bad_evidence_never_cuts_over(self):
        for field, replacement in (("windowId", "other"), ("imageSha", "0" * 40),
                                   ("restoreManifestSha256", "0" * 64),
                                   ("jvmDigest", "sha256:" + "0" * 64),
                                   ("rollbackV3Aware", False)):
            with self.subTest(field=field):
                self.world = World()
                self.helper = AdministrativeFixture(self.world)
                body = backup._proof_payload(PLAN, {"manifestSha256": "c" * 64}, AUDIT)
                body[field] = replacement
                self.helper.proof = {**body, "proofSha256": backup.digest(body)}
                with tempfile.TemporaryDirectory() as directory:
                    self.root = Path(directory)
                    self.hold = MemoryHold()
                    report = self.run_window()
                    self.assert_safe(report)
                    self.assertFalse(self.world.ran_jvm)

    def test_missing_receipt_partial_progress_or_concurrent_writer_blocks(self):
        for kind in ("historicalReceiptVerified", "historicalProvenanceVerified",
                     "pokemonIdentityVerified", "blocked", "writer", "flags"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                self.root = Path(directory)
                self.world = World()
                self.helper = AdministrativeFixture(self.world)
                self.hold = MemoryHold()
                if kind == "writer":
                    self.world.extra_holders = ["intruder\trunning"]
                elif kind == "flags":
                    self.world.flags_ok = False
                else:
                    self.helper.audit[kind] = 1 if kind == "blocked" else False
                report = self.run_window()
                self.assert_safe(report)
                self.assertFalse(self.world.ran_jvm)

    def test_semantic_drift_stays_frozen_without_resume(self):
        self.world.audit["fullSha256"] = "0" * 64
        report = self.run_window()
        self.assert_safe(report)
        self.assertIn("post_v3_live_fingerprint_mismatch", report["blocked"])
        self.assertEqual(self.world.jvm_phase, "frozen")
        self.assertEqual(self.world.node_phase, "quiescent")

    def test_interruptions_resume_same_window_or_require_explicit_recovery(self):
        cases = [
            ("intent", "node_maintenance", None),
            ("step", "jvm_drain", None),
            ("done", "post_v3_backup", None),
            ("done", "post_v3_audit", None),
            ("step", "restore_isolated", None),
            ("done", "update_jvm", "run"),
            ("intent", "update_node", "rename"),
            ("done", "update_node", "run"),
            ("intent", "resume_node", None),
        ]
        for method, target, verb in cases:
            with self.subTest(method=method, target=target, verb=verb), tempfile.TemporaryDirectory() as directory:
                self.root = Path(directory)
                self.world = World()
                self.helper = AdministrativeFixture(self.world)
                self.hold = MemoryHold()
                original = getattr(WindowJournal, method)
                fired = []

                def interrupt(journal, op, **details):
                    original(journal, op, **details)
                    name = op.get("step") if isinstance(op, dict) else op
                    if name == target and (verb is None or details.get("verb") == verb) and not fired:
                        fired.append(True)
                        raise Interrupted()

                with patch.object(WindowJournal, method, interrupt):
                    with self.assertRaises(Interrupted):
                        self.run_window()
                self.assertEqual(self.hold.value, "true")
                report = self.run_window()
                if target == "update_node" and verb == "rename":
                    self.assert_safe(report)
                    self.assertIn("post_v3_explicit_recovery_required", report["blocked"])
                else:
                    self.assertTrue(report["deployComplete"], report["blocked"])
                commands = "\n".join(" ".join(c) for c in self.world.calls)
                self.assertNotIn("a6-v3-xp-reconcile", commands)

    def test_failures_rollback_only_images_and_preserve_state(self):
        for kind in ("jvm-run", "node-run", "health", "contract", "whatsapp"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                self.root = Path(directory)
                self.world = World()
                self.helper = AdministrativeFixture(self.world)
                self.hold = MemoryHold()
                if kind.endswith("-run"):
                    service = kind.split("-")[0]
                    self.world.fault = lambda args, w: (
                        "run" in args and "--name" in args
                        and args[args.index("--name") + 1] == "odisseu-a6-" + service)
                elif kind == "health":
                    self.world.fault = lambda args, w: (
                        setattr(w, "health", "unhealthy") or False) if "run" in args else False
                elif kind == "contract":
                    self.world.contract = "incompatible"
                else:
                    self.world.whatsapp = "DISCONNECTED"
                report = self.run_window()
                self.assert_safe(report)
                for service in ("jvm", "node"):
                    active = [c for n, c in self.world.containers.items()
                              if n.startswith("odisseu-a6-" + service) and c["running"]]
                    self.assertLessEqual(len(active), 1)

    def test_no_journal_or_untrusted_checkpoint_cannot_bypass_gate(self):
        with patch("a6_post_v3_upgrade.post_v3", self.helper):
            report = ops.run_supervised(self.world.runner, lab.SHA, lab.JVM_DIGEST,
                lab.NODE_DIGEST, self.env, str(self.root / "data"), str(self.root),
                window_id="post-v3-test")
        self.assertFalse(report["deployComplete"])
        self.assertIn("post_v3_window_not_authorized", report["blocked"])

    def test_editing_operator_gate_cannot_replace_root_attestation(self):
        original = WindowJournal.step

        def interrupted(journal, item):
            original(journal, item)
            if item.get("step") == "restore_isolated":
                raise Interrupted()

        with patch.object(WindowJournal, "step", interrupted):
            with self.assertRaises(Interrupted):
                self.run_window()
        journal = load(self.root)
        journal["postV3"]["gateVerified"] = True
        journal["postV3"]["liveGate"] = {"verified": True}
        WindowJournal(self.root, "post-v3-test", {}, data=journal).update()
        for service, image in (("jvm", lab.JVM_DIGEST), ("node", lab.NODE_DIGEST)):
            self.world.containers["odisseu-a6-" + service] = {"image": image, "running": True}
        self.world.jvm_phase = self.world.node_phase = "open"
        report = self.run_window()
        self.assert_safe(report)
        self.assertIn("post_v3_record_invalid", report["blocked"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
