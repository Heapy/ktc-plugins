package io.heapy.ktcplugins

import com.charleskorn.kaml.*

/** Validate with a full YAML parser, but insert text without round-tripping unrelated content. */
fun appendYamlList(text: String, key: String, values: List<String>, covers: (String, String) -> Boolean = { a, b -> a == b }): String {
    var result = text
    for (value in values) {
        val root = parseYaml(result) as? YamlMap ?: fail("Expected a root YAML map")
        val pair = root.entries.entries.firstOrNull { it.key.content == key }
        if (pair == null) { result = appendRoot(result, "$key:\n  - ${quote(value)}\n"); continue }
        val existing = if (pair.value is YamlNull) emptyList() else pair.value.list().map { it.string() }
        if (existing.any { covers(it, value) }) continue
        val lines = result.split('\n').toMutableList()
        val index = pair.key.location.line - 1
        checkInstall(pair.key.location.column == 1 && index in lines.indices) { "Cannot safely edit '$key'; use a plain root mapping" }
        val line = lines[index]; val tail = line.substringAfter(':').trimStart()
        if (tail.startsWith('[')) {
            val close = flowClose(line, line.indexOf('['))
            checkInstall(close >= 0) { "Cannot edit multiline/anchored '$key' flow list" }
            val prefix = if (existing.isEmpty()) "" else ", "
            lines[index] = line.substring(0, close) + prefix + quote(value) + line.substring(close)
        } else {
            checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot safely edit anchored or complex '$key'; use a plain block list" }
            val nextKey = root.entries.keys.map { it.location.line - 1 }.filter { it > index }.minOrNull() ?: lines.size
            val end = insertionLine(lines, nextKey)
            val indent = (index + 1 until nextKey).firstOrNull { lines[it].trimStart().startsWith("- ") }?.let { lines[it].takeWhile(Char::isWhitespace) } ?: "  "
            lines.add(end, "$indent- ${quote(value)}")
        }
        result = lines.joinToString("\n")
        parseYaml(result) // fail before any project mutation
    }
    return result
}
private fun insertionLine(lines: List<String>, before: Int): Int {
    var i = before
    while (i > 0 && (lines[i - 1].isBlank() || lines[i - 1].trimStart().startsWith('#'))) i--
    return i
}
private fun appendRoot(text: String, addition: String): String {
    checkInstall(text.lineSequence().none { it.trim() == "..." }) { "Cannot append YAML after a document end marker" }
    return text + if (text.endsWith('\n') || text.isEmpty()) addition else "\n$addition"
}
private fun flowClose(line: String, start: Int): Int {
    var quoted: Char? = null; var escaped = false
    for (i in start + 1 until line.length) {
        val c = line[i]
        if (escaped) { escaped = false; continue }
        if (quoted == '"' && c == '\\') { escaped = true; continue }
        if (quoted != null) { if (c == quoted) quoted = null; continue }
        if (c == '\'' || c == '"') quoted = c
        else if (c == ']') return i
        else if (c == '#') return -1
    }
    return -1
}
fun moduleGlob(pattern: String, path: String): Boolean {
    val p = pattern.removePrefix("./")
    if ("**" in p || p.startsWith("//")) return false
    val regex = p.map { when (it) { '*' -> "[^/]*"; '?' -> "[^/]"; else -> Regex.escape(it.toString()) } }.joinToString("")
    return Regex(regex).matches(path)
}
fun registerPlugin(text: String?, destination: String): String {
    val base = text ?: "modules: []\n"
    val modules = appendYamlList(base, "modules", listOf(destination), ::moduleGlob)
    return appendYamlList(modules, "plugins", listOf("//$destination")) { a, b -> a.removePrefix("//").removePrefix("./") == b.removePrefix("//") }
}
fun addMapEntry(text: String, key: String, name: String, body: String): String {
    val root = parseYaml(text) as? YamlMap ?: fail("Expected a root map")
    val pair = root.entries.entries.firstOrNull { it.key.content == key }
        ?: return appendRoot(text, "$key:\n  ${quote(name)}:\n$body")
    val existing = if (pair.value is YamlNull) emptyMap() else pair.value.map()
    checkInstall(name !in existing) { "Entry '$name' already exists" }
    val lines = text.split('\n').toMutableList(); val index = pair.key.location.line - 1
    val tail = lines[index].substringAfter(':').trimStart()
    checkInstall(pair.key.location.column == 1) { "Cannot safely edit $key" }
    if (tail.startsWith("{}")) lines[index] = lines[index].replaceFirst("{}", "\n  ${quote(name)}:\n${body.trimEnd()}")
    else {
        checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot edit a nonempty flow/anchored mapping '$key'; use a block map" }
        val next = root.entries.keys.map { it.location.line - 1 }.filter { it > index }.minOrNull() ?: lines.size
        lines.add(insertionLine(lines, next), "  ${quote(name)}:\n${body.trimEnd()}")
    }
    return lines.joinToString("\n").also(::parseYaml)
}
fun changeRef(text: String, alias: String, ref: Ref): String {
    val node = parseYaml(text).map().required("plugins").map().required(alias).map().required("ref")
    val key = (node as? YamlMap)?.entries?.keys?.singleOrNull() ?: fail("Cannot safely replace ref")
    val lines = text.split('\n').toMutableList(); val index = key.location.line - 1
    val line = lines[index]
    checkInstall(line.takeWhile(Char::isWhitespace).length + 1 == key.location.column && line.substringAfter(':').trimStart().firstOrNull() !in listOf('{', '[', '&', '*')) { "Use a block ref mapping before changing refs" }
    // Preserve an inline comment, recognizing quoted # characters first.
    var quote: Char? = null; var escaped = false; var comment = ""
    for (i in line.indices) {
        val c = line[i]
        if (escaped) { escaped = false; continue }
        if (quote == '"' && c == '\\') { escaped = true; continue }
        if (quote != null) { if (c == quote) quote = null }
        else if (c == '\'' || c == '"') quote = c
        else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) { comment = " " + line.substring(i); break }
    }
    lines[index] = line.takeWhile(Char::isWhitespace) + ref.kind + ": " + io.heapy.ktcplugins.quote(ref.value) + comment
    return lines.joinToString("\n").also(::declarations)
}
fun enablePlugin(text: String, id: String): String {
    val plugins = parseYaml(text).map()["plugins"]
    if (plugins != null && plugins !is YamlNull && id in plugins.map()) {
        val config = plugins.map().getValue(id)
        val enabled = when (config) {
            is YamlScalar -> config.content == "enabled"
            is YamlMap -> config.map()["enabled"]?.string() == "true"
            else -> false
        }
        checkInstall(enabled) { "Plugin '$id' already has configuration; enable it explicitly in module.yaml" }
        return text
    }
    // A map scalar is simpler than the nested body used by declarations.
    val inserted = addMapEntry(text, "plugins", id, "    enabled: true\n")
    return inserted
}
fun ignoredEntries(text: String, names: List<String>): String {
    val additions = names.map { "/$it/" }.filter { it !in text.lineSequence().map(String::trim).toSet() }
    if (additions.isEmpty()) return text
    return text + (if (text.endsWith('\n') || text.isEmpty()) "" else "\n") + "# ktc-plugins managed entries\n" + additions.joinToString("\n", postfix = "\n")
}

