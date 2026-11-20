#!/usr/bin/env python3
"""Interrupções da janela. Processo filho morto com SIGKILL, gh falso e VM falsa.

Nada aqui fala com o GitHub ou com a VM. O gh falso grava o hold num
arquivo; o World guarda o estado em JSON para a retomada ver o que o
processo morto deixou.
"""

import importlib.util
import json
import os
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))

import a6_window_journal  # noqa: E402
from a6_operational_window import CONFIRM, GithubHold, MemoryHold, execute_window, recover_hold  # noqa: E402
from a6_production_deploy import DeployError  # noqa: E402
from a6_window_journal import JOURNAL, WindowJournal, WindowLock, atomic_write_json, load  # noqa: E402

_spec = importlib.util.spec_from_file_location("a6_supervised_test_world", SCRIPTS / "test-a6-supervised-deploy.py")
SUPERVISED = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(SUPERVISED)

FAKE_GH = """#!/usr/bin/env python3
import json, os, signal, sys
state = os.environ["A6_FAKE_GH_STATE"]
args = sys.argv[1:]
with open(os.environ["A6_FAKE_GH_LOG"], "a", encoding="utf-8") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["variable", "get"]:
    if not os.path.exists(state):
        sys.stderr.write("variable not found\\n")
        sys.exit(1)
    sys.stdout.write(open(state, encoding="utf-8").read().strip() + "\\n")
    sys.exit(0)
if args[:2] == ["variable", "set"]:
    value = args[args.index("--body") + 1]
    with open(state, "w", encoding="utf-8") as handle:
        handle.write(value)
        handle.flush()
        os.fsync(handle.fileno())
    if value == "false" and os.environ.get("A6_FAKE_GH_KILL_PARENT") == "1":
        os.kill(os.getppid(), signal.SIGKILL)
    sys.exit(0)
sys.exit(2)
"""

WINDOW_ID = "janela-interrompida-1"


