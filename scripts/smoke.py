#!/usr/bin/env python3
"""Network acceptance test against pinned real producer repositories; no plugin execution."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('binary', type=Path)
args = p.parse_args()
binary = str(args.binary.resolve())

with tempfile.TemporaryDirectory(prefix='ktc plugins smoke ') as temp:
    root = Path(temp) / 'consumer project'
    cache = Path(temp) / 'source cache'
    root.mkdir()
    subprocess.run(['git', 'init', '-q', str(root)], check=True)
    def run(*arguments, ok=True):
        result = subprocess.run([binary, *arguments, '--project-dir', str(root), '--cache-dir', str(cache)], capture_output=True, text=True)
        if (result.returncode == 0) != ok:
            raise AssertionError(result.stdout + result.stderr)
        return result
    run('add', 'Heapy/ktc-quarkus', '--commit', '364929caf0f7ac4610ce57503815972026e9e2e6', '--path', 'plugins/quarkus', '--license-file', 'LICENSE')
    run('add', 'Heapy/detekt-config', '--commit', '725afe7f30c4caadd1af3090800c1e8514adbf2a', '--path', 'plugins/heapy-detekt', '--license-file', 'LICENSE', '--mode', 'downloaded')
    run('verify')
    assert (root / 'plugins/quarkus/.ktc-licenses/LICENSE').is_file()
    assert (root / 'plugins/heapy-detekt/.ktc-licenses/LICENSE').is_file()
    ignored = subprocess.run(['git', '-C', str(root), 'check-ignore', 'plugins/heapy-detekt/module.yaml'], capture_output=True, text=True, check=True)
    assert 'plugins/heapy-detekt/module.yaml' in ignored.stdout
    changed = subprocess.run(['git', '-C', str(root), 'status', '--porcelain', '--untracked-files=all'], capture_output=True, text=True, check=True).stdout
    assert 'plugins/quarkus/module.yaml' in changed and 'plugins/heapy-detekt/module.yaml' not in changed
    inventory = (root / 'ktc-plugins.lock.yaml').read_bytes()
    shutil.rmtree(root / 'plugins/heapy-detekt')
    run('sync', '--offline')
    assert (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    run('verify')
    run('update', 'quarkus', '--dry-run')
    assert (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    sql = run('add', 'Heapy/kotgent', '--commit', '9c98f3e33dcecff5abd354a6517d691b65b501d0', '--path', 'plugins/sqldelight-gen', '--license-file', 'LICENSE', ok=False)
    assert 'producer version catalog' in sql.stderr, sql.stdout + sql.stderr
    assert (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    print('Real Quarkus/detekt installs, Git ignore rules, offline restore, dry-run and SQLDelight diagnostic passed')
