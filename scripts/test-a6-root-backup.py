#!/usr/bin/env python3
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from a6_mount_policy import Collection, LIVE_VOLUME
from a6_production_contract import VM
from a6_production_backup import ProductionBackupError
import a6_root_backup as admin


class RootBackupTests(unittest.TestCase):
    def test_requires_root(self):
        with patch('os.geteuid', return_value=1000), self.assertRaisesRegex(ProductionBackupError, 'root_required'):
            admin.backup('test')

    @unittest.skipUnless(os.geteuid() == 0, 'ensaio root somente em container descartável')
    def test_root_volume_backup_and_isolated_restore(self):
        # /fixture é um volume Docker novo no harness, nunca o Datomic real.
        source = Path('/fixture')
        if not source.is_dir(): self.skipTest('volume descartável ausente')
        (source / 'save.edn').write_bytes(b'{:save :preserved}\n')
        (source / 'save.edn').chmod(0o600)
        self.assertEqual((source / 'save.edn').stat().st_uid, 0)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve(); root.chmod(0o700)
            docs = ({'Name': '/odisseu-a6-jvm', 'Id': 'a'*64,
                     'State': {'Running': False, 'Status': 'exited'}, 'CapEff': '0',
                     'HostConfig': {'Privileged': False, 'CapAdd': [], 'CapDrop': ['SYS_ADMIN']},
                     'Mounts': [{'Name': LIVE_VOLUME, 'Source': str(source), 'Type': 'volume', 'Destination': '/data/datomic-local', 'RW': True}]},)
            collection = Collection('docker-inspect-subprocess', docs, '', 'quiescent', 'draining', 0, 0, LIVE_VOLUME, 'odisseu-a6-jvm')
            with patch.object(admin, 'BACKUP_ROOT', root), patch.object(admin, 'DATOMIC_SOURCE', str(source)), \
                 patch('a6_production_backup.DATOMIC_SOURCE', str(source)), patch.object(admin, '_collect', return_value=(collection, dict(VM))):
                result = admin.backup('fixture')
            self.assertTrue(result['sourcePreserved'])
            self.assertTrue(result['manifestVerified'])
            self.assertTrue(result['restoreIsolated'])
            self.assertEqual((source / 'save.edn').read_bytes(), (root / 'fixture/restore-isolated/save.edn').read_bytes())
            self.assertTrue((root / 'fixture/backup-manifest.txt').is_file())

if __name__ == '__main__': unittest.main(verbosity=2)
