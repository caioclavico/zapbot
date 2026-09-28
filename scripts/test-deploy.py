"""Exercita as decisões do deploy com Docker simulado, sem tocar na produção."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('deploy-vm.sh').resolve()
SHA = 'a' * 40

DOCKER = '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['DEPLOY_CALLS'], 'a') as f:
    f.write(json.dumps([args, os.environ.get('ZAPBOT_IMAGE')]) + '\\n')
if args[0] == 'inspect':
    fmt = args[2]
    if 'Config.Image' in fmt: print('zapbot:previous')
    elif 'StartedAt' in fmt: print('2026-09-27T17:00:00Z')
    elif '.Id' in fmt: print('new-container')
    else: print('true 1' if os.environ['SCENARIO'] == 'restart' else 'true 0')
elif args[0] == 'exec':
    assert args[2:] == ['node', 'scripts/healthcheck.js']
    sys.exit(1 if os.environ['SCENARIO'] in ['qr', 'disconnected'] else 0)
elif args[0] == 'logs':
    assert args[1:3] == ['--since', '2026-09-27T17:00:00Z']
    assert args[3] == 'new-container'
    scenario = os.environ['SCENARIO']
    if scenario == 'database-error': print('seguindo sem persistência')
    elif scenario != 'qr':
        print('Conectado ao Cassandra; estado particionado carregado.')
        print('conectado e pronto para uso')
    else: print('Escaneie o QR code')
'''


class DeployTest(unittest.TestCase):
    def run_deploy(self, scenario, has_env=True):
        import json
        with tempfile.TemporaryDirectory(prefix='zapbot-deploy-', dir='/tmp') as tmp:
            root = Path(tmp)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name, content in [('docker', DOCKER), ('sleep', '#!/bin/sh\nexit 0\n'),
                                  ('flock', '#!/bin/sh\nexit 0\n')]:
                target = bin_dir / name
                target.write_text(content)
                target.chmod(0o755)
            app = root / 'app'
            release = app / 'releases' / SHA
            release.mkdir(parents=True)
            (release / 'docker-compose.production.yml').write_text('services: {}\n')
            if has_env:
                (app / '.env').write_text('CASSANDRA_CONTACT_POINTS=10.0.0.234\n')
            calls_file = root / 'calls'
            env = dict(os.environ, PATH=str(bin_dir) + ':' + os.environ['PATH'],
                       SCENARIO=scenario, DEPLOY_CALLS=str(calls_file))
            result = subprocess.run(['bash', str(SCRIPT), str(app), SHA], env=env,
                                    capture_output=True, text=True, timeout=15)
            calls = [json.loads(line) for line in calls_file.read_text().splitlines()] if calls_file.exists() else []
            revision = app / 'deployed-revision'
            return result, calls, revision.read_text().strip() if revision.exists() else None

    def test_ready_records_revision_without_touching_database(self):
        result, calls, revision = self.run_deploy('ready')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(revision, SHA)
        updates = [(args, image) for args, image in calls if 'up' in args]
        self.assertEqual(len(updates), 1)
        self.assertEqual(updates[0][1], 'zapbot:' + SHA)
        self.assertIn('--no-deps', updates[0][0])
        self.assertEqual(updates[0][0][-1], 'bot')
        self.assertNotIn('--remove-orphans', str(calls))

    def test_failures_restore_previous_image_and_do_not_record_success(self):
        for scenario in ['database-error', 'restart']:
            with self.subTest(scenario=scenario):
                result, calls, revision = self.run_deploy(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertIsNone(revision)
                updates = [image for args, image in calls if 'up' in args]
                self.assertEqual(updates, ['zapbot:' + SHA, 'zapbot:previous'])

    def test_stale_ready_log_does_not_report_health_or_restart(self):
        result, calls, revision = self.run_deploy('disconnected')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(revision)
        self.assertEqual([image for args, image in calls if 'up' in args], ['zapbot:' + SHA])

    def test_slow_start_does_not_restart_or_rollback(self):
        result, calls, revision = self.run_deploy('qr')
        self.assertNotEqual(result.returncode, 0)
        self.assertIsNone(revision)
        self.assertEqual([image for args, image in calls if 'up' in args], ['zapbot:' + SHA])

    def test_missing_env_fails_before_changing_container(self):
        result, calls, revision = self.run_deploy('ready', has_env=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(calls, [])
        self.assertIsNone(revision)


if __name__ == '__main__':
    unittest.main()
