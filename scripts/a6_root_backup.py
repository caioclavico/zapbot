#!/usr/bin/env python3
"""Helper administrativo: somente backup; sem parâmetro de origem ou restore live.

Instalar código e dependências em diretório root:root não gravável pelo operador.
Não autorizar sudo para uma cópia deste arquivo em checkout gravável.
"""
from __future__ import annotations
import hashlib
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
                                  read_manifest, restore_isolated, _dir_bytes, _verify)
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


def _node_active_operations(node_state):
    """Contador do marcador, ou da API autenticada quando o JSON omite activeOps.

    O marcador quiescent/open do Node costuma ter só phase/timestamps. None != 0
    e quebrava o backup com writes_in_flight. O token fica só no env do container.
    """
    value = node_state.get('activeOps')
    if isinstance(value, int) and value >= 0:
        return value
    script = (
        "node -e "
        "'const http=require(\"http\");"
        "const req=http.request({"
        "hostname:\"127.0.0.1\",port:3001,"
        "path:\"/ops/maintenance/status\",method:\"GET\","
        "headers:{\"x-a6-node-maintenance-token\":process.env.ODISSEU_NODE_MAINTENANCE_TOKEN}"
        "},function(res){var d=\"\";res.on(\"data\",function(c){d+=c});"
        "res.on(\"end\",function(){process.stdout.write(d+\"\\n\"+res.statusCode)})});"
        "req.on(\"error\",function(e){console.error(e);process.exit(1)});req.end()'"
    )
    raw = _read(['docker', 'exec', 'odisseu-a6-node', 'sh', '-c', script])
    lines = raw.splitlines()
    if not lines or not lines[-1].strip().isdigit():
        raise ProductionBackupError('node_ops_unreadable')
    if int(lines[-1]) != 200:
        raise ProductionBackupError('node_ops_unreadable')
    try:
        body = json.loads('\n'.join(lines[:-1]) or '{}')
    except ValueError as exc:
        raise ProductionBackupError('node_ops_unreadable') from exc
    active = body.get('activeOperations')
    if not isinstance(active, int) or active < 0:
        raise ProductionBackupError('node_ops_unreadable')
    return active


def _jvm_active_writes(marker):
    writes = _int_token(marker, ':active-writes')
    if isinstance(writes, int) and writes >= 0:
        return writes
    raise ProductionBackupError('jvm_writes_unreadable')


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
                      _jvm_active_writes(marker), _node_active_operations(node_state),
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
    return read_manifest(path)


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
        'manifestSha256': report.get('manifestSha256'),
    }


def _manifest_sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_attestation(destination, window, manifest):
    """Proveniência durável criada somente depois da cópia e do restore validados."""
    payload = {
        'window': window,
        'volume': DATOMIC_VOLUME,
        'manifestSha256': _manifest_sha256(manifest),
        'files': len(_manifest(manifest)),
        'sourcePreserved': True,
        'restoreIsolated': True,
    }
    target = destination / 'backup-attestation.json'
    encoded = (json.dumps(payload, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')
    descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.write(descriptor, encoded)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _attestation(destination, window, manifest):
    target = destination / 'backup-attestation.json'
    try:
        info = target.lstat()
        if stat.S_ISLNK(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != 0:
            raise ValueError()
        payload = json.loads(target.read_text(encoding='utf-8'))
    except (OSError, ValueError, TypeError) as exc:
        raise ProductionBackupError('backup_attestation_invalid') from exc
    expected = {
        'window': window,
        'volume': DATOMIC_VOLUME,
        'manifestSha256': _manifest_sha256(manifest),
        'files': len(_manifest(manifest)),
        'sourcePreserved': True,
        'restoreIsolated': True,
    }
    if payload != expected:
        raise ProductionBackupError('backup_attestation_invalid')
    return payload


def status(window):
    """Verifica o artefato concluído; não observa nem compara o Datomic em uso."""
    destination = _destination(window)
    preflight(destination / 'data')
    backup, restore = destination / 'data', destination / 'restore-isolated'
    manifest = _manifest(destination / 'backup-manifest.txt')
    _no_symlinks(backup); _no_symlinks(restore)
    attestation = _attestation(destination, window, destination / 'backup-manifest.txt')
    _verify(backup, restore, manifest)
    return _receipt(window, {'manifestVerified': True, 'sourcePreserved': True,
                             'restoreIsolated': True, 'files': len(manifest),
                             'manifestSha256': attestation['manifestSha256']}, reused=True)


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
    _write_attestation(destination, window, destination / 'backup-manifest.txt')
    report['manifestSha256'] = _manifest_sha256(destination / 'backup-manifest.txt')
    return _receipt(window, report)


def _backup_complete(destination):
    destination = Path(destination)
    return (
        (destination / 'backup-manifest.txt').is_file()
        and (destination / 'data').is_dir()
        and (destination / 'restore-isolated').is_dir()
    )


def ensure(window):
    """Retomada idempotente: valida um backup concluído ou cria um novo.

    Diretório vazio/parcial (mkdir sem manifesto) não é tratado como sucesso —
    isso envenenava a janela com backup_admin_unavailable via status().
    """
    destination = _destination(window)
    if destination.exists():
        if _backup_complete(destination):
            return status(window)
        raise ProductionBackupError('backup_incomplete')
    return backup(window)


def cli(argv=None):
    args = sys.argv[1:] if argv is None else argv
    post_ops = ('--post-v3-preflight', '--post-v3-backup', '--post-v3-audit', '--post-v3-status',
                '--post-v3-live-gate', '--post-v3-gate-status', '--post-v3-provision-plan')
    if len(args) != 2 or args[0] not in ('--backup', '--ensure', '--preflight', '--status', *post_ops):
        print('uso: a6-root-backup --preflight|--backup|--ensure|--status WINDOW_ID', file=sys.stderr)
        return 2
    try:
        old = os.umask(0o077)
        try:
            if args[0] in post_ops:
                if os.geteuid() != 0:
                    raise ProductionBackupError('backup_root_required')
                action = args[0].removeprefix('--post-v3-').replace('-', '_')
                if action == "provision_plan":
                    import a6_post_v3_plan
                    result = a6_post_v3_plan.install(args[1])
                else:
                    import a6_post_v3_backup as post
                    result = post.preflight(args[1]) if action == 'preflight' else (
                        getattr(post, action)(sys.modules[__name__], args[1]))
            else:
                result = ({'ready': True, **preflight(BACKUP_ROOT / args[1] / 'data')}
                      if args[0] == '--preflight' else
                      backup(args[1]) if args[0] == '--backup' else
                      ensure(args[1]) if args[0] == '--ensure' else status(args[1]))
        finally:
            os.umask(old)
        print(json.dumps(result))
        return 0
    except (ProductionBackupError, DeployError, OSError, KeyError, ValueError) as exc:
        # Motivo operacional sem caminhos de save/token.
        print('backup_admin_blocked ' + str(exc), file=sys.stderr)
        return 2

if __name__ == '__main__': raise SystemExit(cli())
