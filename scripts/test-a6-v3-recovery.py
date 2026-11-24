#!/usr/bin/env python3
"""Crash AFTER a simulated Datomic commit and BEFORE journal.done.

Uses the actual supervised runner + durable WindowJournal. No production
Docker/VM/WhatsApp or real Datomic writes. The isolated Datomic integration
test is an additional independent release gate, not replaced by this test.
"""
import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import a6_v3_release_gate as gate
from a6_operational_window import MemoryHold
from a6_production_deploy import DeployError
from a6_supervised_deploy import run_supervised
from a6_window_journal import WindowJournal, state_dir

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "a6_supervised_fixture", ROOT / "scripts" / "test-a6-supervised-deploy.py"
)
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)

SHA = fixture.SHA
JVM_DIGEST = fixture.JVM_DIGEST
NODE_DIGEST = fixture.NODE_DIGEST

BEFORE = {
    "status": "BLOCKED", "blocked": 1, "pokemonCount": 1348,
    "playerCount": 39, "saveCount": 102, "eggCount": 43,
    "incubatorCount": 12, "inventoryCount": 158, "coinsTotal": 3055,
    "receiptCount": 8599, "targetLevel": 6, "targetResidual": 21,
    "blockedDetails": [{"id": gate.POKEMON_ID,
                        "reason": "flat9-ambiguous-legacy-xp"}],
    "protectedSha256": "b" * 64,
}
AFTER = dict(BEFORE, status="PASS", blocked=0, blockedDetails=[],
             receiptCount=8600, targetResidual=8,
             reconciliationReceiptVerified=True)
CANDIDATE = {
    "status": "ready", "pokemonId": gate.POKEMON_ID, "level": 6,
    "rawXp": 21, "residualBefore": 21, "residualAfter": 8,
    "snapshotSha256": "a" * 64,
}
REHEARSAL = {"manifestSha256": "c" * 64, "pokemonCount": 1348}
EVIDENCE = {
    "verified": True,
    "receiptId": gate.RECEIPT_ID,
    "pokemonCount": 1348,
    "protectedSha256": "b" * 64,
    "rehearsalManifestSha256": "c" * 64,
}


class PendingCommitRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.isolated = self.root / "export"
        self.isolated.mkdir()
        (self.isolated / "save.edn").write_text(fixture.SAVE)
        self.destination = self.root / "backup"
        self.directory = state_dir(self.root / "journal")
        binding = {
            "vmHostname": "odisseu-core", "imageSha": SHA,
            "jvmDigest": JVM_DIGEST, "nodeDigest": NODE_DIGEST,
            "destination": str(self.destination),
        }
        self.journal = WindowJournal(self.directory, "isolated-cas-crash", binding)
        self.world = fixture.World()
        self.hold = MemoryHold("true")
        self.env = dict(fixture.ENV, A6_V3_SINGLE_CUTOVER=gate.RELEASE_MODE)
        self.receipt_committed = False
        self.recovery_calls = 0

    def runner(self, prior=None, journal=None):
        return run_supervised(
            self.world.runner, SHA, JVM_DIGEST, NODE_DIGEST, self.env,
            self.destination, self.isolated, hold_gate=self.hold,
            prior=prior, journal=journal or self.journal,
            materialize=fixture.materialize,
        )

    def prepare(self, wrapped, prefix):
        self.world.jvm_phase = "frozen"
        return dict(BEFORE)

    def crash_after_cas(self, wrapped, prefix, before, candidate=None):
        self.assertEqual(before, BEFORE)
        self.assertEqual(candidate, CANDIDATE)
        self.receipt_committed = True
        # Models SIGKILL immediately after Datomic's atomic CAS+receipt.
        # SystemExit bypasses runner's ordinary DeployError handler.
        raise SystemExit("after_datomic_commit_before_journal_done")

    def first_run_crash(self, committed=True):
        def crash(wrapped, prefix, before, candidate=None):
            if committed:
                return self.crash_after_cas(wrapped, prefix, before, candidate)
            raise SystemExit("before_datomic_commit")
        with patch("a6_v3_release_gate.verify_rehearsal", return_value=REHEARSAL), \
             patch("a6_v3_release_gate.prepare", side_effect=self.prepare), \
             patch("a6_v3_release_gate.preview", return_value=CANDIDATE), \
             patch("a6_v3_release_gate.commit_once", side_effect=crash):
            with self.assertRaises(SystemExit):
                self.runner()
        state = self.journal.snapshot()
        self.assertEqual(state["pending"]["op"], "v3_release_reconcile")
        self.assertEqual(state["pending"]["before"], BEFORE)
        self.assertEqual(state["pending"]["snapshotSha256"], "a" * 64)
        self.assertEqual(state["steps"][-1], "validate_whatsapp")
        self.assertEqual(self.world.jvm_phase, "frozen")
        self.assertEqual(self.world.node_phase, "quiescent")
        # Official recover_hold restores the guard separately from this runner.
        self.hold.restore()
        self.assertEqual(self.hold.read(), "true")
        return state

    def observe_receipt(self, wrapped, prefix, before, snapshot, rehearsal):
        self.recovery_calls += 1
        if not self.receipt_committed:
            raise DeployError("v3_receipt_not_committed")
        self.assertEqual(before, BEFORE)
        self.assertEqual(snapshot, "a" * 64)
        self.assertEqual(rehearsal, REHEARSAL)
        return dict(EVIDENCE)

    def test_after_commit_crash_recovers_without_second_cas_or_redeploy(self):
        prior = self.first_run_crash(committed=True)
        starting_commands = len(self.world.containers)
        with patch("a6_v3_release_gate.verify_rehearsal", return_value=REHEARSAL), \
             patch("a6_v3_release_gate.recover_committed", side_effect=self.observe_receipt), \
             patch("a6_v3_release_gate.verify_final_state", return_value=AFTER), \
             patch("a6_v3_release_gate.commit_once") as forbidden_cas:
            report = self.runner(prior=prior)
        self.assertTrue(report["deployComplete"], report["blocked"])
        self.assertEqual(self.recovery_calls, 1)
        forbidden_cas.assert_not_called()
        self.assertEqual(len(self.world.containers), starting_commands)
        self.assertTrue(self.receipt_committed)
        self.assertEqual(self.world.jvm_phase, "open")
        self.assertEqual(self.world.node_phase, "open")
        self.assertEqual(self.journal.snapshot()["pending"], None)
        self.assertEqual(self.journal.snapshot()["v3ReleaseEvidence"], EVIDENCE)
        self.assertIn("v3_release_gate", self.journal.snapshot()["steps"])
        self.assertTrue(self.journal.snapshot()["deployComplete"])
        commands = "\n".join(" ".join(x) for x in report["commands"])
        self.assertNotIn(" docker stop ", commands)
        self.assertNotIn(" docker run ", commands)
        self.assertNotIn("/a6-v3-xp-reconcile", commands)

    def test_commit_not_observed_stays_frozen_and_pending(self):
        prior = self.first_run_crash(committed=False)
        with patch("a6_v3_release_gate.verify_rehearsal", return_value=REHEARSAL), \
             patch("a6_v3_release_gate.recover_committed", side_effect=self.observe_receipt), \
             patch("a6_v3_release_gate.commit_once") as forbidden_cas:
            report = self.runner(prior=prior)
        self.assertFalse(report["deployComplete"])
        self.assertIn("v3_receipt_not_committed", report["blocked"])
        forbidden_cas.assert_not_called()
        self.assertEqual(self.world.jvm_phase, "frozen")
        self.assertEqual(self.world.node_phase, "quiescent")
        self.assertEqual(self.journal.snapshot()["pending"]["op"], "v3_release_reconcile")

    def test_post_commit_audit_mismatch_blocks_resume(self):
        prior = self.first_run_crash(committed=True)
        with patch("a6_v3_release_gate.verify_rehearsal", return_value=REHEARSAL), \
             patch("a6_v3_release_gate.recover_committed",
                   side_effect=DeployError("v3_post_audit_manifest_drift")), \
             patch("a6_v3_release_gate.commit_once") as forbidden_cas:
            report = self.runner(prior=prior)
        self.assertIn("v3_post_audit_manifest_drift", report["blocked"])
        self.assertFalse(report["deployComplete"])
        forbidden_cas.assert_not_called()
        self.assertEqual(self.world.jvm_phase, "frozen")
        self.assertEqual(self.journal.snapshot()["pending"]["op"], "v3_release_reconcile")


class ReadOnlyRecoveryTest(unittest.TestCase):
    def test_actual_read_only_recovery_requires_committed_receipt_and_audit(self):
        with patch("a6_v3_release_gate.require_flags"), \
             patch("a6_v3_release_gate.verify_committed_receipt",
                   return_value={"status": "duplicate", "receiptId": gate.RECEIPT_ID,
                                 "snapshotSha256": "a" * 64}), \
             patch("a6_v3_release_gate.audit", return_value=dict(AFTER)), \
             patch("a6_v3_release_gate.reconcile") as forbidden_cas:
            result = gate.recover_committed(lambda args: None, [], BEFORE,
                                             "a" * 64, REHEARSAL)
        self.assertEqual(result, EVIDENCE)
        forbidden_cas.assert_not_called()

    def test_valid_but_different_snapshot_is_rejected_before_audit(self):
        with patch.object(gate, "require_flags"), patch.object(
                gate, "verify_committed_receipt", return_value={"snapshotSha256": "d" * 64}
        ), patch.object(gate, "audit") as audit:
            with self.assertRaisesRegex(DeployError, "receipt_snapshot_mismatch"):
                gate.recover_committed(lambda args: None, [], BEFORE, "a" * 64, REHEARSAL)
        audit.assert_not_called()

    def test_missing_receipt_or_tampered_snapshot_is_fail_closed(self):
        with patch("a6_v3_release_gate.require_flags"), \
             patch("a6_v3_release_gate.verify_committed_receipt",
                   side_effect=DeployError("v3_receipt_not_committed")), \
             patch("a6_v3_release_gate.reconcile") as forbidden_cas:
            with self.assertRaisesRegex(DeployError, "receipt_not_committed"):
                gate.recover_committed(lambda args: None, [], BEFORE, "a" * 64, REHEARSAL)
        forbidden_cas.assert_not_called()
        with self.assertRaisesRegex(DeployError, "missing_snapshot"):
            gate.recover_committed(lambda args: None, [], BEFORE, "", REHEARSAL)


if __name__ == "__main__":
    unittest.main(verbosity=2)
