"""Single-cutover XP v3 release gate. All HTTP goes through JVM's existing
frozen maintenance endpoint; no second Datomic client and no gameplay mutation
except the explicitly scoped CAS/receipt. Fail closed on missing evidence."""

from __future__ import annotations

import json
import re
import shlex
from pathlib import Path

from a6_maintenance_ops import (
    JVM, JVM_HEADER, JVM_PORT, JVM_TOKEN_ENV, JVM_MARKER,
    NODE_MARKER, NODE, command, marker_command, node_marker_phase,
    jvm_marker_phase, jvm_status, node_status, parse_http,
)
from a6_production_deploy import DeployError


RELEASE_MODE = "a6-v3-raid-auto-136"
RELEASE_ENV = "A6_V3_SINGLE_CUTOVER"
POKEMON_ID = "abb8955f-7ebe-4084-a634-fd74cfa9bf62"
HEX64 = re.compile(r"^[0-9a-f]{64}$")
RECEIPT_ID = "a6-v3-charmander-compensation:" + POKEMON_ID + ":v1"


def release_mode(env, isolated_root):
    # This temporary RC runner must never use the old unguarded live window.
    if isolated_root is None and env.get(RELEASE_ENV) != RELEASE_MODE:
        raise DeployError("v3_single_cutover_protocol_required")
    return env.get(RELEASE_ENV) == RELEASE_MODE


def verify_rehearsal(env, sha, jvm_digest, node_digest):
    """Require a local restricted file from an actual isolated-restore rehearsal.
    Human-authored or legacy CI artifacts do not count as proof of VM rehearsal.
    The runner confirms its exact image binding and status, while the operator
    must verify the report's evidence and origin independently."""
    path = env.get("A6_V3_REHEARSAL_REPORT")
    if not path:
        raise DeployError("v3_rehearsal_report_missing")
    p = Path(path)
    if not p.is_absolute() or p.is_symlink() or not p.is_file():
        raise DeployError("v3_rehearsal_report_invalid")
    stat = p.stat()
    if stat.st_mode & 0o077 or stat.st_size > 16384:
        raise DeployError("v3_rehearsal_report_permissions")
    try:
        report = json.loads(p.read_text(encoding="utf-8"))
    except (ValueError, OSError, UnicodeError) as exc:
        raise DeployError("v3_rehearsal_report_invalid") from exc
    expected = {
        "result": "PASS",
        "releaseSha": sha,
        "jvmDigest": jvm_digest,
        "nodeDigest": node_digest,
        "restoreIsolated": True,
        "casReceiptAtomic": True,
        "rollbackV3Aware": True,
        "auditBlocked": 0,
        "levelDrift": 0,
        "onlyOneWriter": True,
    }
    if not isinstance(report, dict) or any(report.get(k) != v for k, v in expected.items()):
        raise DeployError("v3_rehearsal_gate_failed")
    if not isinstance(report.get("manifestSha256"), str) or not HEX64.fullmatch(report["manifestSha256"]):
        raise DeployError("v3_rehearsal_manifest_missing")
    if not isinstance(report.get("pokemonCount"), int) or report["pokemonCount"] <= 0:
        raise DeployError("v3_rehearsal_counts_invalid")
    return {
        "manifestSha256": report["manifestSha256"],
        "pokemonCount": report["pokemonCount"],
    }


def valid_checkpoint(evidence, rehearsal):
    return (
        isinstance(evidence, dict)
        and evidence.get("verified") is True
        and evidence.get("receiptId") == RECEIPT_ID
        and type(evidence.get("pokemonCount")) is int
        and evidence["pokemonCount"] > 0
        and HEX64.fullmatch(str(evidence.get("protectedSha256") or "")) is not None
        and evidence.get("rehearsalManifestSha256") == rehearsal["manifestSha256"]
    )


def _response(wrapped, args, label):
    code, data = parse_http(wrapped(args))
    if code != 200 or data.get("ok") is not True or not isinstance(data.get("data"), dict):
        raise DeployError(label + "_failed")
    return data["data"]


