#!/usr/bin/env python3
import json
import subprocess
import unittest
from unittest.mock import patch

from a6_docker_mediator import MediatorError, command, main


class MediatorTests(unittest.TestCase):
    def test_only_structured_read_operations_are_available(self):
        self.assertEqual(command({"op": "inspect", "service": "node"}),
                         ["docker", "inspect", "odisseu-a6-node"])
        self.assertEqual(command({"op": "read", "service": "node", "path": "/app/data/a6-maintenance.json"}),
                         ["docker", "exec", "odisseu-a6-node", "cat", "/app/data/a6-maintenance.json"])

    def test_refuses_generic_shell_exec_and_socket_paths(self):
        for request in (
            {"op": "shell", "service": "node"},
            {"op": "read", "service": "node", "path": "/var/run/docker.sock"},
            {"op": "run", "service": "node", "image": "alpine"},
            {"op": "stop", "service": "node", "timeout": 1},
            {"op": "inspect", "service": "node", "argv": ["--privileged"]},
        ):
            with self.subTest(request=request):
                with self.assertRaises(MediatorError):
                    command(request)

    def test_only_pinned_digest_images_are_observable(self):
        image = "odisseu-node:a6-0123456789ab@sha256:" + "a" * 64
        self.assertEqual(command({"op": "image", "image": image}), ["docker", "image", "inspect", image])
        with self.assertRaises(MediatorError):
            command({"op": "image", "image": "odisseu-node:latest"})

    def test_main_never_runs_a_command_for_invalid_json(self):
        with patch("sys.stdin") as stream, patch("sys.stdout"), patch("subprocess.run") as run:
            stream.read.return_value = "{"
            self.assertEqual(main(), 2)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
