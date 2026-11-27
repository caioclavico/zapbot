#!/usr/bin/env python3
"""Broker e recovery descartáveis: nenhum acesso ao GitHub, GCP ou produção."""
import base64
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from a6_github_app import (AppBroker, AppGhRunner, GITHUB, INSTALLATION_ID, METADATA,
                           READ, REPO, SECRET_VERSION, SERVICE_ACCOUNT, WRITE, sign)
from a6_operational_window import GithubHold, github_hold, recover_hold
from a6_production_deploy import DeployError


NOW = 1800000000


class Fixture:
    def __init__(self):
        self.issued = 0
        self.calls = []
        self.change = lambda url, value: value
        self.status = 200

    def http(self, url, headers, payload=None):
        self.calls.append((url, payload))
        if url == METADATA + "email":
            return self.status, SERVICE_ACCOUNT.encode()
        if url == METADATA + "scopes":
            return 200, b"https://www.googleapis.com/auth/cloud-platform\n"
        if url == METADATA + "token":
            value = {"access_token": "oauth-secret", "expires_in": 3500}
        elif "secretmanager.googleapis.com" in url:
            value = {"name": SECRET_VERSION, "payload": {
                "data": base64.b64encode(b"private-key-fixture").decode()}}
        elif url.endswith("/installation/repositories"):
            value = {"total_count": 1, "repositories": [{"full_name": REPO}]}
        elif url.endswith("/installation"):
            value = {"id": INSTALLATION_ID, "app_id": 5946171,
                     "account": {"login": "caioclavico"}, "suspended_at": None,
                     "repository_selection": "selected",
                     "permissions": {"actions_variables": "write", "metadata": "read"}}
        elif url.endswith("/access_tokens"):
            self.issued += 1
            value = {"token": "installation-secret-" + str(self.issued),
                     "expires_at": datetime.fromtimestamp(NOW + 3600, timezone.utc).isoformat(),
                     "permissions": {"actions_variables": "write", "metadata": "read"},
                     "repository_selection": "selected"}
            return 201, json.dumps(self.change(url, value)).encode()
        else:
            raise AssertionError(url)
        return 200, json.dumps(self.change(url, value)).encode()

    def broker(self):
        return AppBroker(http=self.http, signer=lambda body, key: b"signed", clock=lambda: NOW)


