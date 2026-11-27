"""Capacidade e integridade dos artefatos, sem Docker ou produção."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import io
import gzip
import tarfile

from a6_capacity import budget
from a6_image_transfer import validate_artifacts, runtime_contract, image_storage_bytes
from a6_production_deploy import DeployError, image_tag
import a6_post_v3_backup as post
from a6_maintenance_ops import request_timeout_ms
from types import SimpleNamespace


class PreparationTests(unittest.TestCase):
    def test_transport_deadline_does_not_abort_a_legitimate_drain(self):
        for server_deadline in (30000, 60000, 900000):
            self.assertGreater(request_timeout_ms(
                "/ops/maintenance/enter?timeoutMs=" + str(server_deadline)), server_deadline)
        self.assertLess(request_timeout_ms("/ops/maintenance/status"), 30000)
        with self.assertRaises(DeployError):
            request_timeout_ms("/ops/maintenance/enter?timeoutMs=unknown")

    def test_capacity_includes_expanded_oci_layers_not_only_docker_size(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "image.tar"
            layer = gzip.compress(b"x" * (1024 * 1024))
            with tarfile.open(archive, "w") as out:
                member = tarfile.TarInfo("blobs/sha256/fixture")
                member.size = len(layer)
                out.addfile(member, io.BytesIO(layer))
            self.assertGreaterEqual(image_storage_bytes(archive, len(layer)),
                                    1024 * 1024 + len(layer))

    def test_exact_capacity_threshold_and_invalid_measurements(self):
        required = budget(10**12, 114586680, 900000000, 2400000000)["requiredBytes"]
        self.assertTrue(budget(required, 114586680, 900000000, 2400000000)["sufficient"])
        with self.assertRaisesRegex(DeployError, "insufficient_operational_space"):
            budget(required - 1, 114586680, 900000000, 2400000000)
        for invalid in (True, -1, None, "100"):
            with self.assertRaisesRegex(DeployError, "capacity_measurement_invalid"):
                budget(invalid, 1)

    def test_corrupt_mixed_revision_and_wrong_contract_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            sha = "a" * 40
            values = {}
            for service in ("node", "jvm"):
                data = service.encode()
                (root / (service + ".tar.gz")).write_bytes(data)
                values[service] = {
                    "schema": 1, "service": service, "revision": sha, "platform": "linux/amd64",
                    "ref": image_tag(service, sha), "archive": service + ".tar.gz",
                    "archiveSha256": hashlib.sha256(data).hexdigest(), "archiveBytes": len(data),
                    "imageBytes": 100, "runtimeContract": runtime_contract(service)}
                values[service].update(sourceImageId="sha256:" + "a" * 64, contentSha256="b" * 64)
            def write():
                hashes = {}
                for service, value in values.items():
                    p = root / (service + ".json")
                    p.write_text(json.dumps(value))
                    hashes[service] = hashlib.sha256(p.read_bytes()).hexdigest()
                return hashes
            self.assertEqual(len(validate_artifacts(root, sha, write())), 2)
            for key, bad in (("revision", "b" * 40), ("platform", "linux/arm64"),
                             ("runtimeContract", {}), ("ref", "foreign:latest")):
                original = values["node"][key]
                values["node"][key] = bad
                with self.assertRaises(DeployError):
                    validate_artifacts(root, sha, write())
                values["node"][key] = original
            hashes = write()
            (root / "node.tar.gz").write_bytes(b"corrupt")
            with self.assertRaises(DeployError):
                validate_artifacts(root, sha, hashes)

    def test_http_failures_never_accept_invalid_status_or_json(self):
        for output, exit_code in (('{"phase":"open"}\n401', 0), ("not-json\n200", 0),
                                  ('{"ok":true,"data":[]}\n200', 0), ("", 127)):
            admin = SimpleNamespace(_run=lambda _: SimpleNamespace(stdout=output, returncode=exit_code))
            with self.assertRaises(post.ProductionBackupError):
                post._http(admin, "jvm", "/ops/maintenance/status")


if __name__ == "__main__":
    unittest.main(verbosity=2)
