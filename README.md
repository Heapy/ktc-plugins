# ktc-plugins

A Kotlin/Native source installer for JetBrains Kotlin Toolchain build plugins.
It downloads a GitHub repository at an explicit tag, branch, or full commit SHA,
installs a self-contained plugin module, and registers it in your project.

See [CHANGELOG.md](CHANGELOG.md) for release history and compatibility notes.

This checkout prepares **0.3.0**. The download instructions below use the latest
published release, **0.2.0**; build locally to use the upcoming release before publication.

## Get version 0.2.0

Download the generated launchers from the [0.2.0 release](https://github.com/Heapy/ktc-plugins/releases/tag/v0.2.0)
into your consumer project and commit them:

```sh
curl --fail --location --output ktc-plugins \
  https://github.com/Heapy/ktc-plugins/releases/download/v0.2.0/ktc-plugins
curl --fail --location --output ktc-plugins.bat \
  https://github.com/Heapy/ktc-plugins/releases/download/v0.2.0/ktc-plugins.bat
chmod +x ktc-plugins
./ktc-plugins --help
```

The launchers download the matching native executable and verify its pinned SHA-256.
The release also includes executables for direct use and `SHA256SUMS`.
The checkout launchers are development templates with no release pins;
use the release assets in consumer projects, or build locally as described below.

## Build and run locally

The project uses the committed Kotlin Toolchain **0.13.0** wrappers and Kotlin **2.4.20**.
For macOS on Apple Silicon:

```sh
./kotlin build -m cli-macos -v release
export KTC_PLUGINS_BINARY="$PWD/build/tasks/_cli-macos_linkMacosArm64Release/cli-macos.kexe"
./ktc-plugins --help
./ktc-plugins status --project-dir /path/to/consumer
```

Linux: build `cli-linux` with `--platform linuxX64` or `--platform linuxArm64`;
the executable is `build/tasks/_cli-linux_link<Platform>Release/cli-linux.kexe`.
Windows x64: `kotlin.bat build -m cli-windows -v release`, then set
`KTC_PLUGINS_BINARY` to `build\tasks\_cli-windows_linkMingwX64Release\cli-windows.exe`
and invoke `ktc-plugins.bat`.

The explicit `KTC_PLUGINS_BINARY` override executes that local executable without
release checksum verification. Leave it unset when using a published wrapper.

The installer needs **curl** for HTTPS requests and **Git** to inspect ignore/index
state inside Git projects. It does not need Java or Kotlin to run. Building installed
plugins still uses the consuming project's Kotlin Toolchain and its dependencies.

## Install a plugin

Run from the consumer project root, or pass `--project-dir` explicitly. The installer
does not search parent directories for a project.

```sh
./ktc-plugins add Heapy/ktc-quarkus \
  --commit 364929caf0f7ac4610ce57503815972026e9e2e6 \
  --path plugins/quarkus --license-file LICENSE \
  --enable-in app
```

`--tag v1.0.0` and `--branch main` select those exact kinds of refs. Branches containing
slashes and annotated tags are supported. A commit must be a full 40-character SHA.

Installation writes `ktc-plugins.yaml`, `ktc-plugins.lock.yaml`, the plugin source,
project registration and ignore entries. It prints the activation key; `--enable-in`
also enables it in the chosen module. Existing settings/comments are preserved;
complex YAML edits that cannot be performed safely fail before project changes.

Producer repositories may provide `ktc-plugin.yaml`, eliminating repeated path and
license arguments. With a single producer entry, selection is automatic. With several,
use `--plugin SELECTOR`. `--name ALIAS` names the client entry; it does not rename the
plugin ID. An inferred plugin ID requires preserving its original directory basename.
Use `--path .` for a standalone plugin at a repository root.

## Update, restore, inspect

```sh
./ktc-plugins update quarkus --dry-run
./ktc-plugins diff quarkus
./ktc-plugins outdated
./ktc-plugins update quarkus
./ktc-plugins update quarkus --tag v1.1.0
./ktc-plugins update --all
./ktc-plugins sync --offline
./ktc-plugins status
./ktc-plugins verify
```

`update` resolves the declaration and advances a branch to its current commit. An
explicit new ref also edits the client manifest. A moved tag fails unless selected
explicitly again. Manually added/edited declarations require `update`; `sync` rejects
missing/stale lock entries and never resolves a moving ref.

`sync` restores missing directories from the locked commit and verifies existing
ones. `--offline` requires a cached archive for any missing source. Neither operation
overwrites local edits, even if committed, or extra files/directories in a plugin.
Updates remove obsolete pristine upstream files. There is no force/merge mode.

`status` reports missing, stale, unlocked or modified plugins. `verify` exits nonzero
on those conditions, invalid Git tracking/ignore state, or an empty installation.
Both operate without network or project mutation.

`--dry-run` prints unified source and metadata diffs for `add`, `update`, `sync`,
`remove` and `wrapper update`. `diff NAME` / `diff --all` previews an update using the
same checks as `update --dry-run`, including local-change protection and explicit
acceptance of moved tags. It may fill the external cache and leaves the project unchanged.
Text files up to 256 KiB receive a unified diff; binary/larger files show size and SHA-256.

`outdated [NAME | --all]` compares locked commits to the exact declared branches/tags
without downloading archives or changing the project. Commit declarations are reported
as pinned without a request. It does not search for newer tags or resolve semver ranges.

## Remove a plugin

```sh
./ktc-plugins remove heapy-detekt --disable-in app --dry-run
./ktc-plugins remove heapy-detekt --disable-in app
```

Removal checks the lockfile and source inventory, refuses local/unowned edits, and
transactionally removes the managed source and manifest/lock entries. Exact project
registrations and managed downloaded ignore entries are removed; shared module globs,
other plugins and user ignore rules remain. Missing source directories can be removed.

Every registered module with configuration for the plugin must be named through
repeatable `--disable-in MODULE` options (`.` selects the implicit root module).
Its configuration is retained as comments, including custom settings. Block YAML and
single-line flow collections are supported; unsupported edits fail before mutation.
The scanner supports `*`, `?` and `**`; wildcard scans skip build/tool state directories.
The installer changes no Git index entries; commit vendored deletions yourself.

## Update project launchers

```sh
./ktc-plugins wrapper update --version 0.2.0 --dry-run
./ktc-plugins wrapper update --version 0.2.0
```

Choose an exact **published stable** version. The updater verifies GitHub asset digests,
`SHA256SUMS`, the embedded version and all four native binary pins before replacing
both project launchers in one transaction. Existing launchers must match their official
release (CRLF/LF checkout conversion is accepted); local changes and development
templates are refused. Review and commit the result. The running executable is not
replaced; subsequent launcher invocations select the chosen release.

## Committed or downloaded sources

`vendored` is the default. Review and commit the sources plus manifests/configuration;
the installer does not stage, commit or push anything. A fresh clone has the plugin
sources, though Toolchain/Maven dependency resolution may still need network.

For ignored downloads:

```sh
./ktc-plugins add Heapy/detekt-config \
  --commit 725afe7f30c4caadd1af3090800c1e8514adbf2a \
  --path plugins/heapy-detekt --license-file LICENSE \
  --mode downloaded --enable-in app
```

Commit the wrappers, client manifest, lockfile, project/module configuration and
`plugins/.gitignore`. The parent ignore file contains `/heapy-detekt/`, so it survives
removing/restoring the download. Other vendored plugins remain visible to Git.
Already tracked downloaded files cause an error; the installer never untracks them.
Mode conversion remains outside this release.

Bootstrap downloaded sources **before** invoking Toolchain, since model loading itself
needs the registered plugin:

```sh
./ktc-plugins sync
./ktc-plugins verify
./kotlin build
```

## Manifests

Producer-side `ktc-plugin.yaml`:

```yaml
schemaVersion: 1
plugins:
  quarkus:
    module: plugins/quarkus
    licenseFiles: [LICENSE, NOTICE]
```

List only files present in that repository. Client-side `ktc-plugins.yaml`:

```yaml
schemaVersion: 1
plugins:
  quarkus:
    repository: Heapy/ktc-quarkus
    ref:
      branch: main
    mode: vendored
    plugin: quarkus
```

Without producer metadata, add `path` and optional `licenseFiles` to the client entry.
An explicit `destination` is optional. The generated lockfile records the resolved
commit, declaration/producer digests, identity, destination, file hashes and executable
bits. Its canonical tree hash makes restoration independent of ZIP timestamps.

License files already inside the plugin stay in place. Selected root/outside files
are copied under `.ktc-licenses/<repository-relative-path>` and included in the lock.
Source headers are preserved. Missing declared files fail; undetected license material
produces a diagnostic. Storage mode does not replace upstream licensing obligations.

From a producer repository, run `ktc-plugins validate` before committing or publishing
the plugin; `--plugin SELECTOR` validates one entry instead of all. It checks the
working-tree manifest, self-contained module, portable YAML paths/catalog use, archive
limits and license material without networking, installation or plugin execution.
In Git repositories it includes tracked and nonignored untracked files, refuses
ignored manifest/license material and reads current working-tree bytes. Outside Git,
it examines the selected directory (excluding `.git`). Validation does not prove
build/runtime compatibility or certify license permissions.

## Producer catalogs and exported libraries

A producer can opt into catalog resolution and explicitly export application libraries:

```yaml
schemaVersion: 1
plugins:
  sqldelight:
    module: plugins/sqldelight
    licenseFiles: [LICENSE]
    catalog:
      file: gradle/libs.versions.toml
      export:
        - sqldelight-runtime
        - sqldelight-native-driver
        - sqldelight-coroutines-extensions
```

`file` is a safe path relative to the producer repository, read at the same locked
commit as its sources. `export` names exact `[libraries]` keys; use `[]` to resolve
internal dependencies without exporting anything. `validate` includes this file
and rejects missing, symlinked or Git-ignored catalogs.

During preparation, `$libs.*` scalar values and mapping keys in `module.yaml`,
`plugin.yaml` and copied `*.module-template.yaml` files become fixed Maven coordinates
from the **producer** catalog. YAML comments, scopes, tags and unrelated source files
are retained. References without `catalog` metadata remain errors; the consumer's
catalog never supplies plugin compiler versions. Toolchain aliases such as `$kotlin.*`
remain unchanged.

Supported library entries are `"group:artifact:version"`, `{ module = "group:artifact",
version = "1.2.3" }`, or `group`/`name` fields, with either a string `version` or
`version.ref` pointing to a string in `[versions]`. Referenced/exported entries require
fixed versions. Rich versions, ranges, dynamic versions, versionless dependencies,
classifiers and packaging suffixes are not supported. Unused library definitions
need not have supported version specifications. Aliases start with an ASCII letter
and contain alphanumeric segments separated by `-`, `_` or `.`; escaped TOML keys
are unsupported. Conflicting normalized accessors fail before changes.

Exports use `ktc-<pluginId>-<producerAlias>`, with dots and underscores changed to
hyphens. The prefix follows the plugin ID, independently of `--name`. For example:

```toml
[versions]
# ktc-plugins begin sqldelight versions
ktc-sqldelight-sqldelight = "2.3.2"
# ktc-plugins end sqldelight versions

[libraries]
# ktc-plugins begin sqldelight
ktc-sqldelight-sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "ktc-sqldelight-sqldelight" }
# ktc-plugins end sqldelight
```

Producer `version.ref` aliases are exported under the same plugin prefix in a managed
`[versions]` block. Libraries sharing a producer version alias share its exported reference;
independent aliases remain independent even when their values match. Inline versions remain inline.
Only versions referenced by exported libraries are added.

The application explicitly selects dependencies, for example
`$libs.ktc.sqldelight.sqldelight.runtime`. Exporting a driver only adds a catalog
entry; it does not add any module dependency. The plugin release and SQLDelight
version remain independent.

The installer edits the existing `libs.versions.toml` or `gradle/libs.versions.toml`,
or creates the root file if neither exists. Having both is an error. Existing files
must use one plain header for each managed `[libraries]` / `[versions]` table (or omit
the table); unsupported layouts fail without changes. User entries and comments stay intact. Managed entries
use flat inline tables and double quotes for Kotlin Toolchain 0.13.0 compatibility.

The lockfile records the producer catalog path/digest, consumer catalog path and
resolved exports and version references. Source, catalog and lock updates share one
transaction and appear in `--dry-run`/`diff`. Updates and removal replace/delete only unchanged managed
blocks; a pre-existing alias is a conflict even if its coordinates match. Separators
are normalized when detecting collisions. Deleted/edited blocks and moved catalogs
must be restored before `update`, `sync` or `remove`; `status`/`verify` report drift.
`sync --offline` restores missing plugin sources using locked exports and the cached
producer archive without resolving newer versions. Keep the consumer catalog in Git.
Removal leaves the catalog file and any empty `[libraries]` / `[versions]` tables in place. Dependency
references in consumer modules are user-owned and must be removed separately.

Existing manifests and locks without catalogs or version references remain supported.
`sync` preserves the literal format of older locks; an explicit `update` adopts the producer
version references. Older installers reject the new lock fields; update the consumer launcher
before adopting them.
TOML is parsed using [ktoml-core 0.7.1](https://github.com/orchestr7/ktoml).

## Cache and platform support

`GITHUB_TOKEN` or `GH_TOKEN` authenticates private repositories/API requests. Tokens
are placed in a private temporary curl configuration, never in process arguments,
manifests, lockfiles or diagnostic output. Credentials are not forwarded to codeload.

Source archives live in the native user cache. Override with `--cache-dir` or
`KTC_PLUGINS_CACHE_DIR`; archive caches must remain outside the project. Project locks always use the native default cache, so changing
archive cache paths cannot bypass mutation serialization. Bootstrap executables use a
separate cache, configurable through `KTC_PLUGINS_BINARY_CACHE`.

| Target | Native runtime requirements |
| --- | --- |
| macOS ARM64 | macOS system libraries; `sh`, curl and a SHA-256 utility for bootstrap |
| Linux x64 | glibc (symbol baseline 2.14), libgcc_s, libcrypt.so.1; `sh`, curl and SHA-256 utility |
| Linux ARM64 | glibc (symbol baseline 2.17), libgcc_s; `sh`, curl and SHA-256 utility |
| Windows x64 | Windows system DLLs, curl.exe; Windows PowerShell for bootstrap |

Linux binaries target glibc; Alpine/musl is not supported. Symbol baselines are binary
inspection results, not a claim of testing every older distribution. CI targets Ubuntu
24.04, macOS 15 ARM64 and Windows 2025; ARM64 Linux binaries/tests are cross-compiled
on x64 and executed in a separate ARM64 job. Intel macOS is outside the initial matrix.

The installer bounds ZIP input to 64 MiB, expanded content to 128 MiB, each entry to
16 MiB and entries to 10,000. It never extracts through shell archive utilities. It
rejects traversal, case collisions, special files and symlinks in the selected plugin,
producer manifest or license material; unrelated repository symlinks are not installed.

Producer `$libs.*` references require explicit `catalog` metadata as described above.
External local helper modules/templates, bundles, semver selection and local patch
merging remain unsupported. Installation does not execute plugin code; subsequent builds do.

## Verification and distribution

```sh
./kotlin test -m core --platform jvm --platform macosArm64
./kotlin build -m cli-macos -m cli-linux -m cli-windows -v release
kotlinr scripts/stage-binaries.main.kts macosArm64 linuxX64 linuxArm64 mingwX64
kotlinr scripts/package-release.main.kts --version 0.3.0 \
  --binaries build/binaries --output build/release
kotlinr scripts/smoke.main.kts build/binaries/ktc-plugins-0.3.0-macos-arm64
kotlinr scripts/test-wrappers.main.kts build/binaries/ktc-plugins-0.3.0-macos-arm64
```

Cross-compilation requires a supported Toolchain compiler host. Build individual
platforms on their supported hosts if needed. Release bundles include the four native
executables, `SHA256SUMS` and self-contained pinned Unix/Windows wrappers. Copy those
**generated wrappers** into a consumer project and commit them. Cached executables
are checksum-verified on every launch and work without network; concurrent downloads
publish only complete verified files. Installer upgrades require reviewing new wrappers.

CI builds/tests every target, runs real-source installation smoke checks, packages the
exact binaries and prepares a **draft** GitHub release on a matching version tag.
Publication is a separate maintainer step; no consumer wrapper is silently upgraded.

`scripts/plugin-runtime-smoke.main.kts BINARY` installs exact commits from the
[Quarkus/detekt producer draft PRs](docs/0.2.0.md) and `Heapy/ktc-sqldelight` through their
manifests and executes Quarkus packaging, detekt checks and SQLDelight generation/compilation
in isolated consumers. SQLDelight verifies shared exported `version.ref` entries and compiles
generated sources against the exported `$libs.ktc.sqldelight.runtime` dependency. The acceptance
script also checks that its historical catalog-dependent module is rejected.
Pass `--toolchain-wrapper PATH` and `--plugins quarkus detekt` to repeat
those checks with the producers' 0.12.2 wrapper.
Pass `--sql-producer-dir /path/to/kotgent` to validate and execute its adapted local
SQLDelight producer without rewriting catalog aliases in the fixture.

See [design.md](docs/design.md) for the full contract and plugin compatibility audit,
and [verification.md](docs/verification.md) for the recorded local results.

## Running verification scripts

The `.main.kts` scripts require JDK 25 and Kotlin 2.4.21+ (`kotlinr` on `PATH`).
Run them with `kotlinr scripts/<name>.main.kts` from the repository root.
The Kotlin Toolchain `./kotlin` command is a separate executable. CI installs the script runner
through `.github/actions/setup-kotlin-script`; the first script run compiles the script and
resolves any pinned Maven dependencies. Later runs use the local script cache.
