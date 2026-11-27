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
from a6_container_residues import FORMAT, IMAGE_FORMAT
from a6_operational_window import (
    EXPECTED_SERVICE_ACCOUNT,
    OAUTH_SCOPE,
    execute_window,
    MemoryHold,
    CONFIRM,
    pause_for_oauth,
    record_oauth_scope_ready,
    record_oauth_snapshot,
    oauth_runtime_status,
    _read_evidence,
    verify_github_app,
)
from a6_production_contract import CURRENT, JVM_MOUNTS, NODE_MOUNTS
from a6_production_deploy import DeployError
from a6_window_journal import atomic_write_json, load, state_dir, WindowJournal

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


def _runtime_fixture():
    result = {
        "dockerAvailable": True,
        "datomicWriterContainers": [],
        "containers": [{
            "name": name,
            "containerId": marker * 64,
            "imageId": image,
            "running": False,
            "mounts": [{
                "type": item["type"],
                "name": item.get("name") or "",
                "source": item.get("source") or "",
                "destination": item["destination"],
                "rw": item["rw"],
            } for item in mounts],
        } for name, image, mounts, marker in (
            ("odisseu-a6-jvm", PLAN["rollbackJvmDigest"], JVM_MOUNTS, "a"),
            ("odisseu-a6-node", PLAN["rollbackNodeDigest"], NODE_MOUNTS, "b"),
        )],
    }
    containers = result["containers"]
    containers.append({
        "name": "odisseu-a6-node-previous-archived-janela-20261123-0926",
        "containerId": "c" * 64,
        "imageId": "sha256:" + "d" * 64,
        "running": False,
        "mounts": [{
            "type": item["type"],
            "name": item.get("name") or "",
            "source": item.get("source") or "",
            "destination": item["destination"],
            "rw": item["rw"],
        } for item in NODE_MOUNTS],
    })
    return result


