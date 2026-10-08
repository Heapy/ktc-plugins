package io.heapy.ktcplugins

import com.charleskorn.kaml.*
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.exceptions.TomlDecodingException
import com.akuleshov7.ktoml.parsers.TomlParser
import com.akuleshov7.ktoml.tree.nodes.*
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlArray
import com.akuleshov7.ktoml.tree.nodes.pairs.values.TomlValue

val catalogLocations = listOf("libs.versions.toml", "gradle/libs.versions.toml")

data class CatalogSpec(val file: String, val exports: List<String>)
fun catalogSpec(node: YamlNode): CatalogSpec {
    val m = node.map()
    m.keysAllowed("file", "export")
    val aliases = m.required("export").list().map { catalogAlias(it.string()) }
    checkInstall(aliases.distinct().size == aliases.size) { "Duplicate catalog export" }
    return CatalogSpec(safeRelative(m.text("file")), aliases)
}

/** The source digest also pins libraries used internally but not exported. */
data class LockedCatalog(
    val source: String, val sha256: String, val libraries: Map<String, String>,
    val file: String = "libs.versions.toml",
    val versionRefs: Map<String, String> = emptyMap(),
) {
    init {
        safeRelative(source)
        checkInstall(file in catalogLocations) { "Invalid consumer catalog location" }
        checkInstall(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid catalog digest" }
        libraries.forEach { (alias, coordinates) -> catalogAlias(alias); pinnedCoordinates(coordinates) }
        uniqueAccessors(libraries.keys)
        versionRefs.forEach { (library, ref) ->
            checkInstall(library in libraries) { "Unknown version reference library: $library" }
            catalogAlias(ref)
        }
        uniqueAccessors(versionRefs.values.distinct())
        versionRefs.entries.groupBy { it.value }.forEach { (ref, entries) ->
            checkInstall(entries.map { libraries.getValue(it.key).substringAfterLast(':') }.distinct().size == 1) {
                "Conflicting catalog version reference: $ref"
            }
        }
    }
    val versions: Map<String, String> get() = versionRefs.entries.associate { (library, ref) ->
        ref to libraries.getValue(library).substringAfterLast(':')
    }
}

fun catalogAlias(value: String): String {
    checkInstall(Regex("[A-Za-z][A-Za-z0-9]*([._-][A-Za-z0-9]+)*").matches(value)) { "Unsupported catalog alias: $value" }
    return value
}
fun catalogAccessor(value: String): String = value.replace('-', '.').replace('_', '.')
private fun uniqueAccessors(aliases: Collection<String>) {
    checkInstall(aliases.map(::catalogAccessor).distinct().size == aliases.size) { "Catalog aliases have colliding accessors" }
}
fun pinnedCoordinates(value: String): String {
    checkInstall(Regex("[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.+\\-]+").matches(value) &&
        !value.substringAfterLast(':').contains('+') && !value.substringAfterLast(':').startsWith("latest.")) {
        "Catalog library must have fixed group:artifact:version coordinates: $value"
    }
    return value
}
private sealed interface CatalogValue
private data class CatalogLiteral(val content: Any) : CatalogValue
private data class CatalogTable(val content: Map<String, CatalogValue>) : CatalogValue, Map<String, CatalogValue> by content
private data class CatalogArray(val content: List<CatalogValue>) : CatalogValue

/** Normalize ktoml's AST for semantic comparisons, preserving types and rejecting duplicate keys. */
private fun parseToml(text: String): CatalogTable {
    val ast = try { TomlParser(TomlInputConfig.compliant()).parseString(text) }
    catch (e: TomlDecodingException) { fail("Invalid TOML catalog: ${e.message}") }
    fun value(v: TomlValue): CatalogValue = if (v is TomlArray) CatalogArray(v.parse().map { value(it as TomlValue) }) else CatalogLiteral(v.content)
    fun table(node: TomlNode, depth: Int = 0): CatalogTable {
        checkInstall(depth <= 100) { "Catalog nesting exceeds limit" }
        val entries = linkedMapOf<String, CatalogValue>()
        for (child in node.children) {
            if (child is TomlStubEmptyNode) continue
            val name = if (child is TomlTable) child.fullTableKey.last() else child.name
            checkInstall('\\' !in name) { "Escaped TOML keys are unsupported: $name" }
            checkInstall(name !in entries) { "Duplicate catalog key: $name" }
            entries[name] = when (child) {
                is TomlKeyValuePrimitive -> value(child.value)
                is TomlKeyValueArray -> value(child.value)
                is TomlTable -> if (child.type == TableType.ARRAY) CatalogArray(child.children.map { table(it, depth + 1) }) else table(child, depth + 1)
                else -> fail("Unsupported catalog node: ${child.name}")
            }
        }
        return CatalogTable(entries)
    }
    return table(ast)
}
private fun CatalogValue.table(): CatalogTable = this as? CatalogTable ?: fail("Expected a catalog table")
private fun CatalogValue.stringValue(): String = (this as? CatalogLiteral)?.content as? String ?: fail("Expected a catalog string")
private fun CatalogTable.section(name: String): CatalogTable = get(name)?.table() ?: CatalogTable(emptyMap())

class ProducerCatalog(text: String) {
    private val root = parseToml(text)
    private val libraries = root.section("libraries")
    private val versions = root.section("versions")
    init { libraries.keys.forEach(::catalogAlias); uniqueAccessors(libraries.keys) }
    fun resolve(alias: String): String {
        val entry = libraries[alias] ?: fail("Unknown producer catalog library: $alias")
        if (entry is CatalogLiteral) return pinnedCoordinates(entry.stringValue())
        val m = entry.table()
        checkInstall((m.keys - setOf("module", "group", "name", "version")).isEmpty()) { "Unsupported catalog library fields: $alias" }
        val module = m["module"]?.stringValue()?.also {
            checkInstall("group" !in m && "name" !in m) { "Ambiguous catalog module: $alias" }
        } ?: "${m["group"]?.stringValue() ?: fail("Missing catalog group: $alias")}:${m["name"]?.stringValue() ?: fail("Missing catalog name: $alias")}"
        val v = m["version"] ?: fail("Missing fixed catalog version: $alias")
        val version = if (v is CatalogLiteral) v.stringValue() else {
            val ref = v.table()
            checkInstall(ref.keys == setOf("ref")) { "Rich catalog versions are unsupported: $alias" }
            val key = ref.getValue("ref").stringValue()
            (versions[key] ?: fail("Unknown catalog version: $key")).stringValue()
        }
        return pinnedCoordinates("$module:$version")
    }
    fun reference(reference: String): String {
        val accessor = reference.removePrefix("\$libs.")
        val alias = libraries.keys.singleOrNull { catalogAccessor(it) == accessor }
            ?: fail("Unknown producer catalog reference: $reference")
        return resolve(alias)
    }
    fun versionRef(alias: String): String? {
        resolve(alias)
        val entry = libraries.getValue(alias) as? CatalogTable ?: return null
        val version = entry["version"] as? CatalogTable ?: return null
        return catalogAlias(version.getValue("ref").stringValue())
    }
}

/** Replace only parsed scalar tokens, including mapping keys and tagged action values. */
fun resolveCatalogYaml(text: String, catalog: ProducerCatalog): String {
    data class Edit(val start: Int, val end: Int, val value: String)
    val edits = mutableListOf<Edit>()
    val offsets = mutableListOf(0)
    text.forEachIndexed { i, c -> if (c == '\n') offsets += i + 1 }
    fun visit(node: YamlNode) {
        when (node) {
            is YamlScalar -> if ("\$libs." in node.content) {
                checkInstall(Regex("\\\$libs\\.[A-Za-z0-9_.-]+").matches(node.content)) { "Catalog reference must be an entire YAML scalar: ${node.content}" }
                val start = offsets[node.location.line - 1] + node.location.column - 1
                val spellings = listOf(node.content, quote(node.content), "\"${node.content}\"")
                val raw = spellings.firstOrNull { text.startsWith(it, start) }
                    ?: fail("Cannot safely rewrite catalog reference at ${node.location}")
                edits += Edit(start, start + raw.length, quote(catalog.reference(node.content)))
            }
            is YamlMap -> { node.entries.keys.forEach(::visit); node.entries.values.forEach(::visit) }
            is YamlList -> node.items.forEach(::visit)
            is YamlTaggedNode -> visit(node.innerNode)
            else -> Unit
        }
    }
    visit(parseYaml(text))
    var result = text
    for (edit in edits.distinct().sortedByDescending { it.start }) result = result.replaceRange(edit.start, edit.end, edit.value)
    parseYaml(result)
    return result
}

/** Managed entries use separate blocks in the flat libraries and versions tables. */
fun catalogBlock(l: Locked, section: String = "libraries"): String {
    val catalog = l.catalog ?: return ""
    val entries = if (section == "versions") catalog.versions else catalog.libraries
    if (entries.isEmpty()) return ""
    val marker = l.pluginId + if (section == "versions") " versions" else ""
    return buildString {
        appendLine("# ktc-plugins begin $marker")
        for ((alias, value) in entries.entries.sortedBy { it.key }) {
            if (section == "versions") appendLine("$alias = \"$value\"") else {
                val version = catalog.versionRefs[alias]?.let { "version.ref = \"$it\"" }
                    ?: "version = \"${value.substringAfterLast(':')}\""
                appendLine("$alias = { module = \"${value.substringBeforeLast(':')}\", $version }")
            }
        }
        appendLine("# ktc-plugins end $marker")
    }
}

private val managedCatalogSections = listOf("versions", "libraries")

fun verifyCatalog(text: String?, l: Locked) {
    if (l.catalog?.libraries.isNullOrEmpty()) return
    checkInstall(text != null) { "Missing managed catalog: ${l.catalog.file}" }
    val normalized = text!!.replace("\r\n", "\n")
    val parsed = parseToml(normalized)
    for (section in managedCatalogSections) {
        val block = catalogBlock(l, section)
        if (block.isEmpty()) continue
        checkInstall(normalized.indexOf(block) >= 0 && normalized.indexOf(block) == normalized.lastIndexOf(block)) {
            "Modified/missing managed catalog exports for ${l.pluginId}"
        }
        val actual = parsed.section(section)
        val expected = parseToml("[$section]\n$block").section(section)
        checkInstall(expected.all { (alias, value) -> actual[alias] == value }) { "Modified managed catalog entries for ${l.pluginId}" }
        uniqueAccessors(actual.keys)
    }
}

private fun unmanagedCatalog(root: CatalogTable, locks: List<Locked>): Map<String, CatalogValue> = buildMap {
    putAll(root.filterKeys { it !in managedCatalogSections })
    for (section in managedCatalogSections) {
        val managed = locks.flatMap {
            if (section == "versions") it.catalog?.versions.orEmpty().keys else it.catalog?.libraries.orEmpty().keys
        }.toSet()
        put(section, CatalogTable(root.section(section).filterKeys { it !in managed }))
    }
}

fun editCatalog(text: String?, old: List<Locked>, next: List<Locked>): String {
    var result = text ?: ""
    val before = parseToml(result)
    // Keep original line endings outside managed blocks.
    for (l in old) {
        verifyCatalog(result, l)
        for (section in managedCatalogSections) {
            val block = catalogBlock(l, section)
            if (block.isNotEmpty()) {
                val raw = if (block in result) block else block.replace("\n", "\r\n")
                result = result.replace(raw, "")
            }
        }
    }
    val root = parseToml(result)
    checkInstall(unmanagedCatalog(before, old) == unmanagedCatalog(root, emptyList())) { "Cannot safely remove catalog exports" }
    for (section in managedCatalogSections) {
        val existing = root.section(section).keys.map(::catalogAccessor).toMutableSet()
        for (l in next) {
            val aliases = if (section == "versions") l.catalog?.versions.orEmpty().keys else l.catalog?.libraries.orEmpty().keys
            for (alias in aliases) {
                checkInstall(existing.add(catalogAccessor(alias))) { "Catalog alias conflict: $alias" }
            }
        }
        val blocks = next.joinToString("") { catalogBlock(it, section) }
        if (blocks.isEmpty()) continue
        val headers = Regex("(?m)^[ \t]*\\[[ \t]*(?:$section|\"$section\"|'$section')[ \t]*][ \t]*(?:#[^\\r\\n]*)?\\r?$").findAll(result).toList()
        if (section !in root) {
            result += (if (result.isEmpty() || result.endsWith('\n')) "" else "\n") + "[$section]\n" + blocks
        } else {
            checkInstall(headers.size == 1) { "Use a single [$section] table header before exporting catalogs" }
            val end = headers.single().range.last + 1
            val newline = if (result.getOrNull(end) == '\r') end + 1 else end
            val hasNewline = result.getOrNull(newline) == '\n'
            val insertion = if (hasNewline) newline + 1 else newline
            result = result.substring(0, insertion) + (if (hasNewline) "" else "\n") + blocks + result.substring(insertion)
        }
    }
    val parsed = parseToml(result)
    // Inserting entries must never change the interpretation of existing content.
    checkInstall(unmanagedCatalog(parsed, next) == unmanagedCatalog(root, emptyList())) { "Cannot safely append catalog exports" }
    next.forEach { verifyCatalog(result, it) }
    return if (managedCatalogSections.all { section ->
        old.map { catalogBlock(it, section) }.sorted() == next.map { catalogBlock(it, section) }.sorted()
    }) text ?: result else result
}
