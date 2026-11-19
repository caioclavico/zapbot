#!/usr/bin/env python3
import subprocess
import unittest
from unittest.mock import patch

from a6_admin_backup_client import HELPER, backup, preflight
from a6_production_backup import ProductionBackupError


class AdminBackupClientTests(unittest.TestCase):
    def test_uses_only_root_owned_helper_with_sudo_noninteractive(self):
        completed = subprocess.CompletedProcess([], 0, '{"ready": true}\n', '')
        with patch('a6_admin_backup_client.subprocess.run', return_value=completed) as run:
            self.assertTrue(preflight('/var/backups/odisseu-a6/window-1/data')['ready'])
        self.assertEqual(run.call_args.args[0], ['sudo', '-n', HELPER, '--preflight', 'window-1'])

    def test_refuses_an_operator_selected_destination_and_sudo_failure(self):
        with self.assertRaisesRegex(ProductionBackupError, 'destination_invalid'):
            backup('/tmp/window-1/data')
        with patch('a6_admin_backup_client.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', '')):
            with self.assertRaisesRegex(ProductionBackupError, 'admin_unavailable'):
                backup('/var/backups/odisseu-a6/window-1/data')


if __name__ == '__main__':
    unittest.main(verbosity=2)
