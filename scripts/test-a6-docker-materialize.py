#!/usr/bin/env python3
"""Executar SOMENTE no daemon dind novo criado pelo harness."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from a6_production_contract import CURRENT, NODE_ENV_KEYS
from a6_production_deploy import recreate_argv
from a6_runtime_materialize import Materializer, ENV_PLACEHOLDER, SECCOMP_PLACEHOLDER


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout


def main():
    if os.environ.get('DOCKER_HOST') != 'unix:///run/a6-test/docker.sock':
        raise SystemExit('isolated_daemon_required')
    # O daemon deve estar vazio antes do fixture; nunca usar o daemon do operador.
    assert not run(['docker', 'ps', '-aq']).strip()
    run(['docker', 'network', 'create', 'odisseu-a6-cutover_default'])
    run(['docker', 'build', '-t', 'a6-ops-fixture', '-'], input='FROM alpine:3.20\nCMD ["sleep","300"]\n')
    profile = {'defaultAction': 'SCMP_ACT_ALLOW', 'syscalls': [{'names': ['mount'], 'action': 'SCMP_ACT_ERRNO'}]}
    # Perfil real customizado; defaultAction ALLOW é o caso de um denylist efetivo
    # e deve ser preservado, sem confundi-lo com seccomp=unconfined.
    with tempfile.TemporaryDirectory() as directory:
        profile_path = Path(directory) / 'seccomp.json'
        profile_path.write_text(json.dumps(profile)); profile_path.chmod(0o600)
        fixture_env = Path(directory) / 'fixture.env'
        fixture_env.write_text('\n'.join(k + '=isolated-test' for k in NODE_ENV_KEYS) + '\nEXTRA= spaces=preserved \n')
        argv = recreate_argv('node', 'a6-ops-fixture', CURRENT['node'], True)
        argv[argv.index(ENV_PLACEHOLDER)] = str(fixture_env)
        argv[argv.index(SECCOMP_PLACEHOLDER)] = 'seccomp=' + str(profile_path)
        run(argv)
        m = Materializer(['docker'], profiles={'node': (str(profile_path), hashlib.sha256(profile_path.read_bytes()).hexdigest())})
        m.preflight('node')
        resolved, cleanup = m(recreate_argv('node', 'a6-ops-fixture', CURRENT['node'], True), 'node')
        env_path = Path(resolved[resolved.index('--env-file') + 1])
        assert env_path.exists()
        before = json.loads(run(['docker', 'inspect', 'odisseu-a6-node']))[0]
        run(['docker', 'stop', 'odisseu-a6-node'])
        run(['docker', 'rename', 'odisseu-a6-node', 'old-node'])
        try:
            run(resolved)
        finally:
            cleanup()
        assert not env_path.exists()
        after = json.loads(run(['docker', 'inspect', 'odisseu-a6-node']))[0]
        assert before['Config']['Env'] == after['Config']['Env']
        assert before['HostConfig']['SecurityOpt'] == after['HostConfig']['SecurityOpt']
        assert before['Mounts'] == after['Mounts']
        assert after['State']['Running']
        print('PASS: argv integral, ambiente tmpfs, seccomp e mounts preservados no daemon isolado')

if __name__ == '__main__': main()
