"""Materialização na VM. Nunca recebe um runner que registre stdout de inspect."""
from __future__ import annotations

import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import signal
import subprocess
import tempfile

from a6_production_contract import JVM_ENV_KEYS, NODE_ENV_KEYS
from a6_production_deploy import DeployError
from a6_v3_release_gate import POKEMON_ID, RELEASE_MODE

ENV_PLACEHOLDER = '<inspect-ao-vivo-sem-log>'
SECCOMP_PLACEHOLDER = '<seccomp-copiado-do-inspect-sem-log>'


def _private(path, mode):
    info = path.lstat()
    if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != mode or path.is_symlink():
        raise DeployError('runtime_permissions_invalid')


def _tmpfs(path):
    if not path.is_absolute() or path.resolve() != path:
        raise DeployError('tmpfs_required')
    # findmnt consulta o filesystem que contém o caminho, não apenas seu nome.
    try:
        result = subprocess.run(['findmnt', '-n', '-o', 'FSTYPE', '-T', str(path)],
                                capture_output=True, text=True, check=False)
    except OSError as exc:
        raise DeployError('tmpfs_required') from exc
    if result.returncode or result.stdout.strip() != 'tmpfs':
        raise DeployError('tmpfs_required')


class Materializer:
    def __init__(self, prefix, root='/dev/shm', profiles=None, v3_release=False):
        self.prefix = list(prefix)
        self.v3_release = v3_release is True
        self.root = Path(root) / ('odisseu-a6-runtime-' + str(os.geteuid()))
        self.profiles = profiles or {}
        self.lock = None

    def prepare(self):
        _tmpfs(self.root.parent)
        old = os.umask(0o077)
        try:
            self.root.mkdir(mode=0o700, exist_ok=True)
            _private(self.root, 0o700)
            fd = os.open(self.root / 'lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
            self.lock = os.fdopen(fd, 'w')
            _private(self.root / 'lock', 0o600)
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # SIGKILL não pode ser tratado. O próximo processo limpa antes de admitir a janela.
            for path in self.root.iterdir():
                if path.name == 'lock':
                    continue
                if not re.fullmatch(r'env-[a-z0-9_]+', path.name):
                    raise DeployError('runtime_residue_unknown')
                _private(path, 0o600)
                if not path.is_file():
                    raise DeployError('runtime_residue_unknown')
                path.unlink()
        except (OSError, ValueError) as exc:
            raise DeployError('runtime_cleanup_failed') from exc
        finally:
            os.umask(old)

    def _inspect(self, service):
        result = subprocess.run(self.prefix + ['inspect', 'odisseu-a6-' + service],
                                capture_output=True, text=True, check=False)
        if result.returncode:
            raise DeployError('runtime_inspect_failed')
        try:
            docs = json.loads(result.stdout)
            if len(docs) != 1 or docs[0]['Name'] != '/odisseu-a6-' + service:
                raise ValueError()
            return docs[0]
        except (ValueError, KeyError, TypeError) as exc:
            raise DeployError('runtime_inspect_invalid') from exc

    def _seccomp(self, service, doc):
        options = doc.get('HostConfig', {}).get('SecurityOpt') or []
        specs = [o[8:] for o in options if o.startswith('seccomp=')]
        if len(specs) > 1 or specs == ['unconfined']:
            raise DeployError('seccomp_refused')
        if not specs:
            info = subprocess.run(self.prefix + ['info', '--format', '{{json .SecurityOptions}}'],
                                  capture_output=True, text=True, check=False)
            if info.returncode or 'name=seccomp,profile=builtin' not in info.stdout:
                raise DeployError('seccomp_default_unverified')
            return None
        known = self.profiles.get(service)
        if not known:
            raise DeployError('seccomp_profile_required')
        path, expected = known
        path = Path(path)
        if not path.is_absolute() or path.resolve() != path or path.is_symlink():
            raise DeployError('seccomp_profile_invalid')
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise DeployError('seccomp_hash_mismatch')
        info = path.stat()
        if info.st_uid not in (0, os.geteuid()) or info.st_mode & 0o022:
            raise DeployError('seccomp_profile_permissions')
        try:
            profile = json.loads(data)
            effective = json.loads(specs[0])
        except ValueError:
            # Docker inspect normalmente guarda JSON inline; caminhos não são evidência suficiente.
            raise DeployError('seccomp_effective_unknown') from None
        if profile != effective or not isinstance(profile, dict) or not profile.get('defaultAction'):
            raise DeployError('seccomp_policy_mismatch')
        return 'seccomp=' + str(path)

    def _environment(self, service, doc):
        env = doc.get('Config', {}).get('Env')
        required = JVM_ENV_KEYS if service == 'jvm' else NODE_ENV_KEYS
        if not isinstance(env, list) or not env:
            raise DeployError('environment_incomplete')
        keys = []
        for item in env:
            if not isinstance(item, str) or '=' not in item or any(c in item for c in '\r\n\0'):
                raise DeployError('environment_unrepresentable')
            key = item.split('=', 1)[0]
            if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', key):
                raise DeployError('environment_unrepresentable')
            keys.append(key)
        if len(set(keys)) != len(keys) or not set(required).issubset(keys):
            raise DeployError('environment_incomplete')
        if any(not item.split('=', 1)[1] for item in env if item.split('=', 1)[0] in required):
            raise DeployError('environment_incomplete')
        return env

    def preflight(self, service):
        if self.lock is None:
            self.prepare()
        doc = self._inspect(service)
        self._environment(service, doc)
        self._seccomp(service, doc)

    def __call__(self, argv, service):
        if self.lock is None:
            raise DeployError('runtime_not_prepared')
        doc = self._inspect(service)
        env = self._environment(service, doc)
        if service == 'jvm' and self.v3_release:
            # Only the JVM is allowed to receive release flags. All other
            # existing tokens and variables remain untouched, never logged.
            overrides = {
                'POKEMON_PROGRESSIVE_XP_V3': 'true',
                'A6_V3_RECONCILIATION_ENABLED': 'true',
                'A6_V3_RECONCILIATION_ALLOWED_ID': POKEMON_ID,
            }
            env = [item for item in env if item.split('=', 1)[0] not in overrides]
            env += [key + '=' + val for key, val in overrides.items()]
        seccomp = self._seccomp(service, doc)
        if argv.count(ENV_PLACEHOLDER) != 1:
            raise DeployError('environment_placeholder_invalid')
        resolved = []
        for arg in argv:
            if arg == SECCOMP_PLACEHOLDER:
                if seccomp:
                    resolved.append(seccomp)
                else:
                    if not resolved or resolved.pop() != '--security-opt':
                        raise DeployError('seccomp_argv_invalid')
            else:
                resolved.append(arg)
        if service == 'jvm' and seccomp:
            resolved[-1:-1] = ['--security-opt', seccomp]
        old = os.umask(0o077)
        path = None
        try:
            fd, name = tempfile.mkstemp(prefix='env-', dir=self.root)
            path = Path(name)
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                stream.write('\n'.join(env) + '\n')
            _private(path, 0o600)
        except BaseException:
            if path is not None:
                try:
                    path.unlink()
                except OSError:
                    raise DeployError('runtime_cleanup_failed') from None
            raise DeployError('runtime_materialization_failed') from None
        finally:
            os.umask(old)
        resolved[resolved.index(ENV_PLACEHOLDER)] = str(path)
        # Guardião separado: EOF do pipe também acontece quando o pai recebe SIGKILL.
        read_fd = write_fd = None
        try:
            read_fd, write_fd = os.pipe()
            pid = os.fork()
        except BaseException:
            for descriptor in (read_fd, write_fd):
                if descriptor is not None:
                    os.close(descriptor)
            try:
                path.unlink()
            except OSError:
                raise DeployError('runtime_cleanup_failed') from None
            raise DeployError('runtime_guardian_failed') from None
        if pid == 0:
            os.close(write_fd)
            self.lock.close()
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            try:
                while os.read(read_fd, 1):
                    pass
                if path.exists():
                    path.unlink()
                os._exit(0)
            except BaseException:
                os._exit(2)
        os.close(read_fd)

        def cleanup():
            try:
                path.unlink()
            except OSError as exc:
                raise DeployError('runtime_cleanup_failed') from exc
            finally:
                os.close(write_fd)
                _, status = os.waitpid(pid, 0)
            if status:
                raise DeployError('runtime_cleanup_failed')
        return resolved, cleanup