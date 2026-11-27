"""POST_V3_UPGRADE no runner oficial. Não contém transações de gameplay."""
from __future__ import annotations

import copy
import re

import a6_supervised_deploy as ops
import a6_v3_release_gate as v3
from a6_admin_backup_client import post_v3
from a6_post_v3_backup import digest, validate_audit
from a6_container_residues import inventory, collisions
from a6_production_backup import BACKUP_AUTHORIZATION, ProductionBackupError
from a6_production_deploy import DeployError, FORBIDDEN, _prefix, observe_vm, preflight


def _helper(destination, action):
    try:
        return post_v3(destination, action)
    except (ProductionBackupError, OSError) as exc:
        raise DeployError(str(exc) or "post_v3_helper_failed") from exc


def _plan(destination, meta):
    response = _helper(destination, "preflight")
    value = response.get("plan")
    if (not isinstance(value, dict) or response.get("planSha256") != digest(value)
            or value.get("protocol") != v3.POST_V3_UPGRADE
            or any(value.get(key) != meta.get(key)
                   for key in ("windowId", "imageSha", "jvmDigest", "nodeDigest"))):
        raise DeployError("post_v3_plan_binding_mismatch")
    return value


def _proof(destination, value, prior=None):
    proof = _helper(destination, "audit")
    body = {k: v for k, v in proof.items() if k != "proofSha256"}
    required = ("windowId", "imageSha", "jvmDigest", "nodeDigest", "auditCodeSha256",
                "rollbackSha", "rollbackJvmDigest", "rollbackNodeDigest")
    try:
        validate_audit(proof.get("audit"))
    except ProductionBackupError as exc:
        raise DeployError(str(exc)) from exc
    if (proof.get("proofSha256") != digest(body) or proof.get("planSha256") != digest(value)
            or proof.get("protocol") != v3.POST_V3_UPGRADE
            or any(proof.get(key) != value[key] for key in required)
            or not re.fullmatch(r"[0-9a-f]{64}", str(proof.get("manifestSha256") or ""))
            or proof.get("restoreManifestSha256") != proof["manifestSha256"]
            or proof.get("rollbackV3Aware") is not True):
        raise DeployError("post_v3_proof_binding_mismatch")
    old = (prior or {}).get("proof")
    if old is not None and proof != old:
        raise DeployError("post_v3_checkpoint_proof_mismatch")
    return proof


def _checkpoint(state, **fields):
    state.update(fields)
    ops._j().update(postV3=copy.deepcopy(state))


def _flags(wrapped, prefix):
    result = wrapped(prefix + ["exec", "odisseu-a6-jvm", "sh", "-c",
        'test "$POKEMON_PROGRESSIVE_XP_V3" = true '
        '&& test "$ODISSEU_BRIDGE_SEED" = false '
        '&& test "$ODISSEU_BRIDGE_RESET" = false '
        '&& test "$ODISSEU_BRIDGE_ALLOW_TEST_RNG" = false '
        '&& test "$A6_V3_RECONCILIATION_ENABLED" = true '
        '&& test "$A6_V3_RECONCILIATION_ALLOWED_ID" = ' + v3.POKEMON_ID + ' '
        '&& printf post_v3_flags_ok'])
    if not ops._ok(result) or ops._out(result).strip() != "post_v3_flags_ok":
        raise DeployError("post_v3_runtime_flags_invalid")


def _match_source(observation, value):
    for service in ("jvm", "node"):
        if (observation[service]["imageId"] != value["rollback" + service.capitalize() + "Digest"]
                or observation[service].get("health") != "healthy"):
            raise DeployError("post_v3_source_image_mismatch")


