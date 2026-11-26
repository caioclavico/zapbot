#!/usr/bin/env python3
import subprocess
import unittest
from unittest.mock import patch

from a6_admin_backup_client import HELPER, backup, preflight, status
from a6_production_backup import ProductionBackupError


class AdminBackupClientTests(unittest.TestCase):
    def test_uses_only_root_owned_helper_with_sudo_noninteractive(self):
        completed = subprocess.CompletedProcess([], 0, '{"ready": true, "window": "window-1", "operation": "preflight"}\n', '')
        with patch('a6_admin_backup_client.subprocess.run', return_value=completed) as run:
            self.assertTrue(preflight('/var/backups/odisseu-a6/window-1/data')['ready'])
        self.assertEqual(run.call_args.args[0], ['sudo', '-n', HELPER, '--preflight', 'window-1'])

    def test_refuses_an_operator_selected_destination_and_sudo_failure(self):
        with self.assertRaisesRegex(ProductionBackupError, 'destination_invalid'):
            backup('/tmp/window-1/data')
        with patch('a6_admin_backup_client.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', '')):
            with self.assertRaisesRegex(ProductionBackupError, 'admin_unavailable'):
                backup('/var/backups/odisseu-a6/window-1/data')

    def test_accepts_only_a_complete_helper_attestation(self):
        receipt = ('{"window":"window-1","operation":"status","reused":true,'
                   '"manifestVerified":true,"sourcePreserved":true,"restoreIsolated":true,'
                   '"volume":"odisseu-a6-cutover-datomic","files":2}\n')
        with patch('a6_admin_backup_client.subprocess.run', return_value=subprocess.CompletedProcess([], 0, receipt, '')) as run:
            self.assertTrue(status('/var/backups/odisseu-a6/window-1/data')['reused'])
        self.assertEqual(run.call_args.args[0], ['sudo', '-n', HELPER, '--status', 'window-1'])
        with patch('a6_admin_backup_client.subprocess.run', return_value=subprocess.CompletedProcess([], 0, '{}\n', '')):
            with self.assertRaisesRegex(ProductionBackupError, 'admin_invalid'):
                backup('/var/backups/odisseu-a6/window-1/data')

    def test_rejects_malformed_manifest_hash_from_helper(self):
        receipt = ('{"window":"window-1","operation":"status",'
                   '"manifestVerified":true,"sourcePreserved":true,"restoreIsolated":true,'
                   '"volume":"odisseu-a6-cutover-datomic","files":2,"manifestSha256":"invalid"}')
        with patch('a6_admin_backup_client.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 0, receipt, '')):
            with self.assertRaisesRegex(ProductionBackupError, 'admin_invalid'):
                status('/var/backups/odisseu-a6/window-1/data')

    def test_surfaces_only_sanitized_operational_reason(self):
        with patch('a6_admin_backup_client.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 2, '', 'backup_admin_blocked post_v3_proof_mismatch\n')):
            with self.assertRaisesRegex(ProductionBackupError, '^post_v3_proof_mismatch$'):
                status('/var/backups/odisseu-a6/window-1/data')
        with patch('a6_admin_backup_client.subprocess.run',
                   return_value=subprocess.CompletedProcess([], 2, '', 'backup_admin_blocked /private/save')):
            with self.assertRaisesRegex(ProductionBackupError, '^backup_admin_unavailable$'):
                status('/var/backups/odisseu-a6/window-1/data')


if __name__ == '__main__':
    unittest.main(verbosity=2)
