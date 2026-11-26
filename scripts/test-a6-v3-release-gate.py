#!/usr/bin/env python3
"""Release #136 — fail-closed checks, no Docker/VM, no real Datomic."""
import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import a6_v3_release_gate as gate
from a6_production_deploy import DeployError


class GateTests(unittest.TestCase):
    def test_release_mode_never_uses_unguarded_live_window(self):
        with self.assertRaisesRegex(DeployError, "protocol_required"):
            gate.release_mode({}, None)
        self.assertTrue(gate.release_mode({gate.RELEASE_ENV: gate.RELEASE_MODE}, None))
        self.assertFalse(gate.release_mode({}, "/tmp/isolated-test"))

    def test_explicit_protocols_never_bypass_restore_proof_or_initial_gate(self):
        initial = {gate.PROTOCOL_ENV: gate.INITIAL_V3_ACTIVATION,
                   gate.RELEASE_ENV: gate.RELEASE_MODE}
        self.assertTrue(gate.release_mode(initial, None))
        with self.assertRaisesRegex(DeployError, "protocol_required"):
            gate.release_mode({gate.PROTOCOL_ENV: gate.INITIAL_V3_ACTIVATION}, "/tmp/lab")
        self.assertFalse(gate.release_mode({gate.PROTOCOL_ENV: gate.POST_V3_UPGRADE}, None))
        with self.assertRaisesRegex(DeployError, "protocol_ambiguous"):
            gate.release_mode(initial | {gate.PROTOCOL_ENV: gate.POST_V3_UPGRADE}, None)
        with self.assertRaisesRegex(DeployError, "protocol_invalid"):
            gate.release_mode({gate.PROTOCOL_ENV: "other"}, None)

    def test_rehearsal_binds_image_digests_and_permissions(self):
        sha = "f" * 40
        jvm = "sha256:" + "a" * 64
        node = "sha256:" + "b" * 64
        template = dict(result="PASS", releaseSha=sha, jvmDigest=jvm,
                        nodeDigest=node, restoreIsolated=True, casReceiptAtomic=True,
                        rollbackV3Aware=True, auditBlocked=0, levelDrift=0,
                        onlyOneWriter=True, manifestSha256="c" * 64, pokemonCount=1336)
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "rehearsal.json"
            p.write_text(json.dumps(template))
            p.chmod(0o600)
            env = {"A6_V3_REHEARSAL_REPORT": str(p)}
            self.assertEqual(gate.verify_rehearsal(env, sha, jvm, node)["pokemonCount"], 1336)
            p.chmod(0o644)
            with self.assertRaisesRegex(DeployError, "permissions"):
                gate.verify_rehearsal(env, sha, jvm, node)
            p.chmod(0o600)
            p.write_text(json.dumps(template | {"rollbackV3Aware": False}))
            with self.assertRaisesRegex(DeployError, "gate_failed"):
                gate.verify_rehearsal(env, sha, jvm, node)

    def test_pre_post_audit_allows_only_targeted_single_reconciliation(self):
        before = {"status": "BLOCKED", "blocked": 1, "pokemonCount": 1336,
                  "targetLevel": 6, "targetResidual": 21,
                  "blockedDetails": [{"id": gate.POKEMON_ID,
                                      "reason": "flat9-ambiguous-legacy-xp"}],
                  "protectedSha256": "e" * 64,
                  "playerCount": 39, "saveCount": 102, "eggCount": 43,
                  "incubatorCount": 0, "inventoryCount": 412, "coinsTotal": 2979,
                  "receiptCount": 100}
        after = dict(before, status="PASS", blocked=0, blockedDetails=[],
                     targetResidual=8, reconciliationReceiptVerified=True,
                     receiptCount=101)
        gate.validate_audit_transition(before, after)
        for drift in ({"pokemonCount": 1335}, {"protectedSha256": "d" * 64},
                      {"reconciliationReceiptVerified": False}, {"blocked": 1}):
            with self.assertRaisesRegex(DeployError, "post_audit_failed"):
                gate.validate_audit_transition(before, after | drift)
        for counts in ({"coinsTotal": 2980}, {"saveCount": 101}):
            with self.assertRaisesRegex(DeployError, "manifest_drift"):
                gate.validate_audit_transition(before, after | counts)
        with self.assertRaisesRegex(DeployError, "receipt_count_invalid"):
            gate.validate_audit_transition(before, after | {"receiptCount": 102})
        with self.assertRaisesRegex(DeployError, "pre_audit_unexpected"):
            gate.validate_audit_transition(before | {"blocked": 2}, after)

    def test_unexpected_pre_audit_never_previews_or_reconciles(self):
        before = dict.fromkeys(gate.AUDIT_COUNTS, 0)
        before.update(status="BLOCKED", blocked=2)
        with patch.object(gate, "preview") as preview, patch.object(gate, "reconcile") as reconcile:
            with self.assertRaisesRegex(DeployError, "pre_audit_unexpected"):
                gate.commit_once(None, [], before)
        preview.assert_not_called()
        reconcile.assert_not_called()

    def test_checkpoint_requires_receipt_evidence_bound_to_rehearsal(self):
        rehearsal = {"manifestSha256": "a" * 64}
        evidence = {"verified": True, "receiptId": gate.RECEIPT_ID,
                    "pokemonCount": 1336, "protectedSha256": "b" * 64,
                    "rehearsalManifestSha256": rehearsal["manifestSha256"]}
        self.assertTrue(gate.valid_checkpoint(evidence, rehearsal))
        self.assertFalse(gate.valid_checkpoint({"verified": True}, rehearsal))
        self.assertFalse(gate.valid_checkpoint(evidence, {"manifestSha256": "c" * 64}))

    def test_no_admin_token_appears_in_command(self):
        seen = []
        def runner(args):
            seen.append(args)
            text = args[-1]
            if "reconcile" in text:
                return SimpleNamespace(returncode=0, stdout=json.dumps(
                    {"ok": True, "data": {"status": "applied",
                     "pokemonId": gate.POKEMON_ID, "level": 6,
                     "residual": 8, "receiptId": gate.RECEIPT_ID}}) + "\n200")
            return SimpleNamespace(returncode=0, stdout="")
        with patch("a6_v3_release_gate._frozen"):
            result = gate.reconcile(runner, ["docker"], "f" * 64)
        self.assertEqual(result["status"], "applied")
        cmd = " ".join(seen[0])
        self.assertIn("$ODISSEU_BRIDGE_MAINTENANCE_TOKEN", cmd)
        self.assertNotIn("not-a-real-secret", cmd)
        self.assertIn(gate.POKEMON_ID, cmd)

    def test_preview_camelcase_fields_exact(self):
        sample = {"status": "ready", "pokemonId": gate.POKEMON_ID,
                  "level": 6, "rawXp": 21, "residualBefore": 21,
                  "residualAfter": 8, "snapshotSha256": "a" * 64}
        with patch("a6_v3_release_gate._frozen"), patch(
                "a6_v3_release_gate._get", return_value=sample):
            self.assertEqual(gate.preview(lambda args: None, ["docker"]), sample)
        with patch("a6_v3_release_gate._frozen"), patch(
                "a6_v3_release_gate._get", return_value=sample | {"level": 100}):
            with self.assertRaisesRegex(DeployError, "precondition_drift"):
                gate.preview(lambda args: None, ["docker"])


if __name__ == "__main__":
    unittest.main(verbosity=2)