def _closed(wrapped, prefix, observation):
    live = ops._live_counters(wrapped, prefix)
    phases = {"node_phase": "quiescent", "jvm_phase": live["jvm"]["phase"]}
    if phases["jvm_phase"] not in ("quiescent", "frozen"):
        raise DeployError("post_v3_jvm_not_drained")
    ops._apply_confirmed(observation, phases, live)
    ops._single_writer(ops._volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
    if ops._external(observation.get("processes")):
        raise DeployError("external_datomic_client")
    return live


def _verify_live(wrapped, prefix, proof):
    _flags(wrapped, prefix)
    v3.freeze(wrapped, prefix)
    ops._single_writer(ops._volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
    after = v3._get(wrapped, prefix,
        "/ops/maintenance/a6-v3-xp-post-audit?pokemonId=" + v3.POKEMON_ID,
        "post_v3_live_audit")
    try:
        validate_audit(after)
    except ProductionBackupError as exc:
        raise DeployError(str(exc)) from exc
    if after != proof["audit"]:
        raise DeployError("post_v3_live_fingerprint_mismatch")
    ops._single_writer(ops._volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])


def _pending(state, prior, observation, wrapped, prefix, value):
    pending = (prior or {}).get("pending")
    if not pending:
        return
    op = pending.get("op")
    if op == "oauth_pause_node":
        if observation.get("node", {}).get("running") is False:
            ops._j().resolve("oauth_pause_observed")
        elif observation.get("node", {}).get("running") is True:
            ops._j().resolve("oauth_pause_not_applied")
        else:
            raise DeployError("oauth_pause_indeterminate")
        return
    if op in ops.REPEATABLE or op in ("post_v3_stop", "post_v3_backup", "post_v3_audit"):
        ops._j().resolve("post_v3_reobserve")
        return
    if op in ("update_jvm", "update_node") and pending.get("verb") in ("run", "inspect"):
        service = op.removeprefix("update_")
        actual = observation[service]["imageId"]
        if actual != value[service + "Digest"]:
            raise DeployError("post_v3_cutover_indeterminate")
        if not ops._previous_stopped(wrapped, prefix, service):
            raise DeployError("post_v3_previous_not_stopped")
        ops._j().resolve("post_v3_replacement_observed")
        return
    # Rename/update interrompido, rollback interrompido ou diário ambíguo:
    # nenhum start/recreate automático. A janela e o HOLD continuam protegidos.
    raise DeployError("post_v3_explicit_recovery_required")


def verify_rollback_target(wrapped, operation, meta):
    proof = meta.get("postV3Proof")
    if not proof or proof.get("rollbackV3Aware") is not True:
        raise DeployError("post_v3_rollback_not_proven")
    service = "node" if operation.endswith("node") else "jvm"
    name = "odisseu-a6-" + service
    if operation != "restart_old_jvm":
        name += "-previous"
    value = meta["postV3Plan"]
    # O prefixo efetivo também é observado, sem assumir acesso direto ao Docker.
    direct = ops._ok(wrapped(["docker", "info"]))
    raw = wrapped(_prefix(direct) + ["inspect", "--format", "{{.Image}}", name])
    if (not ops._ok(raw) or ops._out(raw).strip()
            != value["rollback" + service.capitalize() + "Digest"]):
        raise DeployError("post_v3_rollback_image_mismatch")


def run(runner, sha, jvm_digest, node_digest, env, destination, isolated_root,
        event, hold_gate, prior, meta, materialize, checkpoints, seen):
    state = copy.deepcopy((prior or {}).get("postV3") or {})

    def wrapped(args):
        text = " ".join(map(str, args))
        if any(token in text for token in FORBIDDEN):
            raise DeployError("destructive_command")
        seen.append(list(args))
        return runner(args)

    def blocked(reason, step=None):
        return ops._finish(checkpoints, step or ops._next([c["step"] for c in checkpoints]),
                           [str(reason)], seen, admitted=True, maintenance=True,
                           residueResolutionPlan=state.get("residueResolutionPlan", []))

    def check_names(observation, prefix, value, service=None):
        conflicts = collisions(inventory(wrapped, prefix), value, state, observation, service)
        if conflicts:
            _checkpoint(state, residueResolutionPlan=conflicts)
            raise DeployError("previous_container_requires_resolution")

    try:
        oauth_stage = env.get("A6_OAUTH_STAGE", "")
        protection_stage = oauth_stage == "protection"
        oauth_cutover = oauth_stage == "cutover"
        required = {"A6_SUPERVISED_WINDOW": ops.WINDOW, "A6_EXECUTE": "1",
                    "ODISSEU_PRODUCTION_DEPLOY_HOLD": "true" if protection_stage else "false",
                    "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
                    "ODISSEU_A6_DELIVERY_VALIDATED": "true",
                    "A6_RESUME_EXPLICIT": ops.RESUME_PHRASE}
        if oauth_stage not in ("", "protection", "cutover"):
            raise DeployError("oauth_stage_invalid")
        v3.release_mode(env, isolated_root)
        if (any(env.get(k) != v for k, v in required.items()) or not callable(runner)
                or event == "push" or not meta.get("windowId")
                or not re.fullmatch(r"[0-9a-f]{40}", sha)
                or ops._JOURNAL.get() is None
                or (isolated_root is None and env.get("A6_LIVE_BACKUP") != BACKUP_AUTHORIZATION)):
            raise DeployError("post_v3_window_not_authorized")
        if protection_stage:
            evidence = ops._j().data.get("holdEvidence") or {}
            if (hold_gate is not None or evidence.get("verified") is not True
                    or evidence.get("value") != "true"
                    or evidence.get("windowId") != meta.get("windowId")
                    or not re.fullmatch(r"[0-9a-f]{64}", str(evidence.get("proofSha256") or ""))):
                raise DeployError("oauth_protection_authorization_invalid")
        elif oauth_cutover:
            oauth = ops._j().data.get("oauthTransition") or {}
            if (hold_gate is None or ops._j().data.get("phase") != "CUTOVER"
                    or oauth.get("authVerified") is not True
                    or oauth.get("windowId") != meta.get("windowId")):
                raise DeployError("oauth_authentication_required")
        if (prior or {}).get("deployComplete"):
            raise DeployError("window_already_complete")
        if prior and (prior.get("binding") or {}).get("v3Protocol") != v3.POST_V3_UPGRADE:
            raise DeployError("post_v3_checkpoint_protocol_mismatch")
        value = _plan(destination, meta)
        if state.get("plan") is not None and state["plan"] != value:
            raise DeployError("post_v3_checkpoint_plan_mismatch")
        meta["postV3Plan"] = value
        _checkpoint(state, plan=value)
        ops._mark(checkpoints, {"step": "authorization", "v3ReleaseRequired": True,
                               "protocol": v3.POST_V3_UPGRADE})
        pending = (prior or {}).get("pending") or {}
        allowed = ops.REPEATABLE | {
            "post_v3_stop", "post_v3_backup", "post_v3_audit", "oauth_pause_node",
        }
        if pending and pending.get("op") not in allowed and not (
                pending.get("op") in ("update_jvm", "update_node")
                and pending.get("verb") in ("run", "inspect")):
            raise DeployError("post_v3_explicit_recovery_required")
        try:
            observation = observe_vm(wrapped)
        except DeployError:
            if not state.get("drained") or not state.get("live"):
                raise DeployError("post_v3_observation_indeterminate")
            observation = ops._observe_stopped(wrapped, _prefix(state["drained"]["dockerDirect"]), state["drained"])
            if ops._jvm_running(observation) is not False:
                raise DeployError("post_v3_observation_indeterminate")
        direct = bool(observation.get("dockerDirect"))
        prefix = _prefix(direct)
        meta["vmHostname"] = observation["vm"]["hostname"]
        _pending(state, prior, observation, wrapped, prefix, value)
        check_names(observation, prefix, value)
        errors = preflight(observation)
        if errors:
            raise DeployError(errors[0])
        if not state.get("proof"):
            if ops._jvm_running(observation):
                _match_source(observation, value)
                errors = preflight(observation)
                if errors:
                    raise DeployError(errors[0])
                _flags(wrapped, prefix)
            elif not state.get("drained"):
                raise DeployError("post_v3_missing_drain_checkpoint")
        ops._mark(checkpoints, {"step": "preflight", "vmHostname": meta["vmHostname"]})
        images = ops._images(wrapped, sha, jvm_digest, node_digest, direct)
        unresolved = ops._unresolved(observation, sha, jvm_digest, node_digest, materialize)
        if unresolved:
            raise DeployError(unresolved)
        ops._mark(checkpoints, {"step": "images", "jvmRunRef": images["jvm"]["runRef"],
                               "nodeRunRef": images["node"]["runRef"]})
        if protection_stage:
            ops._j().advance_phase("PREFLIGHT_OK")

        resumed = state.get("gateVerified") and (
            observation["jvm"].get("phase") == "open" or observation["node"].get("phase") == "open")
        if resumed:
            proof = _proof(destination, value, state)
            gate = _helper(destination, "gate-status")
            if gate != state.get("liveGate") or gate.get("proofSha256") != proof["proofSha256"]:
                raise DeployError("post_v3_resume_gate_mismatch")
            if not all(ops._image_matches(observation, s, value[s + "Digest"]) for s in ("jvm", "node")):
                raise DeployError("post_v3_resumed_images_mismatch")
            ops._single_writer(ops._volume_holders(wrapped, prefix), ["odisseu-a6-jvm"])
            if hold_gate is not None and hold_gate.read() != "true":
                raise DeployError("hold_not_protected")
            for name in ops.SUPERVISED_STEPS[3:16]:
                ops._mark(checkpoints, {"step": name, "observed": "post_v3_resume",
                    **({"verified": True, "postV3Evidence": proof} if name == "v3_release_gate" else {})})
            if observation["jvm"]["phase"] != "open":
                raise DeployError("post_v3_resume_phase_ambiguous")
            if observation["node"]["phase"] != "open":
                ops._op("resume_node", lambda: ops.resume_node(wrapped, prefix))
            ops._mark(checkpoints, {"step": "resume", "resumeExplicit": True})
            return ops._confirm_and_validate(wrapped, prefix, checkpoints, seen)

        if hold_gate is not None:
            if hold_gate.read() != "true":
                raise DeployError("hold_not_protected")
            ops._op("hold_release", hold_gate.release)
        if state.get("proof"):
            meta["postV3Proof"] = _proof(destination, value, state)
            if observation["jvm"]["imageId"] == value["jvmDigest"]:
                raw = wrapped(prefix + ["inspect", "--format", "{{.State.Health.Status}}", "odisseu-a6-jvm"])
                if ops._await_healthy(wrapped, prefix, "odisseu-a6-jvm", ops._out(raw)) != "healthy":
                    ops._rollback(wrapped, "jvm", False, direct)
                    raise DeployError("health_unhealthy")
        if ops._jvm_running(observation):
            node = ops._op("node_maintenance", lambda: ops.enter_node(wrapped, prefix))
            ops._mark(checkpoints, {"step": "node_maintenance", **node})
            if observation["jvm"].get("phase") == "frozen":
                jvm = v3.jvm_status(wrapped, prefix)
                v3._frozen(wrapped, prefix)
            else:
                jvm = ops._op("jvm_drain", lambda: ops.enter_jvm(wrapped, prefix))
            ops._mark(checkpoints, {"step": "jvm_drain", **jvm})
            observation = observe_vm(wrapped)
            live = _closed(wrapped, prefix, observation)
            if not state.get("proof"):
                _checkpoint(state, drained=observation, live=live)
        else:
            observation = ops._observe_stopped(wrapped, prefix, state["drained"])
            live = state["live"]
            for name in ("node_maintenance", "jvm_drain"):
                ops._mark(checkpoints, {"step": name, "observed": "post_v3_stopped"})
        ops._mark(checkpoints, {"step": "quiescence", "observed": True})
        if protection_stage:
            ops._j().advance_phase("QUIESCENT")
        if not state.get("proof"):
            if ops._jvm_running(observation):
                check_names(observation, prefix, value)
                ops._j().intent("post_v3_stop")
                stopped = wrapped(prefix + ["stop", "--time", "30", "odisseu-a6-jvm"])
                if not ops._ok(stopped):
                    raise DeployError("post_v3_stop_failed")
                observation = ops._observe_stopped(wrapped, prefix, observation)
                if ops._jvm_running(observation) is not False:
                    raise DeployError("writers_still_running")
                ops._j().done("post_v3_stop", running=False)
            ops._single_writer(ops._volume_holders(wrapped, prefix), [])
            ops._mark(checkpoints, {"step": "stop_jvm", "holdersActive": []})
            ops._mark(checkpoints, {"step": "exclusive", "holdersActive": []})
            ops._op("post_v3_backup", lambda: _helper(destination, "backup"))
            ops._mark(checkpoints, {"step": "backup", "manifestVerified": True})
            ops._mark(checkpoints, {"step": "manifest", "manifestVerified": True})
            proof = ops._op("post_v3_audit", lambda: _proof(destination, value, state))
            _checkpoint(state, proof=proof)
        else:
            proof = _proof(destination, value, state)
            if observation["jvm"]["imageId"] == value["rollbackJvmDigest"]:
                if ops._jvm_running(observation):
                    check_names(observation, prefix, value)
                    ops._j().intent("post_v3_stop")
                    stopped = wrapped(prefix + ["stop", "--time", "30", "odisseu-a6-jvm"])
                    if not ops._ok(stopped):
                        raise DeployError("post_v3_stop_failed")
                    observation = ops._observe_stopped(wrapped, prefix, observation)
                    if ops._jvm_running(observation) is not False:
                        raise DeployError("writers_still_running")
                    ops._j().done("post_v3_stop", running=False)
                ops._single_writer(ops._volume_holders(wrapped, prefix), [])
                ops._op("post_v3_backup", lambda: _helper(destination, "backup"))
            for name in ("stop_jvm", "exclusive", "backup", "manifest"):
                ops._mark(checkpoints, {"step": name, "observed": "authenticated_t0"})
        meta["postV3Proof"] = proof
        ops._mark(checkpoints, {"step": "restore_isolated", "postV3Evidence": proof,
                               "restoreIsolated": True})
        if protection_stage:
            ops._j().advance_phase("T0_VERIFIED", t0ProofSha256=proof["proofSha256"])
            if observation["node"].get("phase") != "quiescent":
                raise DeployError("oauth_node_not_quiescent")
            node_live = (state.get("live") or {}).get("node") or {}
            if node_live.get("activeOperations") != 0:
                raise DeployError("oauth_node_operations_active")
            if observation["node"].get("running") is True:
                ops._j().intent("oauth_pause_node", windowId=meta["windowId"])
                stopped = wrapped(prefix + ["stop", "--time", "30", "odisseu-a6-node"])
                if not ops._ok(stopped):
                    raise DeployError("oauth_node_stop_failed")
                state_after = wrapped(prefix + ["inspect", "--format", "{{.State.Running}}",
                                                "odisseu-a6-node"])
                if not ops._ok(state_after) or ops._out(state_after).strip() != "false":
                    raise DeployError("oauth_node_stop_unconfirmed")
                ops._j().done("oauth_pause_node", running=False)
            return ops._finish(checkpoints, "oauth_snapshot", [], seen, admitted=True,
                               maintenance=True, oauthPaused=True, t0ProofSha256=proof["proofSha256"])

        backup = {"manifestVerified": True, "sourcePreserved": True,
                  "restoreIsolated": True, "jvmStopped": True}
        for service in ("jvm", "node"):
            ops._single_writer(ops._volume_holders(wrapped, prefix),
                               [] if service == "jvm" and not ops._jvm_running(observation)
                               else ["odisseu-a6-jvm"])
            digest_id = value[service + "Digest"]
            candidate = observation[service]["imageId"] == digest_id
            if candidate:
                if not ops._previous_stopped(wrapped, prefix, service):
                    raise DeployError("post_v3_previous_not_stopped")
                previous = wrapped(prefix + ["inspect", "--format", "{{.Image}}",
                                             "odisseu-a6-" + service + "-previous"])
                if (not ops._ok(previous) or ops._out(previous).strip()
                        != value["rollback" + service.capitalize() + "Digest"]):
                    raise DeployError("post_v3_previous_image_mismatch")
                status = wrapped(prefix + ["inspect", "--format", "{{.State.Health.Status}}",
                                          "odisseu-a6-" + service])
                health = ops._await_healthy(wrapped, prefix, "odisseu-a6-" + service, ops._out(status))
                if health != "healthy":
                    ops._rollback(wrapped, service, False, direct)
                    raise DeployError("health_unhealthy")
                ops._mark(checkpoints, {"step": "update_" + service, "observed": True})
            else:
                if observation[service]["imageId"] != value["rollback" + service.capitalize() + "Digest"]:
                    raise DeployError("post_v3_cutover_source_mismatch")
                check_names(observation, prefix, value, service)
                result = ops._update_service(wrapped, observation, sha, service, digest_id, images[service],
                    env, backup, service == "node", direct, seen, checkpoints, materialize)
                if result is not None:
                    return result
            if service == "jvm":
                result = ops._validate_jvm(wrapped, prefix, direct, checkpoints, seen)
                if result is not None:
                    return result
                _verify_live(wrapped, prefix, proof)
                # Node plan exige o estado de manutenção original da observação.
            else:
                ready = wrapped(prefix + ["exec", "odisseu-a6-node", "node", "/app/scripts/healthcheck.js"])
                if not ops._ready(ready):
                    ops._rollback(wrapped, "node", False, direct)
                    raise DeployError("whatsapp_not_ready")
                ops._mark(checkpoints, {"step": "validate_whatsapp", "whatsapp": "READY"})
        _verify_live(wrapped, prefix, proof)
        gate = _helper(destination, "live-gate")
        body = {k: v for k, v in gate.items() if k != "gateSha256"}
        if (gate.get("verified") is not True or gate.get("windowId") != value["windowId"]
                or gate.get("proofSha256") != proof["proofSha256"]
                or gate.get("planSha256") != digest(value) or gate.get("gateSha256") != digest(body)):
            raise DeployError("post_v3_live_gate_invalid")
        _checkpoint(state, gateVerified=True, liveGate=gate)
        if oauth_cutover:
            ops._j().advance_phase("AUDIT_OK", liveGateSha256=gate["gateSha256"])
            ops._j().advance_phase("RECONCILE_OK",
                                   reconciliation="not_applicable_post_v3_upgrade")
            ops._j().advance_phase("INTEGRITY_OK", t0ProofSha256=proof["proofSha256"])
        ops._mark(checkpoints, {"step": "v3_release_gate", "verified": True,
                               "postV3Evidence": proof})
        if hold_gate is not None:
            ops._op("hold_restore", hold_gate.restore)
            if hold_gate.read() != "true":
                raise DeployError("hold_not_restored")
            meta["holdRestored"] = True
        _checkpoint(state, resumeAttempted=True)
        ops._op("resume_jvm", lambda: ops.resume_jvm(wrapped, prefix))
        ops._op("resume_node", lambda: ops.resume_node(wrapped, prefix))
        ops._mark(checkpoints, {"step": "resume", "resumeExplicit": True})
        report = ops._confirm_and_validate(wrapped, prefix, checkpoints, seen)
        if oauth_cutover and report.get("deployComplete"):
            ops._j().advance_phase("DEPLOY_OK", deployComplete=True)
        return report
    except (DeployError, ProductionBackupError) as exc:
        if str(exc) in ops.JOURNAL_ERRORS:
            raise
        return blocked(exc)
