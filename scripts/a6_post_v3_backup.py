"""Prova administrativa pós-v3. Só audita cópias; nunca monta o volume live.

O plano root-owned é autorização de imagens/código, não evidência de auditoria.
A evidência é produzida pelo helper após verificar o T0 e executar as JVMs.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

from a6_production_backup import (ProductionBackupError, _active_writer, _quiescence_reason,
                                  _files, _verify, read_manifest)
from a6_writer_policy import INVENTORY
from a6_production_contract import JVM_MOUNTS, NODE_MOUNTS, mounts_match
from a6_production_deploy import _mounts, _token

PLAN_ROOT = Path("/etc/odisseu-a6/post-v3-windows")
CODE_ROOT = Path("/usr/local/lib/odisseu-a6")
CODE_FILES = ("a6_post_v3_restore_audit.clj", "xp_reconciliation.clj")
IMAGE_CODE = "/app/modules/pokemon/infrastructure/pokemon/infrastructure/datomic/xp_reconciliation.clj"
COUNTS = ("playerCount", "saveCount", "pokemonCount", "eggCount", "incubatorCount",
          "inventoryCount", "coinsTotal", "receiptCount")
CHECKS = ("requiredSchemaVerified", "pokemonIdentityVerified",
          "historicalReceiptVerified", "historicalProvenanceVerified")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def _private(path, mode=None):
    path = Path(path)
    if path.resolve() != path:
        raise ProductionBackupError("post_v3_path_invalid")
    for item in (path, *path.parents):
        info = item.lstat()
        if info.st_uid != 0 or info.st_mode & 0o022 or stat.S_ISLNK(info.st_mode):
            raise ProductionBackupError("post_v3_permissions_invalid")
    if mode is not None and stat.S_IMODE(path.stat().st_mode) != mode:
        raise ProductionBackupError("post_v3_permissions_invalid")


def _read(path):
    try:
        _private(path, 0o600)
        if not stat.S_ISREG(path.stat().st_mode) or path.stat().st_size > 32768:
            raise ValueError()
        value = json.loads(path.read_text())
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (OSError, ValueError, TypeError) as exc:
        raise ProductionBackupError("post_v3_record_invalid") from exc


def _write(path, value):
    if path.exists():
        if _read(path) != value:
            raise ProductionBackupError("post_v3_record_conflict")
        return
    descriptor, temporary = tempfile.mkstemp(prefix="." + path.name, dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(canonical(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.link(temporary, path)
        parent = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        os.unlink(temporary)


def validate_audit(audit):
    if (not isinstance(audit, dict) or audit.get("status") != "PASS"
            or type(audit.get("blocked")) is not int or audit["blocked"] != 0
            or any(audit.get(key) is not True for key in CHECKS)
            or any(type(audit.get(key)) is not int or audit[key] < 0 for key in COUNTS)
            or audit["pokemonCount"] < 1
            or not re.fullmatch(r"[0-9a-f]{64}", str(audit.get("fullSha256") or ""))):
        raise ProductionBackupError("post_v3_semantic_audit_failed")
    return audit


def plan(window):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}", window):
        raise ProductionBackupError("window_id_invalid")
    value = _read(PLAN_ROOT / (window + ".json"))
    expected = {"protocol", "windowId", "imageSha", "jvmDigest", "nodeDigest",
                "rollbackSha", "rollbackJvmDigest", "rollbackNodeDigest",
                "auditCodeSha256", "system", "database"}
    if (set(value) != expected or value["protocol"] != "POST_V3_UPGRADE"
            or value["windowId"] != window
            or any(not re.fullmatch(r"[0-9a-f]{40}", str(value[k]))
                   for k in ("imageSha", "rollbackSha"))
            or any(not re.fullmatch(r"sha256:[0-9a-f]{64}", str(value[k]))
                   for k in ("jvmDigest", "nodeDigest", "rollbackJvmDigest", "rollbackNodeDigest"))
            or not re.fullmatch(r"[0-9a-f]{64}", str(value["auditCodeSha256"]))
            or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,127}", str(value[k]))
                   for k in ("system", "database"))):
        raise ProductionBackupError("post_v3_plan_invalid")
    hashes = {}
    for name in CODE_FILES:
        path = CODE_ROOT / name
        _private(path)
        hashes[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest(hashes) != value["auditCodeSha256"]:
        raise ProductionBackupError("post_v3_audit_code_mismatch")
    return value


def _run(args, timeout=240):
    try:
        result = subprocess.run(args, capture_output=True, text=True, check=False, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ProductionBackupError("post_v3_process_failed") from exc
    if result.returncode:
        # Não imprimir stdout/stderr de imagens ou conteúdo dos registros.
        raise ProductionBackupError("post_v3_process_failed")
    return result.stdout


def verify_images(value):
    for service, rollback in (("jvm", False), ("node", False), ("jvm", True), ("node", True)):
        key = ("rollback" + service.capitalize() + "Digest") if rollback else service + "Digest"
        image = value[key]
        raw = _run(["docker", "image", "inspect", image])
        try:
            docs = json.loads(raw)
            doc = docs[0]
            revision = doc["Config"]["Labels"]["org.opencontainers.image.revision"]
            if (len(docs) != 1 or doc["Id"] != image or doc["Architecture"] != "amd64"
                    or revision != value["rollbackSha" if rollback else "imageSha"]):
                raise ValueError()
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ProductionBackupError("post_v3_image_mismatch") from exc
    actual = _run(["docker", "run", "--rm", "--pull=never", "--network=none",
                   "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                   "--entrypoint=sha256sum", value["jvmDigest"], IMAGE_CODE]).split()
    if not actual or actual[0] != hashlib.sha256((CODE_ROOT / "xp_reconciliation.clj").read_bytes()).hexdigest():
        raise ProductionBackupError("post_v3_image_code_mismatch")


def preflight(window):
    value = plan(window)
    verify_images(value)
    return {"operation": "post-v3-preflight", "plan": value, "planSha256": digest(value)}


def _intent_path(admin, window):
    return admin.BACKUP_ROOT / (window + ".post-v3-intent.json")


def backup(admin, window):
    value = plan(window)
    admin.preflight(admin._destination(window) / "data")
    verify_images(value)
    collection, vm = admin._collect()
    reason = _quiescence_reason(collection, vm) or _active_writer(collection.documents, admin.DATOMIC_VOLUME)
    if reason:
        raise ProductionBackupError(reason)
    source = Path(admin.DATOMIC_SOURCE)
    admin._no_open_handles(source)
    jvm = next(d for d in collection.documents if d["Name"] == "/odisseu-a6-jvm")
    node = next(d for d in collection.documents if d["Name"] == "/odisseu-a6-node")
    if (jvm["Image"] != value["rollbackJvmDigest"]
            or node["Image"] != value["rollbackNodeDigest"]
            or collection.active_jvm_writes != 0 or collection.active_node_ops != 0
            or collection.jvm_phase not in ("quiescent", "frozen")
            or collection.node_phase != "quiescent"):
        raise ProductionBackupError("post_v3_t0_source_mismatch")
    intent = {"windowId": window, "planSha256": digest(value),
              "sourceJvmDigest": jvm["Image"], "sourceNodeDigest": node["Image"]}
    path = _intent_path(admin, window)
    if not path.exists() and admin._destination(window).exists():
        raise ProductionBackupError("post_v3_stale_backup")
    _write(path, intent)
    attestation = admin.ensure(window)
    rows = read_manifest(admin._destination(window) / "backup-manifest.txt")
    protected = [r for r in rows if not r["path"].startswith(".a6-maintenance/")]
    source_paths = {str(p.relative_to(source)) for p in _files(source)
                    if not str(p.relative_to(source)).startswith(".a6-maintenance/")}
    if source_paths != {r["path"] for r in protected} or any(
            hashlib.sha256((source / row["path"]).read_bytes()).hexdigest() != row["sha256"]
            for row in protected):
        raise ProductionBackupError("post_v3_t0_source_drift")
    return {**attestation, "planSha256": digest(value)}


def _physical(admin, window, value):
    expected = {"windowId": window, "planSha256": digest(value),
                "sourceJvmDigest": value["rollbackJvmDigest"],
                "sourceNodeDigest": value["rollbackNodeDigest"]}
    if _read(_intent_path(admin, window)) != expected:
        raise ProductionBackupError("post_v3_t0_intent_mismatch")
    return admin.status(window)


def _audit_image(value, image, restored, work_parent):
    with tempfile.TemporaryDirectory(prefix="post-v3-audit-", dir=work_parent) as directory:
        work = Path(directory) / "storage"
        shutil.copytree(restored, work)
        manifest = read_manifest(work_parent / "backup-manifest.txt")
        _verify(restored, work, manifest)
        expression = (
            '(load-file "/audit/xp_reconciliation.clj") '
            '(load-file "/audit/a6_post_v3_restore_audit.clj") '
            '(apply a6.post-v3-restore-audit/-main *command-line-args*) '
            '(System/exit 0)'
        )
        command = ["docker", "run", "--rm", "--pull=never", "--network=none",
                   "--read-only", "--cap-drop=ALL", "--security-opt=no-new-privileges",
                   "--user=0:0", "--memory=1g", "--pids-limit=256",
                   "--tmpfs=/tmp:rw,nosuid,nodev,size=256m,mode=1777",
                   "--workdir=/tmp",
                   "--env=POKEMON_PROGRESSIVE_XP_V3=true",
                   "--mount=type=bind,src=" + str(work) + ",dst=/audit/storage",
                   "--mount=type=bind,src=" + str(CODE_ROOT) + ",dst=/audit,readonly",
                   "--entrypoint=sh", image, "-c",
                   'cp /app/deps.edn ./deps.edn && exec clojure "$@"', "post-v3-audit",
                   "-Srepro", "-Sdeps",
                   '{:paths ["/app/apps/odisseu/src" "/app/shared/persistence/src" '
                   '"/app/modules/pokemon/domain" "/app/modules/pokemon/infrastructure"]}',
                   "-M", "-e", expression, "--", "/audit/storage", value["system"],
                   value["database"], "abb8955f-7ebe-4084-a634-fd74cfa9bf62"]
        raw = _run(command)
        try:
            result = json.loads(raw.splitlines()[-1])
        except (ValueError, IndexError) as exc:
            raise ProductionBackupError("post_v3_audit_output_invalid") from exc
        return validate_audit(result)


def _proof_payload(value, attestation, audit):
    return {"protocol": "POST_V3_UPGRADE", "windowId": value["windowId"],
            "planSha256": digest(value), "manifestSha256": attestation["manifestSha256"],
            "restoreManifestSha256": attestation["manifestSha256"],
            "imageSha": value["imageSha"], "jvmDigest": value["jvmDigest"],
            "nodeDigest": value["nodeDigest"], "auditCodeSha256": value["auditCodeSha256"],
            "rollbackSha": value["rollbackSha"], "rollbackJvmDigest": value["rollbackJvmDigest"],
            "rollbackNodeDigest": value["rollbackNodeDigest"], "rollbackV3Aware": True,
            "audit": audit}


def status(admin, window):
    value = plan(window)
    attestation = _physical(admin, window, value)
    payload = _read(admin._destination(window) / "post-v3-proof.json")
    proof_hash = payload.get("proofSha256")
    body = {key: item for key, item in payload.items() if key != "proofSha256"}
    audit = validate_audit(body.get("audit"))
    if body != _proof_payload(value, attestation, audit) or proof_hash != digest(body):
        raise ProductionBackupError("post_v3_proof_mismatch")
    return payload


def audit(admin, window):
    value = plan(window)
    attestation = _physical(admin, window, value)
    target = admin._destination(window) / "post-v3-proof.json"
    if target.exists():
        return status(admin, window)
    verify_images(value)
    restored = target.parent / "restore-isolated"
    candidate = _audit_image(value, value["jvmDigest"], restored, target.parent)
    rollback = _audit_image(value, value["rollbackJvmDigest"], restored, target.parent)
    if rollback != candidate:
        raise ProductionBackupError("post_v3_rollback_incompatible")
    # As JVMs receberam apenas cópias. Revalidar os originais após os processos.
    attestation = _physical(admin, window, value)
    payload = _proof_payload(value, attestation, candidate)
    _write(target, {**payload, "proofSha256": digest(payload)})
    return status(admin, window)


def _containers(admin, value):
    try:
        docs = json.loads(admin._read(["docker", "inspect", "odisseu-a6-jvm", "odisseu-a6-node"]))
        by_name = {doc["Name"]: doc for doc in docs}
        identities = {}
        for service in ("jvm", "node"):
            doc = by_name["/odisseu-a6-" + service]
            if (doc["Image"] != value[service + "Digest"] or doc["State"]["Running"] is not True
                    or doc["HostConfig"].get("Privileged")
                    or "SYS_ADMIN" in (doc["HostConfig"].get("CapAdd") or [])
                    or not mounts_match(_mounts(doc), JVM_MOUNTS if service == "jvm" else NODE_MOUNTS)):
                raise ValueError()
            identities[service] = doc["Id"]
        if len(docs) != 2:
            raise ValueError()
        return identities
    except (ValueError, KeyError, TypeError) as exc:
        raise ProductionBackupError("post_v3_live_images_mismatch") from exc


def _http(admin, service, path):
    port, header, token = ((18090, "X-A6-Maintenance-Token", "ODISSEU_BRIDGE_MAINTENANCE_TOKEN")
                          if service == "jvm" else
                          (3001, "x-a6-node-maintenance-token", "ODISSEU_NODE_MAINTENANCE_TOKEN"))
    raw = admin._read(["docker", "exec", "odisseu-a6-" + service, "sh", "-c",
        'curl -fsS -H "' + header + ': $' + token + '" '
        "'http://127.0.0.1:" + str(port) + path + "'"])
    try:
        value = json.loads(raw)
        if service == "jvm":
            if value.get("ok") is not True:
                raise ValueError()
            value = value["data"]
        if not isinstance(value, dict):
            raise ValueError()
        return value
    except (ValueError, KeyError, TypeError) as exc:
        raise ProductionBackupError("post_v3_live_response_invalid") from exc


def _exclusive_writer(admin):
    holders = admin._read(["docker", "ps", "-a", "--filter",
                          "volume=" + admin.DATOMIC_VOLUME, "--format", "{{.Names}}\t{{.State}}"])
    rows = [line.split("\t") for line in holders.splitlines() if line.strip()]
    if any(len(row) != 2 or not all(row) for row in rows):
        raise ProductionBackupError("post_v3_live_writer_unreadable")
    active = [row[0] for row in rows if row[1] not in ("exited", "created", "dead")]
    if active != ["odisseu-a6-jvm"]:
        raise ProductionBackupError("post_v3_live_writer_not_exclusive")
    processes = _run(["ps", "-eo", "args"])
    if any(row["transact"] and row["path"] in processes for row in INVENTORY):
        raise ProductionBackupError("external_datomic_client")
    return holders


def live_gate(admin, window):
    value = plan(window)
    proof = status(admin, window)
    identities = _containers(admin, value)
    holders = _exclusive_writer(admin)
    jvm = _http(admin, "jvm", "/ops/maintenance/status")
    node = _http(admin, "node", "/ops/maintenance/status")
    writes = jvm.get("activeWrites", jvm.get("active-writes"))
    if (jvm.get("phase") != "frozen" or type(writes) is not int or writes != 0
            or node.get("phase") != "quiescent" or node.get("activeOperations") != 0
            or node.get("durable") is not True):
        raise ProductionBackupError("post_v3_live_not_frozen")
    marker = admin._read(["docker", "exec", "odisseu-a6-jvm", "cat",
                         "/data/datomic-local/.a6-maintenance/bridge-maintenance.edn"])
    node_marker = admin._read(["docker", "exec", "odisseu-a6-node", "cat",
                              "/app/data/a6-maintenance.json"])
    try:
        node_phase = json.loads(node_marker).get("phase")
    except (ValueError, AttributeError) as exc:
        raise ProductionBackupError("post_v3_live_marker_invalid") from exc
    if _token(marker, ":phase").lstrip(":") != "frozen" or node_phase != "quiescent":
        raise ProductionBackupError("post_v3_live_marker_mismatch")
    ready = admin._read(["docker", "exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
    try:
        ready = json.loads(ready)
    except ValueError as exc:
        raise ProductionBackupError("post_v3_live_ready_invalid") from exc
    if not isinstance(ready, dict) or ready.get("whatsapp") != "READY" or ready.get("chromium") is not True:
        raise ProductionBackupError("post_v3_live_not_ready")
    observed = _http(admin, "jvm", "/ops/maintenance/a6-v3-xp-post-audit?pokemonId="
                     "abb8955f-7ebe-4084-a634-fd74cfa9bf62")
    if validate_audit(observed) != proof["audit"]:
        raise ProductionBackupError("post_v3_live_fingerprint_mismatch")
    if _containers(admin, value) != identities:
        raise ProductionBackupError("post_v3_live_images_changed")
    if admin._read(["docker", "ps", "-a", "--filter",
                   "volume=" + admin.DATOMIC_VOLUME, "--format", "{{.Names}}\t{{.State}}"]) != holders:
        raise ProductionBackupError("post_v3_live_writer_changed")
    body = {"windowId": window, "proofSha256": proof["proofSha256"],
            "planSha256": digest(value), "containers": identities, "verified": True}
    _write(admin._destination(window) / "post-v3-live-gate.json",
           {**body, "gateSha256": digest(body)})
    return gate_status(admin, window)


def gate_status(admin, window):
    value = plan(window)
    proof = status(admin, window)
    _exclusive_writer(admin)
    gate = _read(admin._destination(window) / "post-v3-live-gate.json")
    body = {"windowId": window, "proofSha256": proof["proofSha256"],
            "planSha256": digest(value), "containers": _containers(admin, value), "verified": True}
    if gate != {**body, "gateSha256": digest(body)}:
        raise ProductionBackupError("post_v3_live_gate_mismatch")
    return gate
