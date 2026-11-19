#!/usr/bin/env python3
import os
import io
import json
from contextlib import redirect_stdout
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
                output = io.StringIO()
                with redirect_stdout(output):
                    self.assertEqual(admin.cli(['--backup', 'fixture']), 0)
                result = json.loads(output.getvalue())
                # A JVM nova pode estar ativa e o volume pode avançar: status
                # valida o artefato concluído, sem chamar _collect nem comparar source.
                (source / 'save.edn').write_bytes(b'{:player "ada" :coins 41}\n')
                output = io.StringIO()
                with patch.object(admin, '_collect', side_effect=AssertionError('status não coleta JVM')):
                    with redirect_stdout(output):
                        self.assertEqual(admin.cli(['--status', 'fixture']), 0)
                observed = json.loads(output.getvalue())
                self.assertTrue(observed['reused'])
                self.assertEqual(observed['files'], result['files'])
            self.assertTrue(result['sourcePreserved'])
            self.assertTrue(result['manifestVerified'])
            self.assertTrue(result['restoreIsolated'])
            self.assertEqual(result['window'], 'fixture')
            self.assertNotEqual((source / 'save.edn').read_bytes(), (root / 'fixture/restore-isolated/save.edn').read_bytes())
            self.assertTrue((root / 'fixture/backup-manifest.txt').is_file())

            data = root / 'fixture/data/save.edn'
            restore = root / 'fixture/restore-isolated/save.edn'
            manifest = root / 'fixture/backup-manifest.txt'
            originals = {path: path.read_bytes() for path in (data, restore, manifest)}
            with patch.object(admin, 'BACKUP_ROOT', root), patch.object(admin, 'DATOMIC_SOURCE', str(source)), \
                 patch('a6_production_backup.DATOMIC_SOURCE', str(source)), patch.object(admin, '_collect', side_effect=AssertionError('status não coleta JVM')):
                for tampered in (data, restore, manifest):
                    tampered.write_bytes(b'tampered\n')
                    with self.assertRaises(ProductionBackupError):
                        # status direto preserva o motivo fechado para artefato adulterado.
                        admin.status('fixture')
                    tampered.write_bytes(originals[tampered])
                with self.assertRaises(ProductionBackupError):
                    admin.status('different-window')
                with patch.object(admin, 'DATOMIC_VOLUME', 'wrong-volume'):
                    with self.assertRaises(ProductionBackupError):
                        admin.status('fixture')

if __name__ == '__main__': unittest.main(verbosity=2)
