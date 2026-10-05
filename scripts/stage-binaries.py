#!/usr/bin/env python3
import argparse
from pathlib import Path
import re
import shutil

TARGETS = {
    'macosArm64': ('cli-macos', 'macos-arm64', '.kexe'),
    'linuxX64': ('cli-linux', 'linux-x64', '.kexe'),
    'linuxArm64': ('cli-linux', 'linux-arm64', '.kexe'),
    'mingwX64': ('cli-windows', 'windows-x64', '.exe'),
}
p = argparse.ArgumentParser()
p.add_argument('platforms', nargs='+', choices=TARGETS)
p.add_argument('--output', type=Path, default=Path('build/binaries'))
args = p.parse_args()
version = re.search(r'const val VERSION = "([^"]+)"', Path('core/src/Cli.kt').read_text()).group(1)
args.output.mkdir(parents=True, exist_ok=True)
for platform in args.platforms:
    module, target, extension = TARGETS[platform]
    source = Path(f'build/tasks/_{module}_link{platform[0].upper() + platform[1:]}Release/{module}{extension}')
    name = f'ktc-plugins-{version}-{target}' + ('.exe' if platform == 'mingwX64' else '')
    shutil.copyfile(source, args.output / name)
    if platform != 'mingwX64':
        (args.output / name).chmod(0o755)
    print(args.output / name)