RUNTIME = _runtime_fixture()


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
        if FORMAT in args:
            name = args[-1]
            c = self.containers[name]
            original = next(d for d in self.documents()
                            if d["Name"] == "/" + name.removesuffix("-previous"))
            return lab.Result(json.dumps({"id": c.get("id", original["Id"]),
                "imageId": c["image"], "running": c["running"],
                "pid": 1 if c["running"] else 0,
                "status": "running" if c["running"] else "exited",
                "labels": {"io.odisseu.a6.window-id": c.get("window")}}))
        if IMAGE_FORMAT in args:
            return lab.Result(json.dumps({"id": args[-1], "revision": getattr(self, "residue_revision", PLAN["rollbackSha"])}))
        if args[-1:] and "printf post_v3_flags_ok" in args[-1]:
            return lab.Result("post_v3_flags_ok" if self.flags_ok else "", 0 if self.flags_ok else 1)
        return super().runner(args)

    def documents(self):
        docs = super().documents()
        if docs:
            for doc in docs:
                doc["Id"] = doc["Id"].removeprefix("sha256:")
        return docs

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

    def run_protection_window(self):
        self.env.update(
            A6_OAUTH_STAGE="protection",
            A6_HOLD_STORE="external-github-read",
            ODISSEU_PRODUCTION_DEPLOY_HOLD="true",
            A6_HOLD_EXTERNAL_VERIFIED="true",
            A6_HOLD_PROOF_SHA256="a" * 64,
        )
        return self.run_window()

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

    def test_oauth_protection_reaches_t0_without_writing_or_releasing_hold(self):
        report = self.run_protection_window()
        self.assertFalse(report["deployComplete"])
        self.assertEqual(report["next"], "oauth_snapshot", report)
        self.assertEqual(self.hold.value, "true")
        self.assertEqual(self.hold.writes, [])
        self.assertFalse(self.world.containers["odisseu-a6-jvm"]["running"])
        self.assertFalse(self.world.containers["odisseu-a6-node"]["running"], report)
        journal = load(self.root)
        self.assertEqual(journal["phase"], "T0_VERIFIED")
        self.assertEqual(journal["phaseHistory"], [
            "PREPARATION", "PREFLIGHT_OK", "QUIESCENT", "T0_VERIFIED"])
        self.assertEqual(journal["next"], "oauth_snapshot")
        self.assertTrue(journal["postV3"]["proof"]["rollbackV3Aware"])
        commands = "\n".join(" ".join(c) for c in self.world.calls)
        self.assertIn("stop --time 30 odisseu-a6-node", commands)
        self.assertNotIn("a6-v3-xp-reconcile", commands)
        self.assertNotIn(" run ", " " + commands + " ")

    def test_oauth_node_stop_interruption_is_observed_without_a_second_stop(self):
        original = self.world.runner

        def stop_then_interrupt(args):
            result = original(args)
            if "stop" in args and args[-1] == "odisseu-a6-node":
                raise Interrupted()
            return result

        with patch.object(self.world, "runner", side_effect=stop_then_interrupt):
            with self.assertRaises(Interrupted):
                self.run_protection_window()
        self.assertEqual(load(self.root)["pending"]["op"], "oauth_pause_node")
        report = self.run_protection_window()
        self.assertEqual(report["next"], "oauth_snapshot", report)
        self.assertEqual(load(self.root)["phase"], "T0_VERIFIED")
        stops = [call for call in self.world.calls
                 if "stop" in call and call[-1] == "odisseu-a6-node"]
        self.assertEqual(len(stops), 1)

    def test_same_window_records_snapshot_scope_and_auth_evidence(self):
        self.run_protection_window()
        snapshots = [{
            "snapshotName": "a6-post-v3-boot",
            "snapshotId": "123456",
            "sourceDiskName": "odisseu-core",
            "sourceDiskId": "654321",
            "status": "READY",
        }]
        record_oauth_snapshot(str(self.root), "post-v3-test", {"windowId": "post-v3-test",
                                                               "snapshots": snapshots})
        identity = {
            "windowId": "post-v3-test",
            "project": "project-e8cd7809-1145-4be9-b8d",
            "zone": "us-central1-a",
            "instance": "odisseu-core",
            "instanceId": "987654",
            "serviceAccount": EXPECTED_SERVICE_ACCOUNT,
            "scopes": ["https://www.googleapis.com/auth/devstorage.read_only"],
            "disks": [{"name": "odisseu-core", "id": "654321", "boot": True}],
            "runtimeStatus": RUNTIME,
        }
        with patch("a6_operational_window.oauth_runtime_status", return_value=RUNTIME):
            pause_for_oauth(str(self.root), "post-v3-test", identity)
        runtime = RUNTIME
        ready = {
            "windowId": "post-v3-test",
            "instanceId": "987654",
            "serviceAccount": EXPECTED_SERVICE_ACCOUNT,
            "scopes": identity["scopes"] + [OAUTH_SCOPE],
            "stopped": True,
            "started": True,
            "stoppedAt": "2026-01-02T03:04:05Z",
            "startedAt": "2026-01-02T03:05:05Z",
        }
        record_oauth_scope_ready(str(self.root), "post-v3-test", ready, runtime=runtime)
        recovery = {
            "windowId": "post-v3-test",
            "holdBefore": "true",
            "holdAfter": "true",
            "restored": False,
            "containersTouched": False,
            "rollback": False,
            "datomicRestored": False,
            "decisions": [],
            "pid": os.getpid() + 1,
            "audit": str(self.root / "recovery.json"),
        }
        class Completed:
            returncode = 0
            stdout = json.dumps(recovery)

        result = verify_github_app(str(self.root), self.hold, recovery_runner=lambda: Completed())
        self.assertEqual(result["phase"], "AUTH_OK")
        self.assertEqual(self.hold.writes, ["true"])
        journal = load(self.root)
        self.assertEqual(journal["phase"], "AUTH_OK")
        self.assertTrue(journal["oauthTransition"]["authVerified"])
        self.assertEqual(journal["oauthTransition"]["originalScopes"], identity["scopes"])

    def test_oauth_checkpoint_rejects_scope_loss_and_phase_tampering(self):
        self.run_protection_window()
        record_oauth_snapshot(str(self.root), "post-v3-test", {"windowId": "post-v3-test",
                                                               "snapshots": [{
                                                                   "snapshotName": "a6-post-v3-boot",
                                                                   "snapshotId": "123456",
                                                                   "sourceDiskName": "odisseu-core",
                                                                   "sourceDiskId": "654321",
                                                                   "status": "READY",
                                                               }]})
        with patch("a6_operational_window.oauth_runtime_status", return_value=RUNTIME):
            pause_for_oauth(str(self.root), "post-v3-test", {
                "windowId": "post-v3-test",
                "project": "project-e8cd7809-1145-4be9-b8d",
                "zone": "us-central1-a",
                "instance": "odisseu-core",
                "instanceId": "987654",
                "serviceAccount": EXPECTED_SERVICE_ACCOUNT,
                "scopes": ["https://www.googleapis.com/auth/devstorage.read_only"],
                "disks": [{"name": "odisseu-core", "id": "654321", "boot": True}],
                "runtimeStatus": RUNTIME,
            })
        ready = {
            "windowId": "post-v3-test",
            "instanceId": "987654",
            "serviceAccount": EXPECTED_SERVICE_ACCOUNT,
            "scopes": ["https://www.googleapis.com/auth/cloud-platform"],
            "stopped": True,
            "started": True,
            "stoppedAt": "2026-01-02T03:04:05Z",
            "startedAt": "2026-01-02T03:05:05Z",
        }
        with self.assertRaises(DeployError):
            record_oauth_scope_ready(str(self.root), "post-v3-test", ready,
                                     runtime={"dockerAvailable": True, "datomicWriterContainers": [],
                                              "containers": []})
        journal = load(self.root)
        journal["phaseHistory"][-1] = "AUTH_OK"
        WindowJournal(self.root, "post-v3-test", {}, data=journal).update(note="tampered")
        with self.assertRaises(DeployError):
            record_oauth_snapshot(str(self.root), "post-v3-test", {"windowId": "post-v3-test",
                                                                   "snapshots": []})

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

    def test_previous_containers_block_before_maintenance_with_resolution_plan(self):
        for running, origin, revision in ((False, "old-window", "d" * 40),
                                         (True, "other-window", "d" * 40),
                                         (False, None, None)):
            with self.subTest(running=running, origin=origin), tempfile.TemporaryDirectory() as directory:
                self.root = Path(directory)
                self.world = World()
                self.world.residue_revision = revision
                self.world.containers["odisseu-a6-jvm-previous"] = {
                    "image": CURRENT["jvm"]["imageId"], "running": running, "window": origin}
                self.helper = AdministrativeFixture(self.world)
                self.hold = MemoryHold()
                report = self.run_window()
                self.assert_safe(report)
                self.assertIn("previous_container_requires_resolution", report["blocked"])
                self.assertTrue(report["residueResolutionPlan"])
                self.assertFalse(report["residueResolutionPlan"][0]["execute"])
                text = "\n".join(" ".join(c) for c in self.world.calls)
                for token in ("/ops/maintenance/enter", " stop ", " rename ", " rm "):
                    self.assertNotIn(token, " " + text + " ")

    def test_residue_appearing_during_drain_blocks_before_stop(self):
        original = self.world.runner

        def runner(args):
            result = original(args)
            if "/ops/maintenance/enter" in args[-1] and "18090" in args[-1]:
                self.world.containers["odisseu-a6-node-previous"] = {
                    "image": CURRENT["node"]["imageId"], "running": False}
            return result

        with patch.object(self.world, "runner", runner):
            report = self.run_window()
        self.assert_safe(report)
        self.assertTrue(self.world.jvm_running)
        self.assertIn("previous_container_requires_resolution", report["blocked"])


