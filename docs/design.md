# Temporary Kotlin Toolchain plugin installer

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

Start with a Kotlin/JVM executable JAR containing its runtime dependencies. Aim
for Java 17 compatibility, to be verified by the build and release checks.
Use a Kotlin Toolchain `jvm/app` project to build the installer itself.

| Option | Requirements and tradeoffs | Decision |
| --- | --- | --- |
| Executable JAR | Java runtime; one artifact across operating systems; supports shared parsing, HTTP, and filesystem logic | First release |
| Kotlin `.main.kts` | Kotlin scripting runtime and dependency resolution; easy prototype but more consumer setup | Prototype alternative |
| Shell plus Windows script | OS-specific implementations and external utilities; quoting, YAML, and update logic would need separate handling | Bootstrap only |
| Java source-file launcher | JDK can compile/run source directly; dependency handling complicates YAML support and gives up Kotlin implementation | Alternative for a very small prototype |
| Kotlin/Native or native-image executables | No Java runtime for consumers; separate builds and artifacts for OS/architecture combinations | Consider after MVP |
| Git submodule or subtree | Existing vendoring mechanisms; require Git workflows and still need project wiring | Document as alternatives |

A JAR does **not** require bash/bat: users can run
`java -jar ktc-plugins.jar ...` directly. Thin launchers make the command shorter
and can download/cache the tool. Unix should use portable `sh`; Windows can use
a `.bat` entry point with PowerShell for download/checksum work. Both launchers
must forward arguments and exit status, with no plugin management logic.

Tool bootstrapping is separate from plugin installation. Pin the installer
release and SHA-256 in committed launchers; cache its JAR outside the project.
Find Java through `JAVA_HOME` or `PATH`; give a clear setup error if unavailable.
Do not assume the Toolchain's automatically provisioned JDK is on `PATH`.
Automatic JDK provisioning is outside the MVP.

[Java launcher reference](https://docs.oracle.com/en/java/javase/17/docs/specs/man/java.html)

## Proposed command contract

All examples are proposals, not currently runnable commands. The `--path` value
is relative to the downloaded repository root. Installation names are local
directory names, distinct from the plugin IDs declared by the source module.

```sh
# Copy plugin sources and leave them available for the user to commit.
ktc-plugins add Heapy/example-plugin --tag v1.0.0 --path plugin --name example --mode vendored

# Track a branch while keeping downloaded sources out of Git.
ktc-plugins add Heapy/example-plugin --branch main --path plugin --name example --mode downloaded

# Pin an exact commit (full SHA required for MVP).
ktc-plugins add Heapy/example-plugin --commit <full-sha> --path plugin --name example

# Restore the recorded commit; never advance a branch or tag.
ktc-plugins sync
ktc-plugins sync --offline

# Re-resolve the branch, or detect a moved tag, explicitly.
ktc-plugins update example
ktc-plugins update --all --dry-run

# Change a tag/branch/commit selection explicitly.
ktc-plugins update example --tag v1.1.0
ktc-plugins update example --commit <new-full-sha>

ktc-plugins status
ktc-plugins verify
```

Exactly one of `--tag`, `--branch`, or `--commit` is required on `add`. Separate
options avoid ambiguity between tags and branches with the same name. `--path`
defaults to the repository root. `--mode` defaults to `vendored`.
No implicit latest version or semver range resolution in the MVP.
Commands run in an explicitly selected project root or the current directory;
do not silently select an enclosing project.

`add` registers the module in `project.yaml`, creating that file for a
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
3. Select the requested repository-relative directory and validate it contains
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

Use GitHub's HTTPS API/archive endpoints; a Git executable should not be needed
to download plugins. Public repositories work unauthenticated, within GitHub's
limits. Optional token authentication supports private repositories; tokens
must never be stored in the lockfile or logged. Do not forward credentials to
unapproved redirect hosts. Submodules and Git LFS payloads are outside MVP.

## Lockfile and installed state

Commit `ktc-plugins.lock.yaml` in **both** modes. It is the single declarative
record for requested refs and resolved commits, avoiding a second manifest in
the MVP. Example schema (illustrative placeholders):

```yaml
schemaVersion: 1
plugins:
  example:
    repository: Heapy/example-plugin
    sourcePath: plugin
    ref:
      kind: branch
      value: main
    commit: <full-resolved-sha>
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

Define a versioned canonical tree digest over sorted normalized relative paths,
file contents and executable flags, excluding installer metadata. Do not rely
on ZIP byte equality: archive packaging can change while source remains the
same. Retain the old file inventory to detect edits, additions, deletions and
stale files during updates.

Cache pristine plugin trees by repository, commit, source path, and packaging
schema version outside the project. Cached data must pass the lockfile digest
check before use. Cache corruption or missing locked commits must fail visibly.

## Committed sources and updates

In `vendored` mode, the plugin source is ordinary project content; installation
and updates do not stage, commit, or push it. The user reviews and commits the
source diff together with lockfile/configuration changes. Fresh clones can
build without downloading the plugin.

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
a new ref is supplied. No-op updates leave all files unchanged.

## Downloaded sources and Git ignore behavior

In `downloaded` mode, commit the lockfile, project registration, activation
settings, and a **parent-folder** ignore file. Restore plugin sources with
`sync` before calling Kotlin Toolchain locally or in CI. `sync` uses the locked
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

## Implementation sequence

1. **CLI and deterministic source resolution:** scaffold the Kotlin/JVM app;
   typed tag/branch/SHA parsing; HTTP acquisition; safe archive extraction;
   self-contained plugin validation; cache and canonical digest format.
2. **Install and project wiring:** versioned lockfile; ownership/collision checks;
   YAML edits; transaction/recovery; vendored default; activation snippet and
   explicit module activation.
3. **Restore and downloaded mode:** committed parent ignore entries; `sync`,
   `verify`, `status`, offline operation, and documented CI bootstrap order.
4. **Explicit updates:** upstream branch commits, new tags and SHAs; drift guards;
   removed-file handling; moved-tag rejection; dry-run and no-op behavior.
5. **Distribution:** reproducible executable JAR packaging; checksums; pinned
   `sh`/Windows launchers; Java 17 runtime checks on Linux, macOS, and Windows;
   usage documentation and first release.

Keep plugin source bundles, semver ranges, local patch merging, automatic mode
conversion, uninstall, native binaries, and automatic update PRs for later.

## Acceptance checks

- Resolve branches with slashes, lightweight/annotated tags, and full commit SHAs;
  distinguish a branch and tag sharing a name.
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
