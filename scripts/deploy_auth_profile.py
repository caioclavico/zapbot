"""Cold, private WhatsApp snapshots. No deletion, logout or live-profile copy.

The deployment caller must prove every container sharing the profile is stopped
before calling snapshot/restore. Failed/partial profiles are retained as evidence.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import errno


class ProfileError(Exception):
    pass


def assert_profile_idle(auth, proc_dir=Path('/proc')):
    """Inspect host processes without logging command lines or touching them."""
    if not proc_dir.is_dir():
        raise ProfileError('Host process inventory unavailable; profile lock cleanup blocked')
    for process in proc_dir.iterdir():
        if not process.name.isdigit():
            continue
        try:
            arguments = (process / 'cmdline').read_bytes().split(b'\0')
        except OSError as error:
            if error.errno in (errno.ENOENT, errno.ESRCH):
                continue  # Process exited during inspection.
            raise ProfileError('Host process cannot be inspected; profile lock cleanup blocked') from error
        for index, argument in enumerate(arguments):
            value = None
            if argument.startswith(b'--user-data-dir='):
                value = os.fsdecode(argument.split(b'=', 1)[1])
            elif argument == b'--user-data-dir' and index + 1 < len(arguments):
                value = os.fsdecode(arguments[index + 1])
            if value is not None:
                path = Path(value)
                if not path.is_absolute():
                    try:
                        path = (process / 'cwd').resolve(strict=True) / path
                    except OSError as error:
                        raise ProfileError('Browser working directory unavailable; lock cleanup blocked') from error
                path = path.resolve()
                # Container paths are intentionally conservative: even another
                # browser using the same internal path blocks lock removal.
                for root in (Path(auth).resolve(), Path('/app/.wwebjs_auth')):
                    if path == root or root in path.parents:
                        raise ProfileError('A browser still owns a WhatsApp profile; lock cleanup blocked')


def sync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def signature(root):
    result = {}
    hardlinks = {}
    def visit(path, relative):
        info = path.lstat()
        entry = {'mode': stat.S_IMODE(info.st_mode), 'uid': info.st_uid, 'gid': info.st_gid}
        if stat.S_ISLNK(info.st_mode):
            entry.update(type='link', target=os.readlink(path))
        elif stat.S_ISDIR(info.st_mode):
            entry['type'] = 'directory'
            for child in sorted(path.iterdir()):
                visit(child, str(Path(relative) / child.name) if relative != '.' else child.name)
        elif stat.S_ISREG(info.st_mode):
            if info.st_nlink > 1:
                group = hardlinks.setdefault((info.st_dev, info.st_ino), {'count': info.st_nlink, 'paths': []})
                group['paths'].append(relative)
            digest = hashlib.sha256()
            with path.open('rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    digest.update(chunk)
            entry.update(type='file', size=info.st_size, digest=digest.hexdigest())
        else:
            raise ProfileError('Profile contains an unsupported special file; snapshot blocked')
        result[relative] = entry
    visit(Path(root), '.')
    for group in hardlinks.values():
        if len(group['paths']) != group['count']:
            raise ProfileError('Profile has hard links outside its root; safe snapshot requires review')
        for relative in group['paths']:
            result[relative]['hardlink'] = min(group['paths'])
    return result


def copy_profile(source, target):
    links = {}
    def copy_file(original, destination):
        info = os.stat(original, follow_symlinks=False)
        key = (info.st_dev, info.st_ino)
        if info.st_nlink > 1 and key in links:
            os.link(links[key], destination, follow_symlinks=False)
            return destination
        value = shutil.copy2(original, destination)
        if info.st_nlink > 1:
            links[key] = destination
        return value
    shutil.copytree(source, target, symlinks=True, copy_function=copy_file)
    # copy2/copystat do not preserve ownership; LocalAuth may run as non-root.
    for relative, entry in signature(source).items():
        path = target if relative == '.' else target / relative
        info = path.lstat()
        if (info.st_uid, info.st_gid) != (entry['uid'], entry['gid']):
            os.chown(path, entry['uid'], entry['gid'], follow_symlinks=False)
        if entry['type'] == 'file':
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        elif entry['type'] == 'directory':
            sync_dir(path)
    sync_dir(target.parent)


class SessionProfile:
    def __init__(self, app_dir, state_dir, owner_uid, read_private, write_private):
        self.auth = Path(app_dir) / '.wwebjs_auth'
        self.state_dir = Path(state_dir)
        self.owner_uid, self.read, self.write = owner_uid, read_private, write_private

    def remove_orphan_locks(self):
        """Caller must prove stopped containers AND an idle host process inventory.

        Only unlink lock entries in real session directories. Never follow a
        SingletonSocket symlink, recurse into a profile, or remove a directory.
        A cold snapshot is taken by the caller before this operation.
        """
        if self.auth.is_symlink() or not self.auth.is_dir():
            raise ProfileError('Session root unavailable; lock cleanup blocked')
        entries = []
        for session in self.auth.iterdir():
            if session.name != 'session' and not session.name.startswith('session-'):
                continue
            if session.is_symlink():
                raise ProfileError('Symlink session directory requires manual lock review')
            if not session.is_dir():
                continue
            for name in ('SingletonLock', 'SingletonSocket', 'SingletonCookie'):
                lock = session / name
                try:
                    info = lock.lstat()
                except FileNotFoundError:
                    continue
                if not (stat.S_ISLNK(info.st_mode) or stat.S_ISREG(info.st_mode)):
                    raise ProfileError('Unexpected Chromium lock type; manual review required')
                entries.append((lock, info.st_dev, info.st_ino))
        for lock, device, inode in entries:
            info = lock.lstat()
            if (info.st_dev, info.st_ino) != (device, inode):
                raise ProfileError('Chromium lock changed during cold cleanup')
            lock.unlink()
            sync_dir(lock.parent)
        return len(entries)

    def paths(self, transaction):
        if not isinstance(transaction, str) or not re.fullmatch(r'[0-9]{1,32}', transaction):
            raise ProfileError('Invalid profile snapshot transaction')
        return self.state_dir / ('auth-' + transaction), self.state_dir / ('auth-' + transaction + '.json')

    def snapshot(self, transaction, container_id, image_id):
        if self.auth.is_symlink() or not self.auth.is_dir():
            raise ProfileError('Session root must be a real directory')
        target, manifest = self.paths(transaction)
        if target.exists() or manifest.exists():
            raise ProfileError('Snapshot evidence already exists; it will not be overwritten')
        original = signature(self.auth)
        size = sum(e.get('size', 0) for e in original.values())
        # Space for the cold snapshot and a staged restore; failed profiles are
        # moved, not duplicated. Checks happen before starting a new browser.
        if min(shutil.disk_usage(self.state_dir).free, shutil.disk_usage(self.auth.parent).free) < 2 * size + 64 * 1024 * 1024:
            raise ProfileError('Insufficient free space for a consistent profile snapshot and restore')
        copy_profile(self.auth, target)
        if signature(target) != original:
            raise ProfileError('Session snapshot verification failed')
        # The state directory is private; also protect the snapshot root itself.
        os.chmod(target, 0o700)
        if target.stat().st_uid != self.owner_uid:
            os.chown(target, self.owner_uid, -1)
        sync_dir(target)
        self.write(manifest, json.dumps({'version': 1, 'transaction': transaction,
                   'container': container_id, 'image': image_id, 'original_root': original['.'],
                   'signature': signature(target)}, sort_keys=True) + '\n')
        return transaction

    def validated(self, transaction, container_id, image_id):
        target, manifest = self.paths(transaction)
        info = target.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != self.owner_uid or target.is_symlink() or info.st_mode & 0o077:
            raise ProfileError('Snapshot directory is not private')
        data = json.loads(self.read(manifest, self.owner_uid))
        if (data.get('version') != 1 or data.get('transaction') != transaction or
                data.get('container') != container_id or data.get('image') != image_id or
                signature(target) != data.get('signature')):
            raise ProfileError('Snapshot identity or contents failed validation')
        return target, data

    def restore(self, transaction, container_id, image_id, phase):
        target, data = self.validated(transaction, container_id, image_id)
        evidence = self.auth.parent / '.zapbot-auth-recovery'
        if not evidence.exists():
            evidence.mkdir(mode=0o700)
            sync_dir(evidence.parent)
        info = evidence.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != self.owner_uid or info.st_mode & 0o077:
            raise ProfileError('Profile recovery evidence directory is unsafe')
        failed, staged = evidence / (transaction + '-failed'), evidence / (transaction + '-restoring')
        expected = dict(data['signature'])
        expected['.'] = data['original_root']
        if self.auth.is_symlink() or failed.is_symlink() or staged.is_symlink():
            raise ProfileError('Profile recovery path must not be a symlink')
        if failed.exists() and self.auth.exists():
            if staged.exists() or signature(self.auth) != expected:
                raise ProfileError('Ambiguous profile restore; evidence retained')
            phase('profile-restored')
            return
        if not self.auth.exists() and not failed.exists():
            raise ProfileError('Session root disappeared outside the recorded restoration')
        if not failed.exists():
            if staged.exists():
                # Preserve an interrupted copy, rather than deleting/reusing it.
                staged.rename(evidence / (transaction + '-incomplete-' + str(time.time_ns())))
                sync_dir(evidence)
            copy_profile(target, staged)
            root = data['original_root']
            os.chmod(staged, root['mode'])
            if (staged.stat().st_uid, staged.stat().st_gid) != (root['uid'], root['gid']):
                os.chown(staged, root['uid'], root['gid'])
            sync_dir(staged)
            if signature(staged) != expected:
                raise ProfileError('Staged session restore failed validation')
            phase('profile-restore-ready')
            self.auth.rename(failed)
            sync_dir(self.auth.parent)
            sync_dir(evidence)
            phase('profile-original-archived')
        if not staged.is_dir() or signature(staged) != expected:
            raise ProfileError('Recorded profile restore is incomplete; evidence retained')
        staged.rename(self.auth)
        sync_dir(self.auth.parent)
        sync_dir(evidence)
        phase('profile-restored')
