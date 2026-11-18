#!/usr/bin/env python3
"""O resolvedor da VM só aceita projeto, zona e nome, com gcloud simulado."""

import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "resolve-gce-instance.sh"


class ResolveTests(unittest.TestCase):
    def test_missing_identity_fails_closed(self):
        result = subprocess.run(["bash", str(SCRIPT)], check=False, capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn("Nenhum IP fixo", result.stderr)

    def test_ip_shaped_name_is_refused(self):
        env = os.environ.copy()
        env.update({
            "ODISSEU_GCP_PROJECT": "lab",
            "ODISSEU_GCP_ZONE": "us-central1-a",
            "ODISSEU_GCP_INSTANCE": "35.238.24.225",
        })
        result = subprocess.run(["bash", str(SCRIPT)], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 2)
        self.assertIn("não podem ser um endereço IP", result.stderr)

    def test_fake_gcloud_resolves_without_a_fixed_address_in_the_script(self):
        text = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn("35.238.24.225", text)
        self.assertNotIn("35.192.113.146", text)
        with tempfile.TemporaryDirectory() as tmp:
            binary = Path(tmp) / "gcloud"
            binary.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' '192.0.2.20'\n",
                encoding="utf-8",
            )
            binary.chmod(binary.stat().st_mode | stat.S_IEXEC)
            env = os.environ.copy()
            env.update({
                "PATH": f"{tmp}:{env.get('PATH', '')}",
                "ODISSEU_GCP_PROJECT": "lab",
                "ODISSEU_GCP_ZONE": "us-central1-a",
                "ODISSEU_GCP_INSTANCE": "odisseu-lab",
            })
            result = subprocess.run(["bash", str(SCRIPT)], check=False, capture_output=True, text=True, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "host=192.0.2.20")


if __name__ == "__main__":
    unittest.main(verbosity=2)
