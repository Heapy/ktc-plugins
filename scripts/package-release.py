#!/usr/bin/env python3
"""Generate self-contained, checksum-pinned wrappers from the binaries being published."""
import argparse
import hashlib
from pathlib import Path
import re
import shutil

TARGETS = ('macos-arm64', 'linux-x64', 'linux-arm64', 'windows-x64')
ROOT = Path(__file__).resolve().parent.parent


def package(version: str, binaries: Path, output: Path):
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?', version):
        raise ValueError('Expected a release version such as 0.1.0')
    source = (ROOT / 'core/src/Cli.kt').read_text()
    if f'const val VERSION = "{version}"' not in source:
        raise ValueError('Release version must match core/src/Cli.kt')
    output.mkdir(parents=True, exist_ok=True)
    sums = []
    unix = (ROOT / 'ktc-plugins').read_text()
    windows = (ROOT / 'ktc-plugins.bat').read_text()
    unix = re.sub(r"^version='[^']+' # VERSION$", f"version='{version}' # VERSION", unix, flags=re.M)
    windows = re.sub(r'^set "ktc_version=[^"]+"$', f'set "ktc_version={version}"', windows, flags=re.M)
    for target in TARGETS:
        name = f'ktc-plugins-{version}-{target}' + ('.exe' if target.startswith('windows') else '')
        binary = binaries / name
        if not binary.is_file() or binary.stat().st_size == 0:
            raise ValueError(f'Missing release binary: {binary}')
        digest = hashlib.sha256(binary.read_bytes()).hexdigest()
        sums.append(f'{digest}  {name}\n')
        marker = 'SHA_' + target.upper().replace('-', '_')
        pattern = rf"sha='[^']+'(?= ;; # {marker}$)" if target != 'windows-x64' else rf"\$sha = '[^']+'(?= # {marker}$)"
        if target == 'windows-x64':
            windows, count = re.subn(pattern, f"$sha = '{digest}'", windows, flags=re.M)
        else:
            unix, count = re.subn(pattern, f"sha='{digest}'", unix, flags=re.M)
        if count != 1:
            raise ValueError(f'Missing/duplicate wrapper pin marker: {marker}')
        if binary.resolve() != (output / name).resolve():
            shutil.copyfile(binary, output / name)
        if not target.startswith('windows'):
            (output / name).chmod(0o755)
    (output / 'ktc-plugins').write_text(unix)
    (output / 'ktc-plugins').chmod(0o755)
    (output / 'ktc-plugins.bat').write_bytes(windows.replace('\n', '\r\n').encode())
    for name in ('ktc-plugins', 'ktc-plugins.bat'):
        sums.append(f'{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n')
    (output / 'SHA256SUMS').write_text(''.join(sums))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--binaries', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    package(args.version, args.binaries, args.output)