/** Split a one-line flow collection without splitting quoted or nested values. */
private fun flowParts(line: String, start: Int): Pair<List<String>, Int> {
    var quote: Char? = null; var escaped = false; var depth = 0; var begin = start + 1
    val parts = mutableListOf<String>()
    var i = begin
    while (i < line.length) {
        val c = line[i]
        if (escaped) { escaped = false; i++; continue }
        if (quote == '"' && c == '\\') { escaped = true; i++; continue }
        if (quote != null) {
            if (c == quote) {
                if (quote == '\'' && i + 1 < line.length && line[i + 1] == '\'') { i += 2; continue }
                quote = null
            }
        } else when (c) {
            '\'', '"' -> quote = c
            '[', '{' -> depth++
            ']', '}' -> if (depth == 0) {
                val last = line.substring(begin, i).trim()
                if (last.isNotEmpty()) parts += last
                return parts to i
            } else depth--
            ',' -> if (depth == 0) { parts += line.substring(begin, i).trim(); begin = i + 1 }
            '#' -> fail("Cannot safely edit a multiline/commented flow collection")
        }
        i++
    }
    fail("Cannot safely edit a multiline flow collection")
}
fun removeYamlListValues(text: String, key: String, matches: (String) -> Boolean): String {
    val root = parseYaml(text) as? YamlMap ?: fail("Expected root mapping")
    val pair = root.entries.entries.firstOrNull { it.key.content == key } ?: return text
    if (pair.value is YamlNull) return text
    val items = pair.value.list()
    val remove = items.indices.filter { matches(items[it].string()) }.toSet()
    if (remove.isEmpty()) return text
    val lines = text.split('\n').toMutableList(); val index = pair.key.location.line - 1
    checkInstall(pair.key.location.column == 1) { "Cannot safely edit $key" }
    val line = lines[index]; val tail = line.substringAfter(':').trimStart()
    if (tail.startsWith('[')) {
        val start = line.indexOf('[', line.indexOf(':') + 1)
        val (parts, end) = flowParts(line, start)
        checkInstall(parts.size == items.size) { "Cannot safely edit flow list $key" }
        lines[index] = line.substring(0, start + 1) + parts.filterIndexed { i, _ -> i !in remove }.joinToString(", ") + line.substring(end)
    } else {
        checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot edit anchored/complex list $key" }
        for (i in remove.sortedDescending()) {
            val at = items[i].location.line - 1
            checkInstall(at > index && lines[at].trimStart().startsWith("- ") && !lines[at].substringAfter("- ").trimStart().startsWith('&')) { "Cannot safely edit list entry $key" }
            // Multi-line scalar entries are not removed through a one-line edit.
            val scalar = parseYaml("value: [${quote(items[i].string())}]").map().required("value").list().single().string()
            checkInstall('\n' !in scalar) { "Cannot safely remove a multiline list entry" }
            lines.removeAt(at)
        }
        if (remove.size == items.size) lines[index] = line.substringBefore(':') + ": []" + tail.takeIf { it.startsWith('#') }?.let { " $it" }.orEmpty()
    }
    return lines.joinToString("\n").also(::parseYaml)
}
fun removeYamlMapEntry(text: String, key: String, name: String, preserveConfiguration: Boolean = false): String {
    val root = parseYaml(text) as? YamlMap ?: fail("Expected root mapping")
    val pair = root.entries.entries.firstOrNull { it.key.content == key } ?: return text
    if (pair.value is YamlNull) return text
    val entries = (pair.value as? YamlMap)?.entries?.entries?.toList() ?: fail("Expected mapping $key")
    val selected = entries.indexOfFirst { it.key.content == name }
    if (selected < 0) return text
    val lines = text.split('\n').toMutableList(); val header = pair.key.location.line - 1
    checkInstall(pair.key.location.column == 1) { "Cannot safely edit $key" }
    val line = lines[header]; val tail = line.substringAfter(':').trimStart()
    if (tail.startsWith('{')) {
        val start = line.indexOf('{', line.indexOf(':') + 1)
        val (parts, end) = flowParts(line, start)
        checkInstall(parts.size == entries.size) { "Cannot safely edit flow map $key" }
        lines[header] = line.substring(0, start + 1) + parts.filterIndexed { i, _ -> i != selected }.joinToString(", ") + line.substring(end)
        if (preserveConfiguration) lines.add(header + 1, "# ktc-plugins removed configuration: ${parts[selected]}")
    } else {
        checkInstall(tail.isEmpty() || tail.startsWith('#')) { "Cannot edit anchored/complex mapping $key" }
        val entry = entries[selected].key
        val start = entry.location.line - 1
        checkInstall(start > header && entry.location.column == lines[start].takeWhile(Char::isWhitespace).length + 1) { "Cannot safely remove anchored/multiline mapping entry $name" }
        val next = entries.getOrNull(selected + 1)?.key?.location?.line?.minus(1)
            ?: root.entries.keys.map { it.location.line - 1 }.filter { it > header }.minOrNull() ?: lines.size
        val end = insertionLine(lines, next)
        checkInstall(end > start) { "Cannot determine mapping entry $name" }
        val saved = lines.subList(start, end).toList()
        lines.subList(start, end).clear()
        if (preserveConfiguration) lines.addAll(start, saved.map { it.takeWhile(Char::isWhitespace) + "# " + it.trimStart() })
        if (entries.size == 1) lines[header] = line.substringBefore(':') + ": {}" + tail.takeIf { it.startsWith('#') }?.let { " $it" }.orEmpty()
    }
    return lines.joinToString("\n").also(::parseYaml)
}
fun unregisterPlugin(text: String, destination: String): String {
    fun same(value: String) = value.removePrefix("//").removePrefix("./") == destination
    return removeYamlListValues(removeYamlListValues(text, "plugins", ::same), "modules", ::same)
}
fun removeManagedIgnore(text: String, basename: String): String {
    var managed = false
    return text.split('\n').filter { line ->
        if (line.trim() == "# ktc-plugins managed entries") { managed = true; true }
        else {
            if (line.isBlank() || line.trimStart().startsWith('#') || !Regex("/[^/]+/").matches(line.trim())) managed = false
            !(managed && line.trim() == "/$basename/")
        }
    }.joinToString("\n")
}
