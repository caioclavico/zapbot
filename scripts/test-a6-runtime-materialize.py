#!/usr/bin/env python3
"""Falhas fechadas e preservação dos bytes; nenhum acesso à produção."""
import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from a6_production_contract import NODE_ENV_KEYS, JVM_ENV_KEYS, VM
from a6_production_deploy import DeployError, observe_gce
from a6_runtime_materialize import Materializer, ENV_PLACEHOLDER, SECCOMP_PLACEHOLDER


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir='/private/tmp' if Path('/private/tmp').is_dir() else None)
        self.addCleanup(self.temp.cleanup)
        self.m = Materializer(['docker'], self.temp.name)
        self.doc = {'Name': '/odisseu-a6-node', 'Config': {'Env': [k + '=dummy' for k in NODE_ENV_KEYS] + ['EXTRA= space=value ']},
                    'HostConfig': {'SecurityOpt': []}}
        self.argv = ['docker', 'run', '--security-opt', SECCOMP_PLACEHOLDER, '--env-file', ENV_PLACEHOLDER, 'image']
        self.addCleanup(lambda: self.m.lock.close() if self.m.lock else None)

    def prepare(self):
        with patch('a6_runtime_materialize._tmpfs'):
            self.m.prepare()

    def test_tmpfs_required(self):
        with self.assertRaises(DeployError):
            self.m.prepare()

    def test_permissions_bytes_and_cleanup(self):
        self.prepare()
        with patch.object(self.m, '_inspect', return_value=self.doc), patch.object(self.m, '_seccomp', return_value=None):
            argv, cleanup = self.m(self.argv, 'node')
        path = Path(argv[argv.index('--env-file') + 1])
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(path.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(path.read_text(), '\n'.join(self.doc['Config']['Env']) + '\n')
        self.assertNotIn('EXTRA=', ' '.join(argv))
        cleanup()
        self.assertFalse(path.exists())

    def test_release_adds_scoped_flags_only_to_jvm_env(self):
        self.prepare()
        self.m.v3_release = True
        jvm_doc = {
            "Name": "/odisseu-a6-jvm",
            "Config": {"Env": [key + "=dummy" for key in JVM_ENV_KEYS] +
                      ["POKEMON_PROGRESSIVE_XP_V3=false", "EXTRA_JVM=value"]},
            "HostConfig": {"SecurityOpt": []},
        }
        with patch.object(self.m, "_inspect", return_value=jvm_doc), \
             patch.object(self.m, "_seccomp", return_value=None):
            argv, cleanup = self.m(self.argv, "jvm")
        path = Path(argv[argv.index("--env-file") + 1])
        text = path.read_text()
        self.assertEqual(text.count("POKEMON_PROGRESSIVE_XP_V3="), 1)
        self.assertIn("POKEMON_PROGRESSIVE_XP_V3=true", text)
        self.assertIn("A6_V3_RECONCILIATION_ENABLED=true", text)
        self.assertIn("A6_V3_RECONCILIATION_ALLOWED_ID=abb8955f-7ebe-4084-a634-fd74cfa9bf62", text)
        self.assertIn("EXTRA_JVM=value", text)
        cleanup()
        with patch.object(self.m, "_inspect", return_value=self.doc), \
             patch.object(self.m, "_seccomp", return_value=None):
            argv, cleanup = self.m(self.argv, "node")
        path = Path(argv[argv.index("--env-file") + 1])
        self.assertNotIn("A6_V3_RECONCILIATION_ENABLED", path.read_text())
        cleanup()

    def test_env_incomplete_and_unrepresentable(self):
        for env in ([], ['A=x'], [k + '=x' for k in NODE_ENV_KEYS] + ['BAD=a\nb']):
            doc = copy.deepcopy(self.doc); doc['Config']['Env'] = env
            with self.assertRaises(DeployError): self.m._environment('node', doc)

    def test_seccomp_policy_and_hash(self):
        profile = {'defaultAction': 'SCMP_ACT_ERRNO', 'syscalls': []}
        path = Path(self.temp.name) / 'profile.json'; path.write_text(json.dumps(profile)); path.chmod(0o600)
        self.m.profiles['node'] = (str(path), hashlib.sha256(path.read_bytes()).hexdigest())
        doc = copy.deepcopy(self.doc); doc['HostConfig']['SecurityOpt'] = ['seccomp=' + json.dumps(profile)]
        self.assertEqual(self.m._seccomp('node', doc), 'seccomp=' + str(path))
        for spec in ('unconfined', json.dumps({'defaultAction': 'SCMP_ACT_ALLOW'})):
            doc['HostConfig']['SecurityOpt'] = ['seccomp=' + spec]
            with self.assertRaises(DeployError): self.m._seccomp('node', doc)
        path.write_text('{}')
        with self.assertRaises(DeployError): self.m._seccomp('node', self.doc | {'HostConfig': {'SecurityOpt': ['seccomp={}']}})

    def test_deletion_failure_blocks(self):
        self.prepare()
        with patch.object(self.m, '_inspect', return_value=self.doc), patch.object(self.m, '_seccomp', return_value=None):
            argv, cleanup = self.m(self.argv, 'node')
        with patch('pathlib.Path.unlink', side_effect=PermissionError()):
            with self.assertRaisesRegex(DeployError, 'cleanup_failed'): cleanup()

    def test_guardian_setup_failure_removes_environment(self):
        self.prepare()
        with patch.object(self.m, '_inspect', return_value=self.doc), patch.object(self.m, '_seccomp', return_value=None), patch('os.fork', side_effect=OSError()):
            with self.assertRaisesRegex(DeployError, 'guardian_failed'):
                self.m(self.argv, 'node')
        self.assertEqual([p.name for p in self.m.root.iterdir()], ['lock'])

    def test_symlink_root_refused(self):
        self.m.root.symlink_to(Path(self.temp.name), target_is_directory=True)
        with patch('a6_runtime_materialize._tmpfs'), self.assertRaises(DeployError): self.m.prepare()

    def test_sigkill_guardian_deletes_environment(self):
        # Processo filho e guardião reais; só o verificador de tmpfs é mockado no macOS.
        read_fd, write_fd = os.pipe()
        pid = os.fork()
        if pid == 0:
            os.close(read_fd)
            self.prepare()
            with patch.object(self.m, '_inspect', return_value=self.doc), patch.object(self.m, '_seccomp', return_value=None):
                argv, cleanup = self.m(self.argv, 'node')
            path = argv[argv.index('--env-file') + 1]
            os.write(write_fd, path.encode()); os.close(write_fd)
            while True: signal.pause()
        os.close(write_fd)
        path = Path(os.read(read_fd, 4096).decode()); os.close(read_fd)
        self.assertTrue(path.exists())
        os.kill(pid, signal.SIGKILL); os.waitpid(pid, 0)
        for _ in range(100):
            if not path.exists(): break
            time.sleep(0.01)
        self.assertFalse(path.exists())

    def test_metadata_official_and_closed(self):
        values = {'instance/name': VM['instance'], 'instance/zone': 'projects/123/zones/' + VM['zone'],
                  'project/project-id': VM['project'], 'instance/id': '123'}
        def runner(args):
            self.assertIn('Metadata-Flavor: Google', args)
            self.assertIn('--noproxy', args)
            return SimpleNamespace(returncode=0, stdout='HTTP/1.1 200 OK\r\nMetadata-Flavor: Google\r\n\r\n' + values[args[-1].split('/v1/')[1]])
        self.assertEqual(observe_gce(runner)['instanceId'], '123')
        for result in (SimpleNamespace(returncode=1, stdout=''), SimpleNamespace(returncode=0, stdout='HTTP/1.1 200 OK\n\nforged')):
            with self.assertRaises(DeployError): observe_gce(lambda args: result)

if __name__ == '__main__': unittest.main(verbosity=2)