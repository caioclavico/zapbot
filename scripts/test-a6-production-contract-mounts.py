"""Regression tests for Docker inspect-style A6 named volume mounts."""

import importlib.util
import pathlib
import unittest

MODULE_PATH = pathlib.Path(__file__).with_name("a6_production_contract.py")
spec = importlib.util.spec_from_file_location("a6_production_contract", MODULE_PATH)
contract = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract)


class MountContractTests(unittest.TestCase):
    def test_jvm_inspect_style_named_volumes(self):
        live = [dict(mount, source=f"/var/lib/docker/volumes/{mount['name']}/_data")
                if mount["type"] == "volume" else dict(mount)
                for mount in contract.JVM_MOUNTS]
        self.assertTrue(contract.mounts_match(live, contract.JVM_MOUNTS))

    def test_node_inspect_style_named_volume(self):
        live = [dict(mount, source=f"/var/lib/docker/volumes/{mount['name']}/_data")
                if mount["type"] == "volume" else dict(mount)
                for mount in contract.NODE_MOUNTS]
        self.assertTrue(contract.mounts_match(live, contract.NODE_MOUNTS))

    def test_bind_source_drift_rejected(self):
        live = [dict(mount) for mount in contract.NODE_MOUNTS]
        live[0]["source"] = "/unexpected/session"
        self.assertFalse(contract.mounts_match(live, contract.NODE_MOUNTS))

    def test_named_volume_name_drift_rejected(self):
        live = [dict(mount) for mount in contract.JVM_MOUNTS]
        live[0]["name"] = "unexpected-volume"
        self.assertFalse(contract.mounts_match(live, contract.JVM_MOUNTS))

    def test_destination_and_rw_drift_rejected(self):
        for field, value in (("destination", "/unexpected"), ("rw", False)):
            with self.subTest(field=field):
                live = [dict(mount) for mount in contract.NODE_MOUNTS]
                live[1][field] = value
                self.assertFalse(contract.mounts_match(live, contract.NODE_MOUNTS))


if __name__ == "__main__":
    unittest.main()
