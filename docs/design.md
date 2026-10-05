# Temporary Kotlin Toolchain plugin installer

## Implemented MVP status (2026-10-04)

The native shared core, platform entry points, six commands, manifests/lockfile,
producer selection, downloaded/vendored modes, drift guards, transaction recovery,
and release packaging are implemented. See [README.md](../README.md) for actual
commands and [verification.md](verification.md) for measured results and boundaries.
All four release targets compile; local execution is verified on macOS ARM64.
Linux/Windows runtime verification is configured in CI and has not run remotely yet.
No installer release has been published; generated release wrappers receive their
hashes from the exact assembled binaries, while checkout wrappers support local builds.

Implementation decisions refining this plan:

- HTTPS uses the host `curl` executable through platform process APIs. The installer
  itself needs no JVM, but curl is a runtime prerequisite; Git is required for index
  and ignore inspection inside Git projects. ZIP/DEFLATE and SHA-256 are portable.
- Dependencies are pinned to Okio 3.18.2 and kotaml 0.111.0; the latter preserves the
  kaml AST package/API and supports the native targets. YAML edits operate on validated
  source text so unrelated comments/settings are retained.
- Repository symlinks outside the selected source/producer/license material are
  inspected as archive records and never written. Selected symlinks/special files,
  unsafe names and case collisions remain errors.
- Project locks use the native default cache independently of archive-cache overrides.
  Archive caches inside the project are rejected. Dry-runs may create external
  cache/lock files but do not change project content. Windows reparse-point checks
  protect against junctions independently of Okio metadata.
- Metadata edits requiring unsupported anchors/flow maps fail before mutation. The
  initial dry-run output lists changed paths and file counts rather than a unified diff.
- Journal recovery covers process interruption and preserves later edits. It does
  not promise complete power-loss durability; see the verification boundaries.
- Quarkus packaging and detekt checks pass after installation on Toolchain 0.13.0
  and their original 0.12.2 pin. Quarkus requires an app subdirectory with resources;
  root application activation has overlapping task paths in the pinned plugin.
  SQLDelight generation/compilation passes on 0.13.0 after the documented temporary
  producer catalog substitution. The installer rejects its unmodified module.
- CI assembles immutable versioned binaries/checksums and self-contained wrappers,
  validates each runtime host, and prepares a draft release. Publishing the first
  release is a separate maintainer action.


## Goal and scope

Provide a small standalone tool that installs GitHub-hosted build plugin sources
into a Kotlin Toolchain project until upstream plugin distribution is available.
The initial compatibility target is Kotlin Toolchain 0.13.0. Check later releases
before implementation; do not depend on internal Toolchain APIs.

The 0.13.0 project model accepts local plugin modules: list each plugin under
both `project.yaml.modules` and `project.yaml.plugins`, then enable its actual
plugin ID in the consuming module or template. The installer must preserve that
distinction. Registration alone does not enable a plugin.

Upstream references:

