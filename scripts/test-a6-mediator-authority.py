#!/usr/bin/env python3
import os
import tempfile
import unittest
from a6_mediator_authority import Authority, AuthorityError


class AuthorityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.now = 1000
        self.authority = Authority(self.temp.name, now=lambda: self.now)
        self.state = {"windowId":"window-1","vm":{"id":"1"},"expiresAt":1100,
                      "containers":{"stop-jvm":"jvm-id"},"images":{"jvm":"sha256:a"},"sequence":["stop-jvm"]}

    def test_consumes_once_in_order(self):
        self.authority.issue(self.state)
        self.authority.transition("window-1", "stop-jvm", "jvm-id")
        self.authority.complete("window-1", "stop-jvm", {"state":"exited"})
        with self.assertRaises(AuthorityError): self.authority.transition("window-1", "stop-jvm", "jvm-id")

    def test_rejects_expiry_wrong_container_and_pending_replay(self):
        self.authority.issue(self.state)
        with self.assertRaises(AuthorityError): self.authority.transition("window-1", "stop-jvm", "other")
        self.authority.transition("window-1", "stop-jvm", "jvm-id")
        with self.assertRaises(AuthorityError): self.authority.transition("window-1", "stop-jvm", "jvm-id")
        self.now = 1200
        with self.assertRaises(AuthorityError): self.authority.complete("window-1", "stop-jvm", {})

if __name__ == "__main__": unittest.main()
