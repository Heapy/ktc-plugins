#!/usr/bin/env python3
"""Exercise generated launchers from paths with spaces and an offline cache."""
import argparse
import concurrent.futures
import importlib.util
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('binary', type=Path)
args = p.parse_args()
spec = importlib.util.spec_from_file_location('packager', ROOT / 'scripts/package-release.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)
version = re.search(r'const val VERSION = "([^"]+)"', (ROOT / 'core/src/Cli.kt').read_text()).group(1)
windows = os.name == 'nt'
target = 'windows-x64' if windows else ('macos-arm64' if platform.system() == 'Darwin' else ('linux-arm64' if platform.machine() in ('aarch64', 'arm64') else 'linux-x64'))
with tempfile.TemporaryDirectory(prefix='ktc wrapper test ') as tmp:
    tmp = Path(tmp)
    binaries = tmp / 'input'; binaries.mkdir()
    # Other target bytes are never executed; package requires every pin to be present.
    for t in packager.TARGETS:
        name = f'ktc-plugins-{version}-{t}' + ('.exe' if t == 'windows-x64' else '')
        shutil.copyfile(args.binary, binaries / name)
    output = tmp / 'release bundle'
    packager.package(version, binaries, output)
    cached = tmp / 'offline binary cache' / version / target
    cached.mkdir(parents=True)
    name = f'ktc-plugins-{version}-{target}' + ('.exe' if windows else '')
    shutil.copyfile(output / name, cached / name)
    if not windows: (cached / name).chmod(0o755)
    env = dict(os.environ, KTC_PLUGINS_BINARY_CACHE=str(tmp / 'offline binary cache'))
    env.pop('KTC_PLUGINS_BINARY', None)
    wrapper = output / ('ktc-plugins.bat' if windows else 'ktc-plugins')
    def run(arguments):
        return subprocess.run([str(wrapper), *arguments], env=env, cwd=tmp, capture_output=True, text=True)
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        for result in pool.map(run, [['--version']] * 4):
            assert result.returncode == 0 and result.stdout.strip() == version, result.stdout + result.stderr
    project = tmp / 'project with spaces'; project.mkdir()
    result = run(['status', '--project-dir', str(project), '--cache-dir', str(tmp / 'sources with spaces')])
    assert result.returncode == 0, result.stdout + result.stderr
    with (cached / name).open('ab') as file: file.write(b'corrupt')
    result = run(['--version'])
    assert result.returncode != 0 and 'checksum mismatch' in result.stderr, result.stdout + result.stderr
    if not windows:
        # Fake only the HTTP acquisition, exercising the real verification/rename/exec bootstrap.
        stub = tmp / 'curl stub'; stub.mkdir()
        curl = stub / 'curl'
        curl.write_text("#!/bin/sh\nwhile [ \"$#\" -gt 0 ]; do\n  if [ \"$1\" = -o ]; then\n    cp \"$KTC_TEST_SOURCE_BINARY\" \"$2\" || exit 1\n    if [ \"${KTC_TEST_CORRUPT:-}\" = 1 ]; then printf corrupt >> \"$2\"; fi\n    exit 0\n  fi\n  shift\ndone\nexit 1\n")
        curl.chmod(0o755)
        env['PATH'] = str(stub) + os.pathsep + env['PATH']
        env['KTC_TEST_SOURCE_BINARY'] = str(args.binary.resolve())
        (cached / name).unlink()
        with concurrent.futures.ThreadPoolExecutor(4) as pool:
            for result in pool.map(run, [['--version']] * 4):
                assert result.returncode == 0 and result.stdout.strip() == version, result.stdout + result.stderr
        (cached / name).unlink()
        env['KTC_TEST_CORRUPT'] = '1'
        result = run(['--version'])
        assert result.returncode != 0 and 'checksum mismatch' in result.stderr
        assert not (cached / name).exists()
    print('Wrapper paths, argument forwarding, concurrent bootstrap/offline launches and checksum rejection passed')
