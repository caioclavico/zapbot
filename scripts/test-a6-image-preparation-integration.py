"""Imagens reais, Docker local do CI e endpoints oficiais; nenhum WhatsApp."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest

from a6_image_transfer import import_artifacts, validate_artifacts
from a6_production_deploy import image_tag, DeployError
import a6_post_v3_backup as post
from types import SimpleNamespace


def docker(*args, **kwargs):
    return subprocess.run(["docker", *args], capture_output=True, text=True,
                          timeout=180, **kwargs)


@unittest.skipUnless(os.environ.get("GITHUB_ACTIONS") == "true"
                     and os.environ.get("A6_IMAGE_PREPARATION_TEST") == "yes",
                     "Docker isolado do GitHub Actions")
class ImagePreparationTest(unittest.TestCase):
    def test_pair_transport_import_and_official_http(self):
        self.assertFalse(os.environ.get("DOCKER_HOST"))
        sha = os.environ["SOURCE_SHA"]
        source = Path(os.environ["A6_IMAGE_ARTIFACTS"]).resolve()
        hashes = {s: hashlib.sha256((source / (s + ".json")).read_bytes()).hexdigest()
                  for s in ("node", "jvm")}
        rows = validate_artifacts(source, sha, hashes)
        rollback = os.environ["A6_POST_V3_ROLLBACK_IMAGE"]
        docker("build", "-t", "a6-fixture-retained-rollback", "-", check=True,
               input="FROM a6-ci-rollback\nLABEL io.odisseu.fixture=retained\n")
        retained = docker("image", "inspect", "--format", "{{.Id}}",
                          "a6-fixture-retained-rollback", check=True).stdout.strip()
        # Remover somente os candidatos deste runner descartável. Assim load
        # não pode passar porque a imagem original continua no image store.
        candidate = next(v for v in rows if v["service"] == "jvm")
        docker("image", "rm", "a6-ci-image", image_tag("jvm", sha), check=True)
        self.assertNotEqual(docker("image", "inspect", candidate["sourceImageId"]).returncode, 0)
        self.assertNotEqual(docker("image", "inspect", image_tag("node", sha)).returncode, 0)
        with tempfile.TemporaryDirectory(prefix="a6-transfer-") as temporary:
            destination = Path(temporary).resolve()
            for service in ("node", "jvm"):
                for suffix in (".json", ".tar.gz"):
                    shutil.copyfile(source / (service + suffix), destination / (service + suffix))
            result = import_artifacts(destination, sha, hashes, 114586680,
                [rollback, retained], os.environ["ROLLBACK_SHA"], authorize=True)
            self.assertTrue(result["rollbackPreserved"])
            self.assertEqual(len(result["loaded"]), 2)
            print("TRANSPORT_EVIDENCE", json.dumps(result, sort_keys=True))
            (destination / "node.tar.gz").write_bytes(b"corrupted")
            with self.assertRaises(DeployError) as blocked:
                validate_artifacts(destination, sha, hashes)
            self.assertIn("artifact_mismatch", str(blocked.exception))
        self.assertEqual(docker("ps", "-a", "--filter", "name=odisseu-a6-", "--format",
                                "{{.Names}}", check=True).stdout.strip(), "")
        ids = []
        try:
            node = docker("run", "-d", "--name", "odisseu-a6-node", "--network=none",
                          "--cap-drop=ALL", "--security-opt=no-new-privileges",
                          "-e", "WHATSAPP_TRANSPORT=disabled",
                          "-e", "ODISSEU_NODE_MAINTENANCE_FILE=/app/data/a6-maintenance.json",
                          "-e", "ODISSEU_NODE_MAINTENANCE_TOKEN=fixture-token",
                          image_tag("node", sha), check=True).stdout.strip()
            ids.append(node)
            program = """
              (require '[odisseu.persistence.datomic :as p]
                       '[pokemon.infrastructure.datomic.store :as s]
                       '[pokemon.infrastructure.datomic.schema :as schema]
                       '[pokemon.infrastructure.http.bridge :as bridge])
              (let [client (p/client {:system "http-fixture" :storage-dir "/tmp/http-datomic"})
                    conn (p/ensure-db! client "fixture")]
                (schema/install! conn)
                (bridge/start! {:store (s/make-store conn) :port 18090 :token "fixture"
                  :maintenance-dir "/tmp/maintenance"
                  :maintenance-token (System/getenv "ODISSEU_BRIDGE_MAINTENANCE_TOKEN")}))
              @(promise)
            """
            jvm = docker("run", "-d", "--name", "odisseu-a6-jvm", "--network=none",
                         "--cap-drop=ALL", "--security-opt=no-new-privileges",
                         "-e", "ODISSEU_BRIDGE_MAINTENANCE_TOKEN=fixture-token",
                         "--entrypoint=clojure", image_tag("jvm", sha),
                         "-M", "-e", program, check=True).stdout.strip()
            ids.append(jvm)
            admin = SimpleNamespace(_run=lambda args: subprocess.run(
                args, capture_output=True, text=True, timeout=20))
            for service in ("node", "jvm"):
                deadline = time.monotonic() + 120
                while True:
                    try:
                        status = post._http(admin, service, "/ops/maintenance/status")
                        break
                    except post.ProductionBackupError:
                        if time.monotonic() >= deadline:
                            self.fail("maintenance_endpoint_not_ready:" + service)
                        time.sleep(1)
                self.assertEqual(status["phase"], "open")
            self.assertNotEqual(docker("exec", "odisseu-a6-node", "sh", "-c",
                                       "command -v curl").returncode, 0)
            self.assertEqual(docker("exec", "odisseu-a6-node", "sh", "-c",
                                    "test ! -e /app/.wwebjs_auth/session").returncode, 0)
            print("HTTP_EVIDENCE Node/JVM oficiais PASS; WhatsApp disabled, sem READY")
        finally:
            for identity in ids:
                docker("rm", "-f", "-v", identity, check=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
