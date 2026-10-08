package io.heapy.ktcplugins

import com.charleskorn.kaml.*
import okio.ByteString.Companion.encodeUtf8

class InstallError(message: String) : Exception(message)
fun checkInstall(condition: Boolean, message: () -> String) { if (!condition) throw InstallError(message()) }
fun fail(message: String): Nothing = throw InstallError(message)

fun parseYaml(text: String): YamlNode = try { Yaml.default.parseToYamlNode(text) }
catch (e: Exception) { fail("Invalid YAML: ${e.message}") }
fun YamlNode.map(): Map<String, YamlNode> = (this as? YamlMap)?.entries?.mapKeys { it.key.content }
    ?: fail("Expected a YAML mapping at $location")
fun YamlNode.string(): String = (this as? YamlScalar)?.content ?: fail("Expected a scalar at $location")
fun YamlNode.list(): List<YamlNode> = (this as? YamlList)?.items ?: fail("Expected a list at $location")
fun Map<String, YamlNode>.required(key: String): YamlNode = this[key] ?: fail("Missing '$key'")
fun Map<String, YamlNode>.text(key: String): String = required(key).string()
fun Map<String, YamlNode>.optional(key: String): String? = this[key]?.string()
fun Map<String, YamlNode>.keysAllowed(vararg keys: String) {
    val unknown = this.keys - keys.toSet()
    checkInstall(unknown.isEmpty()) { "Unknown fields: ${unknown.joinToString()}" }
}
fun schema(text: String): Map<String, YamlNode> = parseYaml(text).map().also {
    checkInstall(it.text("schemaVersion") == "1") { "Unsupported schemaVersion" }
    it.keysAllowed("schemaVersion", "plugins")
}
fun quote(value: String): String = "'" + value.replace("'", "''") + "'"
fun sha256(text: String): String = text.encodeUtf8().sha256().hex()

