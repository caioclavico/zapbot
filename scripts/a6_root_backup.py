#!/usr/bin/env python3
"""Helper administrativo: somente backup; sem parâmetro de origem ou restore live.

Instalar código e dependências em diretório root:root não gravável pelo operador.
Não autorizar sudo para uma cópia deste arquivo em checkout gravável.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
from types import SimpleNamespace

from a6_production_backup import (BACKUP_AUTHORIZATION, ProductionBackupError, execute_copy,
                                  restore_isolated, _dir_bytes, _verify)
from a6_production_contract import DATOMIC_SOURCE, DATOMIC_VOLUME, NODE_MOUNTS, mounts_match
from a6_production_deploy import DeployError, observe_gce, _mounts, _token, _int_token
from a6_mount_policy import Collection

BACKUP_ROOT = Path('/var/backups/odisseu-a6')


def _run(args):
    return subprocess.run(args, capture_output=True, text=True, check=False)


def _read(args):
    result = _run(args)
    if result.returncode:
        raise ProductionBackupError('admin_observation_failed')
    return result.stdout


def _no_symlinks(root):
    root = Path(root)
    if root.resolve() != root or root.is_symlink() or not root.is_dir():
        raise ProductionBackupError('source_path_invalid')
    for path in root.rglob('*'):
        info = path.lstat()
        if not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise ProductionBackupError('source_special_file_refused')


def _no_open_handles(source):
    """Conservador: recusa inclusive leitores e mappings; requer procfs e root."""
    proc = Path('/proc')
    if not (proc / 'self/fd').is_dir():
        raise ProductionBackupError('writer_observation_unavailable')
    for task in proc.iterdir():
        if not task.name.isdigit():
            continue
        try:
            for fd in (task / 'fd').iterdir():
                try:
                    target = os.readlink(fd)
                except FileNotFoundError:
                    continue
                if target == str(source) or target.startswith(str(source) + '/'):
                    raise ProductionBackupError('source_open_handle')
            maps = (task / 'maps').read_text()
            if str(source) + '/' in maps:
                raise ProductionBackupError('source_open_mapping')
        except (FileNotFoundError, ProcessLookupError):
            continue
        except PermissionError as exc:
            raise ProductionBackupError('writer_observation_unavailable') from exc


def _collect():
    vm = observe_gce(_run)
    vm['hostname'] = _read(['hostname']).strip()
    ids = _read(['docker', 'ps', '-aq']).split()
    if not ids:
        raise ProductionBackupError('writer_not_identified')
    docs = json.loads(_read(['docker', 'inspect', *ids]))
    by_name = {d['Name'].lstrip('/'): d for d in docs}
    jvm = by_name['odisseu-a6-jvm']; node = by_name['odisseu-a6-node']
    if jvm['State']['Running'] or not node['State']['Running']:
        raise ProductionBackupError('writers_state_invalid')
    if not mounts_match(_mounts(node), NODE_MOUNTS):
        raise ProductionBackupError('session_bind_mismatch')
    matches = [m for m in jvm['Mounts'] if m.get('Name') == DATOMIC_VOLUME]
    if len(matches) != 1 or matches[0]['Type'] != 'volume' or matches[0]['Source'] != DATOMIC_SOURCE:
        raise ProductionBackupError('source_not_live_datomic')
    # O kernel não preserva CapEff de processo parado; recusa SYS_ADMIN configurado
    # e usa zero apenas para o processo inexistente, já confirmado stopped.
    jvm['CapEff'] = '0'
    node_state = json.loads(_read(['docker', 'exec', 'odisseu-a6-node', 'cat', '/app/data/a6-maintenance.json']))
    marker = (Path(DATOMIC_SOURCE) / '.a6-maintenance/bridge-maintenance.edn').read_text()
    return Collection('docker-inspect-subprocess', tuple(docs), _read(['ps', '-eo', 'args']),
                      _token(marker, ':phase').lstrip(':'), node_state.get('phase'),
                      _int_token(marker, ':active-writes'), node_state.get('activeOps'),
                      DATOMIC_VOLUME, 'odisseu-a6-jvm'), vm


def preflight(destination):
    if os.geteuid() != 0:
        raise ProductionBackupError('backup_root_required')
    destination = Path(destination)
    window = destination.parent.name
    if (not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', window)
            or destination != BACKUP_ROOT / window / 'data'):
        raise ProductionBackupError('backup_destination_invalid')
    info = BACKUP_ROOT.lstat()
    if BACKUP_ROOT.resolve() != BACKUP_ROOT or info.st_uid != 0 or stat.S_IMODE(info.st_mode) != 0o700:
        raise ProductionBackupError('backup_root_permissions')
    return {"ready": True, "window": window, "operation": "preflight"}


def _destination(window):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', window):
        raise ProductionBackupError('window_id_invalid')
    return BACKUP_ROOT / window


def _manifest(path):
    rows = []
    try:
        for line in path.read_text(encoding='utf-8').splitlines():
            digest, relative = line.split('  ', 1)
            if (not re.fullmatch(r'[0-9a-f]{64}', digest) or not relative
                    or Path(relative).is_absolute() or '..' in Path(relative).parts):
                raise ValueError()
            rows.append({'path': relative, 'sha256': digest})
    except (OSError, ValueError) as exc:
        raise ProductionBackupError('backup_manifest_invalid') from exc
    if not rows:
        raise ProductionBackupError('backup_manifest_invalid')
    return rows


def _receipt(window, report, reused=False):
    return {
        'window': window,
        'operation': 'status',
        'reused': bool(reused),
        'manifestVerified': report.get('manifestVerified') is True,
        'sourcePreserved': report.get('sourcePreserved') is True,
        'restoreIsolated': report.get('restoreIsolated') is True,
        'volume': DATOMIC_VOLUME,
        'files': int(report.get('files') or 0),
    }


def status(window):
    """Atestado sem conteúdo dos jogadores; só root lê source, backup e restore."""
    destination = _destination(window)
    preflight(destination / 'data')
    source, backup, restore = Path(DATOMIC_SOURCE), destination / 'data', destination / 'restore-isolated'
    manifest = _manifest(destination / 'backup-manifest.txt')
    _no_symlinks(source); _no_symlinks(backup); _no_symlinks(restore)
    collection, vm = _collect()
    _no_open_handles(source)
    _verify(source, backup, manifest)
    _verify(backup, restore, manifest)
    _no_open_handles(source)
    again, again_vm = _collect()
    if (_signature(again) != _signature(collection) or again_vm != vm
            or again.jvm_phase != collection.jvm_phase or again.node_phase != collection.node_phase
            or again.active_jvm_writes != 0 or again.active_node_ops != 0):
        raise ProductionBackupError('backup_observation_changed')
    return _receipt(window, {'manifestVerified': True, 'sourcePreserved': True,
                             'restoreIsolated': True, 'files': len(manifest)}, reused=True)


def _signature(collection):
    return [(d.get('Id'), d.get('Name'), d.get('Mounts'), d.get('HostConfig'),
             d.get('State', {}).get('Running')) for d in collection.documents]


def backup(window):
    destination = _destination(window)
    preflight(destination / 'data')
    source = Path(DATOMIC_SOURCE)
    _no_symlinks(source)
    collection, vm = _collect()
    _no_open_handles(source)
    destination.mkdir(mode=0o700)  # Não reusa diretório parcial ou preexistente.
    size = _dir_bytes(source)
    free = shutil.disk_usage(destination).free
    report = execute_copy(collection, vm, destination / 'data', 'false', True,
                          BACKUP_AUTHORIZATION, free, size)
    _no_open_handles(source)
    again, again_vm = _collect()
    if (_signature(again) != _signature(collection) or again_vm != vm
            or again.jvm_phase != collection.jvm_phase or again.node_phase != collection.node_phase
            or again.active_jvm_writes != 0 or again.active_node_ops != 0):
        raise ProductionBackupError('backup_observation_changed')
    restored = restore_isolated(report['manifest'], destination / 'data', destination / 'restore-isolated')
    report.update(restoreIsolated=True, files=restored['files'])
    return _receipt(window, report)


def ensure(window):
    """Retomada idempotente: valida um backup concluído ou cria um novo."""
    destination = _destination(window)
    if destination.exists():
        return status(window)
    return backup(window)


def cli(argv=None):
    args = sys.argv[1:] if argv is None else argv
    if len(args) != 2 or args[0] not in ('--backup', '--ensure', '--preflight', '--status'):
        print('uso: a6-root-backup --preflight|--backup|--ensure|--status WINDOW_ID', file=sys.stderr)
        return 2
    try:
        old = os.umask(0o077)
        try:
            result = ({'ready': True, **preflight(BACKUP_ROOT / args[1] / 'data')}
                      if args[0] == '--preflight' else
                      backup(args[1]) if args[0] == '--backup' else
                      ensure(args[1]) if args[0] == '--ensure' else status(args[1]))
        finally:
            os.umask(old)
        print(json.dumps(result))
        return 0
    except (ProductionBackupError, DeployError, OSError, KeyError, ValueError):
        print('backup_admin_blocked', file=sys.stderr)
        return 2

if __name__ == '__main__': raise SystemExit(cli())
