#!/usr/bin/env python3
"""Ciclo de manutenção com o módulo real do Node e o contrato HTTP da JVM.

O runner só fala HTTP e lê o marcador. Quem grava o arquivo é o processo
que atende a API. Volumes e portas são temporários.
"""

import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from a6_maintenance_ops import (
    NODE_TOKEN_ENV,
    command,
    enter_jvm,
    enter_node,
    prove_quiescent,
)
from a6_production_deploy import DeployError


ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "scripts" / "lib" / "a6-maintenance.cjs"
NODE = shutil.which("node") or "/opt/homebrew/bin/node"
TOKEN = "token-de-teste-a6"
HARNESS = r"""
const http = require('http');
const fs = require('fs');
fs.writeFileSync(process.env.PORTFILE, 'starting');
const {create} = require(process.env.MODULE);
const gate = create({file: process.env.FILE, token: process.env.TOKEN});
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const send = (code, body) => {
    res.writeHead(code, {'content-type': 'application/json'});
    res.end(JSON.stringify(body));
  };
  if (url.pathname === '/test/hold' && req.method === 'POST') {
    send(gate.begin() ? 200 : 409, gate.snapshot());
    return;
  }
  if (url.pathname === '/test/release' && req.method === 'POST') {
    gate.end();
    send(200, gate.snapshot());
    return;
  }
  if (!gate.authorized(req.headers['x-a6-node-maintenance-token'] || '')) {
    send(401, {status: 'error', code: 'unauthorized'});
    return;
  }
  if (url.pathname === '/ops/maintenance/status' && req.method === 'GET') {
    send(200, gate.snapshot());
    return;
  }
  if (url.pathname === '/ops/maintenance/enter' && req.method === 'POST') {
    const timeout = Number(url.searchParams.get('timeoutMs') || '30000');
    const result = await gate.enter(Math.max(0, Math.min(900000, timeout)));
    send(result.quiescent ? 200 : 409, result);
    return;
  }
  if (url.pathname === '/ops/maintenance/resume' && req.method === 'POST') {
    try {
      send(200, gate.resume());
    } catch (err) {
      send(409, {status: 'error', code: 'maintenance_resume_blocked'});
    }
    return;
  }
  send(404, {status: 'not_found'});
});
server.listen(0, '127.0.0.1', () => {
  fs.writeFileSync(process.env.PORTFILE, String(server.address().port));
});
"""


