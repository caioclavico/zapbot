#!/usr/bin/env python3
"""Coordena externamente o snapshot e o stop/start OAuth da mesma janela A6."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

from a6_production_contract import (
    JVM_MOUNTS,
    NODE_MOUNTS,
    VM,
    mounts_match,
    preserved_legacy_container_name,
)
from a6_production_deploy import DeployError
from a6_window_journal import (
    WindowLock,
    _contains_secret_material,
    atomic_write_json,
    state_dir,
)


SERVICE_ACCOUNT = "320125207473-compute@developer.gserviceaccount.com"
SECRET = "odisseu-github-app-private-key"
OAUTH_SCOPE = "https://www.googleapis.com/auth/cloud-platform"
REMOTE_RUNNER = "/usr/local/lib/odisseu-a6/current/a6_operational_window.py"
REMOTE_WINDOW = "/usr/local/lib/odisseu-a6/current/a6-operational-window.sh"
REMOTE_STATE = "/home/caiohclavico/.odisseu-a6-window"
HOLD_NAME = "ODISSEU_PRODUCTION_DEPLOY_HOLD"
REPO = "caioclavico/zapbot"
TRANSITION_PHASES = {
    "PROTECTION_INTENT", "T0_VERIFIED", "SNAPSHOTS_READY", "PAUSED_FOR_OAUTH",
    "STOP_INTENT", "VM_STOPPED", "SCOPE_UPDATE_INTENT", "SCOPE_UPDATED",
    "START_INTENT", "VM_STARTED", "OAUTH_SCOPE_READY", "AUTH_OK",
}
CONFIRM_ENV = {
    "A6_EXECUTE": "1",
    "A6_SUPERVISED_WINDOW": "janela-supervisionada-a6",
    "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
    "ODISSEU_A6_DELIVERY_VALIDATED": "true",
    "A6_LIVE_BACKUP": "autorizar-backup-producao-a6",
    "A6_RESUME_EXPLICIT": "confirmar-resume-a6",
    "A6_SUPERVISED_CONFIRM": "executar-janela-a6",
}
SECCOMP_SHA256 = "4e4980dbebe8f2853a3e1df17489a00cf027237bfc044516e4b7e84fc610fa10"


class OAuthTransitionError(Exception):
    pass


def _utc():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _run(args, timeout=180):
    try:
        result = subprocess.run(args, check=False, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise OAuthTransitionError("external_command_failed") from exc
    if result.returncode:
        message = (result.stderr or "").lower()
        if "not found" in message or "not_found" in message or "404" in message:
            raise OAuthTransitionError("resource_not_found")
        raise OAuthTransitionError("external_command_failed")
    return result


def verify_hold(runner=_run, window_id=""):
    """Lê true, grava true idempotente e relê sem transportar credenciais."""
    read = ["gh", "api", "repos/" + REPO + "/actions/variables/" + HOLD_NAME, "--jq", ".value"]
    before = runner(read)
    if before.stdout.strip() != "true":
        raise OAuthTransitionError("hold_not_protected")
    written = runner(["gh", "variable", "set", HOLD_NAME, "--repo", REPO, "--body", "true"])
    if written.returncode:
        raise OAuthTransitionError("hold_write_failed")
    after = runner(read)
    if after.stdout.strip() != "true":
        raise OAuthTransitionError("hold_write_unconfirmed")
    proof = {
        "repository": REPO,
        "variable": HOLD_NAME,
        "value": "true",
        "windowId": window_id,
        "checkedAt": _utc(),
    }
    return proof, hashlib.sha256(json.dumps(
        proof, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


class Gcloud:
    def __init__(self, runner=_run):
        self.runner = runner

    def call(self, args, timeout=180):
        return self.runner(["gcloud", *args], timeout=timeout)

    def json(self, args, timeout=180):
        result = self.call([*args, "--format=json"], timeout=timeout)
        try:
            value = json.loads(result.stdout)
        except (TypeError, json.JSONDecodeError) as exc:
            raise OAuthTransitionError("gcloud_response_invalid") from exc
        if not isinstance(value, dict):
            raise OAuthTransitionError("gcloud_response_invalid")
        return value

    def instance(self):
        return self.json([
            "compute", "instances", "describe", VM["instance"],
            "--project=" + VM["project"], "--zone=" + VM["zone"],
        ])

    def secret_version(self):
        return self.json([
            "secrets", "versions", "describe", "1",
            "--secret=" + SECRET, "--project=" + VM["project"],
        ])

    def secret_policy(self):
        return self.json([
            "secrets", "get-iam-policy", SECRET, "--project=" + VM["project"],
        ])

    def ssh(self, command, tty=False, interactive=False):
        args = [
            "gcloud", "compute", "ssh", VM["instance"],
            "--project=" + VM["project"], "--zone=" + VM["zone"],
            "--quiet", "--command=" + command,
        ]
        if tty:
            args.extend(["--", "-t"])
        if interactive:
            return subprocess.run(args, check=False)
        return self.runner(args, timeout=240)

    def scp(self, source, destination):
        return self.call([
            "compute", "scp", str(source), VM["instance"] + ":" + destination,
            "--project=" + VM["project"], "--zone=" + VM["zone"], "--quiet",
        ])


class OAuthScopeCoordinator:
    def __init__(self, window_id, state, gcloud=None, remote_state=REMOTE_STATE):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", str(window_id or "")):
            raise OAuthTransitionError("window_id_invalid")
        self.window_id = window_id
        self.directory = state_dir(state)
        self.path = self.directory / ("a6-oauth-transition-" + window_id + ".json")
        self.gcloud = gcloud or Gcloud()
        self.remote_state = remote_state
        if not remote_state.startswith("/") or ".." in Path(remote_state).parts:
            raise OAuthTransitionError("remote_state_path_invalid")

    def _save(self, record):
        atomic_write_json(self.path, record)

    def _validate_hold_proof(self, proof, digest):
        if (not isinstance(proof, dict)
                or set(proof) != {"repository", "variable", "value", "windowId", "checkedAt"}
                or proof.get("repository") != REPO
                or proof.get("variable") != HOLD_NAME
                or proof.get("value") != "true"
                or proof.get("windowId") != self.window_id
                or not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ",
                                    str(proof.get("checkedAt") or ""))
                or not re.fullmatch(r"[0-9a-f]{64}", str(digest or ""))
                or hashlib.sha256(json.dumps(
                    proof, sort_keys=True, separators=(",", ":")).encode()).hexdigest() != digest):
            raise OAuthTransitionError("hold_proof_invalid")

    def _exclusive(self, operation, *args):
        try:
            with WindowLock(self.directory):
                return operation(*args)
        except DeployError as exc:
            raise OAuthTransitionError(str(exc)) from exc

    def _load(self):
        if not self.path.exists():
            return None
        try:
            record = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise OAuthTransitionError("external_checkpoint_invalid") from exc
        if (not isinstance(record, dict) or type(record.get("schema")) is not int
                or record.get("schema") != 1
                or record.get("windowId") != self.window_id
                or record.get("project") != VM["project"]
                or record.get("zone") != VM["zone"]
                or record.get("instance") != VM["instance"]
                or not isinstance(record.get("phase"), str)
                or record.get("phase") not in TRANSITION_PHASES
                or _contains_secret_material(record)):
            raise OAuthTransitionError("external_checkpoint_invalid")
        self._validate_checkpoint(record)
        return record

    def _validate_checkpoint(self, record):
        required = {
            "schema", "windowId", "project", "zone", "instance", "instanceId",
            "serviceAccount", "originalScopes", "disks", "snapshots", "t0ProofSha256",
            "holdProof", "holdProofSha256", "checkpointSha256", "phase", "candidate",
            "rollbackJvmDigest", "rollbackNodeDigest", "runtimeBeforeStop",
            "stoppedAt", "startedAt",
        }
        allowed = required | {
            "runtimeStatus",
        }
        if not required.issubset(record) or set(record) - allowed:
            raise OAuthTransitionError("external_checkpoint_invalid")
        if (not re.fullmatch(r"[0-9]+", str(record.get("instanceId") or ""))
                or record.get("serviceAccount") != SERVICE_ACCOUNT
                or not re.fullmatch(r"[0-9a-f]{64}", str(record.get("t0ProofSha256") or ""))
                or not re.fullmatch(r"[0-9a-f]{64}", str(record.get("holdProofSha256") or ""))
                or not isinstance(record.get("holdProof"), dict)
                or not isinstance(record.get("originalScopes"), list)
                or not record["originalScopes"]
                or any(not isinstance(scope, str)
                       or not scope.startswith("https://www.googleapis.com/auth/")
                       for scope in record["originalScopes"])
                or len(record["originalScopes"]) != len(set(record["originalScopes"]))
                or record["originalScopes"] != sorted(record["originalScopes"])
                or OAUTH_SCOPE in record["originalScopes"]):
            raise OAuthTransitionError("external_checkpoint_invalid")
        candidate = record["candidate"]
        if (not isinstance(candidate, dict)
                or set(candidate) != {"imageSha", "jvmDigest", "nodeDigest", "v3Protocol"}
                or candidate.get("v3Protocol") != "POST_V3_UPGRADE"
                or not re.fullmatch(r"[0-9a-f]{40}", str(candidate.get("imageSha") or ""))
                or any(not re.fullmatch(r"sha256:[0-9a-f]{64}",
                                        str(candidate.get(key) or ""))
                       for key in ("jvmDigest", "nodeDigest"))
                or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(record.get(key) or ""))
                       for key in ("rollbackJvmDigest", "rollbackNodeDigest"))):
            raise OAuthTransitionError("external_checkpoint_invalid")
        proof = record["holdProof"]
        expected_proof = {
            "repository": REPO,
            "variable": HOLD_NAME,
            "value": "true",
            "windowId": self.window_id,
        }
        if (any(proof.get(key) != value for key, value in expected_proof.items())
                or not isinstance(proof.get("checkedAt"), str)
                or hashlib.sha256(json.dumps(
                    proof, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
                != record["holdProofSha256"]):
            raise OAuthTransitionError("external_checkpoint_invalid")
        disks = record["disks"]
        if not isinstance(disks, list) or not disks:
            raise OAuthTransitionError("external_checkpoint_invalid")
        disk_map = {}
        for disk in disks:
            if (not isinstance(disk, dict)
                    or set(disk) != {"name", "id", "boot"}
                    or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}", str(disk.get("name") or ""))
                    or not re.fullmatch(r"[0-9]+", str(disk.get("id") or ""))
                    or type(disk.get("boot")) is not bool
                    or disk["name"] in disk_map):
                raise OAuthTransitionError("external_checkpoint_invalid")
            disk_map[disk["name"]] = str(disk["id"])
        if disks != sorted(disks, key=lambda item: item["name"]):
            raise OAuthTransitionError("external_checkpoint_invalid")
        snapshots = record["snapshots"]
        if not isinstance(snapshots, list):
            raise OAuthTransitionError("external_checkpoint_invalid")
        snapshot_map = {}
        for snapshot in snapshots:
            if (not isinstance(snapshot, dict)
                    or set(snapshot) != {"snapshotName", "snapshotId", "sourceDiskName",
                                         "sourceDiskId", "status"}
                    or snapshot.get("status") != "READY"
                    or not re.fullmatch(r"[a-z][a-z0-9-]{0,62}",
                                        str(snapshot.get("snapshotName") or ""))
                    or not re.fullmatch(r"[0-9]+", str(snapshot.get("snapshotId") or ""))
                    or snapshot.get("sourceDiskName") not in disk_map
                    or str(snapshot.get("sourceDiskId")) != disk_map[snapshot["sourceDiskName"]]
                    or snapshot["sourceDiskName"] in snapshot_map):
                raise OAuthTransitionError("external_checkpoint_invalid")
            snapshot_map[snapshot["sourceDiskName"]] = snapshot["snapshotName"]
        snapshot_required = record["phase"] not in ("PROTECTION_INTENT", "T0_VERIFIED")
        if snapshot_required and set(snapshot_map) != set(disk_map):
            raise OAuthTransitionError("external_checkpoint_invalid")
        paused = record["phase"] not in (
            "PROTECTION_INTENT", "T0_VERIFIED", "SNAPSHOTS_READY",
        )
        if paused != bool(re.fullmatch(
                r"[0-9a-f]{64}", str(record.get("checkpointSha256") or ""))):
            raise OAuthTransitionError("external_checkpoint_invalid")
        if record["phase"] in ("OAUTH_SCOPE_READY", "AUTH_OK"):
            if not isinstance(record.get("runtimeStatus"), dict):
                raise OAuthTransitionError("external_checkpoint_invalid")
        self._verify_runtime(
            record["runtimeBeforeStop"],
            record["rollbackJvmDigest"],
            record["rollbackNodeDigest"],
        )

    def _remote_command(self, command):
        prefix = "A6_STATE_DIR=" + shlex.quote(self.remote_state) + " "
        return self.gcloud.ssh(prefix + command)

    def _remote_json(self, arguments):
        command = "python3 " + shlex.quote(REMOTE_RUNNER) + " " + " ".join(
            shlex.quote(str(item)) for item in arguments)
        result = self._remote_command(command)
        lines = (result.stdout or "").strip().splitlines()
        if not lines:
            raise OAuthTransitionError("remote_evidence_missing")
        try:
            value = json.loads(lines[-1])
        except json.JSONDecodeError as exc:
            raise OAuthTransitionError("remote_evidence_invalid") from exc
        if not isinstance(value, dict):
            raise OAuthTransitionError("remote_evidence_invalid")
        return value

    def _remote_checkpoint_hash(self):
        result = self._remote_json(["--oauth-checkpoint-hash"])
        value = result.get("sha256")
        if not re.fullmatch(r"[0-9a-f]{64}", str(value or "")):
            raise OAuthTransitionError("remote_checkpoint_hash_invalid")
        return value

    def _verify_remote_pause(self, record):
        status = self._remote_json(["--oauth-checkpoint-status", self.window_id])
        transition = status.get("oauthTransition")
        binding = status.get("binding")
        if not isinstance(transition, dict) or not isinstance(binding, dict):
            raise OAuthTransitionError("remote_pause_checkpoint_mismatch")
        if (status.get("windowId") != self.window_id
                or status.get("phase") != "PAUSED_FOR_OAUTH"
                or status.get("pending") is not None
                or status.get("holdReleased") is not False
                or status.get("t0ProofSha256") != record.get("t0ProofSha256")
                or status.get("backupManifestSha256") != status.get("restoreManifestSha256")
                or not re.fullmatch(r"[0-9a-f]{64}",
                                    str(status.get("backupManifestSha256") or ""))
                or status.get("rollbackV3Aware") is not True
                or status.get("snapshotEvidence") != record.get("snapshots")
                or status.get("rollbackJvmDigest") != record.get("rollbackJvmDigest")
                or status.get("rollbackNodeDigest") != record.get("rollbackNodeDigest")
                or any(binding.get(key) != record["candidate"].get(key)
                       for key in ("imageSha", "jvmDigest", "nodeDigest", "v3Protocol"))
                or transition.get("windowId") != self.window_id
                or transition.get("project") != VM["project"]
                or transition.get("zone") != VM["zone"]
                or transition.get("instance") != VM["instance"]
                or transition.get("instanceId") != record.get("instanceId")
                or transition.get("serviceAccount") != record.get("serviceAccount")
                or transition.get("originalScopes") != record.get("originalScopes")
                or transition.get("disks") != record.get("disks")
                or transition.get("runtimeBeforeStop") != record.get("runtimeBeforeStop")
                or transition.get("scopeVerified") is not False
                or transition.get("authVerified") is not False):
            raise OAuthTransitionError("remote_pause_checkpoint_mismatch")

    def _upload_evidence(self, action, evidence):
        local = self.directory / ("a6-oauth-" + self.window_id + "-" + action + ".json")
        atomic_write_json(local, evidence)
        remote = self.remote_state.rstrip("/") + "/." + local.name
        self.gcloud.scp(local, remote)
        try:
            result = self._remote_json(["--record-oauth-" + action, remote])
        finally:
            self._remote_command("rm -f -- " + shlex.quote(remote))
        return result

    @staticmethod
    def _account_and_scopes(instance):
        accounts = instance.get("serviceAccounts") or []
        if (not isinstance(accounts, list) or len(accounts) != 1
                or not isinstance(accounts[0], dict)
                or not isinstance(accounts[0].get("email"), str)
                or not isinstance(accounts[0].get("scopes"), list)):
            raise OAuthTransitionError("gce_identity_invalid")
        return accounts[0]["email"], accounts[0]["scopes"]

    def _disk_inventory(self, instance):
        rows = []
        for disk in instance.get("disks") or []:
            source = str(disk.get("source") or "")
            name = source.rsplit("/", 1)[-1]
            if not name or type(disk.get("boot")) is not bool:
                raise OAuthTransitionError("gce_disk_inventory_invalid")
            described = self.gcloud.json([
                "compute", "disks", "describe", name,
                "--project=" + VM["project"], "--zone=" + VM["zone"],
            ])
            identifier = described.get("id")
            if not re.fullmatch(r"[0-9]+", str(identifier or "")):
                raise OAuthTransitionError("gce_disk_identity_invalid")
            rows.append({"name": name, "id": str(identifier), "boot": disk["boot"]})
        if not rows or len({row["name"] for row in rows}) != len(rows):
            raise OAuthTransitionError("gce_disk_inventory_invalid")
        return sorted(rows, key=lambda row: row["name"])

    def _verify_instance(self, instance, record, expected_scopes):
        email, scopes = self._account_and_scopes(instance)
        if (instance.get("id") != record.get("instanceId")
                or email != record.get("serviceAccount")
                or set(scopes) != set(expected_scopes)):
            raise OAuthTransitionError("gce_instance_identity_mismatch")
        disks = self._disk_inventory(instance)
        if disks != record.get("disks"):
            raise OAuthTransitionError("gce_disk_identity_mismatch")
        return instance

    @staticmethod
    def _verify_runtime(runtime, rollback_jvm, rollback_node):
        known = {
            "odisseu-a6-jvm", "odisseu-a6-node",
            "odisseu-a6-jvm-previous", "odisseu-a6-node-previous",
        }
        containers = runtime.get("containers") if isinstance(runtime, dict) else None
        if (not isinstance(runtime, dict)
                or runtime.get("dockerAvailable") is not True
                or runtime.get("datomicWriterContainers") != []
                or not isinstance(containers, list)):
            raise OAuthTransitionError("remote_runtime_not_quiescent")
        by_name = {}
        for item in containers:
            if (not isinstance(item, dict) or not isinstance(item.get("name"), str)
                    or (item["name"] not in known
                        and not preserved_legacy_container_name(item["name"]))
                    or item["name"] in by_name):
                raise OAuthTransitionError("remote_runtime_not_quiescent")
            by_name[item["name"]] = item
        expected = {
            "odisseu-a6-jvm": (rollback_jvm, JVM_MOUNTS),
            "odisseu-a6-node": (rollback_node, NODE_MOUNTS),
        }
        if not set(expected).issubset(by_name):
            raise OAuthTransitionError("remote_runtime_not_quiescent")
        for name, (image, mounts) in expected.items():
            item = by_name[name]
            if (item.get("running") is not False or item.get("imageId") != image
                    or not re.fullmatch(r"[0-9a-f]{12,64}", str(item.get("containerId") or ""))
                    or not isinstance(item.get("mounts"), list)
                    or not mounts_match(item["mounts"], mounts)):
                raise OAuthTransitionError("remote_runtime_not_quiescent")
        for name in set(by_name) - set(expected):
            item = by_name[name]
            if (item.get("running") is not False
                    or not re.fullmatch(r"[0-9a-f]{12,64}", str(item.get("containerId") or ""))
                    or not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item.get("imageId") or ""))
                    or not isinstance(item.get("mounts"), list)
                    or any(
                        not isinstance(mount, dict)
                        or set(mount) != {"type", "name", "source", "destination", "rw"}
                        or mount.get("type") not in ("bind", "volume")
                        or not isinstance(mount.get("name"), str)
                        or not isinstance(mount.get("source"), str)
                        or not isinstance(mount.get("destination"), str)
                        or type(mount.get("rw")) is not bool
                        for mount in item["mounts"]
                    )):
                raise OAuthTransitionError("remote_runtime_not_quiescent")
        return runtime

    @staticmethod
    def _runtime_identity(runtime):
        return {
            item["name"]: {
                "containerId": item["containerId"],
                "imageId": item["imageId"],
                "mounts": item["mounts"],
            }
            for item in runtime["containers"]
        }

    def _adopt_scope_ready(self, record, desired):
        status = self._remote_json(["--oauth-checkpoint-status", self.window_id])
        transition = status.get("oauthTransition")
        binding = status.get("binding")
        if not isinstance(transition, dict) or not isinstance(binding, dict):
            raise OAuthTransitionError("remote_scope_checkpoint_mismatch")
        runtime = transition.get("runtimeStatus")
        if (status.get("windowId") != self.window_id
                or status.get("phase") not in ("OAUTH_SCOPE_READY", "AUTH_OK")
                or status.get("pending") is not None
                or status.get("holdReleased") is not False
                or status.get("t0ProofSha256") != record.get("t0ProofSha256")
                or status.get("backupManifestSha256") != status.get("restoreManifestSha256")
                or not re.fullmatch(r"[0-9a-f]{64}",
                                    str(status.get("backupManifestSha256") or ""))
                or status.get("rollbackV3Aware") is not True
                or any(binding.get(key) != record["candidate"].get(key)
                       for key in ("imageSha", "jvmDigest", "nodeDigest", "v3Protocol"))
                or status.get("rollbackJvmDigest") != record.get("rollbackJvmDigest")
                or status.get("rollbackNodeDigest") != record.get("rollbackNodeDigest")
                or status.get("snapshotEvidence") != record.get("snapshots")
                or status.get("vmStopped") is not True or status.get("vmStarted") is not True
                or transition.get("instanceId") != record.get("instanceId")
                or transition.get("serviceAccount") != record.get("serviceAccount")
                or transition.get("originalScopes") != record.get("originalScopes")
                or transition.get("runtimeBeforeStop") != record.get("runtimeBeforeStop")
                or transition.get("observedScopes") != desired
                or transition.get("scopeVerified") is not True
                or (status.get("phase") == "AUTH_OK" and transition.get("authVerified") is not True)):
            raise OAuthTransitionError("remote_scope_checkpoint_mismatch")
        self._verify_runtime(runtime, record["rollbackJvmDigest"], record["rollbackNodeDigest"])
        if self._runtime_identity(runtime) != self._runtime_identity(record["runtimeBeforeStop"]):
            raise OAuthTransitionError("remote_runtime_identity_changed")
        return status["phase"], runtime

    def _wait_status(self, expected, attempts):
        for attempt in range(attempts):
            instance = self.gcloud.instance()
            if instance.get("status") == expected:
                return instance
            if instance.get("status") not in ("STOPPING", "PROVISIONING", "STAGING"):
                raise OAuthTransitionError("vm_state_indeterminate")
            if attempt + 1 < attempts:
                time.sleep(5)
        raise OAuthTransitionError("vm_operation_timeout")

    def _snapshot_name(self, disk):
        slug = re.sub(r"[^a-z0-9-]", "-", self.window_id.lower()).strip("-")[:32] or "window"
        suffix = hashlib.sha256(disk["name"].encode()).hexdigest()[:8]
        return "a6-" + slug + "-" + suffix

    def _snapshot(self, disk):
        name = self._snapshot_name(disk)
        try:
            existing = self.gcloud.json([
                "compute", "snapshots", "describe", name, "--project=" + VM["project"],
            ])
        except OAuthTransitionError as exc:
            if str(exc) != "resource_not_found":
                raise
            try:
                self.gcloud.call([
                    "compute", "disks", "snapshot", disk["name"],
                    "--project=" + VM["project"], "--zone=" + VM["zone"],
                    "--snapshot-names=" + name, "--guest-flush",
                    "--description=A6 OAuth checkpoint " + self.window_id,
                ], timeout=900)
            except OAuthTransitionError:
                pass
            existing = self.gcloud.json([
                "compute", "snapshots", "describe", name, "--project=" + VM["project"],
            ])
        source = str(existing.get("sourceDisk") or "").rsplit("/", 1)[-1]
        snapshot_id = existing.get("id")
        if (existing.get("status") != "READY" or source != disk["name"]
                or not re.fullmatch(r"[0-9]+", str(snapshot_id or ""))
                or (existing.get("sourceDiskId") is not None
                    and str(existing["sourceDiskId"]) != disk["id"])):
            raise OAuthTransitionError("gce_snapshot_not_ready")
        return {
            "snapshotName": name,
            "snapshotId": str(snapshot_id),
            "sourceDiskName": disk["name"],
            "sourceDiskId": disk["id"],
            "status": "READY",
        }

    def _initial_record(self, hold_proof, hold_hash):
        version = self.gcloud.secret_version()
        if version.get("state") != "ENABLED":
            raise OAuthTransitionError("github_app_secret_unavailable")
        policy = self.gcloud.secret_policy()
        bindings = policy.get("bindings")
        accessor_members = []
        for binding in bindings if isinstance(bindings, list) else []:
            members = binding.get("members") if isinstance(binding, dict) else None
            if not isinstance(members, list):
                raise OAuthTransitionError("secret_iam_policy_invalid")
            if binding.get("role") == "roles/secretmanager.secretAccessor":
                accessor_members.extend(members)
            if ("serviceAccount:" + SERVICE_ACCOUNT) in members and (
                    binding.get("role") != "roles/secretmanager.secretAccessor"
                    or binding.get("condition") is not None):
                raise OAuthTransitionError("secret_iam_scope_invalid")
        if accessor_members != ["serviceAccount:" + SERVICE_ACCOUNT]:
            raise OAuthTransitionError("secret_iam_scope_invalid")
        instance = self.gcloud.instance()
        if instance.get("status") != "RUNNING":
            raise OAuthTransitionError("vm_not_running")
        email, scopes = self._account_and_scopes(instance)
        if (email != SERVICE_ACCOUNT or OAUTH_SCOPE in scopes
                or not re.fullmatch(r"[0-9]+", str(instance.get("id") or ""))):
            raise OAuthTransitionError("gce_identity_or_scope_invalid")
        remote = self._remote_json(["--oauth-checkpoint-status", self.window_id])
        binding = remote.get("binding")
        candidate = {
            key: binding.get(key) if isinstance(binding, dict) else None
            for key in ("imageSha", "jvmDigest", "nodeDigest", "v3Protocol")
        }
        rollback_jvm = remote.get("rollbackJvmDigest")
        rollback_node = remote.get("rollbackNodeDigest")
        if (remote.get("phase") != "T0_VERIFIED"
                or remote.get("windowId") != self.window_id
                or remote.get("pending") is not None
                or remote.get("holdReleased") is True
                or remote.get("rollbackV3Aware") is not True
                or remote.get("backupManifestSha256") != remote.get("restoreManifestSha256")
                or not re.fullmatch(r"[0-9a-f]{64}", str(remote.get("t0ProofSha256") or ""))
                or candidate["v3Protocol"] != "POST_V3_UPGRADE"
                or not re.fullmatch(r"[0-9a-f]{40}", str(candidate["imageSha"] or ""))
                or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(candidate[key] or ""))
                       for key in ("jvmDigest", "nodeDigest"))
                or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(item or ""))
                       for item in (rollback_jvm, rollback_node))):
            raise OAuthTransitionError("remote_t0_not_verified")
        runtime = self._remote_json(["--oauth-runtime-status"])
        self._verify_runtime(runtime, rollback_jvm, rollback_node)
        disks = self._disk_inventory(instance)
        self._remote_command("sync")
        return {
            "schema": 1,
            "windowId": self.window_id,
            "project": VM["project"],
            "zone": VM["zone"],
            "instance": VM["instance"],
            "instanceId": str(instance["id"]),
            "serviceAccount": email,
            "originalScopes": sorted(set(scopes)),
            "disks": disks,
            "snapshots": [],
            "t0ProofSha256": remote["t0ProofSha256"],
            "holdProof": hold_proof,
            "holdProofSha256": hold_hash,
            "checkpointSha256": None,
            "candidate": candidate,
            "rollbackJvmDigest": rollback_jvm,
            "rollbackNodeDigest": rollback_node,
            "runtimeBeforeStop": runtime,
            "phase": "T0_VERIFIED",
            "stoppedAt": None,
            "startedAt": None,
        }

    def prepare_transition(self, hold_proof, hold_hash):
        return self._exclusive(self._prepare_transition, hold_proof, hold_hash)

    def _prepare_transition(self, hold_proof, hold_hash):
        self._validate_hold_proof(hold_proof, hold_hash)
        record = self._load()
        if record is None:
            record = self._initial_record(hold_proof, hold_hash)
            self._save(record)
        elif any(record["holdProof"].get(key) != hold_proof.get(key)
                 for key in ("repository", "variable", "value", "windowId")):
            raise OAuthTransitionError("external_hold_proof_mismatch")
        if record.get("phase") in ("T0_VERIFIED", "SNAPSHOTS_READY"):
            existing = {item["sourceDiskName"]: item for item in record.get("snapshots", [])}
            for disk in record["disks"]:
                snapshot = existing.get(disk["name"]) or self._snapshot(disk)
                if snapshot not in record["snapshots"]:
                    record["snapshots"].append(snapshot)
                    record["snapshots"].sort(key=lambda item: item["sourceDiskName"])
                    self._save(record)
            record["phase"] = "SNAPSHOTS_READY"
            self._save(record)
            self._upload_evidence("snapshot", {
                "windowId": self.window_id,
                "snapshots": record["snapshots"],
            })
            identity = {
                "windowId": self.window_id,
                "project": record["project"],
                "zone": record["zone"],
                "instance": record["instance"],
                "instanceId": record["instanceId"],
                "serviceAccount": record["serviceAccount"],
                "scopes": record["originalScopes"],
                "disks": record["disks"],
                "runtimeStatus": record["runtimeBeforeStop"],
            }
            self._upload_evidence("pause", identity)
            self._verify_remote_pause(record)
            record["checkpointSha256"] = self._remote_checkpoint_hash()
            record["phase"] = "PAUSED_FOR_OAUTH"
            self._save(record)
        return record

    def stop_update_start(self):
        return self._exclusive(self._stop_update_start)

    def _stop_update_start(self):
        record = self._load()
        if not record or record.get("phase") not in (
                "PAUSED_FOR_OAUTH", "STOP_INTENT", "VM_STOPPED",
                "SCOPE_UPDATE_INTENT", "SCOPE_UPDATED", "START_INTENT",
                "VM_STARTED", "OAUTH_SCOPE_READY", "AUTH_OK"):
            raise OAuthTransitionError("external_pause_checkpoint_required")
        desired = sorted(set(record["originalScopes"]) | {OAUTH_SCOPE})
        rollback_jvm = record["rollbackJvmDigest"]
        rollback_node = record["rollbackNodeDigest"]
        instance = self.gcloud.instance()
        email, scopes = self._account_and_scopes(instance)
        current_scopes = set(scopes)
        if current_scopes not in (set(record["originalScopes"]), set(desired)):
            raise OAuthTransitionError("oauth_scope_state_ambiguous")
        self._verify_instance(instance, record, current_scopes)
        status = instance.get("status")
        if status in ("STOPPING", "PROVISIONING", "STAGING"):
            instance = self._wait_status("TERMINATED" if status == "STOPPING" else "RUNNING", 60)
            status = instance.get("status")
            email, scopes = self._account_and_scopes(instance)
            current_scopes = set(scopes)
        if status == "RUNNING" and current_scopes == set(record["originalScopes"]):
            if self._remote_checkpoint_hash() != record.get("checkpointSha256"):
                raise OAuthTransitionError("remote_checkpoint_changed_before_stop")
            self._verify_remote_pause(record)
        if status == "RUNNING" and current_scopes == set(record["originalScopes"]):
            record["phase"] = "STOP_INTENT"
            self._save(record)
            self.gcloud.call([
                "compute", "instances", "stop", record["instance"],
                "--project=" + record["project"], "--zone=" + record["zone"], "--quiet",
            ], timeout=600)
            instance = self.gcloud.instance()
            if instance.get("status") != "TERMINATED":
                raise OAuthTransitionError("vm_stop_unconfirmed")
            record["stoppedAt"] = _utc()
            record["phase"] = "VM_STOPPED"
            self._save(record)
            status = "TERMINATED"
        elif status == "TERMINATED":
            if record.get("phase") == "STOP_INTENT":
                record["stoppedAt"] = record.get("stoppedAt") or _utc()
                record["phase"] = "VM_STOPPED"
                self._save(record)
            else:
                record["stoppedAt"] = record.get("stoppedAt") or _utc()
                record["phase"] = "VM_STOPPED"
                self._save(record)
        elif status == "RUNNING" and current_scopes == set(desired):
            record["startedAt"] = record.get("startedAt") or _utc()
            record["phase"] = "VM_STARTED"
            self._save(record)
        else:
            raise OAuthTransitionError("vm_state_indeterminate")

        instance = self.gcloud.instance()
        email, scopes = self._account_and_scopes(instance)
        if instance.get("status") != "TERMINATED" or email != record["serviceAccount"]:
            if not (instance.get("status") == "RUNNING" and set(scopes) == set(desired)):
                raise OAuthTransitionError("vm_stop_precondition_changed")
        if instance.get("status") == "TERMINATED" and set(scopes) == set(record["originalScopes"]):
            record["phase"] = "SCOPE_UPDATE_INTENT"
            self._save(record)
            self.gcloud.call([
                "compute", "instances", "set-service-account", record["instance"],
                "--project=" + record["project"], "--zone=" + record["zone"],
                "--service-account=" + record["serviceAccount"],
                "--scopes=" + ",".join(desired),
            ], timeout=600)
        elif set(scopes) not in (set(record["originalScopes"]), set(desired)):
            raise OAuthTransitionError("oauth_scope_state_ambiguous")
        instance = self.gcloud.instance()
        self._verify_instance(instance, record, desired)
        if instance.get("status") not in ("TERMINATED", "RUNNING"):
            raise OAuthTransitionError("oauth_scope_change_state_invalid")
        record["phase"] = "SCOPE_UPDATED"
        self._save(record)

        if instance.get("status") == "TERMINATED":
            record["phase"] = "START_INTENT"
            self._save(record)
            self.gcloud.call([
                "compute", "instances", "start", record["instance"],
                "--project=" + record["project"], "--zone=" + record["zone"], "--quiet",
            ], timeout=900)
            instance = self.gcloud.instance()
            if instance.get("status") != "RUNNING":
                raise OAuthTransitionError("vm_start_unconfirmed")
            record["startedAt"] = _utc()
            record["phase"] = "VM_STARTED"
            self._save(record)
        elif instance.get("status") == "RUNNING":
            record["startedAt"] = record.get("startedAt") or _utc()
            record["phase"] = "VM_STARTED"
            self._save(record)
        else:
            raise OAuthTransitionError("vm_state_indeterminate")

        instance = self.gcloud.instance()
        self._verify_instance(instance, record, desired)
        if instance.get("status") != "RUNNING":
            raise OAuthTransitionError("vm_start_unconfirmed")
        remote_hash = self._remote_checkpoint_hash()
        if remote_hash != record.get("checkpointSha256"):
            if record.get("phase") not in ("VM_STARTED", "OAUTH_SCOPE_READY", "AUTH_OK"):
                raise OAuthTransitionError("remote_checkpoint_changed_during_reboot")
            phase, runtime = self._adopt_scope_ready(record, desired)
            record["phase"] = phase
            record["runtimeStatus"] = runtime
            self._save(record)
        else:
            runtime = self._remote_json(["--oauth-runtime-status"])
            self._verify_runtime(runtime, rollback_jvm, rollback_node)
            if self._runtime_identity(runtime) != self._runtime_identity(record["runtimeBeforeStop"]):
                raise OAuthTransitionError("remote_runtime_identity_changed")
            evidence = {
                "windowId": self.window_id,
                "instanceId": record["instanceId"],
                "serviceAccount": record["serviceAccount"],
                "scopes": desired,
                "stopped": True,
                "started": True,
                "stoppedAt": record["stoppedAt"],
                "startedAt": record["startedAt"],
            }
            self._upload_evidence("scope-ready", evidence)
            record["phase"] = "OAUTH_SCOPE_READY"
            record["runtimeStatus"] = runtime
            self._save(record)
        auth = self._remote_json(["--verify-github-app"])
        if auth.get("phase") != "AUTH_OK" or auth.get("hold") != "true":
            raise OAuthTransitionError("github_app_auth_blocked")
        record["phase"] = "AUTH_OK"
        self._save(record)
        return record

    def run_window(self, config, stage):
        return self._exclusive(self._run_window, config, stage)

    def _run_window(self, config, stage):
        if stage not in ("protection", "cutover"):
            raise OAuthTransitionError("oauth_stage_invalid")
        if (not re.fullmatch(r"[0-9a-f]{40}", str(config.get("imageSha") or ""))
                or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(config.get(k) or ""))
                       for k in ("jvmDigest", "nodeDigest"))):
            raise OAuthTransitionError("candidate_binding_invalid")
        if stage == "cutover":
            record = self._load()
            candidate = record.get("candidate") if record else None
            if (not record or record.get("phase") != "AUTH_OK"
                    or any(candidate.get(key) != config.get(key)
                           for key in ("imageSha", "jvmDigest", "nodeDigest"))):
                raise OAuthTransitionError("candidate_binding_mismatch")
        values = {
            **CONFIRM_ENV,
            "A6_OAUTH_STAGE": stage,
            "A6_HOLD_STORE": "external-github-read" if stage == "protection" else "github",
            "A6_WINDOW_ID": self.window_id,
            "A6_V3_PROTOCOL": "POST_V3_UPGRADE",
            "A6_IMAGE_SHA": config["imageSha"],
            "A6_JVM_DIGEST": config["jvmDigest"],
            "A6_NODE_DIGEST": config["nodeDigest"],
            "A6_BACKUP_DESTINATION": "/var/backups/odisseu-a6/" + self.window_id + "/data",
            "A6_NODE_SECCOMP_PATH": "/etc/odisseu-a6/chromium-seccomp.json",
            "A6_NODE_SECCOMP_SHA256": SECCOMP_SHA256,
            "A6_STATE_DIR": self.remote_state,
        }
        if stage == "protection":
            proof, digest = verify_hold(window_id=self.window_id)
            atomic_write_json(self.directory / ("a6-oauth-hold-" + self.window_id + ".json"), {
                "schema": 1, "windowId": self.window_id, "project": VM["project"],
                "zone": VM["zone"], "instance": VM["instance"], "phase": "PROTECTION_INTENT",
                "holdProof": proof, "holdProofSha256": digest,
            })
            values.update({
                "ODISSEU_PRODUCTION_DEPLOY_HOLD": "true",
                "A6_HOLD_EXTERNAL_VERIFIED": "true",
                "A6_HOLD_PROOF_SHA256": digest,
            })
        command = "env " + " ".join(
            key + "=" + shlex.quote(value) for key, value in values.items()
        ) + " bash " + shlex.quote(REMOTE_WINDOW) + " --window"
        result = self.gcloud.ssh(command, tty=True, interactive=True)
        if result.returncode:
            raise OAuthTransitionError("operational_window_blocked")
        return {"stage": stage, "windowId": self.window_id, "result": "completed"}


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--window-id", required=True)
    parser.add_argument("--state-dir", default="~/.odisseu-a6-external")
    parser.add_argument("--remote-state-dir", default=REMOTE_STATE)
    parser.add_argument("--transition", action="store_true")
    parser.add_argument("--run-protection", action="store_true")
    parser.add_argument("--run-cutover", action="store_true")
    parser.add_argument("--image-sha", default=os.environ.get("A6_IMAGE_SHA", ""))
    parser.add_argument("--jvm-digest", default=os.environ.get("A6_JVM_DIGEST", ""))
    parser.add_argument("--node-digest", default=os.environ.get("A6_NODE_DIGEST", ""))
    args = parser.parse_args(argv)
    if sum((args.transition, args.run_protection, args.run_cutover)) != 1:
        parser.error("selecione exatamente uma etapa")
    coordinator = OAuthScopeCoordinator(args.window_id, args.state_dir,
                                        remote_state=args.remote_state_dir)
    try:
        if args.run_protection:
            result = coordinator.run_window({
                "imageSha": args.image_sha,
                "jvmDigest": args.jvm_digest,
                "nodeDigest": args.node_digest,
            }, "protection")
        elif args.transition:
            proof, proof_hash = verify_hold(window_id=args.window_id)
            coordinator.prepare_transition(proof, proof_hash)
            result = coordinator.stop_update_start()
        else:
            record = coordinator._load()
            if not record or record.get("phase") != "AUTH_OK":
                raise OAuthTransitionError("oauth_authentication_required")
            result = coordinator.run_window({
                "imageSha": args.image_sha,
                "jvmDigest": args.jvm_digest,
                "nodeDigest": args.node_digest,
            }, "cutover")
        print(json.dumps(result, sort_keys=True))
        return 0
    except (OAuthTransitionError, DeployError) as exc:
        print(str(exc), file=os.sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
