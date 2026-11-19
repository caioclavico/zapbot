#!/usr/bin/env python3
"""Janela operacional em ambiente descartável. Não fala com a VM nem com o GitHub."""

import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from a6_operational_window import CONFIRM, GithubHold, MemoryHold, execute_window
from a6_production_deploy import DeployError


ROOT = Path(__file__).resolve().parents[1]


def _supervised():
    spec = importlib.util.spec_from_file_location(
        "a6_supervised_window_tests",
        ROOT / "scripts" / "test-a6-supervised-deploy.py",
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SUPERVISED = _supervised()


def _confirm(_prompt):
    return CONFIRM


def _run(world, root, hold=None, confirmer=_confirm, env=None, prior=None, window_id="janela-isolada-1", event=None, isolated=None):
    hold = hold or MemoryHold("true")
    export = isolated or (root / "export")
    export.mkdir(parents=True, exist_ok=True)
    target = export / "save.edn"
    if not target.exists():
        target.write_text(SUPERVISED.SAVE, encoding="utf-8")
    seen = []

    def runner(args):
        seen.append(list(args))
        if "/ops/maintenance/resume" in " ".join(str(part) for part in args):
            seen.append(["hold", hold.read()])
        return world.runner(args)

    report = execute_window(
        runner,
        hold,
        confirmer,
        SUPERVISED.SHA,
        SUPERVISED.JVM_DIGEST,
        SUPERVISED.NODE_DIGEST,
        dict(SUPERVISED.ENV if env is None else env),
        root / "backup",
        export,
        window_id=window_id,
        prior=prior,
        event=event,
    )
    report["seen"] = seen
    return report, hold


class OperationalWindowTests(unittest.TestCase):
    def test_refuses_without_authorization_confirmation_or_protected_hold(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            missing = _run(SUPERVISED.World(), root / "auth", env={"A6_EXECUTE": "0"}, confirmer=_confirm)
            self.assertIn("window_not_authorized", missing[0]["blocked"])
            self.assertEqual(missing[0]["seen"], [])
            self.assertEqual(missing[1].writes, [])
            quiet = _run(SUPERVISED.World(), root / "quiet", confirmer=None)
            self.assertIn("interactive_confirmation_required", quiet[0]["blocked"])
            self.assertEqual(quiet[1].writes, [])
            rejected = _run(SUPERVISED.World(), root / "rejected", confirmer=lambda _prompt: "nao")
            self.assertIn("interactive_confirmation_rejected", rejected[0]["blocked"])
            self.assertEqual(rejected[1].read(), "true")
            active = _run(SUPERVISED.World(), root / "hold", hold=MemoryHold("false"))
            self.assertIn("hold_not_protected", active[0]["blocked"])
            self.assertEqual(active[0]["seen"], [])
            unknown = _run(SUPERVISED.World(), root / "unknown", hold=MemoryHold("talvez"))
            self.assertIn("hold_indeterminate", unknown[0]["blocked"])

    def test_isolated_cycle_restores_hold_before_resume_and_keeps_the_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            world = SUPERVISED.World()
            report, hold = _run(world, root)
            self.assertTrue(report["deployComplete"], report.get("blocked"))
            self.assertEqual(hold.read(), "true")
            self.assertEqual(hold.writes, ["false", "true"])
            self.assertIn("hold_restored_before_resume", report["decisions"])
            self.assertFalse(report["datomicRestored"])
            self.assertFalse(report["executeAllowed"])
            self.assertEqual((root / "export" / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
            self.assertEqual((root / "restore-isolated" / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
            resume_holds = [item[1] for item in report["seen"] if item[:1] == ["hold"]]
            self.assertGreaterEqual(len(resume_holds), 2)
            self.assertTrue(all(value == "true" for value in resume_holds))
            text = "\n".join(" ".join(command) for command in report["commands"])
            self.assertNotIn("TOKEN=", text)
            self.assertIn("/ops/maintenance/resume", text)
            checkpoint = json.loads((root / "a6-window-checkpoint.json").read_text(encoding="utf-8"))
            self.assertEqual(checkpoint["windowId"], "janela-isolada-1")
            self.assertEqual(checkpoint["vmHostname"], "odisseu-core")
            self.assertTrue(checkpoint["holdRestored"])
            self.assertFalse(checkpoint["datomicRestored"])

    def test_failures_restore_the_hold_and_do_not_repeat_a_dangerous_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cases = []
            wrong = SUPERVISED.World()
            wrong.hostname = "outra-vm"
            cases.append((wrong, root / "vm", "vm_mismatch", "stop"))
            digest = SUPERVISED.World()
            digest.jvm_id = "sha256:" + ("ef" * 32)
            cases.append((digest, root / "digest", "image_digest_mismatch", "stop"))
            drain = SUPERVISED.World()
            drain.writes = 1
            cases.append((drain, root / "drain", "jvm_drain_timeout", "stop"))
            health = SUPERVISED.World()
            health.health = "unhealthy"
            cases.append((health, root / "health", "health_unhealthy", None))
            starting = SUPERVISED.World()
            starting.whatsapp = "STARTING"
            cases.append((starting, root / "whatsapp", "whatsapp_not_ready", None))
            for world, folder, reason, absent in cases:
                report, hold = _run(world, folder)
                self.assertIn(reason, report["blocked"], report)
                self.assertEqual(hold.read(), "true")
                self.assertFalse(report["datomicRestored"])
                self.assertEqual((folder / "export" / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
                if absent:
                    self.assertFalse(any(absent in command and command[-1] == "odisseu-a6-jvm" for command in report["commands"]))
            images = SUPERVISED.World()

            def missing_image(args):
                if "image" in args and "inspect" in args:
                    return SUPERVISED.Result("", 1)
                return images.runner(args)

            hold = MemoryHold("true")
            export = root / "images" / "export"
            export.mkdir(parents=True)
            (export / "save.edn").write_text(SUPERVISED.SAVE, encoding="utf-8")
            unloaded = execute_window(
                missing_image, hold, _confirm, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST, SUPERVISED.NODE_DIGEST,
                dict(SUPERVISED.ENV), root / "images" / "backup", export, window_id="janela-isolada-1",
            )
            self.assertIn("image_not_observed", unloaded["blocked"])
            self.assertEqual(hold.read(), "true")
            self.assertFalse(any("/ops/maintenance/enter" in " ".join(command) for command in unloaded["commands"]))
            battle = SUPERVISED.World()
            battle.health = "unhealthy"
            battle.battle = "{:volatil true}\n"
            forward, forward_hold = _run(battle, root / "battle")
            self.assertEqual(forward["recovery"], "forward_only")
            self.assertIn("rollback_incompatible_battle_edn", forward["blocked"])
            self.assertFalse(forward["datomicRestored"])
            self.assertEqual(forward_hold.read(), "true")
            self.assertFalse(any(len(command) >= 2 and command[-2] == "start" for command in forward["commands"]))
            env = dict(SUPERVISED.ENV)
            env.pop("A6_RESUME_EXPLICIT")
            missing_phrase, phrase_hold = _run(SUPERVISED.World(), root / "phrase", env=env)
            self.assertIn("window_not_authorized", missing_phrase["blocked"])
            self.assertEqual(phrase_hold.writes, [])
            world = SUPERVISED.World()
            resume_hold = MemoryHold("true")
            observed = []

            def fail_resume(args):
                if "/ops/maintenance/resume" in " ".join(args):
                    observed.append(resume_hold.read())
                    return SUPERVISED.Result('{"ok":false,"error":{"code":"maintenance_resume_blocked"}}\n409')
                return world.runner(args)

            export = root / "resume" / "export"
            export.mkdir(parents=True)
            (export / "save.edn").write_text(SUPERVISED.SAVE, encoding="utf-8")
            resume = execute_window(
                fail_resume, resume_hold, _confirm, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST,
                SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV), root / "resume" / "backup", export,
                window_id="janela-isolada-1",
            )
            self.assertIn("resume_failed", resume["blocked"])
            self.assertEqual(observed, ["true"])
            self.assertEqual(resume_hold.read(), "true")
            self.assertIn("hold_restored", resume["decisions"])
            self.assertFalse(resume["deployComplete"])
            self.assertFalse(resume["datomicRestored"])

    def test_same_window_resumes_after_a_drain_timeout(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            world = SUPERVISED.World()
            world.writes = 1
            first, hold = _run(world, root, window_id="janela-retomada")
            self.assertIn("jvm_drain_timeout", first["blocked"])
            self.assertEqual(hold.read(), "true")
            self.assertFalse(any("stop" in command and command[-1] == "odisseu-a6-jvm" for command in first["commands"]))
            world.writes = 0
            second, again = _run(world, root, window_id="janela-retomada")
            self.assertTrue(second["deployComplete"], second.get("blocked"))
            self.assertEqual(again.read(), "true")
            self.assertEqual((root / "export" / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
            self.assertFalse(second["datomicRestored"])

    def test_checkpoint_blocks_another_window_and_a_divergent_vm(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            export = root / "export"
            export.mkdir()
            (export / "save.edn").write_text(SUPERVISED.SAVE, encoding="utf-8")
            open_window = {
                "windowId": "janela-antiga",
                "vmHostname": "odisseu-core",
                "deployComplete": False,
                "steps": ["authorization", "preflight"],
                "holdReleased": True,
                "holdRestored": False,
            }
            hold = MemoryHold("true")
            busy = execute_window(
                SUPERVISED.World().runner, hold, _confirm, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST,
                SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV), root / "backup", export,
                window_id="janela-nova", prior=open_window,
            )
            self.assertIn("window_already_open", busy["blocked"])
            self.assertEqual(hold.read(), "true")
            self.assertEqual(hold.writes, ["true"])
            incompatible = execute_window(
                SUPERVISED.World().runner, MemoryHold("true"), _confirm, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST,
                SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV), root / "backup", export,
                window_id="janela-isolada-1",
                prior={"windowId": "janela-isolada-1", "vmHostname": "outra-vm", "deployComplete": False, "steps": ["preflight"]},
            )
            self.assertIn("checkpoint_incompatible", incompatible["blocked"])
            self.assertEqual(incompatible["commands"], [])
            done = execute_window(
                SUPERVISED.World().runner, MemoryHold("true"), _confirm, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST,
                SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV), root / "backup", export,
                window_id="janela-isolada-1",
                prior={"windowId": "janela-isolada-1", "vmHostname": "odisseu-core", "deployComplete": True, "steps": ["validate_game"]},
            )
            self.assertIn("window_already_complete", done["blocked"])
            self.assertEqual(done["commands"], [])

    def test_push_and_shell_cannot_start_the_window(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pushed, hold = _run(SUPERVISED.World(), root, event="push")
            self.assertIn("push_not_authorized", pushed["blocked"])
            self.assertEqual(hold.writes, [])
        script = ROOT / "scripts" / "a6-operational-window.sh"
        env = os.environ.copy()
        for key in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME", "A6_HOLD_STORE", "A6_EXECUTE"):
            env.pop(key, None)
        refused = subprocess.run(["bash", str(script)], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(refused.returncode, 2)
        self.assertIn("window_not_authorized", refused.stderr)
        env.update({
            "GITHUB_ACTIONS": "true",
            "A6_EXECUTE": "1",
            "A6_SUPERVISED_WINDOW": "janela-supervisionada-a6",
            "A6_SUPERVISED_CONFIRM": "executar-janela-a6",
            "PRODUCTION_AUTHORIZATION": "autorizar-producao-a6",
            "ODISSEU_A6_DELIVERY_VALIDATED": "true",
            "A6_LIVE_BACKUP": "autorizar-backup-producao-a6",
            "A6_RESUME_EXPLICIT": "confirmar-resume-a6",
            "A6_WINDOW_ID": "janela-ci",
            "A6_HOLD_STORE": "github",
            "A6_IMAGE_SHA": "abc",
            "A6_JVM_DIGEST": "sha256:" + ("ab" * 32),
            "A6_NODE_DIGEST": "sha256:" + ("cd" * 32),
            "A6_BACKUP_DESTINATION": "/tmp/a6-operational-none",
        })
        ci = subprocess.run(["bash", str(script), "--window"], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(ci.returncode, 2)
        self.assertIn("push_not_authorized", ci.stderr)
        env.pop("GITHUB_ACTIONS")
        interactive = subprocess.run(["bash", str(script), "--window"], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(interactive.returncode, 2)
        self.assertIn("interactive_confirmation_required", interactive.stderr)
        workflow = (ROOT / ".github" / "workflows" / "a6-pr-validation.yml").read_text(encoding="utf-8")
        self.assertNotIn(CONFIRM, workflow)
        self.assertNotIn("A6_HOLD_STORE", workflow)
        self.assertNotIn("- master", workflow.split("pull_request:")[0])
        dry = (ROOT / "scripts" / "a6-supervised-deploy.sh").read_text(encoding="utf-8")
        self.assertIn("apply_requires_live_recheck", dry)
        self.assertNotIn("a6_operational_window", dry)

    def test_github_hold_commands_do_not_carry_a_credential(self):
        state = {"value": "true"}

        def runner(args):
            if args[2] == "get":
                return SUPERVISED.Result(state["value"] + "\n")
            if args[2] == "set":
                state["value"] = args[-1]
                return SUPERVISED.Result("")
            return SUPERVISED.Result("", 1)

        hold = GithubHold(runner)
        self.assertEqual(hold.read(), "true")
        hold.release()
        self.assertEqual(hold.read(), "false")
        hold.restore()
        self.assertEqual(hold.read(), "true")
        recorded = []

        def recording(args):
            recorded.append(list(args))
            return runner(args)

        gate = GithubHold(recording)
        gate.release()
        gate.restore()
        joined = "\n".join(" ".join(command) for command in recorded)
        self.assertNotIn("TOKEN=", joined)
        self.assertIn("--body false", joined)
        self.assertIn("--body true", joined)


if __name__ == "__main__":
    unittest.main(verbosity=2)
