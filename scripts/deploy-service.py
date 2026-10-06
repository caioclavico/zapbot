#!/usr/bin/env python3
"""Deploy one container using its actual runtime configuration, without leaking Env.

The stopped container is the configuration snapshot for rollback. This program
must be installed manually, root-owned, behind the service-specific SSH wrapper.
"""
import base64
import copy
from datetime import datetime
import fcntl
import http.client
import json
import os
from pathlib import Path
import re
import signal
import socket
import stat
import sys
import tempfile
import time
from urllib.parse import quote, urlencode
from deploy_auth_profile import SessionProfile, ProfileError, sync_dir

SERVICES = {
    'odisseu': ('zapbot', '/home/ubuntu/zapbot', 'zapbot'),
    'pokemon': ('zapbot-pokemon', '/home/caiohclavico/pokemon-service', 'zapbot-pokemon'),
}
SERVICE_LABEL = 'io.zapbot.service'
STORAGE_LABEL = 'io.zapbot.persistence-version'
DEPLOYMENT_LABEL = 'io.zapbot.deployment-id'
REVISION_LABEL = 'org.opencontainers.image.revision'
IMAGE_RE = re.compile(r'^ghcr\.io/[a-z0-9][a-z0-9._-]*/(zapbot(?:-pokemon)?):[0-9a-f]{40}$')
POKEMON_HEALTH = """const h=require('node:http');
const host=process.env.HOST==='::1'?'::1':'127.0.0.1';
async function check(path){return new Promise(resolve=>{let body='';
const r=h.get({hostname:host,port:Number(process.env.PORT||8090),path,timeout:3000},s=>{
s.setEncoding('utf8');s.on('data',b=>{body+=b;if(body.length>4096)r.destroy()});
s.on('end',()=>{try{const j=JSON.parse(body);resolve(s.statusCode===200&&
(path==='/health'?j.status==='ok':j.ready===true))}catch{resolve(false)}});
s.on('error',()=>resolve(false))});r.on('timeout',()=>r.destroy());
r.on('error',()=>resolve(false))})}
Promise.all(['/health','/ready'].map(check)).then(ok=>process.exit(ok.every(Boolean)?0:1));"""

class DeployError(Exception):
    """Only operator-safe categories; never expose Docker response bodies."""

class UnixConnection(http.client.HTTPConnection):
    def __init__(self, socket_path, timeout=90):
        super().__init__('localhost', timeout=timeout)
        self.socket_path = str(socket_path)

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.socket_path)

class Engine:
    def __init__(self, socket_path='/var/run/docker.sock', api_version='v1.45'):
        self.socket_path, self.api_version = socket_path, api_version
        self.operation_deadline = None

    def request(self, method, path, data=None, *, expected=(200,), raw=False, headers=None, timeout=90):
        if self.operation_deadline is not None:
            timeout = min(timeout, self.operation_deadline - time.monotonic())
            if timeout <= 0:
                raise DeployError('Docker operation deadline exceeded')
        connection = UnixConnection(self.socket_path, timeout=timeout)
        response = None
        payload = None if data is None else json.dumps(data).encode()
        request_headers = {'Content-Type': 'application/json', **(headers or {})}
        try:
            deadline = time.monotonic() + timeout
            connection.request(method, '/' + self.api_version + path, payload, request_headers)
            wire = connection.sock
            response = connection.getresponse()
            chunks, size = [], 0
            # Python 3.12 closes the response/socket as soon as Content-Length
            # is consumed. Do not reset a closed socket's timeout on that EOF.
            while not response.isclosed():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise DeployError('Docker operation deadline exceeded')
                wire.settimeout(remaining)
                chunk = response.read1(65536)
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                if size > 32 * 1024 * 1024:
                    raise DeployError('Docker response exceeded its size limit')
            body = b''.join(chunks)
            if response.status not in expected:
                raise DeployError('Docker operation failed: ' + method + ' ' + path.split('?')[0].split('/')[1])
            return body if raw else (json.loads(body) if body else None)
        except (OSError, http.client.HTTPException, ValueError) as error:
            raise DeployError('Docker API unavailable or invalid response') from error
        finally:
            try:
                if response is not None:
                    response.close()
            finally:
                connection.close()

    def inspect(self, container):
        return self.request('GET', '/containers/' + quote(container, safe='') + '/json')

    def active(self):
        return [c for c in self.containers() if c.get('State', 'running') in ('running', 'paused', 'restarting')]

    def containers(self):
        return self.request('GET', '/containers/json?all=1')

    def image(self, image):
        return self.request('GET', '/images/' + quote(image, safe='') + '/json')

    def pull(self, image, registry_auth=None):
        headers = {} if registry_auth is None else {'X-Registry-Auth': registry_auth}
        body = self.request('POST', '/images/create?' + urlencode({'fromImage': image}),
                            raw=True, headers=headers, timeout=900)
        try:
            if any(json.loads(line).get('error') or json.loads(line).get('errorDetail')
                   for line in body.splitlines() if line):
                raise DeployError('Image pull failed')
        except ValueError as error:
            raise DeployError('Image pull returned invalid progress') from error

    def stop(self, container, timeout):
        self.request('POST', '/containers/' + quote(container, safe='') + '/stop?t=' + str(timeout),
                     expected=(204, 304), timeout=timeout + 30)

    def start(self, container):
        self.request('POST', '/containers/' + quote(container, safe='') + '/start', expected=(204,))

    def rename(self, container, name):
        self.request('POST', '/containers/' + quote(container, safe='') + '/rename?' + urlencode({'name': name}),
                     expected=(204,))

    def create(self, name, config):
        return self.request('POST', '/containers/create?' + urlencode({'name': name}), config,
                            expected=(201,))['Id']

    def logs(self, container, started):
        # The Engine expects Unix seconds; unlike the Docker CLI it does not
        # parse StartedAt's RFC3339 timestamp. Truncate nanoseconds for Python3.9.
        timestamp = re.sub(r'(\.[0-9]{6})[0-9]+', r'\1', started.replace('Z', '+00:00'))
        since = str(int(datetime.fromisoformat(timestamp).timestamp()))
        query = urlencode({'stdout': '1', 'stderr': '1', 'tail': '2000', 'since': since})
        return self.request('GET', '/containers/' + quote(container, safe='') + '/logs?' + query, raw=True)

    def probe(self, container, service):
        command = ['node', 'scripts/healthcheck.js'] if service == 'odisseu' else ['node', '-e', POKEMON_HEALTH]
        execution = self.request('POST', '/containers/' + quote(container, safe='') + '/exec',
                                 {'AttachStdout': False, 'AttachStderr': False, 'Cmd': command},
                                 expected=(201,))['Id']
        self.request('POST', '/exec/' + execution + '/start', {'Detach': False, 'Tty': False}, raw=True)
        # With no attached output Docker may return before the probe exits.
        deadline = time.monotonic() + 10
        if self.operation_deadline is not None:
            deadline = min(deadline, self.operation_deadline)
        while time.monotonic() < deadline:
            result = self.request('GET', '/exec/' + execution + '/json')
            if not result.get('Running') and result.get('ExitCode') is not None:
                return result['ExitCode'] == 0
            time.sleep(0.1)
        return False

