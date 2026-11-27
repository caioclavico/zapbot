#!/usr/bin/env python3
"""Homologação descartável do checkpoint e da coordenação GCE OAuth."""
import copy
import hashlib
import json
from pathlib import Path
import shlex
import tempfile
import unittest

from a6_oauth_scope_transition import (
    OAuthScopeCoordinator,
    OAuthTransitionError,
    SERVICE_ACCOUNT,
    OAUTH_SCOPE,
    VM,
    verify_hold,
)
from a6_production_contract import JVM_MOUNTS, NODE_MOUNTS
from a6_window_journal import WindowLock


ORIGINAL_SCOPES = ["https://www.googleapis.com/auth/devstorage.read_only"]
REMOTE_HASH = "f" * 64
DISK = {"name": "odisseu-core", "id": "9001", "boot": True}


class Result:
    def __init__(self, stdout="", returncode=0):
        self.stdout = stdout
        self.returncode = returncode


class FakeGcloud:
    def __init__(self):
        self.instance_doc = {
            "id": "7001",
            "status": "RUNNING",
            "serviceAccounts": [{"email": SERVICE_ACCOUNT, "scopes": list(ORIGINAL_SCOPES)}],
            "disks": [{
                "source": "https://www.googleapis.com/compute/v1/projects/project/zones/us-central1-a/disks/odisseu-core",
                "boot": True,
            }],
        }
        self.remote_phase = "T0_VERIFIED"
        self.remote_hash = REMOTE_HASH
        self.uploads = {}
        self.snapshot_evidence = []
        self.snapshots = {}
        self.calls = []
        self.stop_count = 0
        self.scope_count = 0
        self.start_count = 0
        self.fail_scope_once = False
        self.fail_scope_ack_once = False
        self.fail_runtime_ssh_once = False
        self.fail_start_after = False
        self.fail_start_before = False
        self.fail_pause_ack_once = False
        self.fail_snapshot_once = False
        self.fail_auth_once = False
        self.change_hash_after_start = False
        self.change_runtime_after_start = False
        self.secret_enabled = True
        self.secret_policy_valid = True
        self.runtime = {
            "dockerAvailable": True,
            "datomicWriterContainers": [],
            "containers": [
                self._container("odisseu-a6-jvm", "sha256:" + "3" * 64, JVM_MOUNTS, "a"),
                self._container("odisseu-a6-node", "sha256:" + "4" * 64, NODE_MOUNTS, "b"),
                self._container("odisseu-a6-jvm-previous", "sha256:" + "3" * 64, JVM_MOUNTS, "c"),
            ],
        }

    @staticmethod
    def _container(name, image, mounts, marker):
        return {
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
        }

    def instance(self):
        return copy.deepcopy(self.instance_doc)

    def secret_version(self):
        return {"state": "ENABLED" if self.secret_enabled else "DISABLED"}

    def secret_policy(self):
        members = ["serviceAccount:" + SERVICE_ACCOUNT] if self.secret_policy_valid else ["allUsers"]
        return {"bindings": [{"role": "roles/secretmanager.secretAccessor", "members": members}]}

    def json(self, args, timeout=180):
        self.calls.append(("json", list(args)))
        if args[:3] == ["secrets", "versions", "describe"]:
            return {"state": "ENABLED" if self.secret_enabled else "DISABLED"}
        if args[:2] == ["secrets", "get-iam-policy"]:
            members = ["serviceAccount:" + SERVICE_ACCOUNT] if self.secret_policy_valid else ["allUsers"]
            return {"bindings": [{"role": "roles/secretmanager.secretAccessor", "members": members}]}
        if args[:3] == ["compute", "disks", "describe"]:
            return {"id": DISK["id"]}
        if args[:3] == ["compute", "snapshots", "describe"]:
            name = args[3]
            if name not in self.snapshots:
                raise OAuthTransitionError("resource_not_found")
            return copy.deepcopy(self.snapshots[name])
        raise AssertionError(args)

    def call(self, args, timeout=180):
        self.calls.append(("call", list(args)))
        if args[:3] == ["compute", "disks", "snapshot"]:
            if self.fail_snapshot_once:
                self.fail_snapshot_once = False
                raise OAuthTransitionError("external_command_failed")
            name = next(item.split("=", 1)[1] for item in args if item.startswith("--snapshot-names="))
            self.snapshots[name] = {
                "id": "3001",
                "status": "READY",
                "sourceDisk": "https://www.googleapis.com/compute/v1/projects/p/zones/z/disks/" + DISK["name"],
                "sourceDiskId": DISK["id"],
            }
        elif args[:3] == ["compute", "instances", "stop"]:
            self.stop_count += 1
            self.instance_doc["status"] = "TERMINATED"
        elif args[:3] == ["compute", "instances", "set-service-account"]:
            self.scope_count += 1
            if self.fail_scope_once:
                self.fail_scope_once = False
                raise OAuthTransitionError("external_command_failed")
            scope_arg = next(item.split("=", 1)[1] for item in args if item.startswith("--scopes="))
            self.instance_doc["serviceAccounts"][0]["scopes"] = scope_arg.split(",")
        elif args[:3] == ["compute", "instances", "start"]:
            self.start_count += 1
            if self.fail_start_before:
                self.fail_start_before = False
                raise OAuthTransitionError("external_command_failed")
            self.instance_doc["status"] = "RUNNING"
            if self.change_hash_after_start:
                self.remote_hash = "0" * 64
            if self.change_runtime_after_start:
                self.runtime["containers"][0]["containerId"] = "f" * 64
            if self.fail_start_after:
                self.fail_start_after = False
                raise OAuthTransitionError("external_command_failed")
        elif args[:2] == ["compute", "scp"]:
            self.uploads[args[2]] = Path(args[2]).read_text(encoding="utf-8")
        else:
            raise AssertionError(args)
        return Result()

    def ssh(self, command, tty=False, interactive=False):
        self.calls.append(("ssh", command))
        parts = shlex.split(command)
        if "--oauth-checkpoint-status" in parts:
            result = {
                "windowId": "oauth-test",
                "phase": self.remote_phase,
                "pending": None,
                "holdReleased": False,
                "t0ProofSha256": "a" * 64,
                "backupManifestSha256": "b" * 64,
                "restoreManifestSha256": "b" * 64,
                "rollbackV3Aware": True,
                "rollbackJvmDigest": "sha256:" + "3" * 64,
                "rollbackNodeDigest": "sha256:" + "4" * 64,
                "binding": {
                    "imageSha": "a" * 40,
                    "jvmDigest": "sha256:" + "1" * 64,
                    "nodeDigest": "sha256:" + "2" * 64,
                    "v3Protocol": "POST_V3_UPGRADE",
                },
                "snapshotEvidence": self.snapshot_evidence,
                "vmStopped": self.remote_phase in (
                    "OAUTH_SCOPE_READY", "AUTH_OK",
                ),
                "vmStarted": self.remote_phase in (
                    "OAUTH_SCOPE_READY", "AUTH_OK",
                ),
                "oauthTransition": {
                    "windowId": "oauth-test",
                    "project": VM["project"],
                    "zone": VM["zone"],
                    "instance": VM["instance"],
                    "instanceId": "7001",
                    "serviceAccount": SERVICE_ACCOUNT,
                    "originalScopes": sorted(ORIGINAL_SCOPES),
                    "disks": [DISK],
                    "runtimeBeforeStop": self.runtime,
                    "observedScopes": sorted(set(ORIGINAL_SCOPES) | {OAUTH_SCOPE}),
                    "scopeVerified": self.remote_phase in ("OAUTH_SCOPE_READY", "AUTH_OK"),
                    "runtimeStatus": self.runtime,
                    "authVerified": self.remote_phase == "AUTH_OK",
                },
            }
        elif "--oauth-runtime-status" in parts:
            if self.fail_runtime_ssh_once and self.start_count:
                self.fail_runtime_ssh_once = False
                raise OAuthTransitionError("external_command_failed")
            result = self.runtime
        elif "--oauth-checkpoint-hash" in parts:
            result = {"sha256": self.remote_hash}
        elif any(item.startswith("--record-oauth-") for item in parts):
            option = next(item for item in parts if item.startswith("--record-oauth-"))
            remote_path = parts[-1]
            uploaded = next((value for path, value in self.uploads.items()
                             if path.endswith(Path(remote_path).name)), None)
            if uploaded is None:
                raise AssertionError("missing copied evidence")
            if option == "--record-oauth-snapshot":
                if self.remote_phase != "PAUSED_FOR_OAUTH":
                    self.remote_phase = "SNAPSHOT_READY"
                self.snapshot_evidence = json.loads(uploaded)["snapshots"]
            elif option == "--record-oauth-pause":
                self.remote_phase = "PAUSED_FOR_OAUTH"
                if self.fail_pause_ack_once:
                    self.fail_pause_ack_once = False
                    raise OAuthTransitionError("external_command_failed")
            elif option == "--record-oauth-scope-ready":
                self.remote_phase = "OAUTH_SCOPE_READY"
                self.remote_hash = "c" * 64
                if self.fail_scope_ack_once:
                    self.fail_scope_ack_once = False
                    raise OAuthTransitionError("external_command_failed")
            result = {"phase": self.remote_phase}
        elif "--verify-github-app" in parts:
            if self.fail_auth_once:
                self.fail_auth_once = False
                raise OAuthTransitionError("external_command_failed")
            self.remote_phase = "AUTH_OK"
            self.remote_hash = "d" * 64
            result = {"phase": "AUTH_OK", "hold": "true"}
        elif parts[-1] == "sync" or "rm -f --" in command:
            result = {}
        else:
            raise AssertionError(command)
        return Result(json.dumps(result) + "\n")

    def scp(self, source, destination):
        self.calls.append(("scp", str(source), destination))
        self.uploads[destination] = Path(source).read_text(encoding="utf-8")
        return Result()


class OAuthScopeTransitionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.gcloud = FakeGcloud()
        self.coordinator = OAuthScopeCoordinator(
            "oauth-test", str(self.root / "external"), gcloud=self.gcloud,
        )
        self.proof = {
            "repository": "caioclavico/zapbot",
            "variable": "ODISSEU_PRODUCTION_DEPLOY_HOLD",
            "value": "true",
            "windowId": "oauth-test",
            "checkedAt": "2026-01-02T03:04:05Z",
        }
        self.proof_hash = hashlib.sha256(json.dumps(
            self.proof, sort_keys=True, separators=(",", ":")).encode()).hexdigest()

    def tearDown(self):
        self.temporary.cleanup()

    def prepare(self):
        self.coordinator.prepare_transition(self.proof, self.proof_hash)

    def test_hold_verification_never_writes_false(self):
        state = {"value": "true", "writes": []}

        def gh(args):
            if args[1] == "api":
                return Result(state["value"] + "\n")
            value = args[-1]
            state["writes"].append(value)
            state["value"] = value
            return Result()

        proof, digest = verify_hold(gh, "oauth-test")
        self.assertEqual(proof["value"], "true")
        self.assertEqual(state["writes"], ["true"])
        self.assertEqual(len(digest), 64)

    def test_snapshot_stop_scope_start_and_auth_complete_once(self):
        self.prepare()
        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual(self.gcloud.instance_doc["status"], "RUNNING")
        self.assertEqual(
            set(self.gcloud.instance_doc["serviceAccounts"][0]["scopes"]),
            set(ORIGINAL_SCOPES) | {OAUTH_SCOPE},
        )
        self.assertEqual(self.gcloud.instance_doc["serviceAccounts"][0]["email"], SERVICE_ACCOUNT)
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 1))
        self.assertEqual(self.gcloud.remote_phase, "AUTH_OK")
        self.assertEqual(record["snapshots"][0]["status"], "READY")
        text = self.coordinator.path.read_text(encoding="utf-8").lower()
        self.assertNotIn("token", text)
        self.assertNotIn("private key", text)

    def test_lost_pause_ack_reuses_the_same_snapshots_and_checkpoint(self):
        self.gcloud.fail_pause_ack_once = True
        with self.assertRaises(OAuthTransitionError):
            self.prepare()
        self.assertEqual(self.gcloud.remote_phase, "PAUSED_FOR_OAUTH")
        self.assertEqual(self.gcloud.stop_count, 0)

        record = self.coordinator.prepare_transition(self.proof, self.proof_hash)
        snapshot_calls = [
            entry for entry in self.gcloud.calls
            if entry[0] == "call" and entry[1][:3] == ["compute", "disks", "snapshot"]
        ]
        self.assertEqual(len(snapshot_calls), 1)
        self.assertEqual(record["phase"], "PAUSED_FOR_OAUTH")
        self.assertEqual(len(record["snapshots"]), 1)

    def test_snapshot_failure_blocks_before_vm_stop(self):
        self.gcloud.fail_snapshot_once = True
        with self.assertRaises(OAuthTransitionError):
            self.prepare()
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (0, 0, 0))
        self.assertEqual(self.gcloud.snapshots, {})

    def test_concurrent_transition_cannot_pass_the_external_lock(self):
        self.prepare()
        lock = WindowLock(self.coordinator.directory).acquire()
        try:
            with self.assertRaisesRegex(OAuthTransitionError, "window_in_progress"):
                self.coordinator.stop_update_start()
        finally:
            lock.release()
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (0, 0, 0))

    def test_reverified_true_hold_does_not_invalidate_same_window_checkpoint(self):
        self.prepare()
        proof = dict(self.proof, checkedAt="2026-01-02T03:04:06Z")
        proof_hash = hashlib.sha256(json.dumps(
            proof, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        record = self.coordinator.prepare_transition(proof, proof_hash)
        self.assertEqual(record["phase"], "PAUSED_FOR_OAUTH")
        self.assertEqual(record["holdProofSha256"], self.proof_hash)

    def test_scope_update_interruption_resumes_without_second_stop(self):
        self.prepare()
        self.gcloud.fail_scope_once = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.instance_doc["status"], "TERMINATED")
        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual(self.gcloud.stop_count, 1)
        self.assertEqual(self.gcloud.scope_count, 2)
        self.assertEqual(self.gcloud.start_count, 1)

    def test_start_interruption_observes_running_vm_without_restarting(self):
        self.prepare()
        self.gcloud.fail_start_after = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.instance_doc["status"], "RUNNING")
        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 1))

    def test_failed_boot_retries_only_after_observing_terminated_vm(self):
        self.prepare()
        self.gcloud.fail_start_before = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.instance_doc["status"], "TERMINATED")

        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 2))

    def test_ssh_failure_after_boot_resumes_without_repeating_stop(self):
        self.prepare()
        self.gcloud.fail_runtime_ssh_once = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.instance_doc["status"], "RUNNING")

        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 1))

    def test_changed_container_identity_after_reboot_blocks_authentication(self):
        self.prepare()
        self.gcloud.change_runtime_after_start = True
        with self.assertRaisesRegex(OAuthTransitionError, "remote_runtime_identity_changed"):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.remote_phase, "PAUSED_FOR_OAUTH")
        self.assertNotEqual(self.gcloud.remote_phase, "AUTH_OK")

    def test_tampered_external_runtime_checkpoint_blocks_vm_stop(self):
        self.prepare()
        record = self.coordinator._load()
        record["runtimeBeforeStop"]["containers"][0]["containerId"] = "f" * 64
        self.coordinator._save(record)
        with self.assertRaisesRegex(OAuthTransitionError, "remote_pause_checkpoint_mismatch"):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.stop_count, 0)

    def test_auth_failure_resumes_from_scope_ready_without_repeating_vm_operations(self):
        self.prepare()
        self.gcloud.fail_auth_once = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.remote_phase, "OAUTH_SCOPE_READY")

        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 1))

    def test_lost_scope_ready_ack_adopts_verified_remote_checkpoint(self):
        self.prepare()
        self.gcloud.fail_scope_ack_once = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.remote_phase, "OAUTH_SCOPE_READY")

        record = self.coordinator.stop_update_start()
        self.assertEqual(record["phase"], "AUTH_OK")
        self.assertEqual((self.gcloud.stop_count, self.gcloud.scope_count, self.gcloud.start_count), (1, 1, 1))

    def test_secret_iam_failure_blocks_before_snapshot_or_stop(self):
        self.gcloud.secret_policy_valid = False
        with self.assertRaises(OAuthTransitionError):
            self.prepare()
        self.assertEqual(self.gcloud.stop_count, 0)
        self.assertEqual(self.gcloud.snapshots, {})

    def test_secret_unavailable_blocks_before_snapshot_or_stop(self):
        self.gcloud.secret_enabled = False
        with self.assertRaises(OAuthTransitionError):
            self.prepare()
        self.assertEqual(self.gcloud.stop_count, 0)
        self.assertEqual(self.gcloud.snapshots, {})

    def test_checkpoint_digest_mismatch_blocks_auth_and_cutover(self):
        self.prepare()
        self.gcloud.change_hash_after_start = True
        with self.assertRaises(OAuthTransitionError):
            self.coordinator.stop_update_start()
        self.assertEqual(self.gcloud.remote_phase, "PAUSED_FOR_OAUTH")
        self.assertNotEqual(self.gcloud.remote_phase, "AUTH_OK")

    def test_cutover_requires_the_candidate_bound_to_t0(self):
        self.prepare()
        self.coordinator.stop_update_start()
        calls = len(self.gcloud.calls)
        with self.assertRaisesRegex(OAuthTransitionError, "candidate_binding_mismatch"):
            self.coordinator.run_window({
                "imageSha": "f" * 40,
                "jvmDigest": "sha256:" + "1" * 64,
                "nodeDigest": "sha256:" + "2" * 64,
            }, "cutover")
        self.assertEqual(len(self.gcloud.calls), calls)


if __name__ == "__main__":
    unittest.main(verbosity=2)