def _get(wrapped, prefix, path, label):
    return _response(wrapped, command(
        prefix, JVM, "GET", path, JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ), label)


def _post(wrapped, prefix, path, payload, label):
    raw = json.dumps(payload, separators=(",", ":"), sort_keys=True)
    if "'" in raw or "\n" in raw or "\r" in raw or len(raw) > 512:
        raise DeployError(label + "_invalid_payload")
    # Token remains in JVM environment; request body contains only ID/hash.
    script = (
        "curl -sS -w '\\n%{http_code}' -X POST "
        "-H \"" + JVM_HEADER + ": $" + JVM_TOKEN_ENV + "\" "
        "-H 'Content-Type: application/json' "
        "--data-binary " + shlex.quote(raw) + " "
        "'http://127.0.0.1:" + str(JVM_PORT) + path + "'"
    )
    return _response(wrapped, list(prefix) + ["exec", JVM, "sh", "-c", script], label)


def _read_marker(wrapped, args):
    completed = wrapped(args)
    if getattr(completed, "returncode", 1) != 0:
        raise DeployError("v3_marker_unreadable")
    return getattr(completed, "stdout", "")


def require_flags(wrapped, prefix):
    # Never emit the content of any variable to stdout/logs, only a fixed marker.
    script = (
        'test "$ADVENTURE_AUTO_HATCH" = true '
        '&& test "$POKEMON_PROGRESSIVE_XP_V3" = true '
        '&& test "$A6_V3_RECONCILIATION_ENABLED" = true '
        '&& test "$A6_V3_RECONCILIATION_ALLOWED_ID" = '
        + shlex.quote(POKEMON_ID)
        + ' && printf v3_release_flags_ok'
    )
    completed = wrapped(list(prefix) + ["exec", JVM, "sh", "-c", script])
    if getattr(completed, "returncode", 1) != 0 or (
        getattr(completed, "stdout", "") or ""
    ).strip() != "v3_release_flags_ok":
        raise DeployError("v3_release_flags_not_installed")


def _frozen(wrapped, prefix):
    current = jvm_status(wrapped, prefix)
    if current["phase"] != "frozen" or current["activeWrites"] != 0:
        raise DeployError("v3_reconcile_requires_frozen_jvm")
    if jvm_marker_phase(_read_marker(wrapped, marker_command(prefix, JVM, JVM_MARKER))) != "frozen":
        raise DeployError("v3_frozen_marker_mismatch")
    n = node_status(wrapped, prefix)
    if n["phase"] != "quiescent" or n["activeOperations"] != 0:
        raise DeployError("v3_node_not_quiescent")


def freeze(wrapped, prefix):
    current = jvm_status(wrapped, prefix)
    if current["phase"] == "frozen":
        _frozen(wrapped, prefix)
        return
    if current["phase"] != "quiescent" or current["activeWrites"] != 0:
        raise DeployError("v3_cannot_freeze_before_quiescence")
    _response(wrapped, command(
        prefix, JVM, "POST", "/ops/maintenance/freeze",
        JVM_HEADER, JVM_TOKEN_ENV, JVM_PORT,
    ), "v3_freeze")
    _frozen(wrapped, prefix)


def audit(wrapped, prefix):
    _frozen(wrapped, prefix)
    data = _get(
        wrapped, prefix,
        "/ops/maintenance/a6-v3-xp-audit?pokemonId=" + POKEMON_ID,
        "v3_xp_audit",
    )
    if (
        data.get("status") not in ("PASS", "BLOCKED")
        or not isinstance(data.get("pokemonCount"), int)
        or data["pokemonCount"] < 1
        or not isinstance(data.get("blocked"), int)
        or data["blocked"] < 0
        or not isinstance(data.get("protectedSha256"), str)
        or not HEX64.fullmatch(data["protectedSha256"])
    ):
        raise DeployError("v3_xp_audit_invalid")
    return data