class GithubAppTests(unittest.TestCase):
    def test_scoped_tokens_are_fresh_for_each_operation_and_independent_recovery(self):
        fixture = Fixture()
        value, tokens = ["true"], []

        def gh(args, **kw):
            self.assertNotIn(kw["env"]["GH_TOKEN"], " ".join(args))
            self.assertNotIn("GH_DEBUG", kw["env"])
            self.assertNotIn("GITHUB_TOKEN", kw["env"])
            tokens.append(kw["env"]["GH_TOKEN"])
            if args == READ:
                return subprocess.CompletedProcess(args, 0, value[0] + "\n", "secret-output")
            value[0] = args[-1]
            return subprocess.CompletedProcess(args, 0, "secret-output", "secret-error")

        with patch.dict(os.environ, {"GH_DEBUG": "api", "GITHUB_TOKEN": "unrelated-secret"}):
            hold = GithubHold(AppGhRunner(fixture.broker(), gh))
            self.assertEqual(hold.read(), "true")
            hold.restore()
            hold.release()
            self.assertEqual(value[0], "false")
            del hold
            with tempfile.TemporaryDirectory() as tmp:
                fresh = GithubHold(AppGhRunner(fixture.broker(), gh))
                report = recover_hold(fresh, tmp, env={})
                self.assertEqual(value[0], "true")
                self.assertNotIn("secret", json.dumps(report))
                self.assertNotIn("secret", "".join(p.read_text() for p in Path(tmp).glob("*.json")))
        self.assertEqual(len(tokens), len(set(tokens)))
        requests = [payload for url, payload in fixture.calls if url.endswith("/access_tokens")]
        self.assertTrue(all(p == {"repositories": ["zapbot"],
                                 "permissions": {"actions_variables": "write"}} for p in requests))

    def test_expired_or_near_expiry_token_never_reaches_gh(self):
        for delta in (-10, 0, 60, 299, 4000):
            with self.subTest(delta=delta):
                fixture = Fixture()
                fixture.change = lambda url, value: dict(value, expires_at=datetime.fromtimestamp(
                    NOW + delta, timezone.utc).isoformat()) if url.endswith("/access_tokens") else value
                with self.assertRaisesRegex(DeployError, "github_app_token_invalid"):
                    AppGhRunner(fixture.broker(), lambda *a, **k: self.fail("gh executed"))(READ)

    def test_revocation_401_renews_once_but_other_failures_do_not_retry_writes(self):
        for errors, expected in ((["HTTP 401", ""], 2), (["HTTP 403"], 1), (["HTTP 401", "HTTP 401"], 2)):
            fixture = Fixture()
            attempts = []

            def gh(args, **kw):
                error = errors[len(attempts)]
                attempts.append(kw["env"]["GH_TOKEN"])
                return subprocess.CompletedProcess(args, int(bool(error)), "", error)

            runner = AppGhRunner(fixture.broker(), gh)
            if errors[-1]:
                with self.assertRaisesRegex(DeployError, "github_app_gh_failed"):
                    runner(WRITE + ["true"])
            else:
                self.assertEqual(runner(WRITE + ["true"]).stdout, "")
            self.assertEqual(len(attempts), expected)
            self.assertEqual(len(set(attempts)), expected)

    def test_malformed_identity_installation_permissions_and_repo_fail_closed(self):
        cases = [
            ("/installation", {"id": 1}),
            ("/installation", {"suspended_at": "yesterday"}),
            ("/installation", {"permissions": {"actions_variables": "write", "contents": "write"}}),
            ("/installation", {"repository_selection": "all"}),
            ("/installation", {"account": []}),
            ("/access_tokens", {"permissions": {"actions_variables": "read"}}),
            ("/access_tokens", {"token": "secret\ninvalid"}),
            ("/installation/repositories", {"total_count": 2}),
            ("/installation/repositories", {"repositories": [{"full_name": "other/repo"}]}),
            ("/installation/repositories", {"repositories": None}),
        ]
        for suffix, change in cases:
            with self.subTest(suffix=suffix, keys=list(change)):
                fixture = Fixture()
                fixture.change = lambda url, value: dict(value, **change) if url.endswith(suffix) else value
                with self.assertRaises(DeployError):
                    fixture.broker().token()

    def test_scope_and_transport_outage_do_not_fall_back_to_inherited_credentials(self):
        for response, error in (
                ((403, b"secret-error"), "vm_identity"),
                ((200, b"another-account"), "vm_identity")):
            broker = AppBroker(http=lambda *a: response)
            with self.assertRaisesRegex(DeployError, error):
                AppGhRunner(broker, lambda *a, **k: self.fail("gh executed"))(READ)
        fixture = Fixture()
        original = fixture.http
        broker = AppBroker(http=lambda url, *args: (200, b"storage.readonly") if url.endswith("/scopes")
                           else original(url, *args))
        with self.assertRaisesRegex(DeployError, "oauth_scope_missing"):
            broker.token()
        with patch.dict(os.environ, {"A6_GITHUB_AUTH": "personal"}):
            with self.assertRaisesRegex(DeployError, "github_auth_mode_refused"):
                github_hold()

    def test_secret_store_outage_and_invalid_jwt_signing_fail_closed(self):
        fixture = Fixture()
        original = fixture.http
        fixture.http = lambda url, *args: (503, b"private-key-leak") if "secretmanager.googleapis.com" in url else original(url, *args)
        with self.assertRaisesRegex(DeployError, "github_app_http_503"):
            fixture.broker().token()
        fixture = Fixture()
        with self.assertRaisesRegex(DeployError, "github_app_signing_failed"):
            AppBroker(http=fixture.http, signer=lambda *args: (_ for _ in ()).throw(OSError("secret")),
                      clock=lambda: NOW).token()

    def test_command_allowlist_and_timeout_never_emit_secret(self):
        fixture = Fixture()
        runner = AppGhRunner(fixture.broker())
        with self.assertRaisesRegex(DeployError, "command_refused"):
            runner(["gh", "api", "/user"])
        self.assertEqual(fixture.calls, [])
        runner.process = lambda *a, **k: (_ for _ in ()).throw(subprocess.TimeoutExpired("secret", 45))
        with self.assertRaisesRegex(DeployError, "^github_app_gh_failed$"):
            runner(WRITE + ["true"])

    def test_real_openssl_pipe_and_child_process_environment(self):
        key = subprocess.run(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt",
                              "rsa_keygen_bits:2048"], capture_output=True, check=True).stdout
        signature = sign(b"jwt-body", key)
        self.assertEqual(len(signature), 256)
        fixture = Fixture()

        def child(args, **kw):
            return subprocess.run(
                ["python3", "-c",
                 "import os; assert os.environ['GH_TOKEN'].startswith('installation-secret-'); print('true')"],
                **kw)

        result = AppGhRunner(fixture.broker(), child)(READ)
        self.assertEqual(result.stdout.strip(), "true")
        self.assertNotIn("GH_TOKEN", os.environ)


if __name__ == "__main__":
    unittest.main()