- [Project model](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/reference/project.md)
- [Plugin quick start](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/user-guide/plugins/quick-start.md)
- [Plugin structure](https://github.com/JetBrains/kotlin-toolchain/blob/v0.13.0/docs/src/user-guide/plugins/topics/structure.md)

## Distribution decision

Build a Kotlin/Native application and distribute ready-to-run executables through
GitHub Releases. Consumers need no Java or Kotlin installation to run the
installer. Building the installed plugins still uses the consumer's Kotlin
Toolchain and JVM environment.

Use a shared `kmp/lib` core with small Kotlin Toolchain `macos/app`, `linux/app`
and `windows/app` entry-point modules. Initial release targets are `macosArm64`,
`linuxX64`, `linuxArm64`, and `mingwX64`, subject to the portability milestone
below. Publish each target only after runtime checks on that platform. Intel
macOS is deprecated upstream and is outside the initial release matrix.
Toolchain native `build` produces executables; native `package` is not supported
in 0.13.0. Release assembly must collect the build outputs explicitly.

| Option | Requirements and tradeoffs | Decision |
| --- | --- | --- |
| Kotlin/Native executables | No Java/Kotlin runtime for consumers; separate builds and runtime validation per OS/architecture | First release |
| Executable JAR | Java runtime; one artifact across operating systems; JVM libraries simplify HTTP, archives, and YAML | Fallback if the native feasibility milestone fails |
| Kotlin `.main.kts` | Kotlin scripting runtime and dependency resolution; easy prototype but more consumer setup | Prototype alternative |
| Shell plus Windows script | OS-specific implementations and external utilities; quoting, YAML, and update logic would need separate handling | Bootstrap only |
| Java source-file launcher | JDK can compile/run source directly; dependency handling complicates YAML support and gives up Kotlin implementation | Alternative for a very small prototype |
| JVM native-image executables | No Java runtime for consumers; JVM implementation plus a separate native-image toolchain/build matrix | Alternative |
| Git submodule or subtree | Existing vendoring mechanisms; require Git workflows and still need project wiring | Document as alternatives |

Commit `ktc-plugins` (portable `sh`) and `ktc-plugins.bat` (PowerShell for Windows
download/checksum work) in the consuming project. They detect OS/architecture,
download a pinned installer release, verify its per-target SHA-256, cache it
outside the project, and forward arguments and exit status. The native binary
can also be run directly. Wrappers contain no plugin management logic.

Tool bootstrapping is separate from plugin installation. Changing the wrapper
pin upgrades the installer; `update` changes plugin selections/resolutions.
Do not silently download the latest installer. Cache hits work offline; failed
or partial downloads never become executable cache entries. Use a temporary
file and an atomic rename after validation, and serialize concurrent downloads.

Document bootstrap prerequisites (`curl` or an explicitly supported downloader
and checksum utility on Unix, PowerShell on Windows). Native libraries and Linux
libc requirements must be audited and documented; a native executable is not
automatically fully static. The first milestone must prove HTTPS, safe archive
extraction, hashing, filesystem operations, and comment-preserving YAML edits
on the intended targets before selecting library dependencies.

References: [Native applications](https://kotlinlang.org/docs/native-get-started.html),
[targets and hosts](https://kotlinlang.org/docs/native-target-support.html).

## Producer manifest

An optional `ktc-plugin.yaml` at the source repository root declares installable
plugins. Read it from the exact resolved commit together with the source; never
fetch metadata from the default branch separately.

```yaml
schemaVersion: 1
plugins:
  detekt:
    module: plugins/heapy-detekt
    licenseFiles: [LICENSE]
```

Paths are relative to the source repository root. Each `module` selects a
complete local plugin module, including its sources and resources. If exactly
one plugin is declared, select it automatically; otherwise require a `--plugin`
selector and store that selection in the client manifest. Producer selectors
are installation names, distinct from Kotlin Toolchain plugin IDs.

Read the actual plugin ID and settings class from `module.yaml`; do not repeat
them in the producer manifest. When `pluginInfo.id` is absent, preserve the
original module directory name as the destination basename so its inferred ID
does not change. A destination override that changes that name is rejected;
recommend declaring an explicit ID upstream instead of rewriting source YAML.

Default the destination to `plugins/<plugin-id>`. Optional client overrides
remain project-relative. Producer metadata must not enable plugins in arbitrary
consumer modules or write arbitrary project-root files. Activation and settings
belong to the consumer.

Existing repositories without this manifest can be installed using explicit
`--path` and `--license-file` options (the latter repeatable). Record these source
selection overrides in the client manifest. A producer manifest removes that
repetition once added upstream. Root license/notice discovery is a convenience,
not proof of which license applies to every selected file; preserve existing
in-subtree notices as well.

## Client manifest

Commit `ktc-plugins.yaml` to describe the requested plugins in both storage
modes. A local alias identifies the entry used by `update` and `status`.

```yaml
schemaVersion: 1
plugins:
  detekt:
    repository: Heapy/detekt-config
    ref:
      branch: main
    mode: downloaded
    # plugin: detekt                 # needed for a multi-plugin repository
    # destination: plugins/heapy-detekt  # optional override
    # path: plugins/heapy-detekt         # fallback/override of producer module
    # licenseFiles: [LICENSE]        # explicit fallback selection
```

Exactly one of `tag`, `branch`, or `commit` is required in each `ref`. Full commit
SHAs are required initially; refs are treated as typed selections, not inferred
from strings. `mode` defaults to `vendored`. Installer version pins live in the
wrappers, independently of this manifest.

The manifest expresses intent; `ktc-plugins.lock.yaml` records the resolved
installation. Editing the manifest does not silently advance or replace plugins
during `sync`: report a missing/stale lock entry and require `add` or `update` to
resolve it. CI restores only matching locked declarations.

| Command | Client manifest | Lockfile and installed sources |
| --- | --- | --- |
| `add` | Add declaration | Resolve and install |
| `sync` | Read; require matching lock | Restore missing locked sources; verify existing ones |
| `update` | Read | Re-resolve declared ref and update |
| `update --tag/--branch/--commit` | Change requested ref | Resolve and update |
| `status` / `verify` | Read | Inspect without project mutations |

## Proposed command contract

All examples are proposals, not currently runnable commands. Use the committed
wrapper in a consuming project. `--name` selects a client alias; `--plugin`
selects an entry in a multi-plugin producer manifest; `--path` is an explicit
repository-relative source override. These are distinct from the source module's
actual Toolchain plugin ID.

```sh
# Copy plugin sources and leave them available for the user to commit.
./ktc-plugins add Heapy/example-plugin --tag v1.0.0 --name example --mode vendored

# Track a branch while keeping downloaded sources out of Git.
./ktc-plugins add Heapy/example-plugin --branch main --name example --mode downloaded

# Pin an exact commit (full SHA required for MVP).
./ktc-plugins add Heapy/example-plugin --commit <full-sha> --name example

# Existing repository without producer metadata.
./ktc-plugins add Heapy/example-plugin --branch main --path plugin --license-file LICENSE --name example

# Select one of several plugins published by a repository.
./ktc-plugins add Heapy/example-bundle --tag v1.0.0 --plugin example --name example

# Restore the recorded commit; never advance a branch or tag.
./ktc-plugins sync
./ktc-plugins sync --offline

# Re-resolve the branch, or detect a moved tag, explicitly.
./ktc-plugins update example
./ktc-plugins update --all --dry-run

# Change a tag/branch/commit selection explicitly.
./ktc-plugins update example --tag v1.1.0
./ktc-plugins update example --commit <new-full-sha>

./ktc-plugins status
./ktc-plugins verify
```

Exactly one of `--tag`, `--branch`, or `--commit` is required on `add`. Separate
options avoid ambiguity between tags and branches with the same name. A missing
producer manifest requires an explicit `--path` (use `.` for a root module).
`--mode` defaults to `vendored`.
No implicit latest version or semver range resolution in the MVP.
Commands run in an explicitly selected project root or the current directory;
do not silently select an enclosing project.

`add` writes the client declaration and matching lock entry, installs the source,
and registers the module in `project.yaml`, creating that file for a
single-module project if needed. Preserve existing modules, comments, and
settings, and avoid duplicate entries already covered by a module glob.
Print the activation snippet for the plugin's actual ID; offer explicit
`--enable-in <module-path>` rather than enabling it everywhere.

```yaml
modules:
  - app
  - plugins/example

plugins:
  - //plugins/example
```

## Source acquisition and portability

1. Resolve the typed GitHub ref to a full commit SHA, peeling annotated tags.
2. Download the repository archive by that SHA, never by the moving ref.
3. Read producer metadata and select the requested module or explicit source
   override. Validate the directory contains
   `module.yaml` with `product: jvm/amper-plugin` and `plugin.yaml`.
4. Preserve source, resource, executable-file, and license information. Include
   applicable repository license/notice files when copying a subdirectory;
   define their destination as part of the installed file inventory.
5. Validate the selected plugin's module configuration is portable to the
   consuming project before preparing edits.

MVP supports self-contained plugin modules with external Maven dependencies.
Source-relative resources inside the selected directory are supported. Paths
to sibling modules, repository-root templates/catalogs, or required files
outside the copied subtree require a plugin packaging convention or explicit
bundle support later. Fail clearly rather than silently reinterpret those
paths against the consumer's project. Do not automatically rewrite plugin code.

In particular, reject user-catalog references (`$libs.*`) in installed plugin
configuration in the MVP. A consumer catalog might coincidentally define the
same aliases with different versions, so successful resolution in that project
is not proof of portability. Toolchain-provided catalogs are distinct; their
availability must be checked against the supported consumer Toolchain version.
The producer's wrapper is evidence of the version it uses, not a request to copy
or upgrade the consumer's wrapper.

Use GitHub's HTTPS API/archive endpoints; a Git executable should not be needed
to download plugins. Public repositories work unauthenticated, within GitHub's
limits. Optional token authentication supports private repositories; tokens
must never be stored in the lockfile or logged. Do not forward credentials to
unapproved redirect hosts. Submodules and Git LFS payloads are outside MVP.

## Lockfile and installed state

Commit `ktc-plugins.lock.yaml` in **both** modes alongside the client manifest.
It records full resolved commits, producer metadata provenance, installation
paths, and pristine file inventory. Example schema (illustrative placeholders):

```yaml
schemaVersion: 1
plugins:
  example:
    repository: Heapy/example-plugin
    sourcePath: plugin
    declarationSha256: <canonical-client-entry-digest>
    commit: <full-resolved-sha>
    producer:
      path: ktc-plugin.yaml
      sha256: <producer-file-digest>
      plugin: example
    destination: plugins/example
    mode: downloaded
    pluginId: example
    treeSha256: <canonical-installed-tree-digest>
    files:
      module.yaml:
        sha256: <file-digest>
        executable: false
      # Remaining installed files, including license/notice material.
```

Producer provenance is absent for legacy repositories using explicit source
selection. Lock the selected license files and their installed destinations as
part of the file inventory. Never store authentication credentials in either
manifest or lockfile.

Define a versioned canonical tree digest over sorted normalized relative paths,
file contents and executable flags, excluding installer metadata. Do not rely
on ZIP byte equality: archive packaging can change while source remains the
same. Retain the old file inventory to detect edits, additions, deletions and
stale files during updates.

Cache pristine plugin trees by repository, commit, source path, and packaging
schema version outside the project. Cached data must pass the lockfile digest
check before use. Cache corruption or missing locked commits must fail visibly.

## License and notice preservation

Storage mode does not change the plugin's license. Vendoring includes its code
when the consuming repository is distributed. Downloaded mode normally shares
only a declaration, but including downloaded files in a distributed archive or
container is still distribution of those files. Git ignore rules do not grant
additional rights.

Preserve applicable license/copyright/notice material in **both** modes. A plugin
copied from a subdirectory can depend on a root `LICENSE` or `NOTICE`, so producer
`licenseFiles` is a list of repository-relative files to include. Keep files
already inside the module in place. Copy selected out-of-subtree files under
`<destination>/.ktc-licenses/<repository-relative-path>` without overwriting
plugin-owned files, and include their digests in the lockfile inventory. A
reserved-path collision is an error. Preserve source headers; a SPDX identifier
is optional descriptive metadata and cannot replace the applicable texts.

MIT requires copyright and permission notices in copies/substantial portions.
Apache 2.0 redistribution requires the license text, applicable notices including
upstream `NOTICE` attribution, and notices of modified files. Copy sources
unchanged; any user edits remain the user's responsibility. The installer does
not attempt to certify license compatibility or generate legal conclusions.

An absent license file is not proof that permission is absent: notices or
separate agreements may exist. Report missing declared files as errors and
undetected license material as a diagnostic; do not invent a license or assume
public GitHub visibility authorizes arbitrary redistribution. Using a build
plugin does not automatically license the application under the plugin's
license; generated output containing copied plugin material is a separate case.

References: [MIT](https://opensource.org/license/mit),
[Apache 2.0, section 4](https://www.apache.org/licenses/LICENSE-2.0),
[GitHub licensing](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository),
[GNU FAQ on output](https://www.gnu.org/licenses/gpl-faq.en.html#WhatCaseIsOutputGPL).

## Committed sources and updates

In `vendored` mode, the plugin source is ordinary project content; installation
and updates do not stage, commit, or push it. The user reviews and commits the
source diff together with manifest/lockfile/configuration changes. Fresh clones
can build without downloading the plugin.

`update` compares the installed tree against the old locked inventory before
changing it. Refuse to overwrite local modifications or unrecognized files in
the owned destination. Initially require the user to commit their work elsewhere
or restore the pristine tree; patch merging and a destructive force option are
outside MVP. A Git commit of local plugin edits still counts as a local
modification relative to the upstream lockfile; committing does not authorize
overwriting it. An upstream fork can be selected explicitly later.

For branches, update resolves the current branch tip. For tags, update checks
the recorded tag, but refuses an unexpected moved tag unless explicitly
accepted; a new tag is selected with `--tag`. Commit refs remain fixed unless
a new ref is supplied. Ordinary `update` reads the manifest and changes only
the lockfile and installed content; `update --tag/--branch/--commit` also changes
the requested ref in the manifest. No-op updates leave all files unchanged.

## Downloaded sources and Git ignore behavior

In `downloaded` mode, commit the client manifest, lockfile, wrappers, project
registration, activation settings, and a **parent-folder** ignore file. Restore
plugin sources with `sync` before calling Kotlin Toolchain locally or in CI. `sync` uses the locked
commit even when the recorded branch has advanced.

Mixed modes can share `plugins/` using a committed `plugins/.gitignore`:

```gitignore
# ktc-plugins managed entries
/example/
```

Yes, a `.gitignore` inside a folder works. For example, a plugin-folder file
containing `*` and `!.gitignore` ignores its payload while retaining that ignore
file. A parent-folder file is preferable here: it survives deleting/restoring
the downloaded directory and avoids modifying upstream payloads. Ignore only
the individual downloaded plugin directories, leaving vendored plugins visible.

Git ignore rules do **not** remove already tracked files. Converting vendored
sources to downloaded mode requires an explicit index change by the user, such
as `git rm -r --cached -- plugins/example`, and a subsequent commit. The tool
must detect tracked payloads and explain this rather than automatically untrack
them. Do not support mode conversion in the first release. Pre-existing global
or project ignore rules must also be checked and reported for vendored mode.

[Git ignore documentation](https://git-scm.com/docs/gitignore)

## Mutation and failure guarantees

- Only manage installer-owned paths recorded in the lockfile. Reject collisions
  with existing unowned directories and duplicate plugin IDs.
- Validate names, repository-relative paths and destinations. Reject path
  traversal, absolute archive entries, unsafe symlinks, and destination ancestors
  that escape the project. Enforce archive size/file-count limits.
- Serialize mutations per project. Prepare downloads and YAML changes in a
  temporary area; validate before replacing any live content.
- Use a transaction journal and backups for multi-file updates. Individual
  atomic renames do not make the whole operation atomic. Recover or roll back
  after failure/interruption; write the final lockfile only with matching content.
- Preserve user-owned YAML and ignore entries. If an edit cannot be made without
  changing unrelated content, stop with a concrete diagnostic and proposed diff.
- `--dry-run` may resolve/download into cache but makes no project changes.
- `sync` restores missing directories and verifies existing ones; it never
  overwrites differing content. `verify` does not mutate or require network.
- Downloading does not execute plugin code or build it. Installed plugins execute
  during later builds, so source selection remains a trust decision by the user.

## Existing plugin compatibility audit

Inspected local committed plugin trees on 2026-10-04. Quarkus and detekt HEADs
match their fetched `origin/main`. Kotgent was concurrently changed on another
branch, but `plugins/sqldelight-gen` is identical to fetched `origin/main` at
`9c98f3e33dcecff5abd354a6517d691b65b501d0`. No local edits affected the inspected
plugin subtrees. This is a packaging audit, not certification of all plugin
runtime behavior or compatibility with every Toolchain release.

Relocation smoke check: copied each plugin into a separate temporary project
containing only `project.yaml`, a Kotlin Toolchain 0.13.0 wrapper, and the selected
`plugins/<id>` subtree. Ran `./kotlin show modules`, which prepares the plugin
schema and loads the project model:

- Quarkus: passed unchanged.
- Detekt: passed unchanged, preserving directory name `heapy-detekt`.
- SQLDelight: failed unchanged with four `No catalog value` diagnostics. Replacing
  only those aliases with the coordinates below in the temporary copy made it
  pass without the kotgent root catalog.

No source repositories were modified. Packaging/model loading is verified;
application packaging, dev mode, detekt execution/config artifact retrieval, and
database generation were not exercised. Keep task execution as release
acceptance checks, including the producer's original 0.12.2 versions where
support is claimed.

| Repository / module | Actual plugin ID | Source wrapper pin | Packaging result |
| --- | --- | --- | --- |
| `Heapy/ktc-quarkus`, `plugins/quarkus` | `quarkus` (explicit) | 0.12.2 | Self-contained source module; literal Maven dependencies |
| `Heapy/detekt-config`, `plugins/heapy-detekt` | `heapy-detekt` (inferred) | 0.12.2 | Self-contained source module; shared config comes from an external Maven artifact |
| `Heapy/kotgent`, `plugins/sqldelight-gen` | `sqldelight-gen` (inferred) | 0.13.0 | Blocked by four root-catalog aliases; requires a producer packaging change |

No inspected repository currently has `ktc-plugin.yaml`. Explicit source-path
fallbacks can cover Quarkus and detekt immediately under this design. Proposed
producer declarations to add upstream (not changes made by this audit):

```yaml
# Heapy/ktc-quarkus/ktc-plugin.yaml
schemaVersion: 1
plugins:
  quarkus:
    module: plugins/quarkus
    licenseFiles: [LICENSE]
```

```yaml
# Heapy/detekt-config/ktc-plugin.yaml
schemaVersion: 1
plugins:
  detekt:
    module: plugins/heapy-detekt
    licenseFiles: [LICENSE]
```

The detekt selector can be `detekt`, but the installed directory and activation
key remain `heapy-detekt`. The client alias does not rename the plugin ID.

```yaml
# Heapy/kotgent/ktc-plugin.yaml, after making the module self-contained
schemaVersion: 1
plugins:
  sqldelight:
    module: plugins/sqldelight-gen
    licenseFiles: [LICENSE]
```

### Quarkus

At commit `364929caf0f7ac4610ce57503815972026e9e2e6`, the
[module](https://github.com/Heapy/ktc-quarkus/blob/364929caf0f7ac4610ce57503815972026e9e2e6/plugins/quarkus/module.yaml)
has explicit ID/settings class and literal Maven coordinates, with no local
module dependency or root template/catalog requirement. Task inputs use the
consuming module and task output directories, so copying the plugin source does
not tie it to the producer repository layout.

There is a separate consumer-layout limitation in
[Bootstrap.kt](https://github.com/Heapy/ktc-quarkus/blob/364929caf0f7ac4610ce57503815972026e9e2e6/plugins/quarkus/src/Bootstrap.kt):
local dependency source lookup assumes sibling modules named after the module.
Missing sibling source directories produce a diagnostic; compiled dependency
JARs are still unpacked. Dev-mode source visibility for nested/renamed modules
is limited. Installation should document this convention, not rewrite the
consumer's module layout or present it as a source-copying failure.

### Detekt

At commit `725afe7f30c4caadd1af3090800c1e8514adbf2a`, the
[plugin module](https://github.com/Heapy/detekt-config/blob/725afe7f30c4caadd1af3090800c1e8514adbf2a/plugins/heapy-detekt/module.yaml)
does not declare an explicit ID. Preserve `heapy-detekt` as its directory name.
The [task configuration](https://github.com/Heapy/detekt-config/blob/725afe7f30c4caadd1af3090800c1e8514adbf2a/plugins/heapy-detekt/plugin.yaml)
loads `io.heapy.detekt:the-config:0.2.0` alongside detekt CLI/rules. The
`/heapy/detekt.yml` resource is extracted from that external JAR by
[runDetekt.kt](https://github.com/Heapy/detekt-config/blob/725afe7f30c4caadd1af3090800c1e8514adbf2a/plugins/heapy-detekt/src/runDetekt.kt).
Do not copy the producer's separate `the-config` module into the consuming
project: it is an ordinary external artifact for this plugin. Its availability
and task execution still need runtime verification; successful source packaging
does not make dependency resolution offline on a clean machine.

### SQLDelight

At kotgent main commit `9c98f3e33dcecff5abd354a6517d691b65b501d0`,
[module.yaml](https://github.com/Heapy/kotgent/blob/9c98f3e33dcecff5abd354a6517d691b65b501d0/plugins/sqldelight-gen/module.yaml)
uses four aliases from the repository's
[root catalog](https://github.com/Heapy/kotgent/blob/9c98f3e33dcecff5abd354a6517d691b65b501d0/gradle/libs.versions.toml).
The smallest producer change is to replace those aliases with the existing
resolved coordinates, without changing versions:

```yaml
dependencies:
  - app.cash.sqldelight:core:2.3.2
  - app.cash.sqldelight:sqlite-3-38-dialect:2.3.2
  - app.cash.sql-psi:environment:0.7.3
  - app.cash.sqldelight:compiler-env:2.3.2
```

Do not copy/merge kotgent's whole catalog into a consuming project, and do not
silently resolve its aliases using the consumer's catalog. General bundle and
catalog relocation remain outside MVP. The source module pins Kotlin 2.4.20;
that setting travels with the module and must be accepted by the consumer's
Toolchain. Preserve the inferred ID/directory name `sqldelight-gen`.

The task reads `${module.rootDir}/sqldelight`, which is a consumer input directory
and is portable. The consumer owns schema files and SQLDelight runtime/driver
dependencies; the installer registers the generator but does not synthesize
those application dependencies. Preserve the Apache header in the vendored
[SqlDelightEnvironment.kt](https://github.com/Heapy/kotgent/blob/9c98f3e33dcecff5abd354a6517d691b65b501d0/plugins/sqldelight-gen/src/SqlDelightEnvironment.kt).

All three source repository roots have Apache 2.0 `LICENSE` files; no root or
plugin-subtree `NOTICE` file was found. Include each root `LICENSE` through the
producer declaration or explicit fallback, preserving source headers. Do not
infer notices for third-party dependencies from this limited file inventory.

## Implementation sequence

1. **Native feasibility:** establish shared core and native entry points; prove
   HTTPS, archive extraction, hashing, filesystem behavior and YAML edits on
   intended targets. Measure binary size/startup and audit native dependencies.
2. **CLI and deterministic source resolution:** scaffold the native CLI;
   typed tag/branch/SHA parsing; HTTP acquisition; safe archive extraction;
   producer/client schemas; self-contained plugin validation; license/notice
   preservation; cache and canonical digest format.
3. **Install and project wiring:** versioned lockfile; ownership/collision checks;
   YAML edits; transaction/recovery; vendored default; activation snippet and
   explicit module activation.
4. **Restore and downloaded mode:** committed parent ignore entries; `sync`,
   `verify`, `status`, offline operation, and documented CI bootstrap order.
5. **Explicit updates:** upstream branch commits, new tags and SHAs; drift guards;
   removed-file handling; moved-tag rejection; dry-run and no-op behavior.
6. **Distribution:** release native executables and per-target checksums; pinned
   `sh`/Windows wrappers; runtime validation of each OS/architecture artifact;
   documented libc/native/bootstrap dependencies; usage and first release.

Keep plugin source bundles, semver ranges, local patch merging, automatic mode
conversion, uninstall, and automatic update PRs for later.

## Acceptance checks

- Resolve branches with slashes, lightweight/annotated tags, and full commit SHAs;
  distinguish a branch and tag sharing a name.
- Resolve producer metadata from the same commit as source; select a sole plugin
  automatically and reject ambiguous selection in multi-plugin repositories.
- Exercise explicit source/license fallbacks for repositories without producer
  metadata. Preserve inferred plugin IDs when choosing destination paths.
- Reject stale/missing lock entries after manual client manifest edits; prove
  that `sync` never resolves a moving ref and `update` changes the intended files.
- Install a standalone plugin and a portable plugin subdirectory, register both
  module/plugin paths, and demonstrate explicit activation with Toolchain 0.13.0.
- Update vendored files to a new upstream commit and inspect the source/lockfile
  diff; remove only obsolete pristine owned files.
- Refuse local edits even when committed to Git; protect unowned files and
  preserve unrelated YAML comments/settings.
- Clone a downloaded-mode fixture, restore before a build, and confirm with
  `git status`/`git check-ignore` that only metadata/configuration are tracked.
- Advance a tracked branch and prove `sync` restores the old lock while `update`
  advances it. Check offline cache hits/misses and digest mismatch failures.
- Reject moved tags, missing refs, archive traversal, symlink escapes, oversized
  payloads, and unsupported external source dependencies.
- Inject failures between file replacements; recover a consistent project
  without data loss. Exercise launcher paths containing spaces and argument
  forwarding on all supported operating systems.
- Preserve root/subtree license and notice material in both modes, without
  clobbering original files. Verify no credentials enter manifest/lock/log output.
- Run cached wrappers offline and on clean machines without Java/Kotlin; verify
  target selection, checksum rejection and concurrent bootstrap behavior.
- Repeat the three relocation fixtures above. Execute Quarkus packaging, detekt
  checks and SQLDelight generation in minimal consumer projects on claimed
  Toolchain versions; verify consumer inputs and Maven artifact availability.
