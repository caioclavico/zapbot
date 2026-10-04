#!/usr/bin/env python3
"""Regression tests for deployment selection, including cross-service moves."""

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("ci_changes", Path(__file__).with_name("ci-changes.py"))
changes = importlib.util.module_from_spec(spec)
spec.loader.exec_module(changes)


class ChangeSelectionTests(unittest.TestCase):
    def test_skipped_older_push_cannot_lose_changes(self):
        # A changed Odisseu but was skipped because B was already HEAD. B changes
        # Pokemon. B must compare both services to their last actual deployment.
        deployed, push_a, push_b = "d" * 40, "a" * 40, "b" * 40
        def paths(base, head):
            self.assertEqual(head, push_b)
            return ["pokemon-service/runtime/main.cjs"] if base == push_a else ["src/zapbot/core.cljs", "pokemon-service/runtime/main.cjs"]
        self.assertEqual(changes.affected_services(paths(push_a, push_b)), ["pokemon"])
        self.assertEqual(changes.services_since_baselines({"odisseu": deployed, "pokemon": deployed}, push_b, paths), ["odisseu", "pokemon"])

    def test_partial_deployment_has_independent_baselines(self):
        def paths(base, head):
            return ["src/zapbot/core.cljs"] if base == "older" else []
        self.assertEqual(changes.services_since_baselines({"odisseu": "older", "pokemon": "newer"}, "head", paths), ["odisseu"])

    def test_missing_baseline_selects_whole_runtime(self):
        def paths(base, head):
            return ["src/zapbot/core.cljs", "pokemon-service/runtime/main.cjs"] if base is None else []
        self.assertEqual(changes.services_since_baselines({"odisseu": "deployed", "pokemon": None}, "head", paths), ["pokemon"])

    def test_artifacts_from_pr_foreign_repo_and_other_workflow_are_ignored(self):
        repo, sha = "fixture/zapbot", "a" * 40
        trusted = {"event": "push", "head_branch": "main", "head_sha": sha, "head_repository": {"full_name": repo}, "path": ".github/workflows/deploy.yml"}
        runs = {1: {**trusted, "event": "pull_request"}, 2: {**trusted, "head_repository": {"full_name": "attacker/zapbot"}}, 3: {**trusted, "path": ".github/workflows/other.yml"}, 4: trusted}
        artifacts = [{"name": f"deployed-odisseu-{sha}", "workflow_run": {"id": run_id}} for run_id in runs]
        artifacts.append({"name": f"deployed-pokemon-{sha}", "workflow_run": {"id": 4}})
        def fetch(path):
            if "/artifacts?" in path:
                return {"artifacts": artifacts}
            return runs[int(path.rsplit("/", 1)[1])]
        self.assertEqual(changes.deployment_baselines(fetch, repo), {"odisseu": sha, "pokemon": sha})

    def test_rollback_invalidates_only_its_service_even_when_old_marker_exists(self):
        repo, new, old = "fixture/zapbot", "a" * 40, "b" * 40
        def run(sha):
            return {"event": "workflow_dispatch", "head_branch": "main", "head_sha": sha, "head_repository": {"full_name": repo}, "path": ".github/workflows/deploy.yml"}
        artifacts = [
            {"name": f"rolled-back-odisseu-{new}", "workflow_run": {"id": 2}},
            {"name": f"deployed-odisseu-{old}", "workflow_run": {"id": 1}},
            {"name": f"deployed-pokemon-{old}", "workflow_run": {"id": 1}},
        ]
        def fetch(path):
            return {"artifacts": artifacts} if "/artifacts?" in path else run(new if path.endswith("/2") else old)
        self.assertEqual(changes.deployment_baselines(fetch, repo), {"odisseu": None, "pokemon": old})

    def test_missing_or_expired_artifacts_are_conservative(self):
        self.assertEqual(changes.deployment_baselines(lambda _: {"artifacts": []}, "fixture/zapbot"), {"odisseu": None, "pokemon": None})

    def test_pagination_and_expiry_keep_last_actual_service_deployment(self):
        repo, newest, older = "fixture/zapbot", "a" * 40, "b" * 40
        calls = []
        def fetch(path):
            calls.append(path)
            if "&page=1" in path:
                return {"artifacts": [{"name": "unrelated-build-summary"} for _ in range(100)]}
            if "&page=2" in path:
                return {"artifacts": [
                    {"name": f"deployed-odisseu-{newest}", "expired": True, "workflow_run": {"id": 2}},
                    {"name": f"deployed-pokemon-{newest}", "workflow_run": {"id": 2}},
                    {"name": f"deployed-odisseu-{older}", "workflow_run": {"id": 1}},
                ]}
            return {"event": "push", "head_branch": "main", "head_sha": newest if path.endswith("/2") else older,
                    "head_repository": {"full_name": "Fixture/ZapBot"}, "path": ".github/workflows/deploy.yml", "conclusion": "failure"}
        self.assertEqual(changes.deployment_baselines(fetch, repo), {"odisseu": None, "pokemon": newest})
        self.assertTrue(any("page=2" in path for path in calls))

    def test_marker_order_uses_timestamp_not_api_array_order(self):
        repo, old, new = "fixture/zapbot", "a" * 40, "b" * 40
        artifacts = [
            {"id": 1, "created_at": "2026-10-03T00:00:00Z", "name": f"deployed-odisseu-{old}", "workflow_run": {"id": 1}},
            {"id": 2, "created_at": "2026-10-04T00:00:00Z", "name": f"rolled-back-odisseu-{new}", "workflow_run": {"id": 2}},
        ]
        def fetch(path):
            if "/artifacts?" in path:
                return {"artifacts": artifacts, "total_count": 2}
            return {"event": "workflow_dispatch", "head_branch": "main", "head_sha": new if path.endswith("/2") else old,
                    "head_repository": {"full_name": repo}, "path": ".github/workflows/deploy.yml"}
        self.assertEqual(changes.deployment_baselines(fetch, repo), {"odisseu": None, "pokemon": None})

    def test_incomplete_bounded_history_cannot_hide_newer_rollback(self):
        def fetch(path):
            return {"artifacts": [{"name": "unrelated"}] * 100, "total_count": 301}
        self.assertEqual(changes.deployment_baselines(fetch, "fixture/zapbot"), {"odisseu": None, "pokemon": None})

    def test_documentation_does_not_deploy(self):
        self.assertEqual(changes.affected_services(["docs/deploy.md", "README.md", "pokemon-service/README.md"]), [])
        self.assertEqual(changes.affected_services(["docs/deploy.md", "README.md", "LICENSE"]), [])

    def test_odisseu_runtime_and_build(self):
        for path in ("src/zapbot/core.cljs", "test/zapbot/core_test.cljs", "Dockerfile", "package-lock.json", "scripts/healthcheck.js", "assets/abujamra.png"):
            with self.subTest(path=path):
                self.assertEqual(changes.affected_services([path]), ["odisseu"])

    def test_pokemon_is_independent(self):
        self.assertEqual(changes.affected_services(["pokemon-service/runtime/main.cjs", "pokemon-service/Dockerfile"]), ["pokemon"])

    def test_contract_infrastructure_and_unknown_validate_both(self):
        for path in ("scripts/lib/pokemon-http-client.cjs", "scripts/test-pokemon-contract.cjs", ".github/workflows/deploy.yml", "docker-compose.yml", "new-runtime.cjs"):
            with self.subTest(path=path):
                self.assertEqual(changes.affected_services([path]), ["odisseu", "pokemon"])

    def test_empty_change(self):
        self.assertEqual(changes.affected_services([]), [])

    def test_deletion_and_move_include_old_and_new_paths(self):
        with tempfile.TemporaryDirectory(prefix="zapbot-ci-paths-") as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.run(["git", "-C", temp, *args], check=True, capture_output=True, text=True).stdout.strip()
            git("init", "--quiet")
            git("config", "user.email", "ci-test@example.invalid")
            git("config", "user.name", "CI test")
            (root / "src").mkdir()
            (root / "src/runtime.cjs").write_text("module.exports = {};\n")
            git("add", ".")
            git("commit", "--quiet", "-m", "base")
            base = git("rev-parse", "HEAD")
            (root / "pokemon-service").mkdir()
            git("mv", "src/runtime.cjs", "pokemon-service/runtime.cjs")
            git("commit", "--quiet", "-m", "move")
            original_run = subprocess.run
            def run_in_repo(command, **kwargs):
                return original_run(command, cwd=temp, **kwargs)
            with patch.object(changes.subprocess, "run", run_in_repo):
                paths = changes.changed_paths(base, "HEAD")
                self.assertEqual(set(paths), {"src/runtime.cjs", "pokemon-service/runtime.cjs"})
                self.assertEqual(changes.affected_services(paths), ["odisseu", "pokemon"])
                first_push_paths = changes.changed_paths("0" * 40, "HEAD")
                self.assertEqual(first_push_paths, ["pokemon-service/runtime.cjs"])
                missing_baseline_paths = changes.changed_paths("f" * 40, "HEAD")
                self.assertEqual(missing_baseline_paths, ["pokemon-service/runtime.cjs"])


if __name__ == "__main__":
    unittest.main()
