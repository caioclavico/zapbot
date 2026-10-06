"""Exercise the real HTTP/Unix Docker client against an isolated Docker model.

No production host, Docker daemon, registry or application command is contacted.
"""
import base64
import copy
import fcntl
from http.server import BaseHTTPRequestHandler
import importlib.util
import json
import os
from pathlib import Path
import socketserver
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, unquote, urlsplit

SPEC = importlib.util.spec_from_file_location('deployment', Path(__file__).with_name('deploy-service.py'))
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)
OLD_IMAGE = 'sha256:' + 'b' * 64
NEW_IMAGE = 'sha256:' + 'c' * 64
SECRET = 'PRIVATE_TEST_TOKEN_SHOULD_NEVER_APPEAR_IN_OUTPUT'


class Clock:
    def __init__(self):
        self.value = 0
    def now(self):
        return self.value
    def sleep(self, seconds):
        self.value += seconds


class DockerModel:
    def __init__(self, service, app):
        self.service, self.app = service, app
        self.name = DEPLOY.SERVICES[service][0]
        self.image = 'ghcr.io/example/' + DEPLOY.SERVICES[service][2] + ':' + 'a' * 40
        self.scenario = 'ready'
        self.calls = []
        self.executions = {}
        self.execution_polls = {}
        self.created = []
        self.old_starts = 0
        self.faulted = False
        self.architecture, self.version, self.image_service = 'amd64', '1', service
        self.target_id, self.revision = NEW_IMAGE, 'a' * 40
        host = {'Memory': 1342177280, 'MemorySwap': 2147483648, 'ShmSize': 67108864,
                'RestartPolicy': {'Name': 'unless-stopped', 'MaximumRetryCount': 0},
                'NetworkMode': 'bridge', 'AutoRemove': False, 'ReadonlyRootfs': False,
                'LogConfig': {'Type': 'json-file', 'Config': {}},
                'CapDrop': ['NET_RAW'], 'SecurityOpt': ['apparmor=docker-default'],
                'CpuShares': 512, 'Dns': ['1.1.1.1'], 'ExtraHosts': ['example:127.0.0.2'],
                'PortBindings': None, 'Mounts': None}
        config = {'Image': 'legacy:latest', 'Hostname': 'actual-hostname',
                  'User': '', 'WorkingDir': '/app', 'Entrypoint': ['/usr/bin/tini', '--'],
                  'Cmd': ['node', 'target/main.js'] if service == 'odisseu' else ['node', 'runtime/main.cjs'],
                  'Env': ['CASSANDRA_CONTACT_POINTS=144.22.248.79', 'API_TOKEN=' + SECRET,
                          'POKEMON_READ_ONLY=false', 'PORT=8080'],
                  'StopTimeout': 10, 'Labels': {'operator-label': 'must-survive'}}
        mounts = []
        if service == 'odisseu':
            for source, destination in ((app / '.wwebjs_auth', '/app/.wwebjs_auth'),
                                        (app / 'data', '/app/data')):
                mounts.append({'Type': 'bind', 'Source': str(source), 'Destination': destination,
                               'RW': True, 'Mode': 'rw', 'Propagation': 'rprivate'})
            host['Binds'] = [mount['Source'] + ':' + mount['Destination'] + ':rw' for mount in mounts]
        else:
            mounts.append({'Type': 'volume', 'Source': '/var/lib/docker/volumes/existing-data/_data',
                           'Name': 'existing-data', 'Destination': '/app/data', 'RW': True, 'Mode': 'rw'})
            # Dockerfile-created anonymous volume: it is absent from HostConfig.Binds.
            host['Binds'] = None
            host['PortBindings'] = {'8080/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '8080'}]}
            config['Volumes'] = {'/app/data': {}}
        self.containers = {'old': {'Id': 'old', 'Name': '/' + self.name, 'Image': OLD_IMAGE,
                                  'Config': config, 'HostConfig': host, 'Mounts': mounts,
                                  'RestartCount': 0, 'State': {'Running': True, 'Paused': False,
                                  'Restarting': False, 'StartedAt': '2026-10-04T00:00:00Z'},
                                  'NetworkSettings': {'Networks': {'bridge': {'Aliases': None,
                                                                            'IPAMConfig': None}}}}}
        self.original = copy.deepcopy(self.containers['old'])

    def container(self, reference):
        if reference in self.containers:
            return self.containers[reference]
        for container in self.containers.values():
            if container['Name'] == '/' + reference:
                return container
        raise KeyError(reference)

    def response(self, method, url, data, headers):
        route = urlsplit(url)
        path = unquote(route.path.removeprefix('/v1.45')) if hasattr(str, 'removeprefix') else unquote(route.path[6:])
        query = parse_qs(route.query)
        self.calls.append((method, path, query, copy.deepcopy(data)))
        if path == '/containers/json':
            return 200, [{'Id': container['Id'], 'Names': [container['Name']],
                          'Mounts': container['Mounts'], 'Labels': container['Config'].get('Labels'),
                          'State': 'running' if container['State']['Running'] else 'exited'}
                         for container in self.containers.values() if query.get('all') == ['1'] or container['State']['Running']]
        if path.startswith('/images/') and path.endswith('/json'):
            reference = path[len('/images/'):-len('/json')]
            if reference == OLD_IMAGE and self.target_id != OLD_IMAGE:
                return 200, {'Id': OLD_IMAGE, 'Os': 'linux', 'Architecture': 'amd64', 'Config': {'Labels': {}}}
            return 200, {'Id': self.target_id, 'Os': 'linux', 'Architecture': self.architecture,
                         'Config': {'Labels': {DEPLOY.SERVICE_LABEL: self.image_service,
                                               DEPLOY.STORAGE_LABEL: self.version,
                                               DEPLOY.REVISION_LABEL: self.revision}}}
        if path == '/images/create':
            if self.scenario == 'pull-failure':
                return 200, (json.dumps({'error': SECRET}) + '\n').encode()
            if 'X-Registry-Auth' in headers:
                self.registry_auth = json.loads(base64.urlsafe_b64decode(headers['X-Registry-Auth']))
            return 200, b'{"status":"Downloaded"}\n'
        if path == '/containers/create':
            if self.scenario == 'create-failure':
                return 500, {'message': SECRET}
            identifier = 'new' + str(len(self.created) + 1)
            host = copy.deepcopy(data['HostConfig'])
            config = {key: copy.deepcopy(value) for key, value in data.items()
                      if key not in ('HostConfig', 'NetworkingConfig')}
            if self.scenario == 'wrong-created-revision':
                config['Labels'][DEPLOY.REVISION_LABEL] = 'd' * 40
            container = {'Id': identifier, 'Name': '/' + query['name'][0], 'Image': data['Image'],
                         'Config': config, 'HostConfig': host, 'Mounts': copy.deepcopy(self.original['Mounts']),
                         'RestartCount': 0, 'State': {'Running': False, 'Paused': False,
                         'Restarting': False, 'StartedAt': '2026-10-04T01:00:00Z'},
                         'NetworkSettings': copy.deepcopy(self.original['NetworkSettings'])}
            self.created.append(copy.deepcopy(data))
            self.containers[identifier] = container
            if self.scenario == 'daemon-changes-env':
                container['Config']['Env'] = ['POKEMON_READ_ONLY=true']
            elif self.scenario == 'daemon-changes-resources':
                container['HostConfig']['MemorySwap'] = 0
            elif self.scenario == 'daemon-changes-volume':
                container['Mounts'][0]['Name'] = 'unexpected-empty-volume'
            if self.scenario == 'create-response-loss':
                return 500, {'message': SECRET}
            return 201, {'Id': identifier}
        if path.startswith('/exec/'):
            execution, operation = path[len('/exec/'):].split('/')
            if operation == 'start':
                return 200, b''
            self.execution_polls[execution] = self.execution_polls.get(execution, 0) + 1
            if self.scenario == 'async-exec' and self.execution_polls[execution] <= 2:
                return 200, {'Running': True, 'ExitCode': None}
            container = self.containers[self.executions[execution]]
            bad = (container['Id'] != 'old' and self.scenario in ('timeout', 'rollback-failure', 'stop-replacement-failure'))
            bad = bad or (container['Id'] == 'old' and self.scenario == 'rollback-failure' and self.old_starts > 0)
            bad = bad or self.scenario == 'old-not-ready'
            bad = bad or (self.scenario == 'manual-target-failure' and container['Id'] == 'old')
            bad = bad or (self.scenario == 'current-unhealthy' and container['Id'] != 'old')
            return 200, {'Running': False, 'ExitCode': 1 if bad else 0}
        if path.startswith('/containers/'):
            reference, operation = path[len('/containers/'):].rsplit('/', 1)
            container = self.container(reference)
            if operation == 'json':
                if self.scenario == 'restart' and reference != 'old' and len(self.executions) >= 2:
                    container['RestartCount'] += 1
                return 200, copy.deepcopy(container)
            if operation == 'logs':
                assert query['since'][0].isdigit(), 'The Engine accepts Unix seconds, not RFC3339'
                log = ('seguindo sem persistência' if self.scenario == 'database-error' and reference != 'old'
                       else 'Conectado ao Cassandra; estado particionado carregado')
                return 200, (log + '\n' + SECRET).encode()
            if operation == 'exec':
                assert data['AttachStdout'] is False and data['AttachStderr'] is False
                assert data['Cmd'][0] == 'node'
                if self.service == 'pokemon':
                    assert "['/health','/ready']" in data['Cmd'][2]
                    assert '/commands' not in data['Cmd'][2]
                else:
                    assert data['Cmd'] == ['node', 'scripts/healthcheck.js']
                identifier = str(len(self.executions) + 1)
                self.executions[identifier] = container['Id']
                return 201, {'Id': identifier}
            if operation == 'stop':
                assert int(query['t'][0]) >= 60
                if self.scenario == 'stop-replacement-failure' and container['Id'] != 'old':
                    return 500, {'message': SECRET}
                if self.scenario == 'stop-original-failure' and container['Id'] == 'old':
                    return 500, {'message': SECRET}
                container['State']['Running'] = False
                container['State']['ExitCode'] = 137 if self.scenario == 'forced-stop' and container['Id'] == 'old' else 0
                if self.scenario == 'stop-response-loss' and not self.faulted:
                    self.faulted = True
                    return 500, {'message': SECRET}
                return 204, b''
            if operation == 'rename':
                requested = '/' + query['name'][0]
                assert all(other['Id'] == container['Id'] or other['Name'] != requested
                           for other in self.containers.values()), 'Duplicate container name'
                container['Name'] = requested
                if self.scenario == 'rename-response-loss' and not self.faulted:
                    self.faulted = True
                    return 500, {'message': SECRET}
                return 204, b''
            if operation == 'start':
                assert not any(other['State']['Running'] for other in self.containers.values()
                               if other['Id'] != container['Id']), 'Two active writers!'
                if self.scenario == 'start-failure' and container['Id'] != 'old':
                    return 500, {'message': SECRET}
                container['State']['Running'] = True
                if self.scenario == 'fast-restart' and container['Id'] != 'old':
                    container['RestartCount'] = 1
                if container['Id'] == 'old':
                    self.old_starts += 1
                if self.scenario == 'start-response-loss' and not self.faulted:
                    self.faulted = True
                    return 500, {'message': SECRET}
                return 204, b''
        raise AssertionError((method, path, query, data))


class UnixHTTPServer(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    allow_reuse_address = True


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass
    def do_GET(self):
        self.respond('GET')
    def do_POST(self):
        self.respond('POST')
    def respond(self, method):
        payload = self.rfile.read(int(self.headers.get('Content-Length', '0')))
        data = json.loads(payload) if payload else None
        try:
            status, response = self.server.model.response(method, self.path, data, self.headers)
        except KeyError:
            status, response = 404, {'message': SECRET}
        except BaseException as error:
            self.server.errors.append(error)
            status, response = 500, {'message': SECRET}
        body = response if isinstance(response, bytes) else json.dumps(response).encode()
        self.send_response(status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except BrokenPipeError:
            pass  # An empty 204 response may cause the client to close immediately.


class HTTPReplyHandler(BaseHTTPRequestHandler):
    """Exercise HTTP framing independently of the deployment model."""
    protocol_version = 'HTTP/1.1'

    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.send_response(self.server.status)
        if self.server.connection_close:
            self.send_header('Connection', 'close')
        if self.server.chunked:
            self.send_header('Transfer-Encoding', 'chunked')
        else:
            self.send_header('Content-Length', str(len(self.server.body)))
        self.end_headers()
        if self.server.chunked:
            for offset in range(0, len(self.server.body), 16384):
                chunk = self.server.body[offset:offset + 16384]
                self.wfile.write(('%x\r\n' % len(chunk)).encode() + chunk + b'\r\n')
            self.wfile.write(b'0\r\n\r\n')
        else:
            self.wfile.write(self.server.body)


class DeployTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='deploy-', dir='/tmp')
        self.root = Path(self.temporary.name)
        self.servers = []
    def tearDown(self):
        for server, thread in self.servers:
            server.shutdown()
            thread.join(timeout=2)
            server.server_close()
            self.assertEqual(server.errors, [])
        self.temporary.cleanup()

    def fixture(self, service='odisseu'):
        root = self.root / (service + '-' + str(len(self.servers)))
        root.mkdir()
        app, state, config = root / 'app', root / 'state', root / 'config'
        for directory in (app, state, config, app / 'data', app / '.wwebjs_auth'):
            directory.mkdir(mode=0o700)
        (app / '.env').write_text('POKEMON_READ_ONLY=true\nAPI_TOKEN=' + SECRET + '\n')
        (app / '.env').chmod(0o600)
        (app / '.wwebjs_auth' / 'session').write_text('session-must-survive')
        (app / 'data' / 'persistent').write_text('data-must-survive')
        (config / 'persistence-version').write_text('1\n')
        (config / 'persistence-version').chmod(0o600)
        model = DockerModel(service, app)
        server = UnixHTTPServer(str(root / 'docker.sock'), Handler)
        server.model, server.errors = model, []
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.01}, daemon=True)
        thread.start()
        self.servers.append((server, thread))
        self.app, self.state, self.config, self.model = app, state, config, model
        self.engine = DEPLOY.Engine(str(root / 'docker.sock'))
        self.output, self.clock = [], Clock()
        return model

    def deploy(self, action='deploy'):
        deployment = DEPLOY.Deployment(self.model.service, self.engine, app_dir=self.app,
                    state_dir=self.state, config_dir=self.config, owner_uid=os.getuid(),
                    timeout=6, stable_seconds=2, poll_seconds=1,
                    clock=self.clock.now, sleep=self.clock.sleep, output=self.output.append)
        deployment.run(action, self.model.image if action == 'deploy' else None)

    def assert_preserved(self):
        self.assertEqual((self.app / '.env').read_text(), 'POKEMON_READ_ONLY=true\nAPI_TOKEN=' + SECRET + '\n')
        self.assertEqual((self.app / '.wwebjs_auth' / 'session').read_text(), 'session-must-survive')
        self.assertEqual((self.app / 'data' / 'persistent').read_text(), 'data-must-survive')
        self.assertNotIn(SECRET, '\n'.join(self.output))
        self.assertNotIn(SECRET, (self.state / 'state.json').read_text())
        for path in self.state.iterdir():
            self.assertEqual(path.stat().st_mode & 0o777, 0o700 if path.is_dir() else 0o600)
        for _method, path, _query, _data in self.model.calls:
            self.assertNotIn('cassandra', path)
            self.assertNotIn('/delete', path)

    def test_http_framing_preserves_complete_bodies_and_empty_responses(self):
        body = b'large Docker response\n' * 10000
        cases = ((True, False, 200, body), (False, False, 200, body),
                 (False, True, 200, body), (True, False, 204, b''))
        for connection_close, chunked, status, expected in cases:
            with self.subTest(connection_close=connection_close, chunked=chunked, status=status):
                socket_path = str(self.root / ('http-%d.sock' % len(self.servers)))
                server = UnixHTTPServer(socket_path, HTTPReplyHandler)
                server.errors = []
                server.connection_close, server.chunked = connection_close, chunked
                server.status, server.body = status, expected
                thread = threading.Thread(target=server.serve_forever,
                                          kwargs={'poll_interval': 0.01}, daemon=True)
                thread.start()
                self.servers.append((server, thread))
                engine = DEPLOY.Engine(socket_path)
                self.assertEqual(engine.request('GET', '/response', raw=True,
                                                expected=(status,)), expected)

    def test_success_preserves_resources_actual_hostname_and_effective_env(self):
        for service in ('odisseu', 'pokemon'):
            with self.subTest(service=service):
                model = self.fixture(service)
                self.deploy()
                active = model.container(model.name)
                self.assertEqual(active['Image'], NEW_IMAGE)
                self.assertEqual(active['Config']['Labels'][DEPLOY.REVISION_LABEL], 'a' * 40)
                self.assertEqual(active['Config']['Labels']['operator-label'], 'must-survive')
                self.assertFalse(model.containers['old']['State']['Running'])
                self.assertEqual(active['Config']['Env'], model.original['Config']['Env'])
                self.assertIn('POKEMON_READ_ONLY=false', active['Config']['Env'])
                self.assertEqual(active['Config']['Hostname'], 'actual-hostname')
                self.assertEqual(active['Config']['Cmd'], model.original['Config']['Cmd'])
                self.assertEqual(active['Config']['StopTimeout'], 90 if service == 'pokemon' else 60)
                for key, expected in model.original['HostConfig'].items():
                    if key != 'Binds':
                        self.assertEqual(active['HostConfig'][key], expected, key)
                if service == 'pokemon':
                    self.assertEqual(active['HostConfig']['Binds'], ['existing-data:/app/data:rw'])
                else:
                    self.assertEqual(active['HostConfig']['Binds'], model.original['HostConfig']['Binds'])
                paths = [path for _method, path, _query, _data in model.calls]
                self.assertLess(paths.index('/images/create'), paths.index('/containers/old/stop'))
                self.assertLess(paths.index('/containers/old/stop'), paths.index('/containers/create'))
                self.assertGreaterEqual(self.clock.value, 2)
                self.assertEqual(json.loads((self.state / 'state.json').read_text())['last-good-image'], model.image)
                self.assertFalse((self.state / 'transaction.json').exists())
                self.assert_preserved()

    def test_all_post_stop_failures_rollback_and_recheck_health(self):
        for scenario in ('timeout', 'restart', 'database-error', 'create-failure', 'start-failure',
                         'stop-original-failure', 'forced-stop', 'rename-response-loss',
                         'create-response-loss', 'stop-response-loss', 'start-response-loss', 'fast-restart'):
            with self.subTest(scenario=scenario):
                self.fixture('odisseu' if scenario != 'timeout' else 'pokemon')
                self.model.scenario = scenario
                with self.assertRaises(DEPLOY.DeployError):
                    self.deploy()
                self.assertEqual(self.model.container(self.model.name)['Id'], 'old')
                self.assertTrue(self.model.containers['old']['State']['Running'])
                self.assertFalse(any(container['State']['Running'] for key, container in self.model.containers.items() if key != 'old'))
                self.assertTrue(any('rollback healthy' in line for line in self.output))
                state = json.loads((self.state / 'state.json').read_text())
                self.assertEqual(state['last-good-image'], OLD_IMAGE)
                self.assertFalse((self.state / 'transaction.json').exists())
                self.assert_preserved()

    def test_failed_rollback_is_reported_and_evidence_is_retained(self):
        self.fixture('pokemon')
        self.model.scenario = 'rollback-failure'
        with self.assertRaisesRegex(DEPLOY.DeployError, 'rollback failed'):
            self.deploy()
        self.assertTrue(any('ROLLBACK FAILED' in line for line in self.output))
        self.assertTrue((self.state / 'transaction.json').exists())
        logs = list(self.state.glob('failure-*.log'))
        self.assertEqual(len(logs), 1)
        self.assertIn(SECRET.encode(), logs[0].read_bytes())
        self.assert_preserved()

    def test_uncertain_replacement_stop_never_starts_previous_writer(self):
        self.fixture('pokemon')
        self.model.scenario = 'stop-replacement-failure'
        with self.assertRaisesRegex(DEPLOY.DeployError, 'rollback failed'):
            self.deploy()
        self.assertEqual(self.model.old_starts, 0)
        self.assertFalse(self.model.containers['old']['State']['Running'])
        self.assert_preserved()

    def test_bad_image_blocks_before_stopping_old_container(self):
        for scenario in ('architecture', 'version', 'service', 'pull-failure'):
            with self.subTest(scenario=scenario):
                self.fixture()
                if scenario == 'architecture': self.model.architecture = 'arm64'
                elif scenario == 'version': self.model.version = '2'
                elif scenario == 'service': self.model.image_service = 'pokemon'
                else: self.model.scenario = scenario
                with self.assertRaises(DEPLOY.DeployError):
                    self.deploy()
                self.assertTrue(self.model.containers['old']['State']['Running'])
                self.assertFalse(any(path.endswith('/stop') for _, path, _, _ in self.model.calls))
                self.assert_preserved()

    def test_same_image_id_with_stale_or_missing_container_revision_is_replaced(self):
        for service in ('pokemon', 'odisseu'):
            for revision in ('5' * 40, None):
                with self.subTest(service=service, revision=revision):
                    model = self.fixture(service)
                    model.target_id = OLD_IMAGE
                    if revision:
                        model.containers['old']['Config']['Labels'][DEPLOY.REVISION_LABEL] = revision
                    self.deploy()
                    active = model.container(model.name)
                    self.assertNotEqual(active['Id'], 'old')
                    self.assertEqual(active['Image'], OLD_IMAGE)
                    self.assertEqual(active['Config']['Labels'][DEPLOY.REVISION_LABEL], 'a' * 40)
                    self.assertFalse(any('already healthy' in line for line in self.output))
                    decision = next(line for line in self.output if 'version decision' in line)
                    self.assertIn('"decision": "replace"', decision)
                    self.assertIn('"requested_revision": "' + 'a' * 40 + '"', decision)
                    self.assert_preserved()

    def test_same_id_and_revision_is_noop_only_after_health_verification(self):
        model = self.fixture('pokemon')
        model.target_id = OLD_IMAGE
        model.containers['old']['Config']['Labels'][DEPLOY.REVISION_LABEL] = 'a' * 40
        self.deploy()
        self.assertEqual(model.container(model.name)['Id'], 'old')
        self.assertEqual(model.created, [])
        self.assertFalse(any(path.endswith('/stop') for _, path, _, _ in model.calls))
        self.assertTrue(any('already healthy' in line for line in self.output))
        self.assert_preserved()

    def test_missing_or_wrong_image_revision_blocks_before_stop(self):
        for revision in (None, 'd' * 40, SECRET):
            with self.subTest(revision=revision):
                model = self.fixture('pokemon')
                model.revision = revision
                with self.assertRaises(DEPLOY.DeployError):
                    self.deploy()
                self.assertTrue(model.containers['old']['State']['Running'])
                self.assertFalse(any(path.endswith('/stop') for _, path, _, _ in model.calls))
                self.assertTrue(any('"decision": "reject-image"' in line for line in self.output))
                self.assert_preserved()

    def test_rollback_restores_retained_container_even_with_identical_image_id(self):
        model = self.fixture('pokemon')
        model.target_id = OLD_IMAGE
        model.containers['old']['Config']['Labels'][DEPLOY.REVISION_LABEL] = '5' * 40
        self.deploy()
        self.model.scenario = 'current-unhealthy'
        self.deploy('rollback')
        self.assertEqual(model.container(model.name)['Id'], 'old')
        self.assertEqual(model.container(model.name)['Config']['Labels'][DEPLOY.REVISION_LABEL], '5' * 40)
        self.assertEqual(len(model.created), 1)
        self.assert_preserved()

    def test_same_revision_on_different_image_id_still_deploys(self):
        model = self.fixture('pokemon')
        model.containers['old']['Config']['Labels'][DEPLOY.REVISION_LABEL] = 'a' * 40
        self.deploy()
        self.assertEqual(model.container(model.name)['Image'], NEW_IMAGE)
        self.assertNotEqual(model.container(model.name)['Id'], 'old')
        self.assert_preserved()

    def test_wrong_revision_on_created_container_rolls_back_without_starting_it(self):
        model = self.fixture('pokemon')
        model.scenario = 'wrong-created-revision'
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertEqual(model.container(model.name)['Id'], 'old')
        self.assertTrue(model.containers['old']['State']['Running'])
        self.assertFalse(any(path.startswith('/containers/new') and path.endswith('/start')
                             for _, path, _, _ in model.calls))
        self.assert_preserved()

    def test_manual_rollback_reuses_original_container_and_health_is_rechecked(self):
        self.fixture('pokemon')
        self.deploy()
        self.deploy('rollback')
        self.assertEqual(self.model.container(self.model.name)['Id'], 'old')
        self.assertEqual(len(self.model.created), 1)
        self.assertEqual(self.model.container(self.model.name)['Config']['Env'], self.model.original['Config']['Env'])
        self.assertEqual(json.loads((self.state / 'state.json').read_text())['previous-image'], self.model.image)
        self.assert_preserved()

    def test_manual_rollback_failure_restores_current_good_release(self):
        self.fixture('pokemon')
        self.deploy()
        self.model.scenario = 'manual-target-failure'
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy('rollback')
        self.assertEqual(self.model.container(self.model.name)['Image'], NEW_IMAGE)
        self.assertTrue(any('rollback healthy' in line for line in self.output))
        self.assert_preserved()

    def test_manual_rollback_recovers_an_unhealthy_or_crashed_release(self):
        for stopped in (False, True):
            with self.subTest(stopped=stopped):
                self.fixture('pokemon')
                self.deploy()
                self.model.scenario = 'current-unhealthy'
                current = self.model.container(self.model.name)
                if stopped:
                    current['State']['Running'] = False
                    current['State']['ExitCode'] = 137
                    current['State']['OOMKilled'] = True
                self.deploy('rollback')
                self.assertEqual(self.model.container(self.model.name)['Id'], 'old')
                self.assertTrue(self.model.containers['old']['State']['Running'])
                self.assert_preserved()

    def test_registry_auth_is_sent_privately_without_log_exposure(self):
        self.fixture()
        docker_dir = self.config / 'docker'
        docker_dir.mkdir(mode=0o700)
        config = docker_dir / 'config.json'
        config.write_text(json.dumps({'auths': {'ghcr.io': {'auth': base64.b64encode(('user:' + SECRET).encode()).decode()}}}))
        config.chmod(0o600)
        self.deploy()
        self.assertEqual(self.model.registry_auth['password'], SECRET)
        self.assert_preserved()

    def test_missing_persistence_blocks_before_stop(self):
        self.fixture()
        self.model.containers['old']['Mounts'].pop()
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertFalse(any(path.endswith('/stop') for _, path, _, _ in self.model.calls))
        self.assertNotIn(SECRET, '\n'.join(self.output))

    def test_another_writer_blocks_without_touching_any_container(self):
        self.fixture('pokemon')
        duplicate = copy.deepcopy(self.model.containers['old'])
        duplicate['Id'], duplicate['Name'] = 'duplicate', '/old-manual-writer'
        self.model.containers['duplicate'] = duplicate
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertFalse(any(path.endswith('/stop') for _, path, _, _ in self.model.calls))
        self.assertEqual(self.model.old_starts, 0)

    def test_odisseu_always_restart_policy_blocks_before_stop_including_retained_writer(self):
        for retained in (False, True):
            with self.subTest(retained=retained):
                self.fixture('odisseu')
                writer = self.model.containers['old']
                if retained:
                    writer = copy.deepcopy(writer)
                    writer.update(Id='retained', Name='/zapbot-previous-retained')
                    writer['State'].update(Running=False, Pid=0)
                    self.model.containers['retained'] = writer
                writer['HostConfig']['RestartPolicy']['Name'] = 'always'
                with self.assertRaises(DEPLOY.DeployError):
                    self.deploy()
                self.assertFalse(any(path.endswith('/stop') or path.endswith('/start')
                                     for _, path, _, _ in self.model.calls))
                self.assertFalse((self.state / 'transaction.json').exists())

    def test_stale_saved_references_require_review_before_any_container_changes(self):
        self.fixture('odisseu')
        DEPLOY.atomic_private(self.state / 'state.json', json.dumps({'last-good-image-id': NEW_IMAGE}))
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertFalse(any(method != 'GET' for method, _, _, _ in self.model.calls))
        self.assertFalse((self.state / 'transaction.json').exists())

    def test_daemon_configuration_changes_are_rejected_before_new_start(self):
        for scenario in ('daemon-changes-env', 'daemon-changes-resources', 'daemon-changes-volume'):
            with self.subTest(scenario=scenario):
                self.fixture('pokemon')
                self.model.scenario = scenario
                with self.assertRaises(DEPLOY.DeployError):
                    self.deploy()
                self.assertEqual(self.model.container(self.model.name)['Id'], 'old')
                self.assertFalse(any(path.startswith('/containers/new') and path.endswith('/start')
                                     for _, path, _, _ in self.model.calls))
                self.assert_preserved()

    def test_current_unhealthy_container_blocks_before_pull_or_stop(self):
        self.fixture('pokemon')
        self.model.scenario = 'old-not-ready'
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertFalse(any(path.endswith('/stop') or path == '/images/create' for _, path, _, _ in self.model.calls))

    def test_async_exec_is_polled_until_probe_exit(self):
        self.fixture('pokemon')
        self.model.scenario = 'async-exec'
        self.deploy()
        self.assertTrue(all(count >= 3 for count in self.model.execution_polls.values()))
        self.assert_preserved()

    def test_closed_ssh_output_does_not_interrupt_rollback(self):
        self.fixture('pokemon')
        self.model.scenario = 'timeout'
        def closed_channel(_message):
            raise BrokenPipeError('SSH output closed')
        deployment = DEPLOY.Deployment('pokemon', self.engine, app_dir=self.app,
            state_dir=self.state, config_dir=self.config, owner_uid=os.getuid(),
            timeout=6, stable_seconds=2, poll_seconds=1, clock=self.clock.now,
            sleep=self.clock.sleep, output=closed_channel)
        with self.assertRaises(DEPLOY.DeployError):
            deployment.run('deploy', self.model.image)
        self.assertEqual(self.model.container(self.model.name)['Id'], 'old')
        self.assertTrue(self.model.containers['old']['State']['Running'])
        self.assertFalse((self.state / 'transaction.json').exists())

    def test_interrupted_transaction_requires_recovery_without_daemon_changes(self):
        self.fixture()
        (self.state / 'transaction.json').write_text('{}')
        (self.state / 'transaction.json').chmod(0o600)
        with self.assertRaises(DEPLOY.DeployError):
            self.deploy()
        self.assertEqual(self.model.calls, [])

    def test_lock_blocks_concurrent_deploy(self):
        self.fixture()
        descriptor = os.open(self.state / 'deploy.lock', os.O_CREAT | os.O_RDWR, 0o600)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(DEPLOY.DeployError):
                self.deploy()
            self.assertEqual(self.model.calls, [])
        finally:
            os.close(descriptor)

    def test_unchanged_image_does_not_restart(self):
        self.fixture()
        self.deploy()
        count = len([path for _, path, _, _ in self.model.calls if path.endswith('/stop')])
        self.deploy()
        self.assertEqual(len([path for _, path, _, _ in self.model.calls if path.endswith('/stop')]), count)
        self.assert_preserved()

    def test_failed_candidate_profile_is_restored_from_a_snapshot_taken_only_after_clean_stop(self):
        from unittest.mock import patch
        self.fixture('odisseu')
        self.model.scenario = 'timeout'
        snapshot = DEPLOY.SessionProfile.snapshot
        response = self.model.response
        def checked_snapshot(profile, *args):
            self.assertFalse(self.model.containers['old']['State']['Running'])
            self.assertEqual(self.model.containers['old']['State']['ExitCode'], 0)
            return snapshot(profile, *args)
        def modified_profile(method, url, data, headers):
            result = response(method, url, data, headers)
            if '/start' in url and '/old/' not in url and '/exec/' not in url:
                (self.app / '.wwebjs_auth' / 'session').write_text('candidate-auth-changes')
            return result
        with patch.object(DEPLOY.SessionProfile, 'snapshot', checked_snapshot):
            self.model.response = modified_profile
            with self.assertRaises(DEPLOY.DeployError): self.deploy()
        self.assert_preserved()
        failed = list((self.app / '.zapbot-auth-recovery').glob('*-failed/session'))
        self.assertEqual(len(failed), 1)
        self.assertEqual(failed[0].read_text(), 'candidate-auth-changes')
        self.assertTrue(list(self.state.glob('transaction.*.rolled-back.json')))


if __name__ == '__main__':
    unittest.main()
