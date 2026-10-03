"""Valida o carregamento de imagem com Docker simulado, sem acessar a VM."""
import gzip
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('load-image-vm.sh').resolve()
MOCK = '''#!/usr/bin/env python3
import os, pathlib, sys, time
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
if name == 'timeout':
    while args[0].startswith('--'): args.pop(0)
    os.execvp(args[1], args[1:])
if name == 'sleep': time.sleep(0.05)
if name == 'stat': print(pathlib.Path(args[-1]).stat().st_size)
if name == 'docker':
    with open(os.environ['CALLS'], 'a') as f: f.write(' '.join(args) + '\\n')
    if args[0] == 'load':
        assert pathlib.Path(args[-1]).read_bytes() == b'image data'
        sys.exit(int(os.environ['LOAD_STATUS']))
    if args[:2] == ['image', 'inspect']: sys.exit(int(os.environ['INSPECT_STATUS']))
'''


class LoadImageTest(unittest.TestCase):
    def run_load(self, load_status=0, inspect_status=0, corrupt=False):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            for name in ['timeout', 'sleep', 'stat', 'docker', 'flock', 'free', 'df', 'vmstat', 'ps']:
                path = bin_dir / name
                path.write_text(MOCK)
                path.chmod(0o755)
            release = root / 'app' / 'releases' / ('a' * 40)
            release.mkdir(parents=True)
            archive = release / 'image.tar.gz'
            archive.write_bytes(b'invalid gzip' if corrupt else gzip.compress(b'image data'))
            env = dict(os.environ, PATH=str(bin_dir) + ':' + os.environ['PATH'],
                       CALLS=str(root / 'calls'), LOAD_STATUS=str(load_status),
                       INSPECT_STATUS=str(inspect_status))
            result = subprocess.run(['bash', str(SCRIPT), str(archive), 'zapbot:' + 'a' * 40],
                                    env=env, capture_output=True, text=True, timeout=10)
            calls = (root / 'calls').read_text()
            self.assertFalse((release / 'image.tar').exists())
            self.assertEqual(archive.exists(), result.returncode != 0)
            return result, calls

    def test_success_verifies_image_and_removes_archive(self):
        result, calls = self.run_load()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('image inspect zapbot:', calls)

    def test_timeout_is_reported_and_archive_is_preserved(self):
        result, calls = self.run_load(load_status=124)
        self.assertEqual(result.returncode, 124)
        self.assertIn('código 124', result.stderr)
        self.assertNotIn('image inspect', calls)

    def test_missing_tag_does_not_report_success(self):
        result, _ = self.run_load(inspect_status=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('Carregamento confirmado', result.stdout)

    def test_corrupt_archive_does_not_reach_docker_load(self):
        result, calls = self.run_load(corrupt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('load -i', calls)


if __name__ == '__main__':
    unittest.main()
