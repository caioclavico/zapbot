"""Google Compose and orphan-lock regressions: no SSH or production access."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
from types import SimpleNamespace
import unittest

from deploy_compose import ProductionCompose, ComposeError
from deploy_auth_profile import ProfileError, assert_profile_idle

spec = importlib.util.spec_from_file_location('base', Path(__file__).with_name('test-deploy.py'))
B = importlib.util.module_from_spec(spec)
spec.loader.exec_module(B)
D = B.DEPLOY


class ComposeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.app, self.state = root / 'app', root / 'state'
        for path in (self.app, self.state, self.app / '.wwebjs_auth', self.app / 'data'):
            path.mkdir(mode=0o700)
        self.env = self.app / '.env'
        self.env.write_text('CASSANDRA_CONTACT_POINTS=172.17.0.1\nPOKEMON_SERVICE_URL=http://10.128.0.2:8080\nPRIVATE=' + B.SECRET)
        self.env.chmod(0o600)
        self.source = self.app / 'docker-compose.production.yml'
        self.source.write_text('fixture production compose')
        self.original = B.DockerModel('odisseu', self.app).original
        self.configuration = copy.deepcopy(self.original['Config'])
        self.configuration.update(Image=B.NEW_IMAGE, StopTimeout=60,
                                  HostConfig=copy.deepcopy(self.original['HostConfig']))
        self.configuration['Labels'].update({D.DEPLOYMENT_LABEL: '123', D.REVISION_LABEL: 'a' * 40})
        self.model = {'services': {'bot': {'container_name': 'zapbot', 'image': B.NEW_IMAGE,
            'environment': {'FROM_ENV_FILE': B.SECRET},
            'volumes': [{'type': 'bind', 'source': m['Source'], 'target': m['Destination']}
                        for m in self.original['Mounts']]}}}
        self.calls, self.response = [], None
        self.compose = ProductionCompose(self.app, self.state, D.atomic_private, runner=self.runner)

    def tearDown(self):
        self.temp.cleanup()

    def runner(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if isinstance(self.response, BaseException):
            raise self.response
        if self.response:
            return self.response
        return SimpleNamespace(returncode=0, stdout=json.dumps(self.model), stderr=B.SECRET)

    def prepare(self):
        self.compose.prepare('123', 'zapbot', self.original, self.configuration)
        return json.loads(self.compose.plan.read_text())

    def test_frozen_compose_preserves_environment_and_uses_digest(self):
        before = self.env.read_bytes(), self.env.stat().st_mtime_ns, self.source.read_bytes()
        plan = self.prepare()
        service = plan['services']['bot']
        self.assertEqual(service['image'], B.NEW_IMAGE)
        self.assertEqual(service['pull_policy'], 'never')
        self.assertEqual(service['hostname'], 'actual-hostname')
        self.assertEqual(service['environment'], dict(e.split('=', 1) for e in self.configuration['Env']))
        self.assertNotIn('env_file', service)
        self.assertEqual(service['network_mode'], 'bridge')
        self.assertEqual(service['mem_limit'], self.original['HostConfig']['Memory'])
        self.assertEqual(service['cap_drop'], ['NET_RAW'])
        self.assertEqual(before, (self.env.read_bytes(), self.env.stat().st_mtime_ns, self.source.read_bytes()))
        self.assertEqual(self.compose.plan.stat().st_mode & 0o777, 0o600)
        command, options = self.calls[0]
        self.assertEqual(command[-3:], ['config', '--format', 'json'])
        self.assertIn(str(self.source), command)
        self.assertEqual(options['env']['ZAPBOT_APP_DIR'], str(self.app))
        self.assertNotIn('PRIVATE', options['env'])

    def test_external_network_reused_without_new_resources(self):
        self.original['HostConfig']['NetworkMode'] = 'existing-production'
        self.original['NetworkSettings']['Networks'] = {'existing-production': {}, 'second': {}}
        self.configuration['HostConfig'] = copy.deepcopy(self.original['HostConfig'])
        self.configuration['NetworkingConfig'] = {'EndpointsConfig': {'existing-production': {'Aliases': ['bot']}}}
        plan = self.prepare()
        self.assertEqual(plan['networks']['retained0'], {'external': True, 'name': 'existing-production'})
        self.assertEqual(plan['services']['bot']['networks']['retained0']['aliases'], ['bot'])
        self.assertEqual(plan['name'], 'zapbot-deploy-123')
        self.assertNotIn('network_mode', plan['services']['bot'])

    def test_create_is_inert_and_start_validates_actual_identity(self):
        self.prepare()
        engine = SimpleNamespace(inspect=lambda name: {'Id': 'fixture-id'})
        self.assertEqual(self.compose.create(engine, 'zapbot'), 'fixture-id')
        self.assertEqual(self.calls[-1][0][-5:], ['create', '--no-build', '--pull', 'never', 'bot'])
        self.compose.start(engine, 'fixture-id', 'zapbot')
        self.assertEqual(self.calls[-1][0][-2:], ['start', 'bot'])
        with self.assertRaises(ComposeError):
            self.compose.start(engine, 'wrong-id', 'zapbot')
        self.assertFalse(any('down' in c or 'up' in c or 'rm' in c for c, _ in self.calls))

    def test_foreign_services_and_dependencies_rejected_before_creation(self):
        self.model['services']['cassandra'] = {}
        with self.assertRaises(ComposeError):
            self.prepare()
        self.model['services'].pop('cassandra')
        self.model['services']['bot']['depends_on'] = {'pokemon': {}}
        with self.assertRaises(ComposeError):
            self.prepare()

    def test_extra_mount_or_named_volume_cannot_be_created(self):
        self.model['services']['bot']['volumes'].append({'type': 'bind', 'source': '/etc', 'target': '/secrets'})
        with self.assertRaises(ComposeError):
            self.prepare()
        self.model['services']['bot']['volumes'].pop()
        self.model['volumes'] = {'players': {}}
        with self.assertRaises(ComposeError):
            self.prepare()

    def test_legacy_startup_command_is_not_silently_reused(self):
        self.model['services']['bot']['command'] = ['node', 'target/main.js']
        self.original['Config']['Cmd'] = ['sh', '-c', 'legacy startup']
        with self.assertRaises(ComposeError):
            self.prepare()

    def test_symlink_source_and_existing_evidence_rejected(self):
        self.source.unlink()
        self.source.symlink_to(self.env)
        with self.assertRaises(ComposeError):
            self.prepare()
        self.source.unlink()
        self.source.write_text('fixture')
        self.prepare()
        with self.assertRaises(ComposeError):
            self.prepare()

    def test_compose_error_timeout_and_invalid_json_do_not_leak_secrets(self):
        for response in (SimpleNamespace(returncode=1, stdout=B.SECRET, stderr=B.SECRET),
                         subprocess.TimeoutExpired(['docker'], 120, output=B.SECRET),
                         SimpleNamespace(returncode=0, stdout=B.SECRET, stderr=B.SECRET)):
            self.response = response
            with self.assertRaises(ComposeError) as error:
                self.prepare()
            self.assertNotIn(B.SECRET, str(error.exception))

    def test_only_three_orphan_entries_removed_after_cold_snapshot(self):
        session = self.app / '.wwebjs_auth/session'
        session.mkdir()
        (session / 'tokens').write_text('AUTH_MUST_SURVIVE')
        outside = self.app / 'outside-socket'
        outside.write_text('DO_NOT_TOUCH')
        (session / 'SingletonSocket').symlink_to(outside)
        (session / 'SingletonLock').symlink_to('old-host-123')
        (session / 'SingletonCookie').write_text('orphan')
        profile = D.SessionProfile(self.app, self.state, os.getuid(), D.private_file, D.atomic_private)
        profile.snapshot('123', 'old', B.OLD_IMAGE)
        self.assertEqual(profile.remove_orphan_locks(), 3)
        self.assertEqual((session / 'tokens').read_text(), 'AUTH_MUST_SURVIVE')
        self.assertEqual(outside.read_text(), 'DO_NOT_TOUCH')
        profile.validated('123', 'old', B.OLD_IMAGE)
        self.assertTrue((self.state / 'auth-123/session/SingletonLock').is_symlink())
        self.assertEqual(profile.remove_orphan_locks(), 0)

    def test_unexpected_lock_directory_blocks_all_cleanup(self):
        session = self.app / '.wwebjs_auth/session'
        session.mkdir()
        (session / 'SingletonLock').write_text('untouched')
        (session / 'SingletonSocket').mkdir()
        profile = D.SessionProfile(self.app, self.state, os.getuid(), D.private_file, D.atomic_private)
        with self.assertRaises(ProfileError):
            profile.remove_orphan_locks()
        self.assertTrue((session / 'SingletonLock').exists())

    def test_active_host_browser_blocks_cleanup_and_process_is_not_killed(self):
        proc = self.app / 'proc'
        proc.mkdir()
        (proc / '123').mkdir()
        cmdline = proc / '123/cmdline'
        for path in (str(self.app / '.wwebjs_auth/session'), '/app/.wwebjs_auth/session'):
            cmdline.write_bytes(('chromium\0--user-data-dir=' + path + '\0').encode())
            with self.assertRaises(ProfileError):
                assert_profile_idle(self.app / '.wwebjs_auth', proc)
            self.assertTrue(cmdline.exists())
        cmdline.write_bytes(b'chromium\0--user-data-dir=/other/profile\0')
        assert_profile_idle(self.app / '.wwebjs_auth', proc)
        with self.assertRaises(ProfileError):
            assert_profile_idle(self.app / '.wwebjs_auth', proc / 'absent')

    def test_deployment_checks_idle_processes_before_unlinking_any_lock(self):
        session = self.app / '.wwebjs_auth/session'
        session.mkdir()
        lock = session / 'SingletonLock'
        lock.symlink_to('active-host-123')
        def active(auth):
            raise ProfileError('Profile writer is active')
        job = D.Deployment('odisseu', None, app_dir=self.app, state_dir=self.state,
                           process_guard=active)
        job.single_writer = lambda allowed: self.assertIsNone(allowed)
        with self.assertRaises(ProfileError):
            job.cold_locks()
        self.assertTrue(lock.is_symlink())

    def test_relative_browser_profile_uses_that_process_working_directory(self):
        proc = self.app / 'proc'
        (proc / '123').mkdir(parents=True)
        (proc / '123/cwd').symlink_to(self.app)
        (proc / '123/cmdline').write_bytes(b'chromium\0--user-data-dir\0.wwebjs_auth/session\0')
        with self.assertRaises(ProfileError):
            assert_profile_idle(self.app / '.wwebjs_auth', proc)

    def test_google_destination_fixed_and_existing_deploy_gates_preserved(self):
        workflow = Path(__file__).parents[1] / '.github/workflows/deploy.yml'
        text = workflow.read_text()
        self.assertEqual(text.count("SSH_HOST: '35.238.24.225'"), 2)
        self.assertEqual(text.count("SSH_USER: 'caiohclavico'"), 2)
        self.assertNotIn('129.148.52.187', text)
        self.assertIn("vars.AUTO_DEPLOY_ENABLED == 'true'", text)
        self.assertIn('refs/heads/master', text)
        self.assertIn('production', text)
        self.assertEqual(D.SERVICES['odisseu'][1], '/home/caiohclavico/zapbot')
        self.assertEqual(D.SERVICES['pokemon'][1], '/home/caiohclavico/pokemon-service')


if __name__ == '__main__':
    unittest.main()
