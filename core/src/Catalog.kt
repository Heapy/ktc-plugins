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
) {
    init {
        safeRelative(source)
        checkInstall(file in catalogLocations) { "Invalid consumer catalog location" }
        checkInstall(Regex("[0-9a-f]{64}").matches(sha256)) { "Invalid catalog digest" }
        libraries.forEach { (alias, coordinates) -> catalogAlias(alias); pinnedCoordinates(coordinates) }
        uniqueAccessors(libraries.keys)
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

/** Managed entries live in the flat libraries table accepted by Kotlin Toolchain. */
fun catalogBlock(l: Locked): String {
    val libraries = l.catalog?.libraries.orEmpty()
    if (libraries.isEmpty()) return ""
    return buildString {
        appendLine("# ktc-plugins begin ${l.pluginId}")
        for ((alias, coordinates) in libraries.entries.sortedBy { it.key }) {
            appendLine("$alias = { module = \"${coordinates.substringBeforeLast(':')}\", version = \"${coordinates.substringAfterLast(':')}\" }")
        }
        appendLine("# ktc-plugins end ${l.pluginId}")
    }
}

fun verifyCatalog(text: String?, l: Locked) {
    if (l.catalog?.libraries.isNullOrEmpty()) return
    checkInstall(text != null) { "Missing managed catalog: ${l.catalog.file}" }
    val normalized = text!!.replace("\r\n", "\n")
    val block = catalogBlock(l)
    checkInstall(normalized.indexOf(block) >= 0 && normalized.indexOf(block) == normalized.lastIndexOf(block)) {
        "Modified/missing managed catalog exports for ${l.pluginId}"
    }
    val actual = parseToml(normalized).section("libraries")
    val expected = parseToml("[libraries]\n$block").section("libraries")
    checkInstall(expected.all { (alias, value) -> actual[alias] == value }) { "Modified managed catalog entries for ${l.pluginId}" }
    uniqueAccessors(actual.keys)
}

fun editCatalog(text: String?, old: List<Locked>, next: List<Locked>): String {
    var result = text ?: ""
    val before = parseToml(result)
    // Keep original line endings outside managed blocks.
    for (l in old) {
        verifyCatalog(result, l)
        val block = catalogBlock(l)
        if (block.isNotEmpty()) {
            val raw = if (block in result) block else block.replace("\n", "\r\n")
            result = result.replace(raw, "")
        }
    }
    val root = parseToml(result)
    val removed = old.flatMap { it.catalog?.libraries.orEmpty().keys }.toSet()
    checkInstall(before.section("libraries").filterKeys { it !in removed } == root.section("libraries").content &&
        before.filterKeys { it != "libraries" } == root.filterKeys { it != "libraries" }) { "Cannot safely remove catalog exports" }
    val existing = root.section("libraries").keys.map(::catalogAccessor).toMutableSet()
    for (l in next) {
        for (alias in l.catalog?.libraries.orEmpty().keys) {
            checkInstall(existing.add(catalogAccessor(alias))) { "Catalog alias conflict: $alias" }
        }
    }
    val blocks = next.joinToString("") { catalogBlock(it) }
    if (blocks.isNotEmpty()) {
        val headers = Regex("(?m)^[ \t]*\\[[ \t]*(?:libraries|\"libraries\"|'libraries')[ \t]*][ \t]*(?:#[^\\r\\n]*)?\\r?$").findAll(result).toList()
        if ("libraries" !in root) {
            result += (if (result.isEmpty() || result.endsWith('\n')) "" else "\n") + "[libraries]\n" + blocks
        } else {
            checkInstall(headers.size == 1) { "Use a single [libraries] table header before exporting catalogs" }
            val end = headers.single().range.last + 1
            val newline = if (result.getOrNull(end) == '\r') end + 1 else end
            val hasNewline = result.getOrNull(newline) == '\n'
            val insertion = if (hasNewline) newline + 1 else newline
            result = result.substring(0, insertion) + (if (hasNewline) "" else "\n") + blocks + result.substring(insertion)
        }
    }
    val parsed = parseToml(result)
    // Inserting entries must never change the interpretation of existing content.
    val exported = next.flatMap { it.catalog?.libraries.orEmpty().keys }.toSet()
    checkInstall(parsed.section("libraries").filterKeys { it !in exported } == root.section("libraries").content &&
        parsed.filterKeys { it != "libraries" } == root.filterKeys { it != "libraries" }) { "Cannot safely append catalog exports" }
    next.forEach { verifyCatalog(result, it) }
    return if (old.map(::catalogBlock).sorted() == next.map(::catalogBlock).sorted()) text ?: result else result
}
