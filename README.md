# ktc-plugins

A Kotlin/Native source installer for JetBrains Kotlin Toolchain build plugins.
It downloads a GitHub repository at an explicit tag, branch, or full commit SHA,
installs a self-contained plugin module, and registers it in your project.

## Get version 0.1.0

Download the generated launchers from the [0.1.0 release](https://github.com/Heapy/ktc-plugins/releases/tag/v0.1.0)
into your consumer project and commit them:

```sh
curl --fail --location --output ktc-plugins \
  https://github.com/Heapy/ktc-plugins/releases/download/v0.1.0/ktc-plugins
curl --fail --location --output ktc-plugins.bat \
  https://github.com/Heapy/ktc-plugins/releases/download/v0.1.0/ktc-plugins.bat
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

`--dry-run` is available for `add`, `update` and `sync`: it reports planned paths and
file counts, may fill the external source cache, and leaves the project unchanged.

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
Mode conversion and uninstall are outside this release.

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

Plugins using producer `$libs.*` catalogs or external local helper modules/templates
are rejected. Bundles, catalog relocation, semver selection and local patch merging
remain outside MVP. Installation does not execute plugin code; subsequent builds do.

## Verification and distribution

```sh
./kotlin test -m core --platform jvm --platform macosArm64
./kotlin build -m cli-macos -m cli-linux -m cli-windows -v release
python3 scripts/stage-binaries.py macosArm64 linuxX64 linuxArm64 mingwX64
python3 scripts/package-release.py --version 0.1.0 \
  --binaries build/binaries --output build/release
python3 scripts/smoke.py build/binaries/ktc-plugins-0.1.0-macos-arm64
python3 scripts/test-wrappers.py build/binaries/ktc-plugins-0.1.0-macos-arm64
```

Cross-compilation requires a supported Toolchain compiler host. Build individual
platforms on their supported hosts if needed. Release bundles include the four native
executables, `SHA256SUMS` and self-contained pinned Unix/Windows wrappers. Copy those
**generated wrappers** into a consumer project and commit them. Cached executables
are checksum-verified on every launch and work without network; concurrent downloads
publish only complete verified files. Installer upgrades require reviewing new wrappers.

CI builds/tests every target, runs real-source installation smoke checks, packages the
exact binaries and prepares a **draft** GitHub release on a matching `v0.1.0` tag.
Publication is a separate maintainer step; no consumer wrapper is silently upgraded.

`scripts/plugin-runtime-smoke.py BINARY` also executes Quarkus packaging, detekt checks
and SQLDelight generation in isolated consumers. SQLDelight's unmodified pinned module
is correctly rejected by the installer; this runtime fixture replaces only its four
catalog aliases with the documented literal coordinates. Upstream repositories are
not changed. Pass `--toolchain-wrapper PATH` and `--plugins quarkus detekt` to repeat
those checks with the producers' 0.12.2 wrapper.

See [design.md](docs/design.md) for the full contract and plugin compatibility audit,
and [verification.md](docs/verification.md) for the recorded local results.
