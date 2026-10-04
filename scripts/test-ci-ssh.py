#!/usr/bin/env python3
"""Exercise the runner SSH client using fake commands, with no network or VM."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


CLIENT = Path(__file__).with_name("ci-ssh-deploy.sh").resolve()
SHA = "a" * 40


class RunnerSshTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="zapbot-ci-ssh-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        binary = self.root / "bin"
        binary.mkdir()
        real_node = shutil.which("node")
        self.assertIsNotNone(real_node, "Node.js is required to validate the runner HEAD check")
        preload = self.root / "fetch-fixture.cjs"
        preload.write_text('''const fs = require('node:fs');
global.fetch = async (url, options) => {
  fs.writeFileSync(process.env.FAKE_HEAD_URL, String(url));
  if (options.headers.Authorization !== `Bearer ${process.env.GITHUB_TOKEN}`) {
    throw Error('HEAD request authentication was not preserved');
  }
  if (options.headers.Accept !== 'application/vnd.github+json' || !options.signal) {
    throw Error('HEAD request controls were not preserved');
  }
  if (process.env.FAKE_HEAD === 'failure') return {ok:false, status:503};
  const sha = process.env.FAKE_HEAD === 'stale' ? 'b'.repeat(40) :
              process.env.FAKE_HEAD === 'invalid' ? 'HEAD' : process.env.GITHUB_SHA;
  return {ok:true, json:async () => ({object:{sha}})};
};
''')
        commands = {
            "timeout": '#!/bin/bash\nshift\nexec "$@"\n',
            "ssh-keygen": '#!/bin/bash\nexit 0\n',
            "node": '#!/bin/bash\nexec "$FAKE_REAL_NODE" --require "$FAKE_NODE_PRELOAD" -\n',
            "ssh": '''#!/usr/bin/env python3
import json, os, pathlib, sys
args=sys.argv[1:]
key=pathlib.Path(args[args.index('-i')+1])
known=pathlib.Path(next(arg.split('=',1)[1] for arg in args if arg.startswith('UserKnownHostsFile=')))
assert key.stat().st_mode & 0o777 == 0o600
assert known.stat().st_mode & 0o777 == 0o600
pathlib.Path(os.environ['FAKE_SSH_ARGS']).write_text(json.dumps(args))
print('Remote deployment response (fixture).')
sys.exit(int(os.environ.get('FAKE_SSH_STATUS','0')))
''',
        }
        for name, content in commands.items():
            path = binary / name
            path.write_text(content)
            path.chmod(0o700)
        self.env = {
            **os.environ, "PATH": str(binary) + os.pathsep + os.environ["PATH"],
            "RUNNER_TEMP": str(self.root), "GITHUB_REF": "refs/heads/master", "GITHUB_SHA": SHA,
            "GITHUB_EVENT_NAME": "workflow_dispatch", "DEPLOY_OPERATION": "deploy",
            "DEPLOY_SERVICE": "odisseu", "SSH_HOST": "192.0.2.1", "SSH_USER": "zapbot-deploy",
            "SSH_PORT": "22", "SSH_KEY": "PRIVATE_KEY_CANARY_FOR_FIXTURE_ONLY",
            "SSH_KNOWN_HOSTS": "KNOWN_HOSTS_CANARY_FOR_FIXTURE_ONLY",
            "GITHUB_TOKEN": "GITHUB_TOKEN_CANARY_FOR_FIXTURE_ONLY",
            "GITHUB_REPOSITORY": "fixture/zapbot", "GITHUB_API_URL": "https://api.github.com",
            "FAKE_REAL_NODE": real_node, "FAKE_NODE_PRELOAD": str(preload),
            "FAKE_HEAD_URL": str(self.root / "head-url"),
            "FAKE_SSH_ARGS": str(self.root / "ssh-args.json"),
            "GITHUB_OUTPUT": str(self.root / "github-output"),
        }

    def invoke(self, **updates):
        result = subprocess.run(["bash", str(CLIENT)], env={**self.env, **updates}, capture_output=True, text=True)
        for secret in (self.env["SSH_KEY"], self.env["SSH_KNOWN_HOSTS"], self.env["GITHUB_TOKEN"]):
            self.assertNotIn(secret, result.stdout + result.stderr)
        self.assertEqual(list(self.root.glob("zapbot-deploy.*")), [])
        return result

    def args(self):
        return json.loads((self.root / "ssh-args.json").read_text())

    def test_deploy_is_scoped_and_checks_server_identity(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = self.args()
        self.assertEqual(args[-1], f"deploy {SHA}")
        for required in ("StrictHostKeyChecking=yes", "IdentitiesOnly=yes", "GlobalKnownHostsFile=/dev/null", "ClearAllForwardings=yes", "RequestTTY=no"):
            self.assertIn(required, args)
        self.assertEqual(args[-2], "zapbot-deploy@192.0.2.1")
        output = (self.root / "github-output").read_text()
        self.assertTrue(output.endswith("performed=true\ndeployed=true\n"))

    def test_manual_rollback_has_no_untrusted_argument(self):
        result = self.invoke(DEPLOY_OPERATION="rollback")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.args()[-1], "rollback")
        output = (self.root / "github-output").read_text()
        self.assertTrue(output.endswith("performed=true\n"))
        self.assertNotIn("deployed=true", output)

    def test_ssh_failure_also_removes_private_files(self):
        self.assertEqual(self.invoke(FAKE_SSH_STATUS="1").returncode, 1)

    def test_old_automatic_run_cannot_touch_vm(self):
        result = self.invoke(GITHUB_EVENT_NAME="push", FAKE_HEAD="stale")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / "ssh-args.json").exists())
        self.assertEqual(list(self.root.glob("zapbot-stale-*")), [])
        self.assertEqual((self.root / "github-output").read_text(), "performed=false\ndeployed=false\n")

    def test_master_push_rechecks_master_head_before_ssh(self):
        result = self.invoke(GITHUB_EVENT_NAME="push", FAKE_HEAD="fresh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "head-url").read_text(),
                         "https://api.github.com/repos/fixture/zapbot/git/ref/heads/master")
        self.assertEqual(self.args()[-1], f"deploy {SHA}")

    def test_head_lookup_failure_cannot_touch_vm(self):
        self.assertEqual(self.invoke(GITHUB_EVENT_NAME="push", FAKE_HEAD="failure").returncode, 1)
        self.assertFalse((self.root / "ssh-args.json").exists())

    def test_invalid_head_response_cannot_touch_vm(self):
        self.assertEqual(self.invoke(GITHUB_EVENT_NAME="push", FAKE_HEAD="invalid").returncode, 1)
        self.assertFalse((self.root / "ssh-args.json").exists())

    def test_invalid_targets_and_refs_are_rejected(self):
        for update in ({"GITHUB_REF": "refs/heads/main"}, {"GITHUB_REF": "refs/heads/feature/runtime"}, {"SSH_HOST": "-oProxyCommand=bad"}, {"SSH_USER": "root; echo bad"}, {"SSH_PORT": "65536"}, {"DEPLOY_OPERATION": "deploy; command"}, {"GITHUB_SHA": "HEAD"}):
            with self.subTest(update=update):
                self.assertEqual(self.invoke(**update).returncode, 2)
                self.assertFalse((self.root / "ssh-args.json").exists())


if __name__ == "__main__":
    unittest.main()