class Lab:
    """Diretórios de uma janela de teste: estado, backup, export, hold e logs."""

    def __init__(self, root, fresh=True):
        self.root = Path(root)
        self.state = self.root / "state"
        self.destination = self.root / "work" / "backup"
        self.export = self.root / "export"
        self.world_file = self.root / "world.json"
        self.commands_file = self.root / "commands.jsonl"
        self.gh_state = self.root / "gh-hold"
        self.gh_log = self.root / "gh.jsonl"
        self.bin = self.root / "bin"
        if not fresh:
            return
        for directory in (self.state, self.destination.parent, self.export, self.bin):
            directory.mkdir(parents=True, exist_ok=True)
        (self.export / "save.edn").write_text(SUPERVISED.SAVE, encoding="utf-8")
        self.gh_state.write_text("true", encoding="utf-8")
        self.gh_log.write_text("", encoding="utf-8")
        gh = self.bin / "gh"
        gh.write_text(FAKE_GH, encoding="utf-8")
        gh.chmod(0o755)
        self.world_file.write_text(json.dumps(SUPERVISED.World().state()), encoding="utf-8")

    def gh_env(self, kill=False):
        env = {
            "PATH": str(self.bin) + os.pathsep + os.environ.get("PATH", ""),
            "A6_FAKE_GH_STATE": str(self.gh_state),
            "A6_FAKE_GH_LOG": str(self.gh_log),
        }
        if kill:
            env["A6_FAKE_GH_KILL_PARENT"] = "1"
        return env

    def gh_runner(self, kill=False):
        env = self.gh_env(kill)

        def run(args):
            argv = [str(self.bin / "gh")] + list(args[1:]) if args[:1] == ["gh"] else list(args)
            return subprocess.run([sys.executable] + argv, check=False, capture_output=True, text=True, env=env)

        return run

    def world(self):
        return SUPERVISED.World().load(json.loads(self.world_file.read_text(encoding="utf-8")))

    def commands(self):
        lines = self.commands_file.read_text(encoding="utf-8").splitlines() if self.commands_file.exists() else []
        return [json.loads(line) for line in lines if line.strip()]

    def gh_sets(self):
        rows = [json.loads(line) for line in self.gh_log.read_text(encoding="utf-8").splitlines() if line.strip()]
        return [row[row.index("--body") + 1] for row in rows if row[:2] == ["variable", "set"]]

    def hold(self):
        return self.gh_state.read_text(encoding="utf-8").strip()

    def logged(self, world):
        def run(args):
            with self.commands_file.open("a", encoding="utf-8") as log:
                log.write(json.dumps(list(args)) + "\n")
            result = world.runner(args)
            self.world_file.write_text(json.dumps(world.state()), encoding="utf-8")
            return result
        return run

    def resume(self, world=None):
        world = world or self.world()
        report = execute_window(
            self.logged(world), GithubHold(self.gh_runner()), lambda _prompt: CONFIRM,
            SUPERVISED.SHA, SUPERVISED.JVM_DIGEST, SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV),
            self.destination, self.export, window_id=WINDOW_ID, state=self.state,
            materialize=SUPERVISED.materialize,
        )
        return report, world

    def crash(self, point):
        config = self.root / "crash.json"
        config.write_text(json.dumps({"root": str(self.root), "point": point}), encoding="utf-8")
        env = {key: value for key, value in os.environ.items() if key not in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME")}
        env["TMPDIR"] = str(self.root)
        env.update(self.gh_env(kill=point.get("kind") == "gh_after_false"))
        return subprocess.run(
            [sys.executable, str(Path(__file__).resolve()), "--child", str(config)],
            check=False, capture_output=True, text=True, env=env, timeout=120,
        )


def _matches(args, point):
    text = " ".join(str(part) for part in args)
    return all(piece in text for piece in point.get("match", []))


def child(config_path):
    """Corre a janela e morre com SIGKILL no ponto pedido."""
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    lab = Lab(config["root"], fresh=False)
    point = config["point"]
    world = lab.world()
    seen = {"count": 0}

    def kill():
        os.kill(os.getpid(), signal.SIGKILL)

    def runner(args):
        hit = point.get("kind") in ("before", "after") and _matches(args, point)
        if hit:
            seen["count"] += 1
            hit = seen["count"] == point.get("nth", 1)
        if hit and point["kind"] == "before":
            kill()
        with lab.commands_file.open("a", encoding="utf-8") as log:
            log.write(json.dumps(list(args)) + "\n")
        result = world.runner(args)
        lab.world_file.write_text(json.dumps(world.state()), encoding="utf-8")
        if hit and point["kind"] == "after":
            kill()
        return result

    gh = lab.gh_runner(kill=point.get("kind") == "gh_after_false")
    if point.get("kind") == "gh_before_false":
        real = gh

        def gh(args):
            if args[:3] == ["gh", "variable", "set"] and args[-1] == "false":
                kill()
            return real(args)

    if point.get("kind") in ("journal_replace", "journal_partial"):
        real_replace = os.replace
        real_fsync = os.fsync

        def pending_op(path):
            try:
                return (json.loads(Path(path).read_text(encoding="utf-8")).get("pending") or {}).get("op")
            except (OSError, ValueError):
                return None

        if point["kind"] == "journal_replace":
            def replace(src, dst, *args, **kwargs):
                if str(dst).endswith(JOURNAL) and pending_op(src) == point["op"]:
                    kill()
                return real_replace(src, dst, *args, **kwargs)
            a6_window_journal.os.replace = replace
        else:
            def fsync(fd):
                path = os.readlink("/proc/self/fd/%d" % fd) if os.path.exists("/proc/self/fd") else None
                if path is None:
                    import fcntl
                    path = fcntl.fcntl(fd, fcntl.F_GETPATH, bytes(1024)).rstrip(b"\0").decode()
                if JOURNAL in os.path.basename(path) and path.endswith(".tmp") and pending_op(path) == point["op"]:
                    size = os.path.getsize(path)
                    os.truncate(path, size // 2)
                    kill()
                return real_fsync(fd)
            a6_window_journal.os.fsync = fsync

    if point.get("kind") == "backup":
        import a6_production_backup
        real_copytree = a6_production_backup.shutil.copytree

        def copytree(src, dst, *args, **kwargs):
            Path(dst).mkdir(parents=True)
            first = next(path for path in Path(src).rglob("*") if path.is_file())
            (Path(dst) / first.name).write_bytes(first.read_bytes()[:3])
            kill()
            return real_copytree(src, dst, *args, **kwargs)

        a6_production_backup.shutil.copytree = copytree

    execute_window(
        runner, GithubHold(gh), lambda _prompt: CONFIRM,
        SUPERVISED.SHA, SUPERVISED.JVM_DIGEST, SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV),
        lab.destination, lab.export, window_id=WINDOW_ID, state=lab.state,
        materialize=SUPERVISED.materialize,
    )
    return 0


def _verb(command):
    if "docker" not in command:
        return None
    return command[command.index("docker") + 1]


def _count(commands, verb, name=None):
    total = 0
    for command in commands:
        if _verb(command) != verb:
            continue
        if name is None or name in command:
            total += 1
    return total


class InterruptionTests(unittest.TestCase):
    def setUp(self):
        self._ci = {key: os.environ.pop(key) for key in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME") if key in os.environ}

    def tearDown(self):
        os.environ.update(self._ci)

    def _crash(self, lab, point):
        crashed = lab.crash(point)
        self.assertEqual(crashed.returncode, -signal.SIGKILL, crashed.stderr[-2000:])
        return crashed

    def _recover(self, lab):
        audit = recover_hold(GithubHold(lab.gh_runner()), lab.state, env={})
        self.assertEqual(audit["holdAfter"], "true")
        self.assertFalse(audit["containersTouched"])
        self.assertFalse(audit["rollback"])
        self.assertTrue(Path(audit["audit"]).is_file())
        self.assertEqual(lab.hold(), "true")
        return audit

    def _safe(self, lab, world):
        self.assertEqual((lab.export / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
        commands = lab.commands()
        self.assertFalse(any(command[-1].endswith("-previous") and _verb(command) == "start" for command in commands))
        self.assertLessEqual(_count(commands, "run", "odisseu-a6-jvm"), 1)
        self.assertLessEqual(_count(commands, "run", "odisseu-a6-node"), 1)
        running = [name for name, item in world.containers.items() if name.startswith("odisseu-a6-jvm") and item["running"]]
        self.assertLessEqual(len(running), 1, world.containers)
        self.assertEqual(lab.hold(), "true")

    def _complete(self, lab, report, world):
        self.assertTrue(report["deployComplete"], report.get("blocked"))
        self.assertFalse(report["datomicRestored"])
        self.assertEqual(world.containers["odisseu-a6-jvm"]["image"], SUPERVISED.JVM_DIGEST)
        self.assertEqual(world.containers["odisseu-a6-node"]["image"], SUPERVISED.NODE_DIGEST)
        self.assertEqual((lab.root / "work" / "restore-isolated" / "save.edn").read_text(encoding="utf-8"), SUPERVISED.SAVE)
        self._safe(lab, world)

    def _blocked(self, lab, report, world, before):
        self.assertFalse(report["deployComplete"])
        self.assertIn("checkpoint_indeterminate", report["blocked"], report)
        after = lab.commands()[len(before):]
        self.assertFalse(any(_verb(command) in {"stop", "rename", "run", "start", "update"} for command in after), after)
        self._safe(lab, world)

    def test_sigkill_right_after_hold_false_is_recovered_without_the_checkpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            self._crash(lab, {"kind": "gh_after_false"})
            self.assertEqual(lab.hold(), "false")
            journal = load(lab.state)
            self.assertEqual(journal["pending"]["op"], "hold_release")
            self.assertEqual(_count(lab.commands(), "stop"), 0)
            with WindowLock(lab.state):
                pass
            (lab.state / JOURNAL).unlink()
            audit = self._recover(lab)
            self.assertEqual(audit["checkpoint"], "absent")
            self.assertTrue(audit["restored"])
            self.assertEqual(lab.gh_sets(), ["false", "true"])

    def test_sigkill_after_hold_false_then_same_window_completes(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            self._crash(lab, {"kind": "gh_after_false"})
            audit = self._recover(lab)
            self.assertEqual(audit["windowId"], WINDOW_ID)
            self.assertTrue(audit["interruptedWindow"])
            self.assertTrue(load(lab.state)["holdRestored"])
            report, world = lab.resume()
            self._complete(lab, report, world)

    def test_recovery_with_corrupt_checkpoint_still_restores_the_hold(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            self._crash(lab, {"kind": "gh_after_false"})
            (lab.state / JOURNAL).write_text("{\"windowId\": \"janela", encoding="utf-8")
            audit = self._recover(lab)
            self.assertEqual(audit["checkpoint"], "indeterminate")
            report, _world = lab.resume()
            self.assertIn("checkpoint_indeterminate", report["blocked"])
            self.assertEqual(lab.hold(), "true")

    def test_interruptions_resume_or_block_without_repeating(self):
        completes = {
            "before_hold_release": {"kind": "gh_before_false"},
            "before_stop": {"kind": "before", "match": ["stop", "--time", "odisseu-a6-jvm"]},
            "after_docker_run": {"kind": "after", "match": ["docker", " run ", "--name odisseu-a6-jvm"]},
            "before_checkpoint_persisted": {"kind": "journal_replace", "op": "stop_jvm"},
            "before_resume": {"kind": "before", "match": ["18090", "/ops/maintenance/resume", "POST"]},
            "after_resume_before_confirm": {"kind": "after", "match": ["3001", "/ops/maintenance/resume", "POST"]},
        }
        blocks = {
            "after_stop": {"kind": "after", "match": ["stop", "--time", "odisseu-a6-jvm"]},
            "after_rename": {"kind": "after", "match": ["rename", "odisseu-a6-jvm", "odisseu-a6-jvm-previous"]},
            "during_checkpoint_write": {"kind": "journal_partial", "op": "backup"},
            "during_backup": {"kind": "backup"},
        }
        for label, point in completes.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as tmp:
                lab = Lab(tmp)
                self._crash(lab, point)
                load(lab.state)
                self._recover(lab)
                report, world = lab.resume()
                self._complete(lab, report, world)
                if label == "after_docker_run":
                    self.assertEqual(_count(lab.commands(), "run", "odisseu-a6-jvm"), 1)
                    self.assertTrue(any(event.get("resolution") == "replacement_observed" for event in load(lab.state)["events"]))
                if label == "after_resume_before_confirm":
                    rerun = [row for row in lab.commands() if "/ops/maintenance/enter" in " ".join(row)]
                    self.assertEqual(len(rerun), 2, "a retomada não reabre manutenção depois do resume")
        for label, point in blocks.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as tmp:
                lab = Lab(tmp)
                self._crash(lab, point)
                load(lab.state)
                self._recover(lab)
                before = lab.commands()
                report, world = lab.resume()
                self._blocked(lab, report, world, before)
                again, world = lab.resume(world)
                self._blocked(lab, again, world, before)
                if label == "during_checkpoint_write":
                    strays = [path for path in lab.state.iterdir() if path.name.endswith(".tmp")]
                    self.assertTrue(strays)
                if label == "during_backup":
                    self.assertFalse((lab.root / "work" / "backup-manifest.txt").exists())
                    self.assertTrue(lab.destination.exists())


class JournalTests(unittest.TestCase):
    def setUp(self):
        self._ci = {key: os.environ.pop(key) for key in ("GITHUB_ACTIONS", "GITHUB_EVENT_NAME") if key in os.environ}

    def tearDown(self):
        os.environ.update(self._ci)

    def test_atomic_write_keeps_the_previous_version_when_the_disk_refuses(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / JOURNAL
            atomic_write_json(path, {"windowId": "a", "steps": ["authorization"]})
            real = a6_window_journal.os.fsync

            def broken(_fd):
                raise OSError(28, "No space left on device")

            a6_window_journal.os.fsync = broken
            try:
                with self.assertRaises(DeployError) as refused:
                    atomic_write_json(path, {"windowId": "a", "steps": ["authorization", "preflight"]})
            finally:
                a6_window_journal.os.fsync = real
            self.assertEqual(str(refused.exception), "checkpoint_write_failed")
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["steps"], ["authorization"])
            self.assertEqual([item.name for item in Path(tmp).iterdir()], [JOURNAL])
            with self.assertRaises(DeployError):
                atomic_write_json(Path(tmp) / "odisseu-a6-cutover-x.json", {})

    def test_persistence_failure_blocks_before_the_next_vm_change(self):
        for op, stopped in (("stop_jvm", False), ("backup", True)):
            with self.subTest(op), tempfile.TemporaryDirectory() as tmp:
                lab = Lab(tmp)
                world = lab.world()
                real = a6_window_journal.atomic_write_json

                def failing(path, payload):
                    if (payload.get("pending") or {}).get("op") == op:
                        raise DeployError("checkpoint_write_failed")
                    return real(path, payload)

                a6_window_journal.atomic_write_json = failing
                try:
                    report, world = lab.resume(world)
                finally:
                    a6_window_journal.atomic_write_json = real
                self.assertFalse(report["deployComplete"])
                self.assertIn("checkpoint_write_failed", report["blocked"])
                self.assertEqual(lab.hold(), "true")
                commands = lab.commands()
                self.assertEqual(_count(commands, "stop", "odisseu-a6-jvm"), 1 if stopped else 0)
                self.assertEqual(_count(commands, "start"), 0)
                self.assertEqual(_count(commands, "run"), 0)
                self.assertFalse(lab.destination.exists())
                self.assertEqual(world.containers["odisseu-a6-jvm"]["running"], not stopped)

    def test_second_window_and_incompatible_checkpoint_are_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            with WindowLock(lab.state):
                report, _world = lab.resume()
                with self.assertRaises(DeployError) as busy:
                    recover_hold(GithubHold(lab.gh_runner()), lab.state, env={})
            self.assertIn("window_in_progress", report["blocked"])
            self.assertEqual(str(busy.exception), "window_in_progress")
            self.assertEqual(lab.commands(), [])
            self.assertEqual(lab.gh_sets(), [])
            other = WindowJournal(lab.state, WINDOW_ID, {
                "vmHostname": "odisseu-core",
                "imageSha": SUPERVISED.SHA,
                "jvmDigest": "sha256:" + ("ef" * 32),
                "nodeDigest": SUPERVISED.NODE_DIGEST,
                "destination": str(lab.destination.resolve()),
            })
            other.note("window_opened")
            incompatible, _world = lab.resume()
            self.assertIn("checkpoint_incompatible", incompatible["blocked"])
            self.assertEqual(lab.commands(), [])
            (lab.state / JOURNAL).unlink()
            WindowJournal(lab.state, "outra-janela", {}).note("window_opened")
            busy_window, _world = lab.resume()
            self.assertIn("window_already_open", busy_window["blocked"])
            self.assertEqual(lab.commands(), [])

    def test_completed_window_is_archived_and_never_repeated(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            report, world = lab.resume()
            self.assertTrue(report["deployComplete"], report["blocked"])
            journal = load(lab.state)
            self.assertTrue(journal["deployComplete"])
            self.assertTrue(journal["holdRestored"])
            self.assertIsNone(journal["pending"])
            self.assertFalse(journal["datomicRestored"])
            intents = [event["op"] for event in journal["events"] if event["kind"] == "intent"]
            for op in ("hold_release", "node_maintenance", "jvm_drain", "stop_jvm", "backup", "restore_isolated",
                       "update_jvm", "update_node", "hold_restore", "resume_jvm", "resume_node"):
                self.assertIn(op, intents)
            self.assertLess(intents.index("hold_restore"), intents.index("resume_jvm"))
            before = lab.commands()
            again, _world = lab.resume(world)
            self.assertIn("window_already_complete", again["blocked"])
            self.assertEqual(lab.commands(), before)
            self.assertNotIn("TOKEN=", (lab.state / JOURNAL).read_text(encoding="utf-8"))

    def test_memory_hold_window_still_restores_before_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            lab = Lab(tmp)
            hold = MemoryHold("true")
            report = execute_window(
                lab.logged(lab.world()), hold, lambda _prompt: CONFIRM, SUPERVISED.SHA, SUPERVISED.JVM_DIGEST,
                SUPERVISED.NODE_DIGEST, dict(SUPERVISED.ENV), lab.destination, lab.export,
                window_id=WINDOW_ID, state=lab.state,
            )
            self.assertIn("run_argv_unresolved", report["blocked"])
            self.assertEqual(hold.writes, [])
            self.assertEqual(_count(lab.commands(), "stop"), 0)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--child":
        raise SystemExit(child(sys.argv[2]))
    unittest.main(verbosity=2)