data class Ref(val kind: String, val value: String) {
    init {
        checkInstall(kind in setOf("tag", "branch", "commit") && value.isNotBlank() && value.none { it.code < 32 }) { "Invalid ref" }
        if (kind == "commit") checkInstall(Regex("[0-9a-fA-F]{40}").matches(value)) { "A commit must be a full 40-character SHA" }
    }
}
data class Declaration(
    val repository: String, val ref: Ref, val mode: String = "vendored", val plugin: String? = null,
    val destination: String? = null, val path: String? = null, val licenseFiles: List<String>? = null,
) {
    init {
        checkInstall(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository) && repository.split('/').none { it in setOf(".", "..") }) { "Use a GitHub repository in owner/repository form" }
        checkInstall(mode in setOf("vendored", "downloaded")) { "mode must be vendored or downloaded" }
        plugin?.let(::safeName)
        destination?.let(::safeRelative)
        path?.let { safeRelative(it, allowRoot = true) }
        licenseFiles?.forEach { safeRelative(it) }
    }
    fun yaml(indent: String = "    "): String = buildString {
        appendLine("${indent}repository: ${quote(repository)}")
        appendLine("${indent}ref:")
        appendLine("${indent}  ${ref.kind}: ${quote(ref.value)}")
        appendLine("${indent}mode: $mode")
        plugin?.let { appendLine("${indent}plugin: ${quote(it)}") }
        destination?.let { appendLine("${indent}destination: ${quote(it)}") }
        path?.let { appendLine("${indent}path: ${quote(it)}") }
        licenseFiles?.let { appendLine("${indent}licenseFiles: [${it.joinToString(", ", transform = ::quote)}]") }
    }
    val digest: String get() = sha256(yaml())
}
fun safeName(value: String): String {
    checkInstall(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*").matches(value) && value.length <= 100) { "Invalid name: $value" }
    return value
}
fun safeRelative(value: String, allowRoot: Boolean = false): String {
    if (allowRoot && value == ".") return value
    checkInstall(value.isNotBlank() && value.length <= 500 && value.none { it.code < 32 || it in "\\:*?\"<>|" } && !value.startsWith('/') && !value.endsWith('/') &&
        value.split('/').none { it.isEmpty() || it in setOf(".", "..") || it.endsWith('.') || it.endsWith(' ') ||
            it.substringBefore('.').uppercase() in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9") }) { "Unsafe relative path: $value" }
    return value
}
fun validateDestination(value: String): String {
    safeRelative(value)
    checkInstall(catalogLocations.none { value.equals(it, ignoreCase = true) || value.lowercase().startsWith("$it/") || it.startsWith(value.lowercase() + "/") }) { "Destination overlaps a consumer catalog: $value" }
    checkInstall(value.split('/').none { it.lowercase() in setOf(".git", ".ktc-plugins") } && value.lowercase() !in setOf(".gitignore", "ktc-plugins.yaml", "ktc-plugins.lock.yaml", "project.yaml", "module.yaml", "ktc-plugins", "ktc-plugins.bat")) { "Reserved destination: $value" }
    return value
}
fun rejectCaseCollisions(paths: Iterable<String>) {
    val spellings = mutableMapOf<String, String>()
    for (path in paths) {
        val parts = path.split('/')
        for (count in 1..parts.size) {
            val prefix = parts.take(count).joinToString("/")
            val prior = spellings.put(prefix.lowercase(), prefix)
            checkInstall(prior == null || prior == prefix) { "Case-colliding path: $prior and $prefix" }
        }
    }
}
fun declarations(text: String): Map<String, Declaration> = schema(text).required("plugins").map().mapValues { (name, node) ->
    safeName(name)
    val m = node.map()
    m.keysAllowed("repository", "ref", "mode", "plugin", "destination", "path", "licenseFiles")
    val r = m.required("ref").map()
    checkInstall(r.size == 1) { "Exactly one tag, branch, or commit is required for $name" }
    Declaration(m.text("repository"), Ref(r.keys.single(), r.values.single().string()), m.optional("mode") ?: "vendored", m.optional("plugin"), m.optional("destination"), m.optional("path"), m["licenseFiles"]?.list()?.map { it.string() })
}
fun manifestYaml(entries: Map<String, Declaration>): String = "schemaVersion: 1\nplugins:" + if (entries.isEmpty()) " {}\n" else "\n" + entries.entries.sortedBy { it.key }.joinToString("") { (name, d) -> "  ${quote(name)}:\n${d.yaml()}" }

data class FileRecord(val sha256: String, val executable: Boolean)
data class Producer(val sha256: String, val plugin: String)
data class Locked(
    val repository: String, val declarationSha256: String, val commit: String, val sourcePath: String,
    val destination: String, val mode: String, val pluginId: String, val producer: Producer?,
    val treeSha256: String, val files: Map<String, FileRecord>, val catalog: LockedCatalog? = null,
) {
    init {
        validateDestination(destination); safeRelative(sourcePath, true); safeName(pluginId)
        catalog?.let { c ->
            val prefix = "ktc-${pluginId.replace('.', '-').replace('_', '-')}-"
            checkInstall(c.libraries.keys.all { it.startsWith(prefix) }) { "Catalog export does not belong to plugin $pluginId" }
        }
        checkInstall(mode in setOf("vendored", "downloaded")) { "Invalid lockfile mode" }
        checkInstall(Regex("[0-9a-f]{40}").matches(commit)) { "Invalid locked commit" }
        for (hash in listOf(declarationSha256, treeSha256) + files.values.map { it.sha256 } + listOfNotNull(producer?.sha256)) {
            checkInstall(Regex("[0-9a-f]{64}").matches(hash)) { "Invalid lockfile digest" }
        }
        files.keys.forEach { safeRelative(it) }
        rejectCaseCollisions(files.keys)
        checkInstall(treeDigest(files) == treeSha256) { "Lockfile inventory digest mismatch" }
    }
}
fun treeDigest(files: Map<String, FileRecord>): String = sha256("ktc-plugins-tree-v1\n" + files.entries.sortedBy { it.key }.joinToString("") { (name, f) -> "$name\u0000${f.sha256}\u0000${if (f.executable) 1 else 0}\n" })
fun locks(text: String): Map<String, Locked> = schema(text).required("plugins").map().mapValues { (name, node) ->
    safeName(name)
    val m = node.map()
    m.keysAllowed("repository", "declarationSha256", "commit", "sourcePath", "destination", "mode", "pluginId", "producer", "treeSha256", "files", "catalog")
    val files = m.required("files").map().mapValues { (_, n) ->
        val f = n.map(); f.keysAllowed("sha256", "executable")
        checkInstall(f.text("executable") in setOf("true", "false")) { "Invalid executable flag" }
        FileRecord(f.text("sha256"), f.text("executable") == "true")
    }
    val producer = m["producer"]?.map()?.let { p ->
        p.keysAllowed("path", "sha256", "plugin")
        checkInstall(p.text("path") == "ktc-plugin.yaml") { "Invalid producer metadata path" }
        Producer(p.text("sha256"), safeName(p.text("plugin")))
    }
    val catalog = m["catalog"]?.map()?.let { c ->
        c.keysAllowed("source", "sha256", "file", "libraries", "versionRefs")
        LockedCatalog(c.text("source"), c.text("sha256"), c.required("libraries").map().mapValues { it.value.string() }, c.text("file"),
            c["versionRefs"]?.map()?.mapValues { it.value.string() }.orEmpty())
    }
    Locked(m.text("repository"), m.text("declarationSha256"), m.text("commit"), m.text("sourcePath"), m.text("destination"), m.text("mode"), m.text("pluginId"), producer, m.text("treeSha256"), files, catalog)
}
fun lockYaml(entries: Map<String, Locked>): String = buildString {
    appendLine("schemaVersion: 1")
    if (entries.isEmpty()) { appendLine("plugins: {}"); return@buildString }
    appendLine("plugins:")
    entries.entries.sortedBy { it.key }.forEach { (name, l) ->
        appendLine("  ${quote(name)}:")
        for ((key, value) in listOf("repository" to l.repository, "declarationSha256" to l.declarationSha256, "commit" to l.commit, "sourcePath" to l.sourcePath, "destination" to l.destination, "mode" to l.mode, "pluginId" to l.pluginId, "treeSha256" to l.treeSha256)) appendLine("    $key: ${quote(value)}")
        l.producer?.let { appendLine("    producer:\n      path: ktc-plugin.yaml\n      sha256: ${quote(it.sha256)}\n      plugin: ${quote(it.plugin)}") }
        l.catalog?.let { c ->
            appendLine("    catalog:")
            appendLine("      source: ${quote(c.source)}\n      sha256: ${quote(c.sha256)}\n      file: ${quote(c.file)}")
            if (c.libraries.isEmpty()) appendLine("      libraries: {}") else {
                appendLine("      libraries:")
                c.libraries.entries.sortedBy { it.key }.forEach { (alias, coordinates) -> appendLine("        ${quote(alias)}: ${quote(coordinates)}") }
            }
            if (c.versionRefs.isNotEmpty()) {
                appendLine("      versionRefs:")
                c.versionRefs.entries.sortedBy { it.key }.forEach { (alias, ref) -> appendLine("        ${quote(alias)}: ${quote(ref)}") }
            }
        }
        appendLine("    files:")
        l.files.entries.sortedBy { it.key }.forEach { (path, f) -> appendLine("      ${quote(path)}:\n        sha256: ${quote(f.sha256)}\n        executable: ${f.executable}") }
    }
}
