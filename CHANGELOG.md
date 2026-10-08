# Changelog

Notable changes to ktc-plugins are recorded here. Unreleased changes are grouped
separately from tagged releases.

## [Unreleased]

## [0.3.0] - 2026-10-08

### Changed

- Exported libraries preserve producer `version.ref` aliases in managed `[versions]`
  entries under the plugin prefix instead of repeating literal versions. Libraries
  sharing an alias share its version; independent aliases remain separate even when
  their values match. Inline versions remain inline.
- Catalog updates, removal, collision checks and drift detection cover managed
  version entries as well as libraries, preserving user entries and comments.
- SQLDelight runtime smoke checks compile a consumer using exported catalog version
  references from the pinned `Heapy/ktc-sqldelight` producer.
- Verification, packaging and CI scripts now use Kotlin `.main.kts` with the
  checksum-pinned Kotlin 2.4.21 runner instead of Python.

### Fixed

- Producer validation checks dependencies and nested `apply` references in copied
  plugin templates, rejecting unavailable helper files and unresolved catalog references.
- Wrapper updates accept additional release assets only when every checksum entry
  agrees with the verified GitHub asset inventory.
- Windows wrapper tests cover empty-cache downloads, concurrent bootstrap and
  corrupt downloads; runner checksum validation handles Windows path escaping.

### Compatibility

- Existing lockfiles remain supported. `sync` preserves their literal catalog format;
  an explicit `update` adopts producer version references. Locks using references
  record a new `catalog.versionRefs` field, which older installers reject; update
  consumer launchers before adopting this format.

## [0.2.0] - 2026-10-08

### Added

- `diff NAME` and `diff --all` to preview plugin updates without changing project files.
- `outdated` to compare locked commits with declared branches or tags without
  downloading source archives.
- `remove` with transactional cleanup of managed sources, declarations, registrations
  and ignore entries. Explicit `--disable-in` selections retain module configuration
  as comments.
- `wrapper update --version` to update both project launchers after verifying release
  metadata, checksums and native binary pins, while refusing local launcher edits.
- Offline, read-only `validate` for producer packaging, including Git ignore checks.
- Opt-in producer catalog support through `catalog.file` and `catalog.export`.
  Producer `$libs.*` references in plugin configuration and copied module templates
  resolve to fixed Maven coordinates from the locked producer catalog.
- Explicit library exports into the consumer catalog under `ktc-<pluginId>-<alias>`
  names, with alias collision checks, managed-block drift detection and preservation
  of user entries and comments.
- Lockfile catalog metadata recording the producer catalog path and digest, consumer
  catalog location and resolved exports. Catalog, source and lockfile changes share
  one transaction, including updates and removal.

### Changed

- Mutation dry-runs show unified source, configuration and lockfile diffs instead of
  only paths and file counts. Binary and large-file changes show sizes and digests.
- Acceptance and runtime checks install pinned producer commits through their
  manifests without client-side path or catalog substitution.

### Fixed

- Patch-application tests no longer depend on the host's Git newline conversion.
- Negative producer catalog tests preserve unrelated valid aliases and check the
  expected failure reason, preventing unrelated missing-alias errors from masking
  validation regressions.

### Compatibility

- Existing manifests and lockfiles without catalogs remain supported with schema
  version 1. Older installers reject the new `catalog` fields; update consumer
  launchers before adopting catalog support.
- Exported libraries are catalog entries only. Consumer module dependencies remain
  explicitly managed by the user.

## [0.1.0] - 2026-10-05

### Added

- Initial native CLI for macOS ARM64, Linux x64/ARM64 and Windows x64.
- `add`, `update`, `sync`, `status` and `verify` for GitHub-hosted plugin sources,
  with explicit tag, branch or commit selection and producer manifest support.
- Vendored and downloaded installation modes, project registration, optional module
  activation and preservation of license material.
- Commit-pinned lockfiles, cached offline restoration, local-change protection,
  project mutation locks and transactional recovery.
- Archive size limits and validation of paths, symlinks, special files and case
  collisions before installation.
- Unix and Windows launchers with pinned SHA-256 verification, native release
  binaries, checksums and CI checks across all four runtime targets.

[Unreleased]: https://github.com/Heapy/ktc-plugins/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Heapy/ktc-plugins/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/Heapy/ktc-plugins/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/Heapy/ktc-plugins/releases/tag/v0.1.0
