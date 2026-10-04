# ktc-plugins

A planned, temporary source installer for JetBrains Kotlin Toolchain build plugins.

Download a plugin from GitHub by tag, branch, or commit SHA and install it as a local
module in a consuming project. Support both committed plugin sources and ignored
downloads, with explicit updates and reproducible restoration from a lockfile.

**Status: design stage. No installer or release is available yet.**

See [the design and implementation plan](docs/design.md) for distribution options,
proposed commands, Git behavior, and acceptance criteria.

The proposed first release is a Kotlin/Native application with committed Unix
and Windows bootstrap wrappers. Producer and client manifests describe what to
install; a committed lockfile records the exact sources. Installation copies
source files; Kotlin Toolchain then builds the plugin as a local plugin module.