def preview(wrapped, prefix):
    _frozen(wrapped, prefix)
    data = _get(
        wrapped, prefix,
        "/ops/maintenance/a6-v3-xp-preview?pokemonId=" + POKEMON_ID,
        "v3_xp_preview",
    )
    if (
        data.get("status") != "ready"
        or data.get("pokemonId") != POKEMON_ID
        or data.get("level") != 6
        or data.get("rawXp") != 21
        or data.get("residualBefore") != 21
        or data.get("residualAfter") != 8
        or not HEX64.fullmatch(str(data.get("snapshotSha256") or ""))
    ):
        raise DeployError("v3_charmander_precondition_drift")
    return data


def reconcile(wrapped, prefix, snapshot_sha):
    _frozen(wrapped, prefix)
    data = _post(
        wrapped, prefix, "/ops/maintenance/a6-v3-xp-reconcile",
        {"pokemonId": POKEMON_ID, "snapshotSha256": snapshot_sha},
        "v3_xp_reconcile",
    )
    if (
        data.get("status") not in ("applied", "duplicate")
        or data.get("pokemonId") != POKEMON_ID
        or data.get("level") != 6
        or data.get("residual") != 8
        or data.get("receiptId") != RECEIPT_ID
    ):
        raise DeployError("v3_charmander_receipt_invalid")
    return data


AUDIT_COUNTS = ("playerCount", "saveCount", "eggCount", "incubatorCount",
                "inventoryCount", "coinsTotal", "receiptCount")


def validate_pre_audit(before):
    for key in AUDIT_COUNTS:
        if type(before.get(key)) is not int or before[key] < 0:
            raise DeployError("v3_pre_audit_counts_invalid")
    if (
        before.get("status") != "BLOCKED"
        or before.get("blocked") != 1
        or before.get("targetLevel") != 6
        or before.get("targetResidual") != 21
        or before.get("blockedDetails") !=
           [{"id": POKEMON_ID, "reason": "flat9-ambiguous-legacy-xp"}]
    ):
        raise DeployError("v3_pre_audit_unexpected")


def validate_audit_transition(before, after):
    validate_pre_audit(before)
    for key in AUDIT_COUNTS:
        if type(after.get(key)) is not int or after[key] < 0:
            raise DeployError("v3_post_audit_counts_invalid")
        expected = before[key] + (1 if key == "receiptCount" else 0)
        if after[key] != expected:
            if key == "receiptCount":
                raise DeployError("v3_post_audit_receipt_count_invalid")
            raise DeployError("v3_post_audit_manifest_drift")
    if (
        after.get("status") != "PASS"
        or after.get("blocked") != 0
        or after.get("reconciliationReceiptVerified") is not True
        or after.get("targetLevel") != 6
        or after.get("targetResidual") != 8
        or after.get("pokemonCount") != before.get("pokemonCount")
        or after.get("protectedSha256") != before.get("protectedSha256")
    ):
        raise DeployError("v3_post_audit_failed")


def prepare(wrapped, prefix):
    require_flags(wrapped, prefix)
    freeze(wrapped, prefix)
    return audit(wrapped, prefix)


def commit_once(wrapped, prefix, before):
    validate_pre_audit(before)
    candidate = preview(wrapped, prefix)
    first = reconcile(wrapped, prefix, candidate["snapshotSha256"])
    # Prove retry idempotence without a second grant.
    second = reconcile(wrapped, prefix, candidate["snapshotSha256"])
    if second.get("status") != "duplicate":
        raise DeployError("v3_reconciliation_not_idempotent")
    after = audit(wrapped, prefix)
    validate_audit_transition(before, after)
    return {
        "receiptId": first["receiptId"],
        "pokemonCount": after["pokemonCount"],
        "protectedSha256": after["protectedSha256"],
        "status": after["status"],
        "reconciliationStatus": first["status"],
    }