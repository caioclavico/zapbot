"""Recovery and cold-session tests; fake Engine, temporary profiles, no Docker."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('existing_deploy_tests', Path(__file__).with_name('test-deploy.py'))
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
D = BASE.DEPLOY
OLD, NEW = '1' * 64, '2' * 64
TX = '123456789'


class MemoryEngine:
    operation_deadline = None
    def __init__(self, app):
        sample = BASE.DockerModel('odisseu', app).original
        sample['Id'] = OLD
        sample['State'].update(Running=False, ExitCode=0, Pid=0)
        self.data = {OLD: sample}
        self.mutations, self.reads = [], []
        self.healthy, self.stop_error = True, False

    def inspect(self, identifier):
        self.reads.append(identifier)
        return copy.deepcopy(self.data[identifier])

    def containers(self):
        return [{'Id': c['Id'], 'Names': [c['Name']], 'Mounts': c['Mounts'],
                 'Labels': c['Config'].get('Labels'),
                 'State': 'running' if c['State']['Running'] else 'exited'} for c in self.data.values()]

    def active(self):
        return [c for c in self.containers() if c['State'] == 'running']

    def image(self, identifier):
        return {'Id': identifier, 'Os': 'linux', 'Architecture': 'amd64',
                'Config': {'Labels': {} if identifier == BASE.OLD_IMAGE else
                           {D.SERVICE_LABEL: 'odisseu', D.STORAGE_LABEL: '1', D.REVISION_LABEL: 'a' * 40}}}

    def stop(self, identifier, timeout):
        if self.stop_error:
            raise D.DeployError('Test stop was uncertain')
        self.mutations.append(('stop', identifier))
        if self.data[identifier]['State']['Running']:
            self.data[identifier]['State'].update(Running=False, ExitCode=0, Pid=0)

    def rename(self, identifier, name):
        self.mutations.append(('rename', identifier))
        self.data[identifier]['Name'] = '/' + name

    def start(self, identifier):
        if any(c['State']['Running'] for key, c in self.data.items() if key != identifier):
            raise AssertionError('Two writers would become active')
        self.mutations.append(('start', identifier))
        self.data[identifier]['State'].update(Running=True, Pid=123)

    def logs(self, identifier, started):
        return b'Conectado ao Cassandra; estado particionado carregado'

    def probe(self, identifier, service):
        return self.healthy


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='zapbot-recovery-', dir='/tmp')
        root = Path(self.temp.name)
        self.app, self.state, self.config = root / 'app', root / 'state', root / 'config'
        for path in (self.app, self.state, self.config, self.app / 'data', self.app / '.wwebjs_auth'):
            path.mkdir(mode=0o700)
        (self.app / '.env').write_text('SECRET_KEEP_PRIVATE=true\n')
        (self.app / '.env').chmod(0o600)
        (self.app / 'data' / 'players').write_text('DO_NOT_MODIFY')
        (self.app / '.wwebjs_auth' / 'session').write_text('ORIGINAL_AUTH')
        (self.config / 'persistence-version').write_text('1\n')
        (self.config / 'persistence-version').chmod(0o600)
        self.engine, self.clock, self.logs = MemoryEngine(self.app), BASE.Clock(), []
        self.deployment = self.instance()
        d = self.deployment
        d.previous = self.engine.inspect(OLD)
        self.engine.data[OLD]['Name'] = '/zapbot-previous-' + TX
        d.transaction_name = TX
        d.original_state = {'last-good-image': BASE.OLD_IMAGE, 'last-good-image-id': BASE.OLD_IMAGE,
                            'previous-image': '', 'previous-image-id': '', 'previous-container': None, 'deployed-revision': ''}
        d.record(d.original_state)
        d.requested_image, d.requested_revision, d.new_id = BASE.NEW_IMAGE, 'a' * 40, NEW
        d.auth_backup = d.profile.snapshot(TX, OLD, BASE.OLD_IMAGE)
        configuration = d.clone(d.previous, self.engine.image(BASE.NEW_IMAGE))
        new = copy.deepcopy(d.previous)
        new.update(Id=NEW, Image=BASE.NEW_IMAGE, Name='/zapbot')
        new['Config'] = {key: value for key, value in configuration.items() if key not in ('HostConfig', 'NetworkingConfig')}
        new['HostConfig'] = configuration['HostConfig']
        new['State'].update(Running=True, ExitCode=0, Pid=456)
        self.engine.data[NEW] = new
        d.new_start_attempted = True
        d.transaction('replacement-started')
        (self.app / '.wwebjs_auth' / 'session').write_text('CANDIDATE_MODIFIED_AUTH')

    def tearDown(self):
        self.temp.cleanup()

    def instance(self):
        return D.Deployment('odisseu', self.engine, app_dir=self.app, state_dir=self.state,
                            config_dir=self.config, owner_uid=os.getuid(), timeout=6, stable_seconds=2,
                            poll_seconds=1, clock=self.clock.now, sleep=self.clock.sleep, output=self.logs.append,
                            compose=BASE.FakeCompose(), process_guard=lambda auth: None)

    def rewrite(self, **values):
        path = self.state / 'transaction.json'
        data = json.loads(path.read_text())
        data.update(values)
        D.atomic_private(path, json.dumps(data))

    def preserved(self):
        self.assertEqual((self.app / '.env').read_text(), 'SECRET_KEEP_PRIVATE=true\n')
        self.assertEqual((self.app / 'data' / 'players').read_text(), 'DO_NOT_MODIFY')
        self.assertNotIn('SECRET_KEEP_PRIVATE', '\n'.join(self.logs))

    def test_interrupted_candidate_restores_cold_auth_before_start_and_keeps_evidence(self):
        self.instance().run('recover')
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'ORIGINAL_AUTH')
        failed = self.app / '.zapbot-auth-recovery' / (TX + '-failed') / 'session'
        self.assertEqual(failed.read_text(), 'CANDIDATE_MODIFIED_AUTH')
        self.assertTrue(self.engine.data[OLD]['State']['Running'])
        self.assertFalse(self.engine.data[NEW]['State']['Running'])
        self.assertFalse((self.state / 'transaction.json').exists())
        archived = self.state / ('transaction.' + TX + '.rolled-back.json')
        self.assertTrue(json.loads(archived.read_text())['auth-restored'])
        self.preserved()

    def test_recovery_is_idempotent_after_success_and_does_not_overwrite_old_evidence(self):
        legacy = self.state / 'transaction.recovered.json'
        legacy.write_text('OLD_OPERATOR_EVIDENCE')
        self.instance().run('recover')
        count = len(self.engine.mutations)
        self.instance().run('recover')
        self.assertEqual(len(self.engine.mutations), count)
        self.assertEqual(legacy.read_text(), 'OLD_OPERATOR_EVIDENCE')

    def test_recovery_rejects_always_restart_writer_without_stopping_it_or_archiving(self):
        self.engine.data[OLD]['HostConfig']['RestartPolicy']['Name'] = 'always'
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        self.assertTrue((self.state / 'transaction.json').exists())
        self.preserved()

    def test_restore_interrupted_between_renames_resumes_without_deleting_profiles(self):
        self.engine.data[NEW]['State'].update(Running=False, Pid=0)
        self.engine.data[NEW]['Name'] = '/zapbot-failed-' + TX
        def phase(name):
            self.deployment.transaction(name)
            if name == 'profile-original-archived':
                raise KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.deployment.profile.restore(TX, OLD, BASE.OLD_IMAGE, phase)
        self.assertFalse((self.app / '.wwebjs_auth').exists())
        self.instance().run('recover')
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'ORIGINAL_AUTH')
        self.preserved()

    def test_recovery_after_old_started_never_restores_over_a_live_profile(self):
        self.engine.data[NEW]['State'].update(Running=False, Pid=0)
        self.engine.data[NEW]['Name'] = '/zapbot-failed-' + TX
        self.deployment.profile.restore(TX, OLD, BASE.OLD_IMAGE, self.deployment.transaction)
        self.engine.data[OLD]['Name'] = '/zapbot'
        self.engine.data[OLD]['State'].update(Running=True, Pid=123)
        (self.app / '.wwebjs_auth' / 'session').write_text('LIVE_AUTH_AFTER_RESTART')
        self.instance().run('recover')
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'LIVE_AUTH_AFTER_RESTART')
        self.assertNotIn(('start', OLD), self.engine.mutations)

    def test_verified_commit_recovery_checks_health_without_touching_containers_or_auth(self):
        self.deployment.record({'last-good-image': BASE.NEW_IMAGE, 'last-good-image-id': BASE.NEW_IMAGE,
            'previous-image': BASE.OLD_IMAGE, 'previous-image-id': BASE.OLD_IMAGE,
            'previous-container': OLD, 'deployed-revision': 'a' * 40})
        self.deployment.transaction('committed')
        self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'CANDIDATE_MODIFIED_AUTH')
        self.assertTrue((self.state / ('transaction.' + TX + '.committed.json')).exists())

    def test_committed_but_unhealthy_does_not_clear_evidence_or_claim_success(self):
        self.deployment.record({'last-good-image': BASE.NEW_IMAGE, 'last-good-image-id': BASE.NEW_IMAGE,
            'previous-image': BASE.OLD_IMAGE, 'previous-image-id': BASE.OLD_IMAGE,
            'previous-container': OLD, 'deployed-revision': 'a' * 40})
        self.deployment.transaction('committed')
        self.engine.healthy = False
        with self.assertRaises(D.DeployError):
            self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        self.assertTrue((self.state / 'transaction.json').exists())

    def test_invalid_ids_images_names_or_labels_are_rejected_before_mutation(self):
        cases = ['short-id', 'wrong-image', 'wrong-label', 'wrong-name', 'wrong-returned-id', 'missing-id']
        for case in cases:
            with self.subTest(case=case):
                original_data = copy.deepcopy(self.engine.data)
                original_journal = (self.state / 'transaction.json').read_text()
                if case == 'short-id': self.rewrite(**{'original-container': '1' * 12})
                if case == 'wrong-image': self.engine.data[NEW]['Image'] = BASE.OLD_IMAGE
                if case == 'wrong-label': self.engine.data[NEW]['Config']['Labels'][D.DEPLOYMENT_LABEL] = '999'
                if case == 'wrong-name': self.engine.data[OLD]['Name'] = '/another-operator-container'
                if case == 'wrong-returned-id': self.engine.data[OLD]['Id'] = '3' * 64
                if case == 'missing-id': del self.engine.data[NEW]
                with self.assertRaises(D.DeployError): self.instance().run('recover')
                self.assertEqual(self.engine.mutations, [])
                self.engine.data = original_data
                D.atomic_private(self.state / 'transaction.json', original_journal)

    def test_two_running_writers_or_parent_bind_blocks_recovery(self):
        self.engine.data[OLD]['State'].update(Running=True, Pid=123)
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        self.engine.data[OLD]['State'].update(Running=False, Pid=0)
        outsider = copy.deepcopy(self.engine.data[NEW])
        outsider.update(Id='3' * 64, Name='/unrelated')
        outsider['Config']['Labels'] = {}
        outsider['Mounts'] = [{'Type': 'bind', 'Source': str(self.app), 'Destination': '/elsewhere'}]
        self.engine.data[outsider['Id']] = outsider
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])

    def test_uncertain_stop_or_unclean_exit_never_starts_old_or_restores_auth(self):
        self.engine.stop_error = True
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        self.engine.stop_error = False
        self.engine.data[NEW]['State'].update(Running=False, Pid=0, ExitCode=137)
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertNotIn(('start', OLD), self.engine.mutations)
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'CANDIDATE_MODIFIED_AUTH')

    def test_corrupt_snapshot_and_legacy_record_require_review_without_mutation(self):
        snapshot = self.state / ('auth-' + TX) / 'session'
        snapshot.write_text('CORRUPT')
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])
        D.atomic_private(self.state / 'transaction.json', '{"phase":"prepared"}')
        with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertEqual(self.engine.mutations, [])

    def test_snapshot_preserves_symlinks_without_reading_targets_and_file_permissions(self):
        other = self.app / 'private-outside'
        other.write_text('OUTSIDE_NOT_COPIED')
        auth = self.app / '.wwebjs_auth'
        (auth / 'SingletonSocket').symlink_to(other)
        (auth / 'session').chmod(0o640)
        tx = '987654321'
        self.deployment.profile.snapshot(tx, OLD, BASE.OLD_IMAGE)
        copied = self.state / ('auth-' + tx)
        self.assertTrue((copied / 'SingletonSocket').is_symlink())
        self.assertEqual((copied / 'session').stat().st_mode & 0o777, 0o640)
        self.assertNotIn('OUTSIDE_NOT_COPIED', (self.state / ('auth-' + tx + '.json')).read_text())

    def test_incomplete_restore_copy_is_archived_and_not_deleted(self):
        evidence = self.app / '.zapbot-auth-recovery'
        evidence.mkdir(mode=0o700)
        staged = evidence / (TX + '-restoring')
        staged.mkdir();(staged / 'partial').write_text('PARTIAL_EVIDENCE')
        self.instance().run('recover')
        copies = list(evidence.glob(TX + '-incomplete-*'))
        self.assertEqual(len(copies), 1)
        self.assertEqual((copies[0] / 'partial').read_text(), 'PARTIAL_EVIDENCE')

    def test_low_disk_space_blocks_snapshot_without_touching_original(self):
        with patch('deploy_auth_profile.shutil.disk_usage') as usage:
            usage.return_value.free = 0
            with self.assertRaises(D.ProfileError):
                self.deployment.profile.snapshot('777', OLD, BASE.OLD_IMAGE)
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'CANDIDATE_MODIFIED_AUTH')

    def test_recovery_after_completed_restore_and_later_stop_keeps_updated_auth(self):
        self.engine.data[NEW]['State'].update(Running=False, Pid=0)
        self.engine.data[NEW]['Name'] = '/zapbot-failed-' + TX
        self.deployment.profile.restore(TX, OLD, BASE.OLD_IMAGE, self.deployment.transaction)
        (self.app / '.wwebjs_auth' / 'session').write_text('UPDATED_AFTER_RESTORE')
        self.instance().run('recover')
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'UPDATED_AFTER_RESTORE')

    def test_archive_written_before_interruption_is_reused_idempotently(self):
        def interrupted_archive(deployment, outcome):
            os.link(self.state / 'transaction.json', self.state / ('transaction.' + TX + '.' + outcome + '.json'))
            raise KeyboardInterrupt()
        with patch.object(D.Deployment, 'archive_transaction', interrupted_archive):
            with self.assertRaises(D.DeployError): self.instance().run('recover')
        self.assertTrue((self.state / 'transaction.json').exists())
        self.instance().run('recover')
        self.assertFalse((self.state / 'transaction.json').exists())
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'ORIGINAL_AUTH')

    def test_internal_hard_links_survive_snapshot_and_external_links_fail_closed(self):
        auth = self.app / '.wwebjs_auth'
        os.link(auth / 'session', auth / 'linked-session')
        self.deployment.profile.snapshot('888', OLD, BASE.OLD_IMAGE)
        copied = self.state / 'auth-888'
        self.assertEqual((copied / 'session').stat().st_ino, (copied / 'linked-session').stat().st_ino)
        os.link(auth / 'session', self.app / 'external-link')
        with self.assertRaises(D.ProfileError):
            self.deployment.profile.snapshot('999', OLD, BASE.OLD_IMAGE)


if __name__ == '__main__':
    unittest.main()
