#!/usr/bin/env python3
"""Retomada do runner contra o helper instalado, sem mockar --status.

Executado só pelo harness AMD64 depois de o sudoers e o backup sintético
root-owned serem montados.
"""
import importlib.util
from pathlib import Path

from a6_supervised_deploy import run_supervised


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('a6_supervised_fixture', ROOT / 'scripts' / 'test-a6-supervised-deploy.py')
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def main():
    destination = '/var/backups/odisseu-a6/fixture/data'
    world = fixture.World()
    # A substituição já ocorreu: JVM nova saudável e antiga parada.
    world.containers['odisseu-a6-jvm']['image'] = fixture.JVM_DIGEST
    world.containers['odisseu-a6-jvm-previous'] = {
        'image': fixture.CURRENT['jvm']['imageId'], 'running': False,
    }
    env = dict(fixture.ENV, A6_LIVE_BACKUP='autorizar-backup-producao-a6',
               A6_STATE_DIR='/tmp/a6-real-admin-state')
    report = run_supervised(world.runner, fixture.SHA, fixture.JVM_DIGEST, fixture.NODE_DIGEST,
                            env, destination, None, prior={'steps': ['update_jvm']},
                            materialize=fixture.materialize)
    assert report['deployComplete'], report
    assert report['blocked'] == []
    assert 'stop_jvm' in report['steps']
    assert world.containers['odisseu-a6-jvm']['running']
    print('PASS: retomada usa --status real com JVM nova em execução')


if __name__ == '__main__':
    main()
