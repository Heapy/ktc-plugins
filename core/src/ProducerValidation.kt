package io.heapy.ktcplugins

import okio.Path

/** Validate working-tree producer packaging without networking, installing, or executing code. */
fun validateProducer(root: Path, selector: String? = null, report: (String) -> Unit = ::println) {
    val manifest = contained(root, "ktc-plugin.yaml")
    checkInstall(fs.exists(manifest)) { "No ktc-plugin.yaml in producer repository" }
    val entries = schema(readText(manifest)).required("plugins").map()
    checkInstall(entries.isNotEmpty()) { "Producer must declare at least one plugin" }
    entries.keys.forEach(::safeName)
    if (selector != null) checkInstall(selector in entries) { "Unknown producer plugin: $selector" }
    val selected = if (selector == null) entries.keys.sorted() else listOf(selector)
    val gitFiles = if (fs.exists(root / ".git")) {
        val result = Platform.run(listOf("git", "-C", root.toString(), "ls-files", "--cached", "--others", "--exclude-standard", "-z"))
        checkInstall(result.code == 0) { "Cannot inspect producer Git files" }
        result.stdout.split('\u0000').filter(String::isNotEmpty).toSet()
    } else null
    checkInstall(gitFiles == null || "ktc-plugin.yaml" in gitFiles) { "Producer manifest is Git-ignored and will not be distributed" }
    val repo = linkedMapOf<String, Payload>("ktc-plugin.yaml" to Payload(fs.readBytes(manifest, MAX_FILE.toLong())))
    var bytes = repo.getValue("ktc-plugin.yaml").bytes.size.toLong()
    fun include(relative: String) {
        if (relative in repo) return
        val file = contained(root, relative)
        checkInstall(fs.exists(file)) { "Missing producer file: $relative" }
        checkInstall(gitFiles == null || relative in gitFiles) { "Producer file is Git-ignored and will not be distributed: $relative" }
        val info = fs.metadata(file)
        checkInstall(info.isRegularFile) { "Expected a regular producer file: $relative" }
        val content = fs.readBytes(file, MAX_FILE.toLong())
        bytes += content.size
        checkInstall(bytes <= MAX_TREE && repo.size < MAX_FILES) { "Producer exceeds archive size/file-count limits" }
        repo[relative] = Payload(content, Platform.executable(file))
    }
    for (name in selected) {
        val entry = entries.getValue(name).map()
        entry.keysAllowed("module", "licenseFiles")
        val source = safeRelative(entry.text("module"), allowRoot = true)
        val folder = if (source == ".") root else contained(root, source)
        checkInstall(fs.metadata(folder).isDirectory) { "Producer module is not a directory: $source" }
        var visited = 0
        fun walk(dir: Path, depth: Int = 0) {
            checkInstall(depth <= 100) { "Producer directory nesting exceeds limit" }
            for (file in fs.list(dir)) {
                checkInstall(++visited <= MAX_FILES) { "Producer exceeds directory entry limit" }
                // Development state is never part of a root-module distribution.
                if (file.name == ".git") continue
                checkNoLink(file)
                val info = fs.metadata(file)
                if (info.isDirectory) walk(file, depth + 1)
                else include(file.relativeTo(root).toString().replace('\\', '/'))
            }
        }
        if (gitFiles == null) walk(folder)
        else gitFiles.filter { source == "." || it.startsWith("$source/") }.sorted().forEach(::include)
        val licenses = entry["licenseFiles"]?.list()?.map { safeRelative(it.string()) }
            ?: fs.list(root).map { it.name }.filter { Regex("(?i)(LICENSE|NOTICE|COPYING)(\\.[a-z]+)?").matches(it) }
        licenses.forEach(::include)
        checkInstall(licenses.isNotEmpty() || repo.keys.any { it.startsWith(if (source == ".") "" else "$source/") && it.substringAfterLast('/').uppercase().startsWith("LICENSE") }) { "No license material for producer '$name'; declare licenseFiles" }
        val repository = root.name.takeIf { Regex("[A-Za-z0-9_.-]+").matches(it) && it !in setOf(".", "..") } ?: "producer"
        val prepared = prepareRepository(Declaration("local/$repository", Ref("commit", "0".repeat(40)), plugin = name), "0".repeat(40), repo, report)
        report("$name: valid (${prepared.lock.pluginId}, ${prepared.lock.sourcePath}, ${prepared.lock.files.size} files)")
    }
}
