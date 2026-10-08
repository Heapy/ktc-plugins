# Implementation verification

## Catalog export extension (2026-10-08)

Verified on macOS ARM64 with Toolchain 0.13.0 and Kotlin 2.4.20:

- `./kotlin test -m core --platform jvm --platform macosArm64`: all 47 tests
  passed on each runtime (94 executions), including 12 catalog tests.
- `./kotlin build -m cli-macos -m cli-linux -m cli-windows -v release`:
  all four release executables built with `com.akuleshov7:ktoml-core:0.7.1`.
  Linux and Windows execution remains a CI check.
- An isolated JVM consumer compiled a function using SQLDelight `SqlDriver`
  through `$libs.ktc.sql.runtime`, with the emitted flat inline-table shape and
  double-quoted module/version values. This caught Toolchain 0.13.0 reader
  differences for TOML subtables and literal strings before the final format.
- Catalog coverage includes producer resolution in quoted scalars, dependency
  mapping keys, tagged actions and templates; explicit exports and empty export
  lists; normalized alias collisions; missing/invalid/unpinned versions; symlink
  and ignored-file rejection; dry-run, locked restore, update/removal, rollback,
  multiple plugins, `update --all`, no-op updates, drift protection and CRLF.

Test/build commands needed access beyond the workspace sandbox for installer
cache locks and Toolchain process inspection. No upstream repository or release
was changed. The older results below describe the pre-catalog implementation.


Recorded on 2026-10-04, macOS ARM64. Toolchain 0.13.0, Kotlin 2.4.20.
No upstream plugin repository was modified and no release was published.

## Core and native builds

The shared core has 23 tests, run on JVM and macOS ARM64 (including the 2026-10-05
Windows path/read-only cleanup regression). Coverage includes:

- Locked restoration without branch resolution, branch updates, removed pristine
  files, no-op updates and local/unowned-file drift protection.
- Producer selection, inferred IDs, multi-plugin ambiguity, manual declarations,
  declaration/lock mismatches, explicit moved-tag acceptance and annotated tags.
- Typed branch/tag refs containing slashes, commit refs and same-name namespaces.
- Root license/notice preservation, catalog aliases in mapping keys/tagged actions,
  unsafe paths, symlinks, case collisions and independent fixed/dynamic DEFLATE vectors.
- Comment/glob-preserving YAML registration, activation settings, dry-run immutability,
  transaction failure rollback and interruption recovery with later-edit protection.
- Mutation lock exclusion, cache overrides, tracked downloaded payload rejection,
  filesystem symlink/junction protection and rejection of project-local archive caches.

Release binaries cross-compile for all four targets:

| Target | Approximate unstripped binary size | Local execution |
| --- | --- | --- |
| macOS ARM64 | 3.0 MiB | Core tests, CLI, real GitHub sources and wrapper tests |
| Linux x64 | 3.2 MiB | Cross-compiled; execution assigned to CI |
| Linux ARM64 | 2.9 MiB | Cross-compiled, including native test executable; execution assigned to CI |
| Windows x64 | 3.1 MiB | Cross-compiled; execution assigned to CI |

A 25-run macOS `--version` spawn/exit sample had approximately 4.9 ms median latency.
These are local measurements, not performance guarantees. No Java runtime is linked.
macOS imports system frameworks/libraries; Windows imports KERNEL32.dll and msvcrt.dll.
Linux uses glibc/libgcc_s, with a libcrypt.so.1 dependency on x64. Highest referenced
glibc symbol versions are 2.14 (x64) and 2.17 (ARM64). Older distributions and musl have
not been validated. curl and Git are external tools, not statically bundled libraries. Windows reparse
points are checked explicitly: Okio metadata alone does not expose junctions.
Windows verification skips Unix executable bits; vendored executable files need
correct Git index modes when committed from Windows for later POSIX clones.

The runtime dependency graph is pinned: Okio 3.18.2, kotaml 0.111.0,
kotlinx.serialization-core 1.11.0, SnakeYAML Engine KMP 4.0.1, urlencoder-lib 1.6.0
and Kotlin 2.4.20. Producer catalog resolution is not part of the installer.

## Real sources and Git behavior

`scripts/smoke.py` runs the native binary from a temporary path containing spaces:

