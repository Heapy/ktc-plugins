#!/usr/bin/env python3
"""Run plugin tasks in isolated consumers. SQLDelight uses a temporary producer packaging fix."""
import argparse
import concurrent.futures
import io
from pathlib import Path
import shutil
import subprocess
import zipfile
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

def install(root, repository, commit, path, module="app"):
    command(root, [str(binary), 'add', repository, '--commit', commit, '--path', path, '--license-file', 'LICENSE', '--enable-in', module, '--cache-dir', str(cache)])

def quarkus():
    root = consumer('quarkus-app')
    write(root, 'project.yaml', 'modules: [app]\n')
    write(root, 'app/module.yaml', 'product: jvm/app\ndependencies:\n  - bom: io.quarkus.platform:quarkus-bom:3.39.4\n  - io.quarkus:quarkus-rest\n  - io.quarkus:quarkus-kotlin\n')
    write(root, 'app/src/example/HelloResource.kt', 'package example\n\nimport jakarta.ws.rs.GET\nimport jakarta.ws.rs.Path\n\n@Path("/hello")\nclass HelloResource {\n    @GET\n    fun hello(): String = "ok"\n}\n')
    write(root, 'app/resources/application.properties', 'quarkus.analytics.disabled=true\n')
    install(root, 'Heapy/ktc-quarkus', '364929caf0f7ac4610ce57503815972026e9e2e6', 'plugins/quarkus')
    output = command(root, [str(root / 'kotlin'), 'do', 'quarkusBuild', '-m', 'app'])
    assert list((root / 'build').rglob('quarkus-run.jar')), output
    print('Quarkus packaging passed', flush=True)

def detekt():
    root = consumer('detekt')
    write(root, 'project.yaml', 'modules: [app]\n')
    write(root, 'app/module.yaml', 'product: jvm/lib\n')
    write(root, 'app/src/io/heapy/fixture/Greeting.kt', 'package io.heapy.fixture\n\nfun greeting(\n    name: String,\n): String = "hello $name"\n')
    install(root, 'Heapy/detekt-config', '725afe7f30c4caadd1af3090800c1e8514adbf2a', 'plugins/heapy-detekt')
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
    commit = '9c98f3e33dcecff5abd354a6517d691b65b501d0'
    archive = subprocess.run(['curl', '--disable', '--fail', '--silent', '--show-error', '--proto', '=https', '--max-time', '120', f'https://codeload.github.com/Heapy/kotgent/zip/{commit}'], capture_output=True, check=True).stdout
    prefix = 'plugins/sqldelight-gen/'
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        for name in z.namelist():
            relative = name.partition('/')[2]
            if relative.startswith(prefix) and not name.endswith('/'):
                assert '..' not in Path(relative).parts
                file = root / relative; file.parent.mkdir(parents=True, exist_ok=True); file.write_bytes(z.read(name))
    module = root / prefix / 'module.yaml'
    replacements = {
        '$libs.sqldelight.core': 'app.cash.sqldelight:core:2.3.2',
        '$libs.sqldelight.sqlite.dialect': 'app.cash.sqldelight:sqlite-3-38-dialect:2.3.2',
        '$libs.sql.psi.environment': 'app.cash.sql-psi:environment:0.7.3',
        '$libs.sqldelight.compiler.env': 'app.cash.sqldelight:compiler-env:2.3.2',
    }
    # Resolve only these known catalog pins in the temporary fixture, never upstream sources.
    text = module.read_text()
    for alias, coordinate in replacements.items():
        assert text.count(alias) == 1
        text = text.replace(alias, coordinate)
    assert '$libs.' not in text
    module.write_text(text)
    sql_consumer(root)

def sql_consumer(root):
    write(root, 'project.yaml', 'modules: [plugins/sqldelight-gen]\nplugins: [//plugins/sqldelight-gen]\n')
    write(root, 'module.yaml', 'product: jvm/lib\ndependencies:\n  - app.cash.sqldelight:runtime:2.3.2\nplugins:\n  sqldelight-gen: enabled\n')
    write(root, 'sqldelight/io/kotgent/db/Item.sq', 'CREATE TABLE item (id INTEGER NOT NULL PRIMARY KEY);\n\nselectAll:\nSELECT * FROM item;\n')
    output = command(root, [str(root / 'kotlin'), 'build', '-m', 'sqldelight'])
    # Root module name is its directory basename; generated sources compile with consumer dependencies.
    assert list((root / 'build').rglob('KotgentDatabase.kt')), output
    packaging = 'local self-contained producer' if args.sql_producer_dir else 'temporary literal coordinates'
    print(f'SQLDelight generation and generated-source compilation passed with {packaging}', flush=True)

failures = []
with concurrent.futures.ThreadPoolExecutor(3) as executor:
    for future in [executor.submit({'quarkus': quarkus, 'detekt': detekt, 'sql': sql}[name]) for name in args.plugins]:
        try: future.result()
        except Exception as error: failures.append(str(error))

print(f'Consumer fixtures retained at {work}')
if failures: raise RuntimeError('\n'.join(failures))
