"""Validate the forced-command boundary without SSH, sudo or production."""
import importlib.util
import io
from contextlib import redirect_stdout
from pathlib import Path
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ssh = load("deploy-ssh-command")
entry = load("deploy-entry")
SHA = "a" * 40


class RestrictedDeployTest(unittest.TestCase):
    def test_only_fixed_deploy_rollback_and_diagnostic_commands(self):
        for service in ("odisseu", "pokemon"):
            self.assertEqual(ssh.arguments(service, "deploy " + SHA), ["deploy", SHA])
            self.assertEqual(ssh.arguments(service, "rollback"), ["rollback"])
        for command in ("", "bash", "deploy latest", "deploy " + SHA + "; id",
                        "deploy " + SHA + "\n", "rollback now", "rollback; id",
                        "deploy  " + SHA, "deploy " + "A" * 40, "scp -t /tmp/file",
                        "check; id", "check now"):
            with self.subTest(command=command), self.assertRaises(ValueError):
                ssh.arguments("odisseu", command)
        with self.assertRaises(ValueError):
            ssh.arguments("pokemon", "check")

    def test_odisseu_check_does_not_call_sudo(self):
        output = io.StringIO()
        with patch.dict(ssh.os.environ, {"SSH_ORIGINAL_COMMAND": "check"}, clear=True), \
             patch.object(ssh.sys, "argv", ["deploy-ssh-command.py", "odisseu"]), \
             patch.object(ssh.subprocess, "call") as call, redirect_stdout(output):
            self.assertEqual(ssh.main(), 0)
        call.assert_not_called()
        self.assertIn("no deployment action was run", output.getvalue())

    def test_registry_and_app_directory_cannot_be_overridden(self):
        result = entry.deployment_args("pokemon", ["deploy", SHA], {"ghcr_owner": "caioclavico"})
        self.assertEqual(result, ["pokemon", "deploy", "ghcr.io/caioclavico/zapbot-pokemon:" + SHA,
                                  "/home/caiohclavico/pokemon-service"])
        self.assertEqual(entry.deployment_args("odisseu", ["rollback"], {"ghcr_owner": "caioclavico"}),
                         ["odisseu", "rollback", "/home/caiohclavico/zapbot"])
        invalid = ({"ghcr_owner": "owner/foreign"}, {"ghcr_owner": "owner; id"},
                   {"ghcr_owner": "owner", "app_dir": "/tmp"}, {"ghcr_owner": "Owner"})
        for config in invalid:
            with self.subTest(config=config), self.assertRaises(ValueError):
                entry.deployment_args("pokemon", ["deploy", SHA], config)
        with self.assertRaises(ValueError):
            entry.deployment_args("cassandra", ["deploy", SHA], {"ghcr_owner": "owner"})


if __name__ == "__main__":
    unittest.main()
