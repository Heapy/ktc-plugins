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
    assert 'pinned' in run('outdated', '--all').stdout
    run('diff', 'quarkus')
    assert (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    sql = run('add', 'Heapy/kotgent', '--commit', '9c98f3e33dcecff5abd354a6517d691b65b501d0', '--path', 'plugins/sqldelight-gen', '--license-file', 'LICENSE', ok=False)
    assert 'producer version catalog' in sql.stderr, sql.stdout + sql.stderr
    assert (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    producer = Path(temp) / 'producer fixture'
    shutil.copytree(root / 'plugins/quarkus', producer / 'plugins/quarkus', ignore=shutil.ignore_patterns('.ktc-licenses'))
    shutil.copyfile(root / 'plugins/quarkus/.ktc-licenses/LICENSE', producer / 'LICENSE')
    (producer / 'ktc-plugin.yaml').write_text('schemaVersion: 1\nplugins:\n  quarkus:\n    module: plugins/quarkus\n    licenseFiles: [LICENSE]\n')
    validated = subprocess.run([binary, 'validate', '--project-dir', str(producer)], capture_output=True, text=True)
    assert validated.returncode == 0 and 'quarkus: valid' in validated.stdout, validated.stdout + validated.stderr
    detekt_module = root / 'plugins/heapy-detekt/module.yaml'
    original = detekt_module.read_bytes()
    detekt_module.write_bytes(original + b'\n# local edit\n')
    refused = run('remove', 'heapy-detekt', ok=False)
    assert 'Modified plugin file' in refused.stderr
    detekt_module.write_bytes(original)
    preview = run('remove', 'heapy-detekt', '--dry-run')
    assert 'deleted file mode' in preview.stdout and (root / 'ktc-plugins.lock.yaml').read_bytes() == inventory
    run('remove', 'heapy-detekt')
    assert not detekt_module.exists()
    run('verify')
    assert '/heapy-detekt/' not in (root / 'plugins/.gitignore').read_text()
    # Exercise the real release API/checksum transport against the published predecessor.
    run('wrapper', 'update', '--version', '0.1.0', '--dry-run')
    assert not (root / 'ktc-plugins').exists()
    run('wrapper', 'update', '--version', '0.1.0')
    assert (root / 'ktc-plugins').is_file() and (root / 'ktc-plugins.bat').is_file()
    assert "version='0.1.0'" in (root / 'ktc-plugins').read_text()
    print('Real installs, offline restore, diff/outdated, safe removal, producer validation and verified release launcher update passed')
