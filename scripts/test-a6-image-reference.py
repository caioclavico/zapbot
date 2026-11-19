#!/usr/bin/env python3
"""Referência real de imagem num daemon Docker descartável (opcional).

Só roda com A6_DIND_CONTAINER apontando para um container docker-in-docker
cujo nome começa com a6sup. Nunca fala com o daemon de produção. As imagens
candidatas precisam estar carregadas nele via docker save/load.
"""

import os
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from a6_production_deploy import image_ref, image_tag  # noqa: E402

DIND = os.environ.get("A6_DIND_CONTAINER", "")
SHA = os.environ.get("A6_DIND_SHA", "faedebd5036124eb4f946147c5012c48122acc69")
TEMP = "a6sup-ref-%d" % os.getpid()


def docker(*args, stdin=None):
    return subprocess.run(
        ["docker", "exec", "-i", DIND, "docker", *args],
        check=False, capture_output=True, text=True, input=stdin, timeout=600,
    )


def _usable():
    return DIND.startswith("a6sup") and "cutover" not in DIND


@unittest.skipUnless(_usable(), "defina A6_DIND_CONTAINER=a6sup-... para provar a referência num daemon descartável")
class ImageReferenceTests(unittest.TestCase):
    created = []

    @classmethod
    def tearDownClass(cls):
        for name in cls.created:
            docker("rm", "-f", name)
        docker("image", "rm", TEMP + ":movida")

    def _create(self, ref, suffix, *extra):
        name = "%s-%s" % (TEMP, suffix)
        result = docker("create", "--pull", "never", "--name", name, *extra, ref)
        if result.returncode == 0:
            self.created.append(name)
        return name, result

    def _image(self, name):
        return docker("inspect", "--format", "{{.Image}}", name).stdout.strip()

    def _identity(self, kind):
        tag = image_tag(kind, SHA)
        result = docker("image", "inspect", "--format",
                        "{{.Id}}|{{.Architecture}}|{{index .Config.Labels \"org.opencontainers.image.revision\"}}|{{json .RepoDigests}}",
                        tag)
        self.assertEqual(result.returncode, 0, result.stderr)
        image_id, arch, revision, digests = result.stdout.strip().split("|", 3)
        return tag, image_id, arch, revision, digests

    def test_candidates_keep_identity_after_save_and_load(self):
        for kind in ("jvm", "node"):
            with self.subTest(kind):
                tag, image_id, arch, revision, digests = self._identity(kind)
                self.assertTrue(image_id.startswith("sha256:"))
                self.assertEqual(arch, "amd64")
                self.assertTrue(revision.startswith(SHA), revision)
                self.assertIn(image_id.split(":", 1)[1], digests)
                archive = "/tmp/%s-%s.tar" % (TEMP, kind)
                try:
                    self.assertEqual(docker("save", "-o", archive, tag).returncode, 0)
                    loaded = docker("load", "-i", archive)
                    self.assertEqual(loaded.returncode, 0, loaded.stderr)
                finally:
                    subprocess.run(["docker", "exec", DIND, "rm", "-f", archive], check=False, capture_output=True)
                self.assertEqual(self._identity(kind)[1], image_id)

    def test_runner_reference_creates_from_the_approved_id_without_pull(self):
        for kind in ("jvm", "node"):
            with self.subTest(kind):
                _tag, image_id, _arch, _revision, _digests = self._identity(kind)
                ref = image_ref(kind, SHA, image_id)
                self.assertTrue(ref.endswith("@" + image_id))
                name, created = self._create(ref, kind)
                self.assertEqual(created.returncode, 0, created.stderr)
                self.assertEqual(self._image(name), image_id)
                state = docker("inspect", "--format", "{{.State.Status}}", name).stdout.strip()
                self.assertEqual(state, "created")
                _name, missing = self._create(image_ref(kind, SHA, "sha256:" + "0" * 64), kind + "-ausente")
                self.assertNotEqual(missing.returncode, 0)
                _name, placeholder = self._create(ref, kind + "-placeholder", "--env-file", "<inspect-ao-vivo-sem-log>")
                self.assertNotEqual(placeholder.returncode, 0)

    def test_moved_tag_fails_closed_with_digest_but_swaps_without_it(self):
        _tag, jvm_id, *_ = self._identity("jvm")
        _tag, node_id, *_ = self._identity("node")
        moved = TEMP + ":movida"
        self.assertEqual(docker("tag", jvm_id, moved).returncode, 0)
        name, pinned = self._create(moved + "@" + jvm_id, "fixada")
        self.assertEqual(pinned.returncode, 0, pinned.stderr)
        self.assertEqual(self._image(name), jvm_id)
        self.assertEqual(docker("tag", node_id, moved).returncode, 0)
        _name, refused = self._create(moved + "@" + jvm_id, "recusada")
        self.assertNotEqual(refused.returncode, 0)
        name, swapped = self._create(moved, "trocada")
        self.assertEqual(swapped.returncode, 0, swapped.stderr)
        self.assertEqual(self._image(name), node_id)
        name, by_id = self._create(jvm_id, "por-id")
        self.assertEqual(by_id.returncode, 0, by_id.stderr)
        self.assertEqual(self._image(name), jvm_id)


if __name__ == "__main__":
    unittest.main(verbosity=2)