- Quarkus at `364929caf0f7ac4610ce57503815972026e9e2e6` installs vendored sources.
- Detekt at `725afe7f30c4caadd1af3090800c1e8514adbf2a` installs downloaded sources.
- Root licenses are copied into each inventory; native `verify` passes.
- `git check-ignore` confirms detekt's parent ignore rule. `git status` shows vendored
  Quarkus files and excludes downloaded detekt files.
- Deleting detekt and running `sync --offline` restores the locked tree without
  changing the lockfile. A no-op/dry-run update leaves metadata unchanged.
- Kotgent at `9c98f3e33dcecff5abd354a6517d691b65b501d0` is rejected for its producer
  catalog aliases, without changing the project. Its unrelated `.agents` symlinks
  are not extracted and do not prevent selecting a regular-file plugin subtree.

## Plugin task execution

`scripts/plugin-runtime-smoke.py` retains isolated consumer fixtures and logs.

| Plugin | Toolchain 0.13.0 | Producer Toolchain 0.12.2 |
| --- | --- | --- |
| Quarkus | Packaging produces `quarkus-run.jar` | Packaging produces `quarkus-run.jar` |
| Detekt | Check executes with shared Maven config/rules | Check executes with shared Maven config/rules |
| SQLDelight | Generation and generated-source compilation pass after temporary literal-coordinate substitution | Not claimed/tested |

Quarkus and detekt are installed by the native CLI with explicit `--enable-in app`.
Quarkus's pinned plugin needs an application in a subdirectory and an existing
resources directory: root application activation produces overlapping task paths;
a missing `app/resources` fails Quarkus bootstrap. The runtime fixture supplies
`app/resources/application.properties`. These are plugin requirements, not source
rewrites performed by the installer.

Detekt also rejects violating consumer code as expected. A clean fixture follows the
shared function-signature formatting rules. Its external `the-config:0.2.0` artifact
resolves and the shared rules execute; installing sources alone does not make Maven
resolution offline.

The SQLDelight fixture copies only its pinned plugin subtree, preserves source
headers, and replaces four aliases with the existing coordinates recorded in design.md.
It supplies consumer SQL inputs and the SQLDelight runtime dependency. This proves the
packaging adaptation needed upstream; the installer deliberately does not rewrite
catalog references or modify kotgent.

## Launchers and release packaging

`scripts/package-release.py` assembles all four binaries, `SHA256SUMS`, and Unix/Windows
wrappers with embedded version and exact per-target hashes. Generated local release
assets are under `build/release/`; checkout wrappers are pre-release templates with an
explicit development binary override.

`scripts/test-wrappers.py` verifies macOS paths containing spaces, argument forwarding,
concurrent cached launches, offline operation and cached checksum rejection. It also
uses a curl stub to test concurrent clean bootstrap and corrupted-download rejection;
no incomplete/unverified executable is published. Actual release-host downloads are
not possible before the first release is published. Windows launcher execution and
Linux runtime checks remain assigned to the configured CI jobs.

Workflow syntax passes `actionlint`; the Unix launcher passes `shellcheck`. CI tests
JVM/native core behavior on macOS, Linux x64 and Windows, and executes cross-compiled
ARM64 tests on an ARM64 runner. Pinned real-source smoke tests and wrapper tests run
on each runtime host. A version tag prepares a draft release only after these checks.
The first remote run on 2026-10-05 passed Linux x64 checks and found a macOS GitHub
API rate limit plus Windows JVM path parsing and read-only Git object cleanup issues.
CI now authenticates network fixtures; host paths are normalized before Okio parsing
on Windows, and guarded cleanup clears the read-only attribute before deletion.
Subsequent platform results are recorded in [GitHub Actions](https://github.com/Heapy/ktc-plugins/actions).

## Failure/recovery boundaries

Mutations stage source/configuration changes before journaled rename replacements;
a completed marker distinguishes committed cleanup from rollback. Recovery preserves
later edits and stops for manual inspection when it cannot safely restore a backup.
A crash during cleanup can leave an inert `cleanup-*` directory under the ignored
installer state folder. Fully durable recovery across sudden power loss/filesystem
failure is not claimed: journal files are flushed, but directory entries and every
staged source file are not independently fsynced.

YAML insertion supports ordinary block maps/lists and single-line flow lists. Anchored
or nonempty flow maps requiring modification fail with a diagnostic instead of being
reformatted. Dry-run reports changed paths/counts rather than a full unified diff.
Bundles, arbitrary source relocation, mode/repository/destination migration, force,
uninstall, semver selection and automatic wrapper upgrades are outside this MVP.