def private_file(path, owner_uid=0, max_bytes=65536):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != owner_uid or info.st_mode & 0o077:
        raise DeployError('Deployment configuration must be private and owned by root')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    try:
        opened = os.fstat(descriptor)
        if (opened.st_ino, opened.st_dev) != (info.st_ino, info.st_dev):
            raise DeployError('Deployment configuration changed while opening')
        with os.fdopen(descriptor, 'r', encoding='utf-8') as stream:
            descriptor = None
            value = stream.read(max_bytes + 1)
            if len(value) > max_bytes:
                raise DeployError('Private deployment record exceeds its size limit')
            return value
    finally:
        if descriptor is not None:
            os.close(descriptor)

def atomic_private(path, value):
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)

class Deployment:
    def __init__(self, service, engine, *, app_dir=None, state_dir=None, config_dir=None,
                 owner_uid=0, timeout=600, stable_seconds=30, poll_seconds=5,
                 clock=time.monotonic, sleep=time.sleep, output=print):
        if service not in SERVICES:
            raise DeployError('Unknown service')
        self.service = service
        self.name, default_app, self.repository = SERVICES[service]
        self.app_dir = Path(app_dir or default_app)
        self.state_dir = Path(state_dir or '/var/lib/zapbot-deploy/' + service)
        self.config_dir = Path(config_dir or '/etc/zapbot-deploy/' + service)
        self.owner_uid, self.engine = owner_uid, engine
        self.grace_seconds = 90 if service == 'pokemon' else 60
        self.timeout, self.stable_seconds, self.poll_seconds = timeout, stable_seconds, poll_seconds
        self.clock, self.sleep = clock, sleep
        def safe_output(message):
            # Losing the SSH log channel must never interrupt cleanup/rollback.
            try:
                output(message)
            except (OSError, ValueError):
                pass
        self.output = safe_output
        self.previous = self.new_id = self.original_state = self.lock = None
        self.stop_attempted = self.original_name_changed = False
        self.create_attempted = False
        self.transaction_name = str(time.time_ns())
        self.auth_backup = None
        self.auth_restored = False
        self.new_start_attempted = False
        self.requested_image = self.retained_id = None
        self.requested_revision = None
        self.profile = SessionProfile(self.app_dir, self.state_dir, owner_uid,
            lambda path, uid: private_file(path, uid, max_bytes=16 * 1024 * 1024), atomic_private)

    def acquire(self, recovery=False):
        for directory in (self.config_dir, self.state_dir):
            info = directory.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != self.owner_uid or info.st_mode & 0o077:
                raise DeployError('Deployment directories must be private and owned by root')
        self.lock = os.open(self.state_dir / 'deploy.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise DeployError('Another deployment is running') from error
        if not recovery and (self.state_dir / 'transaction.json').exists():
            raise DeployError('Interrupted deployment requires operator recovery; see transaction.json')

    def registry_auth(self):
        config = self.config_dir / 'docker/config.json'
        if not config.exists():
            return None
        try:
            data = json.loads(private_file(config, self.owner_uid))
            if data.get('credsStore') or data.get('credHelpers', {}).get('ghcr.io'):
                raise DeployError('Use a private Docker login config without credential helpers')
            entry = data.get('auths', {}).get('ghcr.io', {})
            if not entry.get('auth'):
                raise DeployError('GHCR login configuration is missing')
            username, password = base64.b64decode(entry['auth']).decode().split(':', 1)
            auth = {'username': username, 'password': password, 'serveraddress': 'ghcr.io'}
            return base64.urlsafe_b64encode(json.dumps(auth).encode()).decode()
        except (ValueError, KeyError, UnicodeError) as error:
            raise DeployError('GHCR login configuration is invalid') from error

    def state(self):
        path = self.state_dir / 'state.json'
        if not path.exists():
            return None
        try:
            return json.loads(private_file(path, self.owner_uid))
        except ValueError as error:
            raise DeployError('Saved deployment references are invalid') from error

    def record(self, state):
        # state.json is authoritative; each operator-readable reference is atomic.
        for key in ('last-good-image', 'previous-image', 'deployed-revision'):
            atomic_private(self.state_dir / key, state.get(key, '') + '\n')
        atomic_private(self.state_dir / 'state.json', json.dumps(state, sort_keys=True) + '\n')

    def compatibility(self, image, *, legacy=False):
        if image.get('Os') != 'linux' or image.get('Architecture') != 'amd64':
            raise DeployError('Image must use linux/amd64')
        labels = image.get('Config', {}).get('Labels') or {}
        baseline = private_file(self.config_dir / 'persistence-version', self.owner_uid).strip()
        if not re.fullmatch(r'[A-Za-z0-9._-]{1,64}', baseline):
            raise DeployError('Persistence baseline is invalid')
        if labels.get(SERVICE_LABEL) != self.service and not (legacy and SERVICE_LABEL not in labels):
            raise DeployError('Image belongs to a different service or lacks its service label')
        version = labels.get(STORAGE_LABEL, baseline if legacy else None)
        if version != baseline:
            raise DeployError('Incompatible persistence version; controlled migration required')

    def persistence(self, current, *, restoring_profile=False):
        config, host = current['Config'], current['HostConfig']
        if self.service == 'odisseu' and (host.get('RestartPolicy') or {}).get('Name', 'no') not in ('', 'no', 'unless-stopped', 'on-failure'):
            raise DeployError('Odisseu restart policy can revive a retained profile writer; operator review required')
        if host.get('AutoRemove'):
            raise DeployError('Auto-remove must be disabled to retain a rollback container')
        if host.get('NetworkMode', '').startswith('container:'):
            raise DeployError('Shared container networking requires controlled migration')
        for network in (current.get('NetworkSettings', {}).get('Networks') or {}).values():
            if network.get('IPAMConfig'):
                raise DeployError('Static network addresses require controlled migration')
        if not config.get('Hostname'):
            raise DeployError('Container hostname must be preserved explicitly')
        env = dict(item.split('=', 1) for item in config.get('Env', []) if '=' in item)
        if not env.get('CASSANDRA_CONTACT_POINTS'):
            raise DeployError('Existing container lacks explicit Cassandra configuration')
        info = (self.app_dir / '.env').lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_size == 0 or info.st_mode & 0o027:
            raise DeployError('Existing application .env must remain a private regular file')
        mounts = {mount['Destination']: mount for mount in current.get('Mounts', [])}
        if self.service == 'odisseu':
            for destination, source in (('/app/.wwebjs_auth', self.app_dir / '.wwebjs_auth'),
                                        ('/app/data', self.app_dir / 'data')):
                mount = mounts.get(destination, {})
                if (mount.get('Type') != 'bind' or mount.get('Source') != str(source)
                        or mount.get('RW') is not True or source.is_symlink()
                        or not (source.is_dir() or (restoring_profile and destination == '/app/.wwebjs_auth'))):
                    raise DeployError('Required WhatsApp/session data persistence is missing')
        else:
            mount = mounts.get('/app/data', {})
            if mount.get('Type') not in ('bind', 'volume') or mount.get('RW') is not True:
                raise DeployError('Required Pokemon data persistence is missing')
            if mount.get('Type') == 'bind' and not Path(mount.get('Source', '')).is_dir():
                raise DeployError('Pokemon data bind mount is unavailable')
            ports = host.get('PortBindings') or {}
            if not any(binding.get('HostPort') == '8080'
                       for bindings in ports.values() for binding in bindings or []):
                raise DeployError('Existing Pokemon HTTP port 8080 must be published')

    def clone(self, current, image):
        config, host = copy.deepcopy(current['Config']), copy.deepcopy(current['HostConfig'])
        config['Image'] = image['Id']
        config['StopTimeout'] = max(self.grace_seconds, config.get('StopTimeout') or 0)
        config['Labels'] = dict(config.get('Labels') or {})
        for label in (SERVICE_LABEL, STORAGE_LABEL, REVISION_LABEL):
            config['Labels'][label] = image['Config']['Labels'][label]
        config['Labels'][DEPLOYMENT_LABEL] = self.transaction_name
        # Reattach every Docker-created anonymous volume by its actual name.
        bound = {bind.split(':')[1] for bind in host.get('Binds') or [] if ':' in bind}
        bound.update(mount['Target'] for mount in host.get('Mounts') or [])
        binds = list(host.get('Binds') or [])
        for mount in current.get('Mounts', []):
            destination = mount['Destination']
            if destination not in bound and mount['Type'] in ('bind', 'volume'):
                source = mount['Name'] if mount['Type'] == 'volume' else mount['Source']
                binds.append(source + ':' + destination + ':' + (mount.get('Mode') or ('rw' if mount['RW'] else 'ro')))
        host['Binds'] = binds or None
        config['HostConfig'] = host
        if host.get('NetworkMode') not in ('host', 'none'):
            endpoints = {}
            for name, network in (current.get('NetworkSettings', {}).get('Networks') or {}).items():
                endpoint = {}
                aliases = [alias for alias in network.get('Aliases') or []
                           if alias not in (current['Id'], current['Id'][:12])]
                if aliases:
                    endpoint['Aliases'] = aliases
                if network.get('DriverOpts'):
                    endpoint['DriverOpts'] = network['DriverOpts']
                endpoints[name] = endpoint
            if endpoints:
                config['NetworkingConfig'] = {'EndpointsConfig': endpoints}
        return config

    def running(self, container, restart_count):
        inspected = self.engine.inspect(container)
        if inspected.get('Id') != container:
            raise DeployError('Readiness response belongs to a different container')
        state = inspected['State']
        if not state.get('Running') or state.get('Paused') or state.get('Restarting'):
            raise DeployError('Container stopped, paused or restarted during readiness verification')
        if inspected.get('RestartCount', 0) != restart_count:
            raise DeployError('Container restarted during readiness verification')
        return inspected

    def verify_created(self, original, replacement):
        actual = self.engine.inspect(replacement)
        for key in ('Hostname', 'Domainname', 'User', 'WorkingDir', 'Entrypoint', 'Cmd',
                    'Tty', 'OpenStdin', 'StdinOnce', 'StopSignal'):
            if actual['Config'].get(key) != original['Config'].get(key):
                raise DeployError('Replacement changed an existing runtime setting')
        old_env = dict(item.split('=', 1) for item in original['Config'].get('Env', []) if '=' in item)
        new_env = dict(item.split('=', 1) for item in actual['Config'].get('Env', []) if '=' in item)
        if any(new_env.get(key) != value for key, value in old_env.items()):
            raise DeployError('Replacement changed existing effective environment')
        for key, value in original['HostConfig'].items():
            # Binds may gain explicit references to existing anonymous volumes.
            if key != 'Binds' and (actual['HostConfig'].get(key) or None) != (value or None):
                raise DeployError('Replacement changed existing container resources or security settings')
        def signatures(container):
            return {(mount['Type'], mount['Destination'], mount.get('RW'),
                     mount.get('Name') if mount['Type'] == 'volume' else mount.get('Source'))
                    for mount in container.get('Mounts', []) if mount['Type'] in ('bind', 'volume')}
        if signatures(actual) != signatures(original):
            raise DeployError('Replacement changed existing persistence mounts')

    def wait_ready(self, container, *, stable_seconds=None, new_start=True, require_no_restarts=False):
        previous_deadline = self.engine.operation_deadline
        self.engine.operation_deadline = time.monotonic() + self.timeout
        try:
            self._wait_ready(container, stable_seconds=stable_seconds, new_start=new_start,
                             require_no_restarts=require_no_restarts)
        finally:
            self.engine.operation_deadline = previous_deadline

    def _wait_ready(self, container, *, stable_seconds=None, new_start=True, require_no_restarts=False):
        deadline = self.clock() + self.timeout
        restart_count = self.engine.inspect(container).get('RestartCount', 0)
        if require_no_restarts and restart_count != 0:
            raise DeployError('Replacement restarted before readiness verification')
        stable = None
        stable_seconds = self.stable_seconds if stable_seconds is None else stable_seconds
        attempts = 0
        while self.clock() <= deadline:
            current = self.running(container, restart_count)
            ready = self.engine.probe(container, self.service)
            if self.service == 'odisseu' and new_start:
                logs = self.engine.logs(container, current['State']['StartedAt'])
                if b'seguindo sem persist\xc3\xaancia' in logs:
                    raise DeployError('Cassandra initialization failed')
                ready = ready and b'Conectado ao Cassandra; estado particionado carregado' in logs
            if ready:
                stable = self.clock() if stable is None else stable
                if self.clock() - stable >= stable_seconds:
                    return
            else:
                stable = None
            attempts += 1
            if attempts % 12 == 0:
                self.output(self.service + ': waiting for readiness')
            self.sleep(self.poll_seconds)
        raise DeployError('Readiness timeout')

    def ensure_stopped(self, container, *, clean=False):
        inspected = self.engine.inspect(container)
        if inspected.get('Id') != container:
            raise DeployError('Stop verification belongs to a different container')
        state = inspected['State']
        if state.get('Running') or state.get('Restarting') or state.get('Paused') or state.get('Pid', 0):
            raise DeployError('Previous process is still running; replacement cannot start')
        if clean and (state.get('OOMKilled') or state.get('ExitCode', 0) != 0):
            raise DeployError('Previous process did not shut down cleanly; replacement blocked')

    def single_writer(self, expected):
        mounts = {(mount['Type'], mount.get('Name') if mount['Type'] == 'volume' else mount.get('Source'))
                  for mount in self.previous.get('Mounts', []) if mount['Type'] in ('bind', 'volume')}
        inventory = self.engine.containers() if self.service == 'odisseu' else self.engine.active()
        for container in inventory:
            same_mount = any((mount['Type'], mount.get('Name') if mount['Type'] == 'volume' else mount.get('Source'))
                             in mounts for mount in container.get('Mounts', []) if mount['Type'] in ('bind', 'volume'))
            # Docker normalizes paths differently from an operator's bind. Also
            # reject a bind of an ancestor/descendant (e.g. /home/ubuntu/zapbot).
            protected = [Path(m['Source']).resolve() for m in self.previous.get('Mounts', []) if m['Type'] == 'bind']
            for mount in container.get('Mounts', []):
                if mount['Type'] == 'bind':
                    source = Path(mount['Source']).resolve()
                    same_mount = same_mount or any(source == path or source in path.parents or path in source.parents for path in protected)
            same_service = (container.get('Labels') or {}).get(SERVICE_LABEL) == self.service
            names = [name.lstrip('/') for name in container.get('Names', [])]
            if self.service == 'pokemon':
                same_service = same_service or any(name.startswith('zapbot-pokemon') for name in names)
            else:
                same_service = same_service or any(name == 'zapbot' or
                    (name.startswith('zapbot-') and not name.startswith(('zapbot-pokemon', 'zapbot-cassandra')))
                    for name in names)
            if same_mount or same_service:
                if self.service == 'odisseu':
                    actual = self.engine.inspect(container['Id'])
                    if (actual['HostConfig'].get('RestartPolicy') or {}).get('Name', 'no') not in ('', 'no', 'unless-stopped', 'on-failure'):
                        raise DeployError('Related Odisseu container can restart a retained profile writer; operator review required')
                    state = actual['State']
                    active = state.get('Running') or state.get('Paused') or state.get('Restarting') or state.get('Pid', 0)
                    if container['Id'] == expected or not active:
                        continue
                elif container['Id'] == expected:
                    continue
                raise DeployError('Another active container shares this service or its persistence; start blocked')

    def preserve_logs(self, container):
        if container is None:
            return
        try:
            current = self.engine.inspect(container)
            body = self.engine.logs(container, current['State']['StartedAt'])
            descriptor, path = tempfile.mkstemp(prefix='failure-', suffix='.log', dir=self.state_dir)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(body)
            self.output(self.service + ': failure logs saved privately in deployment state directory')
        except Exception:
            self.output(self.service + ': could not save failure logs')

    def transaction(self, phase):
        if phase == 'profile-restored':
            self.auth_restored = True
        data = {'version': 2, 'transaction-id': self.transaction_name,
                'phase': phase, 'original-container': self.previous['Id'],
                'original-image': self.previous['Image'], 'requested-image': self.requested_image,
                'requested-revision': self.requested_revision,
                'replacement-container': self.new_id, 'retained-container': self.retained_id,
                'replacement-start-attempted': self.new_start_attempted,
                'auth-backup': self.auth_backup, 'original-state': self.original_state,
                'auth-restored': self.auth_restored,
                'service': self.service}
        atomic_private(self.state_dir / 'transaction.json', json.dumps(data) + '\n')

    def archive_transaction(self, outcome):
        path = self.state_dir / 'transaction.json'
        if not path.exists():
            return
        archive = self.state_dir / ('transaction.' + self.transaction_name + '.' + outcome + '.json')
        # Never overwrite earlier evidence, including transaction.recovered.json.
        if archive.exists():
            if private_file(archive, self.owner_uid) != private_file(path, self.owner_uid):
                raise DeployError('Transaction archive conflicts with existing evidence')
        else:
            os.link(path, archive, follow_symlinks=False)
            sync_dir(self.state_dir)
        path.unlink()
        sync_dir(self.state_dir)

    def restore(self):
        # Never start the old writer before proving the replacement has exited.
        # A create may be committed by Docker even if its response is lost. Only
        # adopt a container marked by THIS transaction, never an operator's bot.
        if self.new_id is None and self.create_attempted:
            try:
                candidate = self.engine.inspect(self.name)
            except DeployError:
                candidate = None
            if candidate and candidate['Config'].get('Labels', {}).get(DEPLOYMENT_LABEL) == self.transaction_name:
                self.new_id = candidate['Id']
        if self.new_id:
            candidate = self.engine.inspect(self.new_id)
            if (candidate.get('Id') != self.new_id or candidate.get('Image') != self.requested_image or
                    (self.new_id != self.retained_id and (candidate['Config'].get('Labels') or {}).get(DEPLOYMENT_LABEL) != self.transaction_name)):
                raise DeployError('Rollback replacement identity is uncertain; no container will be stopped')
            self.preserve_logs(self.new_id)
            self.engine.stop(self.new_id, self.grace_seconds)
            self.ensure_stopped(self.new_id, clean=self.service == 'odisseu' and self.new_start_attempted)
            failed_name = self.name + '-failed-' + self.transaction_name
            if self.engine.inspect(self.new_id)['Name'] != '/' + failed_name:
                self.engine.rename(self.new_id, failed_name)
        original_id = self.previous['Id']
        was_running = self.engine.inspect(original_id)['State'].get('Running')
        if was_running and self.service == 'odisseu' and self.auth_backup and self.new_start_attempted and not self.auth_restored:
            raise DeployError('Original was restarted before profile restoration was confirmed; manual review required')
        if self.service == 'odisseu' and self.auth_backup and self.new_start_attempted and not self.auth_restored:
            self.ensure_stopped(original_id)
            self.single_writer(None)
            self.profile.restore(self.auth_backup, original_id, self.previous['Image'], self.transaction)
        if self.service == 'odisseu' and (not self.profile.auth.is_dir() or self.profile.auth.is_symlink()):
            raise DeployError('Restored session root is unavailable; old client will not be started with an empty profile')
        # Rename may also have been committed before a failed HTTP response.
        if self.engine.inspect(original_id)['Name'] != '/' + self.name:
            self.engine.rename(original_id, self.name)
        self.single_writer(original_id)
        if not self.engine.inspect(original_id)['State'].get('Running'):
            self.engine.start(original_id)
        self.wait_ready(original_id, new_start=not was_running)
        if self.original_state:
            self.record(self.original_state)
        self.transaction('rolled-back')
        self.archive_transaction('rolled-back')
        self.output(self.service + ': rollback healthy; restored ' + self.previous['Image'])

    def recover(self):
        """Operator-only recovery; never implicitly executed by deploy or SSH."""
        path = self.state_dir / 'transaction.json'
        if not path.exists():
            self.output(self.service + ': no pending transaction; no recovery action performed')
            return
        try:
            data = json.loads(private_file(path, self.owner_uid))
        except (ValueError, OSError) as error:
            raise DeployError('Interrupted transaction record is invalid; evidence retained') from error
        if data.get('version') != 2 or data.get('service') != self.service:
            raise DeployError('Legacy or foreign transaction requires manual review; no Docker mutations performed')
        tx = data.get('transaction-id')
        if not isinstance(tx, str) or not re.fullmatch(r'[0-9]{1,32}', tx):
            raise DeployError('Invalid transaction identity')
        phases = {'prepared', 'original-stopped', 'auth-snapshot-ready', 'creating-replacement',
                  'replacement-created', 'starting-replacement', 'replacement-started',
                  'replacement-verified', 'committed', 'recovering-rollback', 'profile-restore-ready',
                  'profile-original-archived', 'profile-restored', 'rolled-back'}
        if (data.get('phase') not in phases or not isinstance(data.get('replacement-start-attempted'), bool)
                or not isinstance(data.get('auth-restored'), bool)):
            raise DeployError('Invalid transaction phase')
        def identifier(value):
            return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value)
        original_id, new_id = data.get('original-container'), data.get('replacement-container')
        retained = data.get('retained-container')
        if not identifier(original_id) or (new_id is not None and not identifier(new_id)) or (
                retained is not None and not identifier(retained)) or original_id == new_id:
            raise DeployError('Recovery requires full, distinct container IDs')
        for key in ('original-image', 'requested-image'):
            if not isinstance(data.get(key), str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', data[key]):
                raise DeployError('Recovery requires immutable image IDs')
        prior = data.get('original-state')
        if not isinstance(prior, dict) or set(prior) != {'last-good-image', 'last-good-image-id',
                'previous-image', 'previous-image-id', 'previous-container', 'deployed-revision'}:
            raise DeployError('Recovery lacks the saved deployment references')
        if retained and (retained != prior.get('previous-container') or data['requested-image'] != prior.get('previous-image-id')):
            raise DeployError('Retained rollback target differs from saved references')
        self.transaction_name, self.original_state = tx, prior
        self.requested_image, self.requested_revision = data['requested-image'], data.get('requested-revision')
        self.retained_id, self.auth_backup = retained, data.get('auth-backup')
        self.new_start_attempted = data['replacement-start-attempted']
        self.auth_restored = data['auth-restored']
        if self.auth_backup is not None and (self.service != 'odisseu' or self.auth_backup != tx):
            raise DeployError('Snapshot belongs to another transaction or service')
        if self.service == 'odisseu' and self.new_start_attempted and not self.auth_backup:
            raise DeployError('Started replacement has no consistent session snapshot; manual review required')
        summaries = self.engine.containers()
        ids = {c['Id'] for c in summaries}
        if original_id not in ids or (new_id is not None and new_id not in ids):
            raise DeployError('Recorded container disappeared; no recovery mutations performed')
        original = self.engine.inspect(original_id)
        if original['State'].get('Paused') or original['State'].get('Restarting') or (
                not original['State'].get('Running') and original['State'].get('Pid', 0)):
            raise DeployError('Original container has an ambiguous process state')
        if original.get('Id') != original_id or original.get('Image') != data['original-image'] or original.get('Name') not in (
                '/' + self.name, '/' + self.name + '-previous-' + tx):
            raise DeployError('Original container identity or name differs from the transaction')
        self.previous = original
        missing_auth = bool(self.auth_backup and not self.profile.auth.exists())
        self.persistence(original, restoring_profile=missing_auth)
        self.compatibility(self.engine.image(original['Image']), legacy=True)
        if self.auth_backup:
            self.profile.validated(tx, original_id, original['Image'])
        candidates = [c for c in summaries if (c.get('Labels') or {}).get(DEPLOYMENT_LABEL) == tx and c['Id'] != original_id]
        if len(candidates) > 1 or (candidates and new_id is not None and candidates[0]['Id'] != new_id):
            raise DeployError('Ambiguous replacement identity; containers and evidence retained')
        if new_id is None and candidates:
            if self.new_start_attempted:
                raise DeployError('Started replacement is missing its recorded ID')
            new_id = candidates[0]['Id']
        if new_id is None and retained is not None and retained in ids:
            # A retained-container rename may have committed before the next
            # journal write. Identify it by its saved full ID and image, not by
            # an unrelated container occupying the production name.
            candidate = self.engine.inspect(retained)
            if candidate.get('Name') in ('/' + self.name, '/' + self.name + '-failed-' + tx):
                new_id = retained
        replacement = self.engine.inspect(new_id) if new_id else None
        if replacement:
            if (replacement.get('Id') != new_id or replacement.get('Image') != self.requested_image or
                    replacement.get('Name') not in ('/' + self.name, '/' + self.name + '-failed-' + tx) or
                    (not retained and (replacement['Config'].get('Labels') or {}).get(DEPLOYMENT_LABEL) != tx) or
                    (replacement['Config'].get('Labels') or {}).get(REVISION_LABEL) != self.requested_revision):
                raise DeployError('Replacement identity differs from the recorded target')
            self.persistence(replacement, restoring_profile=missing_auth)
            if not retained:
                self.verify_created(original, new_id)
            if replacement['State'].get('Running') and not self.new_start_attempted:
                raise DeployError('Replacement started outside the recorded transaction')
        self.new_id = new_id
        if original['State'].get('Running') and replacement and replacement['State'].get('Running'):
            raise DeployError('Both writers are active; manual review required before recovery')
        for c in self.engine.active():
            if c['Id'] not in (original_id, new_id):
                # Validate other containers without exempting either transaction
                # writer from the profile-overlap check.
                self.single_writer(new_id if replacement and replacement['State'].get('Running') else original_id)
                break
        committed = data['phase'] == 'committed' or (data['phase'] == 'replacement-verified' and
            (self.state() or {}).get('last-good-image-id') == self.requested_image)
        if committed:
            if not replacement or replacement['Name'] != '/' + self.name or (
                    self.state() or {}).get('last-good-image-id') != self.requested_image:
                raise DeployError('Committed state does not match the actual replacement')
            self.ensure_stopped(original_id)
            self.single_writer(new_id)
            self.wait_ready(new_id, new_start=False)
            self.archive_transaction('committed')
            self.output(self.service + ': committed replacement verified; transaction archived')
            return
        if original['State'].get('Running') and original['Name'] != '/' + self.name:
            raise DeployError('Original is active under an unexpected name; manual review required')
        self.transaction('recovering-rollback')
        self.restore()

    def run(self, action, image=None):
        try:
            self.acquire(recovery=action == 'recover')
            if action == 'recover':
                self.recover()
                return
            current = self.engine.inspect(self.name)
            self.previous, self.original_state = current, self.state()
            if self.original_state is not None and self.original_state.get('last-good-image-id') != current['Image']:
                raise DeployError('Saved deployment references differ from the current container; operator review required before deployment')
            self.single_writer(current['Id'])
            self.persistence(current)
            current_image = self.engine.image(current['Image'])
            self.compatibility(current_image, legacy=True)
            def safe_revision(value):
                return value if isinstance(value, str) and re.fullmatch(r'[0-9a-f]{40}', value) else 'missing-or-invalid'
            # Emergency rollback must work when the currently deployed version
            # has become unhealthy or stopped after its original success.
            if action == 'deploy':
                self.wait_ready(current['Id'], stable_seconds=0, new_start=False)
            if self.original_state is None:
                self.original_state = {'last-good-image': current['Image'],
                                       'last-good-image-id': current['Image'],
                                       'previous-image': '', 'previous-image-id': '',
                                       'previous-container': None, 'deployed-revision': ''}
                self.record(self.original_state)
            if action == 'deploy':
                if not IMAGE_RE.fullmatch(image or '') or image.split('/')[-1].split(':')[0] != self.repository:
                    raise DeployError('Deploy requires this service GHCR image with a full commit SHA')
                self.output(self.service + ': pulling immutable image ' + image)
                self.engine.pull(image, self.registry_auth())
                target = self.engine.image(image)
                self.compatibility(target)
                requested_revision = image.rsplit(':', 1)[-1]
                if (target.get('Config', {}).get('Labels') or {}).get(REVISION_LABEL) != requested_revision:
                    self.output(self.service + ': version decision ' + json.dumps({
                        'current_revision': safe_revision((current['Config'].get('Labels') or {}).get(REVISION_LABEL)),
                        'requested_revision': requested_revision,
                        'requested_image_revision': safe_revision((target.get('Config', {}).get('Labels') or {}).get(REVISION_LABEL)),
                        'decision': 'reject-image'}, sort_keys=True))
                    raise DeployError('Requested image revision is missing or differs from commit SHA')
                configuration, retained = self.clone(current, target), None
            elif action == 'rollback':
                if not self.original_state or not self.original_state.get('previous-container'):
                    raise DeployError('No retained rollback container is available')
                retained = self.engine.inspect(self.original_state['previous-container'])
                self.ensure_stopped(retained['Id'])
                if retained['Image'] != self.original_state.get('previous-image-id'):
                    raise DeployError('Retained rollback container differs from saved image')
                self.persistence(retained)
                target = self.engine.image(retained['Image'])
                self.compatibility(target, legacy=True)
                image = self.original_state['previous-image']
                requested_revision = (retained['Config'].get('Labels') or {}).get(REVISION_LABEL)
            else:
                raise DeployError('Unknown deployment action')
            current_revision = (current['Config'].get('Labels') or {}).get(REVISION_LABEL)
            already_current = (action == 'deploy' and target['Id'] == current['Image']
                               and current_revision == requested_revision)
            self.output(self.service + ': version decision ' + json.dumps({
                'action': action, 'current_image_id': current['Image'], 'requested_image_id': target['Id'],
                'current_revision': safe_revision(current_revision),
                'requested_revision': safe_revision(requested_revision),
                'current_image_revision': safe_revision((current_image.get('Config', {}).get('Labels') or {}).get(REVISION_LABEL)),
                'requested_image_revision': safe_revision((target.get('Config', {}).get('Labels') or {}).get(REVISION_LABEL)),
                'decision': 'already-healthy' if already_current else 'replace'}, sort_keys=True))
            if already_current:
                self.output(self.service + ': requested image is already healthy; no restart')
                return
            old_reference = (self.original_state['last-good-image']
                             if self.original_state and self.original_state.get('last-good-image-id') == current['Image']
                             else current['Image'])
            self.requested_image = target['Id']
            self.requested_revision = requested_revision
            self.retained_id = retained['Id'] if retained is not None else None
            self.transaction('prepared')
            self.stop_attempted = True
            self.engine.stop(current['Id'], max(self.grace_seconds, current['Config'].get('StopTimeout') or 0))
            self.ensure_stopped(current['Id'], clean=action == 'deploy')
            self.transaction('original-stopped')
            if self.service == 'odisseu':
                self.ensure_stopped(current['Id'], clean=True)
                self.single_writer(None)
                self.auth_backup = self.profile.snapshot(self.transaction_name, current['Id'], current['Image'])
                self.profile.validated(self.auth_backup, current['Id'], current['Image'])
                self.transaction('auth-snapshot-ready')
            self.engine.rename(current['Id'], self.name + '-previous-' + self.transaction_name)
            self.original_name_changed = True
            if retained is None:
                self.create_attempted = True
                self.transaction('creating-replacement')
                self.new_id = self.engine.create(self.name, configuration)
                self.verify_created(current, self.new_id)
                created = self.engine.inspect(self.new_id)
                if (created['Image'] != target['Id'] or
                        (created['Config'].get('Labels') or {}).get(REVISION_LABEL) != requested_revision):
                    raise DeployError('Replacement image identity or revision differs from requested image')
            else:
                self.new_id = retained['Id']
                self.engine.rename(self.new_id, self.name)
            self.transaction('replacement-created')
            self.single_writer(self.new_id)
            self.new_start_attempted = True
            self.transaction('starting-replacement')
            self.engine.start(self.new_id)
            self.transaction('replacement-started')
            self.wait_ready(self.new_id, require_no_restarts=retained is None)
            self.transaction('replacement-verified')
            state = {'last-good-image': image, 'last-good-image-id': target['Id'],
                     'previous-image': old_reference, 'previous-image-id': current['Image'],
                     'previous-container': current['Id'],
                     'deployed-revision': image.rsplit(':', 1)[-1] if IMAGE_RE.fullmatch(image) else ''}
            self.record(state)
            self.transaction('committed')
            self.archive_transaction('committed')
            self.output(self.service + ': deployment healthy; active image ' + image)
        except BaseException as error:
            self.output(self.service + ': ' + (str(error) if isinstance(error, (DeployError, ProfileError))
                                             else 'Deployment interrupted or internal failure'))
            if self.stop_attempted:
                try:
                    self.restore()
                except BaseException:
                    self.output(self.service + ': ROLLBACK FAILED; operator recovery required. Containers and private evidence retained.')
                    raise DeployError('Deployment and rollback failed') from None
            raise DeployError('Deployment failed') from None
        finally:
            if self.lock is not None:
                os.close(self.lock)
                self.lock = None

def main(argv):
    os.umask(0o077)
    if os.geteuid() != 0:
        raise DeployError('Run through the installed service-specific sudo wrapper')
    if len(argv) < 2 or argv[0] not in SERVICES or argv[1] not in ('deploy', 'rollback', 'recover'):
        raise DeployError('Usage: SERVICE deploy IMAGE [APP_DIR] | SERVICE rollback|recover [APP_DIR]')
    service, action = argv[:2]
    if len(argv) not in ((3, 4) if action == 'deploy' else (2, 3)):
        raise DeployError('Invalid deployment arguments')
    image = argv[2] if action == 'deploy' else None
    app_dir = argv[3] if action == 'deploy' and len(argv) == 4 else (argv[2] if action in ('rollback', 'recover') and len(argv) == 3 else None)
    if app_dir and not re.fullmatch(r'/[A-Za-z0-9_./-]+', app_dir):
        raise DeployError('Invalid application directory')
    interruption_seen = False
    def interrupted(_signum, _frame):
        nonlocal interruption_seen
        if not interruption_seen:
            interruption_seen = True
            raise DeployError('Deployment interrupted')
    for signum in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(signum, interrupted)
    Deployment(service, Engine(), app_dir=app_dir).run(action, image)

if __name__ == '__main__':
    try:
        main(sys.argv[1:])
    except (DeployError, OSError):
        print('Deployment did not complete; consult safe status and private VM state.', file=sys.stderr)
        sys.exit(1)
