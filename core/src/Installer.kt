package io.heapy.ktcplugins

import okio.Path
import okio.ByteString.Companion.toByteString

class Installer(
    root: Path, private val cache: Path, private val remote: Remote,
    private val report: (String) -> Unit = ::println, private val failAfter: Int? = null,
) {
    private val root = fs.canonicalize(root)
    private fun text(relative: String): String? = contained(root, relative).let { if (fs.exists(it)) readText(it) else null }
    private fun declarations(): Map<String, Declaration> = text("ktc-plugins.yaml")?.let(::declarations) ?: emptyMap()
    private fun locks(): Map<String, Locked> = text("ktc-plugins.lock.yaml")?.let(::locks) ?: emptyMap()
    private fun <T> mutation(dryRun: Boolean, action: () -> T): T {
        fun comparable(path: Path) = path.toString().replace('\\', '/').let { if (Platform.windows || Platform.macos) it.lowercase() else it }.trimEnd('/')
        val projectPath = comparable(root)
        val cachePath = comparable(absoluteLocation(cache))
        checkInstall(cachePath != projectPath && !cachePath.startsWith("$projectPath/")) { "Source cache must be outside the project (including for dry-run)" }
        // A cache override must not let a second process bypass the project lock.
        val lockDirectory = systemPath(defaultCache()) / "locks"
        fs.createDirectories(lockDirectory)
        Platform.permissions(lockDirectory, true, private = true)
        val canonicalKey = root.toString().let { if (Platform.windows || Platform.macos) it.lowercase() else it }
        Platform.lock(lockDirectory / "${sha256(canonicalKey)}.lock").use {
            val txn = Transaction(root)
            if (dryRun) checkInstall(!fs.exists(contained(root, ".ktc-plugins/transaction"))) { "Pending transaction; run sync for recovery before dry-run" }
            else txn.recover()
            return action()
        }
    }
    fun add(d: Declaration, alias: String?, enableIn: String?, dryRun: Boolean) = mutation(dryRun) {
        val current = declarations(); val oldLocks = locks()
        checkInstall((oldLocks.keys - current.keys).isEmpty()) { "Lockfile has undeclared plugins; restore their declarations (uninstall is outside MVP)" }
        val sha = remote.resolve(d.repository, d.ref)
        val prepared = bindCatalog(prepare(d, sha, remote.archive(d.repository, sha), report), null)
        val name = safeName(alias ?: prepared.lock.pluginId)
        checkInstall(name !in current && name !in oldLocks) { "Plugin '$name' already exists; use update" }
        ensureOwnership(prepared.lock, null)
        val entries = current + (name to d); val locked = oldLocks + (name to prepared.lock)
        val manifest = text("ktc-plugins.yaml")?.let { addMapEntry(it, "plugins", name, d.yaml()) } ?: manifestYaml(entries)
        apply(listOf(prepared), entries, locked, manifest, enableIn?.let { it to prepared.lock.pluginId }, dryRun)
    }
    fun update(alias: String?, all: Boolean, ref: Ref?, dryRun: Boolean) = mutation(dryRun) {
        val declarations = declarations().toMutableMap(); val locked = locks().toMutableMap()
        checkInstall((locked.keys - declarations.keys).isEmpty()) { "Lockfile has undeclared plugins; restore their declarations (uninstall is outside MVP)" }
        val names = selection(declarations, alias, all)
        checkInstall(ref == null || names.size == 1) { "A new ref requires one named plugin" }
        var manifest = text("ktc-plugins.yaml") ?: fail("No ktc-plugins.yaml")
        val prepared = names.map { name ->
            val old = locked[name]
            val before = declarations.getValue(name)
            val d = if (ref == null) before else before.copy(ref = ref)
            if (old != null) {
                checkInstall(old.mode == d.mode && old.repository == d.repository && (d.destination == null || d.destination == old.destination)) { "Mode/repository/destination migration is outside MVP; restore the declaration for $name" }
                verifyInstalled(old, allowMissing = true)
            }
            val sha = remote.resolve(d.repository, d.ref)
            checkInstall(old == null || d.ref.kind != "tag" || sha == old.commit || ref != null || d.digest != old.declarationSha256) { "Tag '${d.ref.value}' moved from ${old?.commit} to $sha; select an explicit new ref to accept it" }
            val p = bindCatalog(prepare(d, sha, remote.archive(d.repository, sha), report), old)
            checkInstall(old == null || p.lock.destination == old.destination && p.lock.pluginId == old.pluginId) { "Plugin identity/destination changed; migration needs manual review" }
            ensureOwnership(p.lock, old)
            declarations[name] = d; locked[name] = p.lock
            if (ref != null && ref != before.ref) manifest = changeRef(manifest, name, ref)
            p
        }
        apply(prepared, declarations, locked, manifest, null, dryRun)
    }
    fun sync(dryRun: Boolean = false) = mutation(dryRun) {
        val declarations = declarations(); val locked = locks()
        matching(declarations, locked)
        val missing = locked.filterValues { !fs.exists(contained(root, it.destination)) }
        locked.values.forEach { verifyInstalled(it, allowMissing = true); ensureOwnership(it, it) }
        val prepared = missing.map { (name, l) ->
            val p = bindCatalog(prepare(declarations.getValue(name), l.commit, remote.archive(l.repository, l.commit), report), l)
            checkInstall(p.lock == l) { "Cached/upstream contents do not match the lockfile for $name" }
            ensureOwnership(p.lock, l)
            p
        }
        apply(prepared, declarations, locked, text("ktc-plugins.yaml") ?: fail("Missing manifest"), null, dryRun)
    }
    fun status(verify: Boolean = false) {
        checkInstall(!fs.exists(contained(root, ".ktc-plugins/transaction"))) { "Pending transaction; run sync to recover" }
        val declarations = declarations(); val locked = locks()
        var failures = 0
        for ((name, declaration) in declarations) {
            val l = locked[name]
            val state = when {
                l == null -> "unlocked"
                l.declarationSha256 != declaration.digest -> "stale lock"
                !fs.exists(contained(root, l.destination)) -> "missing"
                else -> try { verifyInstalled(l); ensureOwnership(l, l); "clean" } catch (e: InstallError) { e.message ?: "modified" }
            }
            report("$name: $state${l?.let { " (${it.mode}, ${it.commit})" } ?: ""}")
            if (state != "clean") failures++
        }
        if (locked.keys - declarations.keys != emptySet<String>()) { report("Lockfile contains undeclared entries"); failures++ }
        if (verify) checkInstall(failures == 0 && declarations.isNotEmpty()) { "Verification failed; run status, then sync/update as appropriate" }
    }
    fun outdated(alias: String? = null, all: Boolean = alias == null) {
        checkInstall(!fs.exists(contained(root, ".ktc-plugins/transaction"))) { "Pending transaction; run sync for recovery" }
        val entries = declarations(); val locked = locks()
        matching(entries, locked)
        for (name in selection(entries, alias, all)) {
            val d = entries.getValue(name); val old = locked.getValue(name)
            if (d.ref.kind == "commit") { report("$name: pinned (${old.commit})"); continue }
            val current = remote.resolve(d.repository, d.ref)
            report("$name: ${old.commit} -> $current (${if (current == old.commit) "up to date" else if (d.ref.kind == "tag") "tag moved; explicit ref required" else "update available"})")
        }
    }
    fun remove(alias: String, disableIn: Set<String> = emptySet(), dryRun: Boolean = false) = mutation(dryRun) {
        val entries = declarations(); val locked = locks()
        matching(entries, locked)
        val old = locked[alias] ?: fail("Unknown plugin: $alias")
        verifyInstalled(old, allowMissing = true)
        ensureOwnership(old, old)
        val modules = projectModuleFiles(root, text("project.yaml"), locked.values.map { it.destination }.toSet())
        val configured = modules.filterValues { file ->
            val plugins = parseYaml(text(file) ?: fail("Missing module: $file")).map()["plugins"]
            plugins != null && plugins !is com.charleskorn.kaml.YamlNull && old.pluginId in plugins.map()
        }
        disableIn.forEach { safeRelative(it, allowRoot = true) }
        checkInstall(configured.keys == disableIn) {
            "Plugin '${old.pluginId}' has configuration in ${configured.keys.sorted().joinToString().ifEmpty { "no modules" }}; specify exactly those modules with --disable-in (repeatable)"
        }
        val changes = linkedMapOf<String, Map<String, Payload>?>()
        if (fs.exists(contained(root, old.destination))) changes[old.destination] = null
        fun metadata(path: String, value: String) { if (text(path) != value) changes[path] = mapOf("" to Payload(value.encodeToByteArray())) }
        metadata("ktc-plugins.yaml", removeYamlMapEntry(text("ktc-plugins.yaml")!!, "plugins", alias))
        metadata("ktc-plugins.lock.yaml", lockYaml(locked - alias))
        old.catalog?.takeIf { it.libraries.isNotEmpty() }?.let { c ->
            metadata(c.file, editCatalog(text(c.file), listOf(old), emptyList()))
        }
        text("project.yaml")?.let { metadata("project.yaml", unregisterPlugin(it, old.destination)) }
        for ((_, file) in configured) metadata(file, removeYamlMapEntry(text(file)!!, "plugins", old.pluginId, preserveConfiguration = true))
        if (old.mode == "downloaded") {
            val parent = old.destination.substringBeforeLast('/', "")
            val ignore = if (parent.isEmpty()) ".gitignore" else "$parent/.gitignore"
            text(ignore)?.let { metadata(ignore, removeManagedIgnore(it, old.destination.substringAfterLast('/'))) }
        }
        if (dryRun) reportChanges(root, changes, report) else {
            Transaction(root).commit(changes, failAfter)
            report("Removed $alias (${old.destination}); module configuration retained as comments")
        }
    }
    fun updateWrappers(version: String, source: ReleaseSource, dryRun: Boolean = false) = mutation(dryRun) {
        val releases = mutableMapOf<String, LauncherRelease>()
        fun release(v: String) = releases.getOrPut(v) { source.release(v).also { validateLauncherRelease(v, it) } }
        val next = release(version)
        val changes = linkedMapOf<String, Map<String, Payload>>()
        for (name in listOf("ktc-plugins", "ktc-plugins.bat")) {
            val path = contained(root, name)
            val wanted = next.files.getValue(name)
            if (fs.exists(path)) {
                val existing = fs.readBytes(path, 1024L * 1024)
                if (existing.contentEquals(wanted.bytes) && (Platform.windows || Platform.executable(path) == wanted.executable)) continue
                val oldVersion = launcherVersion(name, existing)
                val official = release(oldVersion).files.getValue(name).bytes
                checkInstall(existing.decodeToString().replace("\r\n", "\n") == official.decodeToString().replace("\r\n", "\n")) { "Modified/unrecognized launcher $name; preserve local edits before updating" }
            }
            changes[name] = mapOf("" to wanted)
        }
        if (changes.isEmpty()) { report("Launchers already at $version"); return@mutation }
        val ignore = ignoredEntries(text(".gitignore") ?: "", listOf(".ktc-plugins"))
        if (text(".gitignore") != ignore) changes[".gitignore"] = mapOf("" to Payload(ignore.encodeToByteArray()))
        if (dryRun) reportChanges(root, changes, report)
        else { Transaction(root).commit(changes, failAfter); report("Updated project launchers to $version; review and commit both files") }
    }
    private fun verifyInstalled(l: Locked, allowMissing: Boolean = false) = verifyInstalledAt(root, l, allowMissing)
    private fun bindCatalog(p: Prepared, old: Locked?): Prepared {
        val c = p.lock.catalog ?: return p
        if (c.libraries.isEmpty()) return p
        val existing = catalogLocations.filter { fs.exists(contained(root, it)) }
        checkInstall(existing.size <= 1) { "Both consumer catalog locations exist" }
        val file = old?.catalog?.takeIf { it.libraries.isNotEmpty() }?.file ?: existing.singleOrNull() ?: catalogLocations.first()
        checkInstall(existing.isEmpty() || existing.single() == file) { "Managed catalog location changed; restore $file" }
        return p.copy(lock = p.lock.copy(catalog = c.copy(file = file)))
    }
    private fun matching(declarations: Map<String, Declaration>, locked: Map<String, Locked>) {
        checkInstall(declarations.isNotEmpty() && declarations.keys == locked.keys) { "Missing/stale lock entries; use add/update to resolve declarations" }
        declarations.forEach { (name, d) -> checkInstall(locked.getValue(name).declarationSha256 == d.digest) { "Stale lock for $name; run update explicitly" } }
    }
    private fun selection(entries: Map<String, Declaration>, alias: String?, all: Boolean): List<String> {
        checkInstall((alias != null) xor all) { "Specify one plugin name or --all" }
        if (alias != null) checkInstall(alias in entries) { "Unknown plugin: $alias" }
        checkInstall(entries.isNotEmpty()) { "No installed plugins" }
        return if (alias != null) listOf(alias) else entries.keys.sorted()
    }
    private fun ensureOwnership(l: Locked, old: Locked?) {
        val target = contained(root, l.destination)
        if (old == null) checkInstall(!fs.exists(target)) { "Destination already exists and is not owned: ${l.destination}" }
        val git = gitRootPresent()
        if (git) {
            if (l.mode == "downloaded") {
                val tracked = Platform.run(listOf("git", "-C", root.toString(), "ls-files", "--", l.destination))
                checkInstall(tracked.code == 0) { "Git is required to inspect tracking in this project" }
                checkInstall(tracked.stdout.isBlank()) { "Downloaded payload is tracked; explicitly untrack it with git rm --cached before installation" }
            } else {
                val ignored = Platform.run(listOf("git", "-C", root.toString(), "check-ignore", "--no-index", "--quiet", "--", "${l.destination}/module.yaml"))
                checkInstall(ignored.code in setOf(0, 1)) { "Cannot inspect Git ignore rules" }
                checkInstall(ignored.code == 1) { "Vendored destination is ignored by existing Git rules: ${l.destination}" }
            }
        }
    }
    private fun gitRootPresent(): Boolean {
        var path: Path? = root
        while (path != null) { if (fs.exists(path / ".git")) return true; path = path.parent }
        return false
    }
    private fun apply(prepared: List<Prepared>, declarations: Map<String, Declaration>, locked: Map<String, Locked>, manifest: String, activation: Pair<String, String>?, dryRun: Boolean) {
        checkInstall(locked.values.map { it.pluginId }.distinct().size == locked.size) { "Duplicate plugin IDs" }
        val paths = locked.values.map { it.destination }
        rejectCaseCollisions(paths)
        checkInstall(paths.size == paths.distinct().size && paths.indices.none { i -> paths.indices.any { j -> i != j && paths[j].startsWith(paths[i] + "/") } }) { "Plugin destinations overlap" }
        val changes = linkedMapOf<String, Map<String, Payload>>()
        for (p in prepared) {
            val target = contained(root, p.lock.destination)
            val pristine = if (fs.exists(target)) try { verifyInstalledAt(root, p.lock.copy(catalog = null)); true } catch (e: InstallError) { false } else false
            if (!pristine) changes[p.lock.destination] = p.payload
        }
        fun metadata(relative: String, content: String) { if (text(relative) != content) changes[relative] = mapOf("" to Payload(content.encodeToByteArray())) }
        val oldLocks = locks()
        val changedDestinations = prepared.map { it.lock.destination }.toSet()
        val oldCatalogs = oldLocks.values.filter { it.destination in changedDestinations && !it.catalog?.libraries.isNullOrEmpty() }
        val newCatalogs = prepared.map { it.lock }.filter { !it.catalog?.libraries.isNullOrEmpty() }
        for (file in (oldCatalogs + newCatalogs).map { it.catalog!!.file }.distinct()) {
            metadata(file, editCatalog(text(file), oldCatalogs.filter { it.catalog!!.file == file }, newCatalogs.filter { it.catalog!!.file == file }))
        }
        metadata("ktc-plugins.yaml", manifest)
        metadata("ktc-plugins.lock.yaml", lockYaml(locked))
        var project = text("project.yaml")
        locked.values.forEach { project = registerPlugin(project, it.destination) }
        metadata("project.yaml", project ?: "modules: []\n")
        metadata(".gitignore", ignoredEntries(text(".gitignore") ?: "", listOf(".ktc-plugins")))
        locked.values.filter { it.mode == "downloaded" }.groupBy { it.destination.substringBeforeLast('/', "") }.forEach { (parent, entries) ->
            val ignore = if (parent.isEmpty()) ".gitignore" else "$parent/.gitignore"
            val previous = changes[ignore]?.get("")?.bytes?.decodeToString() ?: text(ignore) ?: ""
            metadata(ignore, ignoredEntries(previous, entries.map { it.destination.substringAfterLast('/') }))
        }
        activation?.let { (module, id) ->
            safeRelative(module, allowRoot = true)
            val file = if (module == ".") "module.yaml" else "$module/module.yaml"
            metadata(file, enablePlugin(text(file) ?: fail("No module.yaml at $module"), id))
        }
        if (changes.isEmpty()) { report("Already up to date"); return }
        if (dryRun) reportChanges(root, changes, report)
        else for ((path, payload) in changes) report("Updating $path (${payload.size} ${if (payload.keys == setOf("")) "metadata file" else "files"})")
        if (!dryRun) Transaction(root).commit(changes, failAfter)
        for (p in prepared) report("Enable in the consuming module: plugins: {${p.lock.pluginId}: enabled}")
    }
}
fun verifyInstalledAt(project: Path, l: Locked, allowMissing: Boolean = false) {
    l.catalog?.takeIf { it.libraries.isNotEmpty() }?.let { c ->
        val existing = catalogLocations.filter { fs.exists(contained(project, it)) }
        checkInstall(existing == listOf(c.file)) { "Missing or ambiguous managed catalog: ${c.file}" }
        verifyCatalog(readText(contained(project, c.file)), l)
    }
    val target = contained(project, l.destination)
    if (!fs.exists(target)) { checkInstall(allowMissing) { "Missing plugin: ${l.destination}" }; return }
    checkInstall(fs.metadata(target).isDirectory) { "Plugin destination is not a directory" }
    val seen = mutableSetOf<String>()
    val directories = l.files.keys.flatMap { path -> path.split('/').dropLast(1).indices.map { path.split('/').take(it + 1).joinToString("/") } }.toSet()
    for (file in fs.listRecursively(target)) {
        checkNoLink(file)
        val relative = file.relativeTo(target).toString().replace('\\', '/')
        val info = fs.metadata(file)
        checkInstall(info.symlinkTarget == null) { "Symlink in installed plugin: $relative" }
        if (info.isDirectory) { checkInstall(relative in directories) { "Unowned directory in plugin: $relative" }; continue }
        checkInstall(info.isRegularFile) { "Special file in plugin: $relative" }
        val expected = l.files[relative] ?: fail("Unowned file in plugin: $relative")
        checkInstall(fs.readBytes(file, MAX_FILE.toLong()).toByteString().sha256().hex() == expected.sha256 && (Platform.windows || Platform.executable(file) == expected.executable)) { "Modified plugin file: $relative" }
        seen += relative
    }
    checkInstall(seen == l.files.keys) { "Deleted plugin files: ${(l.files.keys - seen).joinToString()}" }
}