class OAuthRuntimeInventoryTests(unittest.TestCase):
    def runtime(self, names, running=None, holders=""):
        running = running or {}
        mounts = {
            "odisseu-a6-jvm": JVM_MOUNTS,
            "odisseu-a6-node": NODE_MOUNTS,
            "odisseu-a6-jvm-previous": JVM_MOUNTS,
            "odisseu-a6-node-previous": NODE_MOUNTS,
            "odisseu-a6-node-previous-archived-janela-20261123-0926": NODE_MOUNTS,
        }
        documents = [{
            "Name": "/" + name,
            "Id": "id-" + name,
            "Image": "sha256:" + ("a" * 64),
            "State": {"Running": running.get(name, False)},
            "Mounts": [{
                "Type": item["type"],
                "Name": item.get("name") or "",
                "Source": item.get("source") or "",
                "Destination": item["destination"],
                "RW": item["rw"],
            } for item in mounts[name]],
        } for name in names if name in mounts]
        volumes = sorted({
            item["name"]
            for expected in (JVM_MOUNTS, NODE_MOUNTS)
            for item in expected
            if item["type"] == "volume"
        })

        def runner(args):
            if args == ["docker", "info"]:
                return lab.Result("")
            if args[:3] == ["docker", "ps", "-a"]:
                return lab.Result("\n".join(names))
            if args[:2] == ["docker", "inspect"]:
                return lab.Result(json.dumps(documents))
            if args[:2] == ["docker", "volume"]:
                return lab.Result(json.dumps([{"Name": name} for name in volumes]))
            if args[:3] == ["docker", "ps", "-q"]:
                return lab.Result(holders)
            raise AssertionError(args)

        return runner

    def test_runtime_inventory_accepts_only_stopped_known_containers(self):
        archived = "odisseu-a6-node-previous-archived-janela-20261123-0926"
        names = ["odisseu-a6-jvm", "odisseu-a6-node",
                 "odisseu-a6-jvm-previous", archived]
        result = oauth_runtime_status(self.runtime(names))
        self.assertTrue(result["dockerAvailable"])
        self.assertEqual(result["datomicWriterContainers"], [])
        self.assertEqual(len(result["containers"]), len(names))

        with self.assertRaisesRegex(DeployError, "oauth_container_inventory_incomplete"):
            oauth_runtime_status(self.runtime(names + ["odisseu-a6-untracked"]))
        with self.assertRaisesRegex(DeployError, "oauth_legacy_container_running"):
            oauth_runtime_status(self.runtime(
                names, running={"odisseu-a6-jvm-previous": True}
            ))
        with self.assertRaisesRegex(DeployError, "oauth_legacy_container_running"):
            oauth_runtime_status(self.runtime(names, running={archived: True}))
        with self.assertRaisesRegex(DeployError, "oauth_datomic_writer_present"):
            oauth_runtime_status(self.runtime(names, holders="container-id"))

    def test_oauth_evidence_is_read_only_from_private_window_state(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = state_dir(Path(temporary) / "state")
            evidence = state / ".a6-oauth-window-1-pause.json"
            atomic_write_json(evidence, {"windowId": "window-1"})
            self.assertEqual(_read_evidence(evidence, state, "pause")["windowId"], "window-1")
            with self.assertRaisesRegex(DeployError, "oauth_evidence_invalid"):
                _read_evidence(evidence, state, "snapshot")
            evidence.chmod(0o644)
            with self.assertRaisesRegex(DeployError, "oauth_evidence_invalid"):
                _read_evidence(evidence, state, "pause")


if __name__ == "__main__":
    unittest.main(verbosity=2)
