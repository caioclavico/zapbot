#!/usr/bin/env python3
"""Teste em processos separados, somente com Datomic temporário."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
POKEMON_ID = "abb8955f-7ebe-4084-a634-fd74cfa9bf62"


class PostV3RestoreTest(unittest.TestCase):
    def test_cold_copy_has_same_audit_in_independent_jvm(self):
        with tempfile.TemporaryDirectory(prefix="a6-post-v3-test-") as directory:
            root = Path(directory).resolve()
            source = root / "source"
            source.mkdir()
            program = """
              (require 'pokemon.tests.xp-reconciliation-cas-a6-test
                       'pokemon.infrastructure.datomic.xp-reconciliation
                       'pokemon.infrastructure.http.json)
              (let [c (#'pokemon.tests.xp-reconciliation-cas-a6-test/seeded)
                    id "abb8955f-7ebe-4084-a634-fd74cfa9bf62"
                    p (pokemon.infrastructure.datomic.xp-reconciliation/preview c id id)]
                (pokemon.infrastructure.datomic.xp-reconciliation/reconcile! c id id (:snapshot-sha256 p))
                (println "AUDIT_JSON"
                  (pokemon.infrastructure.http.json/write-str
                    (pokemon.infrastructure.datomic.xp-reconciliation/audit-post-v3 c id id)))
                (shutdown-agents))
            """
            env = dict(os.environ, JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=" + str(source))
            created = subprocess.run(["clojure", "-M", "-e", program], cwd=ROOT, env=env,
                                     text=True, capture_output=True, timeout=180, check=True)
            lines = [line.removeprefix("AUDIT_JSON ") for line in created.stdout.splitlines()
                     if line.startswith("AUDIT_JSON ")]
            self.assertEqual(len(lines), 1, created.stdout)
            original = json.loads(lines[0])
            self.assertEqual(original["status"], "PASS")
            systems = [item for item in source.iterdir()
                       if item.is_dir() and item.name.startswith("a6-reconcile-")]
            self.assertEqual(len(systems), 1)
            restored = root / "restored"
            shutil.copytree(systems[0], restored)
            expression = '(load-file "scripts/a6_post_v3_restore_audit.clj") (apply a6.post-v3-restore-audit/-main *command-line-args*) (System/exit 0)'
            audited = subprocess.run(["clojure", "-M", "-e", expression, "--",
                                      str(restored), systems[0].name, "a6-recon", POKEMON_ID],
                                     cwd=ROOT, text=True, capture_output=True, timeout=180)
            self.assertEqual(audited.returncode, 0, audited.stdout + audited.stderr)
            after = json.loads(audited.stdout.splitlines()[-1])
            self.assertEqual(after, original)
            missing = subprocess.run(["clojure", "-M", "-e", expression, "--",
                                      str(restored), systems[0].name, "missing", POKEMON_ID],
                                     cwd=ROOT, text=True, capture_output=True, timeout=180)
            self.assertNotEqual(missing.returncode, 0)
            self.assertIn("restore_audit_database_missing", missing.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