def _load_supervised():
    spec = importlib.util.spec_from_file_location(
        "a6_supervised_window_tests",
        ROOT / "scripts" / "test-a6-supervised-deploy.py",
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Result:
    def __init__(self, stdout="", returncode=0):
        self.stdout = stdout
        self.returncode = returncode


class JvmGate:
    def __init__(self, marker):
        self.marker = Path(marker)
        self.phase = "open"
        self.active = 0
        self.admitted = 0
        self.completed = 0
        self.failed = 0
        self.rejected = 0
        self._write()

    def _write(self):
        self.marker.write_text(
            "{:phase :%s :active-writes %s}\n" % (self.phase, self.active),
            encoding="utf-8",
        )

    def data(self, quiescent):
        payload = {
            "phase": self.phase,
            "active-writes": self.active,
            "admitted-writes": self.admitted,
            "completed-writes": self.completed,
            "failed-writes": self.failed,
            "rejected-writes": self.rejected,
        }
        if quiescent:
            payload["quiescent?"] = True
        return payload


class JvmHandler(BaseHTTPRequestHandler):
    gate = None
    token = ""

    def log_message(self, fmt, *args):
        return

    def _send(self, code, body):
        raw = json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorized(self):
        return self.headers.get("X-A6-Maintenance-Token") == self.token

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._send(200, {
                "ok": True,
                "data": {
                    "status": "up",
                    "maintenance": {"phase": self.gate.phase, "active-writes": self.gate.active},
                },
            })
            return
        if not self._authorized():
            self._send(401, {"ok": False, "error": {"code": "unauthorized"}})
            return
        if path == "/ops/maintenance/status":
            self._send(200, {"ok": True, "data": self.gate.data(False)})
            return
        self._send(404, {"ok": False, "error": {"code": "not_found"}})

    def do_POST(self):
        if not self._authorized():
            self._send(401, {"ok": False, "error": {"code": "unauthorized"}})
            return
        path = self.path.split("?", 1)[0]
        gate = self.gate
        if path == "/ops/maintenance/enter":
            if gate.phase == "frozen":
                self._send(500, {"ok": False, "error": {"code": "maintenance_enter_forbidden_while_frozen"}})
                return
            if gate.active != 0:
                gate.phase = "draining"
                gate._write()
                self._send(409, {"ok": False, "error": {"code": "maintenance_drain_timeout"}})
                return
            gate.phase = "quiescent"
            gate._write()
            self._send(200, {"ok": True, "data": gate.data(True)})
            return
        if path == "/ops/maintenance/resume":
            if gate.phase not in ("quiescent", "frozen") or gate.active != 0:
                self._send(409, {"ok": False, "error": {"code": "maintenance_resume_blocked"}})
                return
            gate.phase = "open"
            gate._write()
            self._send(200, {"ok": True, "data": {"phase": "open", "resumed-at": "t"}})
            return
        self._send(404, {"ok": False, "error": {"code": "not_found"}})


class MaintenanceCycleTests(unittest.TestCase):
    def test_command_keeps_the_token_out_of_argv(self):
        argv = command(["sudo", "-n", "docker"], "odisseu-a6-node", "POST", "/ops/maintenance/enter", "x-a6-node-maintenance-token", NODE_TOKEN_ENV, 3001)
        text = " ".join(argv)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn("TOKEN=", text)
        self.assertNotIn(">", text)
        self.assertIn("process.env." + NODE_TOKEN_ENV, text)
        self.assertIn("node -e", text)
        self.assertNotIn("curl", text)
        self.assertIn("/ops/maintenance/enter", text)

    def test_jvm_command_still_uses_curl(self):
        argv = command(["sudo", "-n", "docker"], "odisseu-a6-jvm", "GET", "/ops/maintenance/status", "X-A6-Maintenance-Token", "ODISSEU_BRIDGE_MAINTENANCE_TOKEN", 18090)
        text = " ".join(argv)
        self.assertIn("curl", text)
        self.assertIn("$ODISSEU_BRIDGE_MAINTENANCE_TOKEN", text)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(">", text)

    def test_active_writes_zero_is_not_quiescence(self):
        with self.assertRaises(DeployError) as caught:
            prove_quiescent({
                "phase": "open",
                "quiescent": False,
                "activeWrites": 0,
                "failedWrites": 0,
                "admittedWrites": 0,
                "completedWrites": 0,
                "rejectedWrites": 3,
            })
        self.assertEqual(str(caught.exception), "jvm_not_quiescent")

    def test_real_node_module_and_jvm_contract_complete_the_cycle(self):
        supervised = _load_supervised()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            node_file = root / "a6-maintenance.json"
            jvm_file = root / "bridge-maintenance.edn"
            node_file.write_text('{"phase":"open"}\n', encoding="utf-8")
            gate = JvmGate(jvm_file)
            JvmHandler.gate = gate
            JvmHandler.token = TOKEN
            jvm_server = ThreadingHTTPServer(("127.0.0.1", 0), JvmHandler)
            thread = threading.Thread(target=jvm_server.serve_forever, daemon=True)
            thread.start()
            harness = root / "harness.cjs"
            port_file = root / "node.port"
            harness.write_text(HARNESS, encoding="utf-8")
            node = subprocess.Popen(
                [NODE, str(harness)],
                cwd=ROOT,
                env={
                    "MODULE": str(MODULE),
                    "FILE": str(node_file),
                    "TOKEN": TOKEN,
                    "PORTFILE": str(port_file),
                    "PATH": os.environ.get("PATH", ""),
                },
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            try:
                node_port = _wait_port(port_file, node)
                self.assertIsInstance(node_port, int)
                jvm_port = jvm_server.server_address[1]
                bases = {"3001": "http://127.0.0.1:%s" % node_port, "18090": "http://127.0.0.1:%s" % jvm_port}

                def forward(script, token):
                    if "node -e" in script:
                        method = "POST" if '"POST"' in script or "'POST'" in script else "GET"
                        port = "3001"
                        path_marker = 'path:"'
                        path = script.split(path_marker, 1)[1].split('"', 1)[0]
                        header = "x-a6-node-maintenance-token"
                    else:
                        method = "POST" if " -X POST " in script else "GET"
                        quoted = script.split("'http://127.0.0.1:", 1)[1]
                        port, path = quoted.split("/", 1)
                        path = "/" + path.rstrip("'")
                        header = "x-a6-node-maintenance-token" if port == "3001" else "X-A6-Maintenance-Token"
                    request = urllib.request.Request(bases[port] + path, data=b"" if method == "POST" else None, method=method)
                    if token:
                        request.add_header(header, token)
                    try:
                        with urllib.request.urlopen(request, timeout=5) as response:
                            return Result(response.read().decode("utf-8") + "\n" + str(response.status))
                    except urllib.error.HTTPError as exc:
                        return Result(exc.read().decode("utf-8") + "\n" + str(exc.code))
                    except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
                        return Result(str(exc), 1)

                def runner(token):
                    world = supervised.World()

                    def _run(args):
                        script = str(args[-1]) if args else ""
                        if len(args) >= 2 and args[-2] == "-c" and ("curl" in script or "node -e" in script):
                            return forward(script, token)
                        if str(args[-1]).endswith("a6-maintenance.json"):
                            return Result(node_file.read_text(encoding="utf-8"))
                        if str(args[-1]).endswith("bridge-maintenance.edn"):
                            return Result(jvm_file.read_text(encoding="utf-8"))
                        return world.runner(args)

                    return _run

                with self.assertRaises(DeployError) as missing:
                    enter_node(runner(""), ["sudo", "-n", "docker"])
                self.assertEqual(str(missing.exception), "maintenance_unauthorized")

                denied = urllib.request.Request(
                    bases["3001"] + "/ops/maintenance/enter?timeoutMs=30",
                    data=b"",
                    method="POST",
                    headers={"x-a6-node-maintenance-token": "errado"},
                )
                with self.assertRaises(urllib.error.HTTPError) as http_error:
                    urllib.request.urlopen(denied, timeout=5)
                self.assertEqual(http_error.exception.code, 401)

                held = urllib.request.Request(bases["3001"] + "/test/hold", data=b"", method="POST")
                with urllib.request.urlopen(held, timeout=5) as response:
                    self.assertEqual(response.status, 200)
                waiting = urllib.request.Request(
                    bases["3001"] + "/ops/maintenance/enter?timeoutMs=40",
                    data=b"",
                    method="POST",
                    headers={"x-a6-node-maintenance-token": TOKEN},
                )
                with self.assertRaises(urllib.error.HTTPError) as timed:
                    urllib.request.urlopen(waiting, timeout=5)
                self.assertEqual(timed.exception.code, 409)
                self.assertEqual(json.loads(node_file.read_text(encoding="utf-8"))["phase"], "draining")
                released = urllib.request.Request(bases["3001"] + "/test/release", data=b"", method="POST")
                with urllib.request.urlopen(released, timeout=5) as response:
                    self.assertEqual(response.status, 200)

                entered = enter_node(runner(TOKEN), ["sudo", "-n", "docker"])
                self.assertEqual(entered["phase"], "quiescent")
                self.assertEqual(entered["activeOperations"], 0)
                again = enter_node(runner(TOKEN), ["sudo", "-n", "docker"])
                self.assertEqual(again["phase"], "quiescent")
                self.assertEqual(json.loads(node_file.read_text(encoding="utf-8"))["phase"], "quiescent")
                restarted = _snapshot(node_file)
                self.assertEqual(restarted["phase"], "quiescent")
                self.assertFalse(restarted["admitted"])

                drained = enter_jvm(runner(TOKEN), ["sudo", "-n", "docker"])
                self.assertEqual(drained["phase"], "quiescent")
                self.assertEqual(drained["activeWrites"], 0)
                self.assertEqual(drained["failedWrites"], 0)
                self.assertIn(":phase :quiescent", jvm_file.read_text(encoding="utf-8"))

                export = root / "export"
                export.mkdir()
                (export / "save.edn").write_text(supervised.SAVE, encoding="utf-8")
                before = (export / "save.edn").read_bytes()
                report = supervised.run_supervised(
                    runner(TOKEN),
                    supervised.SHA,
                    supervised.JVM_DIGEST,
                    supervised.NODE_DIGEST,
                    dict(supervised.ENV),
                    root / "backup",
                    export,
                    materialize=supervised.materialize,
                )
                self.assertTrue(report["deployComplete"], report["blocked"])
                self.assertNotIn("activeOps", json.loads(node_file.read_text(encoding="utf-8")))
                self.assertEqual(report["steps"], list(supervised.SUPERVISED_STEPS))
                self.assertFalse(report["datomicRestored"])
                self.assertEqual((export / "save.edn").read_bytes(), before)
                self.assertEqual((root / "restore-isolated" / "save.edn").read_bytes(), before)
                text = "\n".join(" ".join(command) for command in report["commands"])
                self.assertNotIn(TOKEN, text)
                self.assertNotIn("TOKEN=", text)
                self.assertIn("/ops/maintenance/enter", text)
                self.assertIn("/ops/maintenance/resume", text)
                self.assertNotIn("inspect --format " + supervised.RESUME_PHRASE, text)
                self.assertEqual(json.loads(node_file.read_text(encoding="utf-8"))["phase"], "open")
                self.assertIn("resumedAt", node_file.read_text(encoding="utf-8"))
                self.assertIn(":phase :open", jvm_file.read_text(encoding="utf-8"))
                reopened = _snapshot(node_file)
                self.assertTrue(reopened["admitted"])
                self.assertEqual(reopened["phase"], "open")
                checkpoint = json.loads((root / "a6-window-checkpoint.json").read_text(encoding="utf-8"))
                self.assertEqual(checkpoint["lastCompleted"], "validate_game")
                self.assertFalse(checkpoint["datomicRestored"])
            finally:
                node.kill()
                node.wait(timeout=5)
                node.stdout.close()
                node.stderr.close()
                jvm_server.shutdown()
                jvm_server.server_close()


def _wait_port(path, process):
    for _ in range(40):
        if path.exists():
            text = path.read_text(encoding="utf-8").strip()
            if text.isdigit():
                return int(text)
        if process.poll() is not None:
            break
        time.sleep(0.05)
    process.kill()
    out, err = process.communicate(timeout=2)
    raise AssertionError(err or out or "porta do node ausente")


def _snapshot(path):
    completed = subprocess.run(
        [NODE, "-e", (
            "const {create}=require(process.env.MODULE);"
            "const gate=create({file:process.env.FILE, token:process.env.TOKEN});"
            "process.stdout.write(JSON.stringify({phase:gate.snapshot().phase, admitted:gate.begin()}));"
        )],
        check=False,
        capture_output=True,
        text=True,
        env={"MODULE": str(MODULE), "FILE": str(path), "TOKEN": TOKEN, "PATH": os.environ.get("PATH", "")},
    )
    if completed.returncode != 0:
        raise AssertionError(completed.stderr)
    return json.loads(completed.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
