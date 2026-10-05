#!/usr/bin/env python3
"""Install pinned producer manifests and run plugin tasks in isolated consumers."""
import argparse
import concurrent.futures
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('binary', type=Path)
p.add_argument('--work-dir', type=Path, default=None)
p.add_argument('--toolchain-wrapper', type=Path, default=ROOT / 'kotlin')
p.add_argument('--plugins', nargs='+', choices=['quarkus', 'detekt', 'sql'], default=['quarkus', 'detekt', 'sql'])
p.add_argument('--sql-producer-dir', type=Path, help='Validate and execute a local self-contained SQLDelight producer without catalog substitution')
args = p.parse_args()
binary = args.binary.resolve(); work = args.work_dir.resolve() if args.work_dir else Path(tempfile.mkdtemp(prefix='ktc-plugin-runtime-'))
work.mkdir(parents=True, exist_ok=True)
cache = work / 'source-cache'
wrapper = args.toolchain_wrapper.resolve()

def write(root, path, content):
    file = root / path; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(content)

def command(root, arguments):
    result = subprocess.run(arguments, cwd=root, capture_output=True, text=True)
    log = root / 'fixture-validation.log'
    with log.open('a') as file: file.write(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError((result.stdout + result.stderr)[:4000] + f'\nFull log: {log}')
    return result.stdout

def consumer(name):
    root = work / name
    if root.exists():
        if not (root / '.ktc-runtime-fixture').is_file(): raise ValueError(f'Refusing to replace an unowned fixture: {root}')
        shutil.rmtree(root)
    root.mkdir()
    (root / '.ktc-runtime-fixture').touch()
    shutil.copyfile(wrapper, root / 'kotlin'); (root / 'kotlin').chmod(0o755)
    return root

def install(root, repository, commit, selector, module="app"):
    arguments = [str(binary), 'add', repository, '--commit', commit, '--plugin', selector, '--cache-dir', str(cache)]
    if module is not None: arguments += ['--enable-in', module]
    command(root, arguments)

def quarkus():
    root = consumer('quarkus-app')
    write(root, 'project.yaml', 'modules: [app]\n')
    write(root, 'app/module.yaml', 'product: jvm/app\ndependencies:\n  - bom: io.quarkus.platform:quarkus-bom:3.39.4\n  - io.quarkus:quarkus-rest\n  - io.quarkus:quarkus-kotlin\n')
    write(root, 'app/src/example/HelloResource.kt', 'package example\n\nimport jakarta.ws.rs.GET\nimport jakarta.ws.rs.Path\n\n@Path("/hello")\nclass HelloResource {\n    @GET\n    fun hello(): String = "ok"\n}\n')
    write(root, 'app/resources/application.properties', 'quarkus.analytics.disabled=true\n')
    install(root, 'Heapy/ktc-quarkus', 'ab131cc9ee67571bd1c09993bf6096ac27b591a0', 'quarkus')
    output = command(root, [str(root / 'kotlin'), 'do', 'quarkusBuild', '-m', 'app'])
    assert list((root / 'build').rglob('quarkus-run.jar')), output
    print('Quarkus packaging passed', flush=True)

def detekt():
    root = consumer('detekt')
    write(root, 'project.yaml', 'modules: [app]\n')
    write(root, 'app/module.yaml', 'product: jvm/lib\n')
    write(root, 'app/src/io/heapy/fixture/Greeting.kt', 'package io.heapy.fixture\n\nfun greeting(\n    name: String,\n): String = "hello $name"\n')
    install(root, 'Heapy/detekt-config', '95f58d20f7fd1d2ad6c65d62c777f97af17b37c2', 'detekt')
    command(root, [str(root / 'kotlin'), 'check', '-m', 'app'])
    print('Detekt execution and config artifact resolution passed', flush=True)

def sql():
    root = consumer('sqldelight')
    if args.sql_producer_dir:
        source = args.sql_producer_dir.resolve()
        validated = command(root, [str(binary), 'validate', '--project-dir', str(source), '--plugin', 'sqldelight'])
        assert ', plugins/sqldelight-gen,' in validated
        module_path = 'plugins/sqldelight-gen'
        files = subprocess.run(['git', '-C', str(source), 'ls-files', '--cached', '--others', '--exclude-standard', '-z', '--', module_path], capture_output=True, text=True, check=True).stdout.split('\0')
        for name in filter(None, files):
            file = root / name; file.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, file)
        license_target = root / module_path / '.ktc-licenses/LICENSE'
        license_target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / 'LICENSE', license_target)
        assert '$libs.' not in (root / module_path / 'module.yaml').read_text()
        sql_consumer(root)
        return
    install(root, 'Heapy/kotgent', '2a000743c6e77540e959ba641b7c844f6d10871e', 'sqldelight', module=None)
    assert (root / 'plugins/sqldelight-gen/.ktc-licenses/LICENSE').is_file()
    command(root, [str(binary), 'verify', '--cache-dir', str(cache)])
    sql_consumer(root)

def sql_consumer(root):
    write(root, 'project.yaml', 'modules: [plugins/sqldelight-gen]\nplugins: [//plugins/sqldelight-gen]\n')
    write(root, 'module.yaml', 'product: jvm/lib\ndependencies:\n  - app.cash.sqldelight:runtime:2.3.2\nplugins:\n  sqldelight-gen: enabled\n')
    write(root, 'sqldelight/io/kotgent/db/Item.sq', 'CREATE TABLE item (id INTEGER NOT NULL PRIMARY KEY);\n\nselectAll:\nSELECT * FROM item;\n')
    output = command(root, [str(root / 'kotlin'), 'build', '-m', 'sqldelight'])
    # Root module name is its directory basename; generated sources compile with consumer dependencies.
    assert list((root / 'build').rglob('KotgentDatabase.kt')), output
    packaging = 'local self-contained producer' if args.sql_producer_dir else 'installed producer manifest'
    print(f'SQLDelight generation and generated-source compilation passed with {packaging}', flush=True)

failures = []
with concurrent.futures.ThreadPoolExecutor(3) as executor:
    for future in [executor.submit({'quarkus': quarkus, 'detekt': detekt, 'sql': sql}[name]) for name in args.plugins]:
        try: future.result()
        except Exception as error: failures.append(str(error))

print(f'Consumer fixtures retained at {work}')
if failures: raise RuntimeError('\n'.join(failures))
